# Keep the NativeActivity entry point and any JNI-registered classes untouched.
-keep class com.spatiallauncher.app.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
