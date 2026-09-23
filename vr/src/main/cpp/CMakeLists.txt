cmake_minimum_required(VERSION 3.22.1)
project("vr" LANGUAGES C)

# NOTE: we vendor the Khronos loader (headers + arm64 .so) instead of using
# the prefab package: AGP 8.1 does not stage this AAR's prefab metadata, so
# find_package(openxr_loader) fails at configure time. Source:
# org.khronos.openxr:openxr_loader_for_android:1.1.63 (Apache-2.0).

add_library(native_app_glue STATIC
    ${ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c)
target_include_directories(native_app_glue PUBLIC
    ${ANDROID_NDK}/sources/android/native_app_glue)

add_library(openxr_loader SHARED IMPORTED)
set_target_properties(openxr_loader PROPERTIES
    IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopenxr_loader.so)

add_library(vr SHARED vr_room.c)
# The framework loads ANativeActivity_onCreate out of this .so via dlsym, so
# nothing references it at link time and the static glue object would be
# discarded. Force it undefined to pull the object in (hello_xr pattern).
target_link_options(vr PRIVATE "-u ANativeActivity_onCreate")
target_include_directories(vr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/openxr/include
    ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty)
target_link_libraries(vr
    native_app_glue
    openxr_loader
    android
    log
    m
    EGL
    GLESv3)
