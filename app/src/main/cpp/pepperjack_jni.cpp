// JNI bridge between the Kotlin simulation engine and ngspice's shared library.
//
// libngspice.so is loaded with dlopen() so the app still builds and runs (with
// simulation disabled) when the native engine has not been bundled. ngspice
// calls back from its own background thread; callbacks only buffer data here
// and the Kotlin side polls, so no JNIEnv is ever needed off the caller thread.

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "PepperjackNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// Layouts mirror ngspice's src/include/ngspice/sharedspice.h (ngspice 47, BSD-3-Clause).
struct NgComplex {
    double real;
    double imag;
};

struct VectorInfo {
    char* name;
    int type;
    short flags;
    double* realData;
    NgComplex* complexData;
    int length;
};

struct VecInfo {
    int number;
    char* name;
    bool isReal;
    void* dvec;
    void* scaleDvec;
};

struct VecInfoAll {
    char* name;   // plot title, e.g. "Transient Analysis"
    char* title;  // circuit title
    char* date;
    char* type;   // plot type name, e.g. "tran1"
    int count;
    VecInfo** vecs;
};

struct VecValuesAll;  // streamed per-point data; not consumed

using SendChar = int(char*, int, void*);
using SendStat = int(char*, int, void*);
using ControlledExit = int(int, bool, bool, int, void*);
using SendData = int(VecValuesAll*, int, int, void*);
using SendInitData = int(VecInfoAll*, int, void*);
using BGThreadRunning = int(bool, int, void*);

using InitFn = int (*)(SendChar*, SendStat*, ControlledExit*, SendData*, SendInitData*,
                       BGThreadRunning*, void*);
using CommandFn = int (*)(char*);
using CircFn = int (*)(char**);
using VecInfoFn = VectorInfo* (*)(char*);
using StringFn = char* (*)();
using StringArrayFn = char** (*)();
using StringArrayArgFn = char** (*)(char*);
using BoolFn = bool (*)();
using CmInputPathFn = char* (*)(const char*);

constexpr short kVfComplex = 1 << 1;
constexpr size_t kMaxBufferedLines = 50000;

struct Engine {
    void* handle = nullptr;
    InitFn init = nullptr;
    CommandFn command = nullptr;
    CircFn circ = nullptr;
    VecInfoFn vecInfo = nullptr;
    StringFn curPlot = nullptr;
    StringArrayFn allPlots = nullptr;
    StringArrayArgFn allVecs = nullptr;
    BoolFn running = nullptr;
    CmInputPathFn cmInputPath = nullptr;
    bool initialized = false;

    std::mutex outMutex;
    std::deque<std::string> output;
    bool truncated = false;
    std::string status;
    std::map<std::string, std::string> scaleNames;
    std::map<std::string, std::string> plotTitles;

    std::mutex bgMutex;
    std::condition_variable bgCond;
    bool bgRunning = false;

    std::atomic<bool> exited{false};
};

Engine g;

// JNI's NewStringUTF wants modified UTF-8; ngspice echoes arbitrary netlist bytes.
// Keep valid 1-3 byte sequences and replace everything else with '?'.
std::string sanitize(const char* text) {
    std::string out;
    if (text == nullptr) return out;
    const auto* s = reinterpret_cast<const unsigned char*>(text);
    while (*s) {
        unsigned char c = *s;
        int extra = -1;
        if (c < 0x80) {
            extra = 0;
        } else if ((c & 0xE0) == 0xC0 && c >= 0xC2) {
            extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            extra = 2;
        }
        bool valid = extra >= 0;
        for (int i = 1; valid && i <= extra; ++i) {
            valid = (s[i] & 0xC0) == 0x80;
        }
        if (valid) {
            out.append(reinterpret_cast<const char*>(s), static_cast<size_t>(extra + 1));
            s += extra + 1;
        } else {
            out.push_back('?');
            ++s;
        }
    }
    return out;
}

jstring toJava(JNIEnv* env, const char* text) {
    std::string clean = sanitize(text);
    return env->NewStringUTF(clean.c_str());
}

std::string fromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jobjectArray toJavaArray(JNIEnv* env, const std::vector<std::string>& items) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(items.size()), stringClass, nullptr);
    for (size_t i = 0; i < items.size(); ++i) {
        jstring item = toJava(env, items[i].c_str());
        env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    env->DeleteLocalRef(stringClass);
    return array;
}

std::vector<std::string> collect(char** list) {
    std::vector<std::string> items;
    for (int i = 0; list != nullptr && list[i] != nullptr; ++i) items.emplace_back(list[i]);
    return items;
}

void setBackgroundRunning(bool running) {
    {
        std::lock_guard<std::mutex> lock(g.bgMutex);
        g.bgRunning = running;
    }
    g.bgCond.notify_all();
}

// ---- ngspice callbacks (may run on ngspice's background thread) ----

int onSendChar(char* text, int, void*) {
    std::lock_guard<std::mutex> lock(g.outMutex);
    if (g.output.size() >= kMaxBufferedLines) {
        g.output.pop_front();
        g.truncated = true;
    }
    g.output.emplace_back(text != nullptr ? text : "");
    return 0;
}

int onSendStat(char* text, int, void*) {
    std::lock_guard<std::mutex> lock(g.outMutex);
    g.status = text != nullptr ? text : "";
    return 0;
}

int onControlledExit(int exitStatus, bool, bool quit, int, void*) {
    LOGW("ngspice requested exit (status %d, quit %d)", exitStatus, quit ? 1 : 0);
    g.exited = true;
    setBackgroundRunning(false);
    return 0;
}

int onSendData(VecValuesAll*, int, int, void*) { return 0; }

int onSendInitData(VecInfoAll* info, int, void*) {
    if (info == nullptr || info->type == nullptr) return 0;
    std::string scale;
    for (int i = 0; i < info->count; ++i) {
        VecInfo* vec = info->vecs[i];
        if (vec != nullptr && vec->dvec != nullptr && vec->dvec == vec->scaleDvec && vec->name != nullptr) {
            scale = vec->name;
            break;
        }
    }
    std::lock_guard<std::mutex> lock(g.outMutex);
    g.scaleNames[info->type] = scale;
    g.plotTitles[info->type] = info->name != nullptr ? info->name : "";
    return 0;
}

int onBackgroundThread(bool finished, int, void*) {
    setBackgroundRunning(!finished);
    return 0;
}

template <typename T>
bool bind(T& target, const char* symbol, bool required, std::string& error) {
    target = reinterpret_cast<T>(dlsym(g.handle, symbol));
    if (target == nullptr && required) {
        error = std::string("Missing symbol ") + symbol;
        return false;
    }
    return true;
}

}  // namespace

extern "C" {

#define JNI_FN(name) JNICALL Java_io_github_l0gic0l_pepperjack_sim_NgSpiceNative_##name

JNIEXPORT jstring JNI_FN(load)(JNIEnv* env, jobject, jstring libraryPath) {
    if (g.handle != nullptr) return nullptr;
    std::string path = fromJava(env, libraryPath);
    g.handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (g.handle == nullptr) {
        g.handle = dlopen("libngspice.so", RTLD_NOW | RTLD_LOCAL);
    }
    if (g.handle == nullptr) {
        const char* reason = dlerror();
        return toJava(env, reason != nullptr ? reason : "libngspice.so not found");
    }
    std::string error;
    bool ok = bind(g.init, "ngSpice_Init", true, error) &&
              bind(g.command, "ngSpice_Command", true, error) &&
              bind(g.circ, "ngSpice_Circ", true, error) &&
              bind(g.vecInfo, "ngGet_Vec_Info", true, error) &&
              bind(g.curPlot, "ngSpice_CurPlot", true, error) &&
              bind(g.allPlots, "ngSpice_AllPlots", true, error) &&
              bind(g.allVecs, "ngSpice_AllVecs", true, error) &&
              bind(g.running, "ngSpice_running", true, error) &&
              bind(g.cmInputPath, "ngCM_Input_Path", false, error);
    if (!ok) {
        dlclose(g.handle);
        g.handle = nullptr;
        return toJava(env, error.c_str());
    }
    LOGI("Loaded ngspice from %s", path.c_str());
    return nullptr;
}

JNIEXPORT jboolean JNI_FN(initialize)(JNIEnv*, jobject) {
    if (g.handle == nullptr) return JNI_FALSE;
    if (g.initialized) return JNI_TRUE;
    // Deliberately no ngSpice_nospinit()/ngSpice_nospiceinit(): in ngspice 47 they
    // crash before ngSpice_Init (cp_vset reaches if_option while ft_sim is still
    // null). spinit is looked up under the desktop install prefix, which does not
    // exist on Android, and the app loads the code models itself after init.
    g.exited = false;
    LOGI("Calling ngSpice_Init");
    int rc = g.init(onSendChar, onSendStat, onControlledExit, onSendData, onSendInitData,
                    onBackgroundThread, nullptr);
    LOGI("ngSpice_Init returned %d", rc);
    g.initialized = rc == 0;
    return g.initialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNI_FN(unload)(JNIEnv*, jobject) {
    if (g.handle == nullptr) return;
    dlclose(g.handle);
    g.handle = nullptr;
    g.initialized = false;
    g.exited = false;
    setBackgroundRunning(false);
}

JNIEXPORT jint JNI_FN(command)(JNIEnv* env, jobject, jstring command) {
    if (!g.initialized) return -1;
    std::string text = fromJava(env, command);
    std::vector<char> buffer(text.begin(), text.end());
    buffer.push_back('\0');
    return g.command(buffer.data());
}

JNIEXPORT jint JNI_FN(loadCircuit)(JNIEnv* env, jobject, jobjectArray lines) {
    if (!g.initialized) return -1;
    jsize count = env->GetArrayLength(lines);
    std::vector<std::string> storage;
    storage.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto line = static_cast<jstring>(env->GetObjectArrayElement(lines, i));
        storage.push_back(fromJava(env, line));
        env->DeleteLocalRef(line);
    }
    std::vector<char*> pointers;
    pointers.reserve(storage.size() + 1);
    for (auto& line : storage) pointers.push_back(line.data());
    pointers.push_back(nullptr);
    return g.circ(pointers.data());
}

JNIEXPORT jint JNI_FN(startBackground)(JNIEnv* env, jobject, jstring command) {
    if (!g.initialized) return -1;
    setBackgroundRunning(true);
    std::string text = fromJava(env, command);
    std::vector<char> buffer(text.begin(), text.end());
    buffer.push_back('\0');
    int rc = g.command(buffer.data());
    if (rc != 0) setBackgroundRunning(false);
    return rc;
}

JNIEXPORT jboolean JNI_FN(waitForBackground)(JNIEnv*, jobject, jlong timeoutMs) {
    std::unique_lock<std::mutex> lock(g.bgMutex);
    bool done = g.bgCond.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                                  [] { return !g.bgRunning; });
    return done ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNI_FN(halt)(JNIEnv*, jobject) {
    if (!g.initialized) return;
    char cmd[] = "bg_halt";
    g.command(cmd);
    setBackgroundRunning(false);
}

JNIEXPORT jboolean JNI_FN(isRunning)(JNIEnv*, jobject) {
    if (!g.initialized) return JNI_FALSE;
    return g.running() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNI_FN(hasExited)(JNIEnv*, jobject) {
    return g.exited ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNI_FN(drainOutput)(JNIEnv* env, jobject) {
    std::vector<std::string> lines;
    {
        std::lock_guard<std::mutex> lock(g.outMutex);
        if (g.truncated) {
            lines.emplace_back("stderr [Pepperjack] Output was truncated; oldest lines dropped.");
            g.truncated = false;
        }
        lines.insert(lines.end(), g.output.begin(), g.output.end());
        g.output.clear();
    }
    return toJavaArray(env, lines);
}

JNIEXPORT jstring JNI_FN(status)(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g.outMutex);
    return toJava(env, g.status.c_str());
}

JNIEXPORT jstring JNI_FN(currentPlot)(JNIEnv* env, jobject) {
    if (!g.initialized) return nullptr;
    char* plot = g.curPlot();
    return plot != nullptr ? toJava(env, plot) : nullptr;
}

JNIEXPORT jobjectArray JNI_FN(allPlots)(JNIEnv* env, jobject) {
    std::vector<std::string> plots;
    if (g.initialized) plots = collect(g.allPlots());
    return toJavaArray(env, plots);
}

JNIEXPORT jobjectArray JNI_FN(allVectors)(JNIEnv* env, jobject, jstring plot) {
    std::vector<std::string> vectors;
    if (g.initialized) {
        std::string name = fromJava(env, plot);
        std::vector<char> buffer(name.begin(), name.end());
        buffer.push_back('\0');
        vectors = collect(g.allVecs(buffer.data()));
    }
    return toJavaArray(env, vectors);
}

JNIEXPORT jstring JNI_FN(scaleName)(JNIEnv* env, jobject, jstring plot) {
    std::string name = fromJava(env, plot);
    std::lock_guard<std::mutex> lock(g.outMutex);
    auto it = g.scaleNames.find(name);
    if (it == g.scaleNames.end() || it->second.empty()) return nullptr;
    return toJava(env, it->second.c_str());
}

JNIEXPORT jstring JNI_FN(plotTitle)(JNIEnv* env, jobject, jstring plot) {
    std::string name = fromJava(env, plot);
    std::lock_guard<std::mutex> lock(g.outMutex);
    auto it = g.plotTitles.find(name);
    if (it == g.plotTitles.end()) return nullptr;
    return toJava(env, it->second.c_str());
}

JNIEXPORT jobject JNI_FN(getVector)(JNIEnv* env, jobject, jstring name) {
    if (!g.initialized) return nullptr;
    std::string text = fromJava(env, name);
    std::vector<char> buffer(text.begin(), text.end());
    buffer.push_back('\0');
    VectorInfo* info = g.vecInfo(buffer.data());
    if (info == nullptr || info->length < 0) return nullptr;

    const jsize length = info->length;
    const bool complex = (info->flags & kVfComplex) != 0 && info->complexData != nullptr;
    jdoubleArray real = env->NewDoubleArray(length);
    jdoubleArray imag = nullptr;
    if (complex) {
        imag = env->NewDoubleArray(length);
        std::vector<double> re(static_cast<size_t>(length));
        std::vector<double> im(static_cast<size_t>(length));
        for (jsize i = 0; i < length; ++i) {
            re[static_cast<size_t>(i)] = info->complexData[i].real;
            im[static_cast<size_t>(i)] = info->complexData[i].imag;
        }
        env->SetDoubleArrayRegion(real, 0, length, re.data());
        env->SetDoubleArrayRegion(imag, 0, length, im.data());
    } else if (info->realData != nullptr) {
        env->SetDoubleArrayRegion(real, 0, length, info->realData);
    }

    jclass vectorClass = env->FindClass("io/github/l0gic0l/pepperjack/sim/NativeVector");
    if (vectorClass == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(vectorClass, "<init>", "(Ljava/lang/String;IZ[D[D)V");
    if (ctor == nullptr) return nullptr;
    jstring vectorName = toJava(env, info->name != nullptr ? info->name : text.c_str());
    jobject result = env->NewObject(vectorClass, ctor, vectorName, static_cast<jint>(info->type),
                                    complex ? JNI_TRUE : JNI_FALSE, real, imag);
    env->DeleteLocalRef(vectorClass);
    env->DeleteLocalRef(vectorName);
    return result;
}

JNIEXPORT jboolean JNI_FN(setWorkingDirectory)(JNIEnv* env, jobject, jstring path) {
    std::string dir = fromJava(env, path);
    bool ok = chdir(dir.c_str()) == 0;
    if (ok && g.initialized && g.cmInputPath != nullptr) g.cmInputPath(dir.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
