     .global blob
     .global blob_size
     .section .rodata
 blob:
 // glibc fork: 64-bit only (Android 15+/API 35). 32-bit ARMs (armeabi-v7a,
 // x86) purged; grun + glibc loader require arm64-v8a / x86_64.
#if defined __x86_64__
     .incbin "bootstrap-x86_64.zip"
#elif defined __aarch64__
     .incbin "bootstrap-aarch64.zip"
#else
# error Unsupported arch (glibc fork supports only arm64-v8a and x86_64)
#endif
 1:
 blob_size:
     .int 1b - blob
