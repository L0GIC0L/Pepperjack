cmake_minimum_required(VERSION 3.22.1)
project(pepperjack_jni LANGUAGES CXX)

# JNI bridge to ngspice. libngspice.so itself is prebuilt by
# scripts/build-ngspice-android.sh and loaded at runtime with dlopen(),
# so the app still builds (with simulation disabled) when it is absent.
add_library(pepperjack SHARED pepperjack_jni.cpp)

target_compile_features(pepperjack PRIVATE cxx_std_17)
target_compile_options(pepperjack PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(pepperjack PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
target_link_libraries(pepperjack ${log-lib} dl)
