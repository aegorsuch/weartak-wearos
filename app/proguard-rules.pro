# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# For Ditto
-keep class live.ditto.internal.swig.ffi.** { *; }
-keep class live.ditto.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }

# For Gson
# Keep generic type signatures used for reflection
-keepattributes Signature

# Keep names of fields (important for Gson's field binding)
-keepattributes *Annotation*

# Keep Gson's internal TypeToken use and your custom anonymous subclasses
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken {
    <init>(...);
}

# Preserve your model classes (adjust the package!)
-keep class com.aitech.weartak_civ.data.DataRepository {
    <fields>;
}

# Optional: keep SerializedName annotation bindings
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep classes that are used in fromJson() / toJson() calls if not already covered
# Useful if you use reflection-based Gson adapters
-keep class com.aitech.weartak_civ.** { *; }

# ONNX Runtime minify crash prevention in release build (Java API + JNI bindings)
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# JNI resolves these instance native methods by their generated symbol names.
-keep class com.aitech.weartak_civ.service.openvpn.OpenVpnNativeBridge { *; }

# Jackson optional Java desktop APIs
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn com.fasterxml.jackson.databind.ext.Java7SupportImpl

# Apache Commons Lang optional MethodHandle support
-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn org.apache.commons.lang3.function.MethodInvokers
