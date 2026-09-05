APP_STL := c++_static
# Housewife: ARM64 phones only (Android 15+/API 35).
APP_ABI := arm64-v8a
APP_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
