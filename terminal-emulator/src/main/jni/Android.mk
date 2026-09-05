LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# Phase 8.5: PTY hot path runs here — throughput flags (keep warnings clean).
LOCAL_CFLAGS += -Wall -Wextra -O3 -flto -fomit-frame-pointer -funroll-loops
# Android 15+ (API 35): enforce 16 KB page-size alignment.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -flto -Wl,-O1 -Wl,--gc-sections
include $(BUILD_SHARED_LIBRARY)
