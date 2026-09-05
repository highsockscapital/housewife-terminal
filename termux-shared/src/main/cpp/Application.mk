APP_STL := c++_static
# glibc fork: 64-bit only (Android 15+/API 35).
APP_ABI := arm64-v8a x86_64
APP_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
