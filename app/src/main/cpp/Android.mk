LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
# Android 15+ (API 35): enforce 16 KB page-size alignment.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)

# glibc runner: tiny Bionic host binary installed as $PREFIX/bin/grun.
# The Docker toolchain (toolchain/) cross-compiles this same source with the
# glibc sysroot for inclusion in the bootstrap zip; this module keeps the JNI
# PTY bridge self-contained for local ndk-build validation.
include $(CLEAR_VARS)
LOCAL_MODULE := grun
LOCAL_SRC_FILES := grun.c
LOCAL_CFLAGS += -std=c11 -Wall -Wextra -Werror -Os
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_EXECUTABLE)
