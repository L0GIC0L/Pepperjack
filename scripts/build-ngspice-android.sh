#!/usr/bin/env bash
# Cross-compiles ngspice as a shared library, plus its XSPICE code models, for
# Android and drops the results into app/src/main/jniLibs/<abi>/.
#
#   ANDROID_NDK_HOME=/path/to/ndk scripts/build-ngspice-android.sh
#
# Optional environment: ABIS (default "arm64-v8a x86_64"), API_LEVEL (26),
# JOBS (CPU count), WORK_DIR (build/ngspice), OUT_DIR (app/src/main/jniLibs).
#
# Needs a host C compiler (cc/gcc), make, curl, tar and sha256sum or shasum.
set -euo pipefail

NGSPICE_VERSION="47"
NGSPICE_SHA256="894e649651f1838a14095e5a5439e7d3aa63e87ede14d283173fda4fcdef675f"
NGSPICE_URL="https://sourceforge.net/projects/ngspice/files/ng-spice-rework/${NGSPICE_VERSION}/ngspice-${NGSPICE_VERSION}.tar.gz/download"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABIS="${ABIS:-arm64-v8a x86_64}"
API_LEVEL="${API_LEVEL:-26}"
WORK_DIR="${WORK_DIR:-$ROOT/build/ngspice}"
OUT_DIR="${OUT_DIR:-$ROOT/app/src/main/jniLibs}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

# The XSPICE code models ngspice's spinit normally loads. They are renamed to
# lib*.so so Android packages and extracts them next to libngspice.so.
CODE_MODELS="spice2poly analog digital xtradev xtraevt table tlines"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an Android NDK r28 or newer}"
case "$(uname -s)" in
    Linux) HOST_TAG=linux-x86_64 ;;
    Darwin) HOST_TAG=darwin-x86_64 ;;
    *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
[ -d "$TOOLCHAIN" ] || { echo "No NDK toolchain at $TOOLCHAIN" >&2; exit 1; }

sha256() {
    if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

TARBALL="ngspice-${NGSPICE_VERSION}.tar.gz"
if [ ! -f "$TARBALL" ] || [ "$(sha256 "$TARBALL")" != "$NGSPICE_SHA256" ]; then
    echo ">> Downloading ngspice ${NGSPICE_VERSION}"
    curl -fL --retry 4 --retry-delay 5 -o "$TARBALL" "$NGSPICE_URL"
fi
if [ "$(sha256 "$TARBALL")" != "$NGSPICE_SHA256" ]; then
    echo "Checksum mismatch for $TARBALL" >&2
    exit 1
fi

SRC="$WORK_DIR/ngspice-${NGSPICE_VERSION}"
rm -rf "$SRC"
tar xzf "$TARBALL"

# The Icarus Verilog / GHDL co-simulation shims need pthread_cancel(), which
# Android's libc does not have, and are of no use on a phone. Drop them.
sed -i.orig -E 's/^(SUBDIRS = .*) verilog vhdl[[:space:]]*$/\1/' "$SRC/src/xspice/Makefile.in"
grep -q '^SUBDIRS = .* verilog' "$SRC/src/xspice/Makefile.in" && {
    echo "Failed to remove the verilog/vhdl subdirectories" >&2
    exit 1
}

for ABI in $ABIS; do
    case "$ABI" in
        arm64-v8a) TRIPLE=aarch64-linux-android ;;
        x86_64) TRIPLE=x86_64-linux-android ;;
        armeabi-v7a) TRIPLE=armv7a-linux-androideabi ;;
        x86) TRIPLE=i686-linux-android ;;
        *) echo "Unknown ABI $ABI" >&2; exit 1 ;;
    esac
    echo ">> Building ngspice ${NGSPICE_VERSION} for $ABI (API $API_LEVEL)"

    BUILD="$WORK_DIR/build-$ABI"
    STAGE="$WORK_DIR/stage-$ABI"
    rm -rf "$BUILD" "$STAGE"
    mkdir -p "$BUILD/stub"

    # Bionic has pthreads inside libc and no libpthread. An empty archive lets
    # configure's "-lpthread" probe succeed so ngspice enables its bg thread.
    "$TOOLCHAIN/bin/llvm-ar" rcs "$BUILD/stub/libpthread.a"

    (
        cd "$BUILD"
        export ac_cv_lib_pthread_pthread_create=yes
        export ac_cv_func_malloc_0_nonnull=yes
        export ac_cv_func_realloc_0_nonnull=yes
        "$SRC/configure" \
            --build="$("$SRC/config.guess")" \
            --host="$TRIPLE" \
            --prefix=/ngspice \
            --with-ngshared \
            --enable-xspice \
            --enable-cider \
            --enable-osdi \
            --disable-debug \
            --without-x \
            --with-fftw3=no \
            CC="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang" \
            CXX="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang++" \
            AR="$TOOLCHAIN/bin/llvm-ar" \
            RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
            NM="$TOOLCHAIN/bin/llvm-nm" \
            STRIP="$TOOLCHAIN/bin/llvm-strip" \
            CC_FOR_BUILD="${CC_FOR_BUILD:-cc}" \
            CFLAGS="-O2 -fPIC" \
            CXXFLAGS="-O2 -fPIC" \
            LDFLAGS="-L$BUILD/stub -Wl,-z,max-page-size=16384" \
            LIBS="-lc++_shared"
    )

    # The code-model makefile runs "cmpp -p" to list models; point it at the
    # host-built cmpp instead of the Android binary it would pick by default.
    make -C "$BUILD" -j"$JOBS" cmpp="$BUILD/src/xspice/cmpp/build/cmpp"
    make -C "$BUILD" install DESTDIR="$STAGE" cmpp="$BUILD/src/xspice/cmpp/build/cmpp"

    DEST="$OUT_DIR/$ABI"
    mkdir -p "$DEST"
    cp -L "$STAGE/ngspice/lib/libngspice.so" "$DEST/libngspice.so"
    for CM in $CODE_MODELS; do
        cp "$STAGE/ngspice/lib/ngspice/$CM.cm" "$DEST/libngspice_cm_$CM.so"
    done
    "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$DEST"/libngspice*.so

    echo ">> $ABI dependencies:"
    "$TOOLCHAIN/bin/llvm-readelf" -d "$DEST/libngspice.so" | grep -E 'NEEDED|SONAME' || true
    for CM in $CODE_MODELS; do
        COUNT=$("$TOOLCHAIN/bin/llvm-nm" -D --defined-only "$DEST/libngspice_cm_$CM.so" | wc -l)
        echo "   libngspice_cm_$CM.so exports $COUNT symbols"
    done
done

echo ">> ngspice libraries are in $OUT_DIR"
