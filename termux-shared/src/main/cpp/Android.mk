LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_LDLIBS := -llog
LOCAL_MODULE := local-socket
LOCAL_SRC_FILES := local-socket.cpp
# Phase 8.5: socket fast path — throughput flags (C++: LOCAL_CFLAGS covers .cpp).
LOCAL_CFLAGS += -O3 -flto -fomit-frame-pointer -funroll-loops
# Android 15+ (API 35): enforce 16 KB page-size alignment.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -flto -Wl,-O1 -Wl,--gc-sections
include $(BUILD_SHARED_LIBRARY)
