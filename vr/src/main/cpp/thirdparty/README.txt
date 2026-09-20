Vendored from org.khronos.openxr:openxr_loader_for_android:1.1.63 (Apache-2.0):
- cpp/thirdparty/openxr/include/openxr/* = prefab/modules/headers/include/openxr/*
- jniLibs/arm64-v8a/libopenxr_loader.so =
  prefab/modules/openxr_loader/libs/android.arm64-v8a/libopenxr_loader.so

Vendored because AGP 8.1 cannot stage this AAR's prefab metadata
(find_package(openxr_loader) fails at CMake configure). Revisit if the
toolchain is ever upgraded past AGP 8.11 (SDK 0.13.x era).
