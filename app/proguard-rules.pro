# Native code looks these up by name through JNI.
-keepclasseswithmembernames class * {
    native <methods>;
}
