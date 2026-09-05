LOCAL_PATH:= $(call my-dir)
# Phase 8.5: throughput-oriented flags for our own native code (never glibc
# itself: upstream requires -O2 there and LTO is unsupported in its build).
# -O3/-flto need matching link flags; LTO objects require the linker plugin
# path, which the NDK toolchain resolves automatically for clang/lld.
HOUSEWIFE_PERF_CFLAGS := -O3 -flto -fomit-frame-pointer -funroll-loops
HOUSEWIFE_PERF_LDFLAGS := -flto -Wl,-O1 -Wl,--gc-sections
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
LOCAL_CFLAGS += -std=c11 -Wall -Wextra -Werror $(HOUSEWIFE_PERF_CFLAGS)
# Android 15+ (API 35): enforce 16 KB page-size alignment.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 $(HOUSEWIFE_PERF_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)

# glibc runner: tiny Bionic host binary installed as $PREFIX/bin/grun.
# The Docker toolchain (toolchain/) cross-compiles this same source with the
# glibc sysroot for inclusion in the bootstrap zip; this module keeps the JNI
# PTY bridge self-contained for local ndk-build validation.
include $(CLEAR_VARS)
LOCAL_MODULE := grun
LOCAL_SRC_FILES := grun.c
LOCAL_CFLAGS += -std=c11 -Wall -Wextra -Werror $(HOUSEWIFE_PERF_CFLAGS)
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 $(HOUSEWIFE_PERF_LDFLAGS)
include $(BUILD_EXECUTABLE)
