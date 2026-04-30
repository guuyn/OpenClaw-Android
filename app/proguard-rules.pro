# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in Android SDK tools.
# For more details, see
#   https://developer.android.com/build/shrink-code

# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class **$* {
    *** Companion;
}
-keepclasseswithmembers class **$* {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# TensorFlow Lite and LiteRT-LM
-dontwarn com.google.auto.value.AutoValue
-dontwarn org.tensorflow.lite.**
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }

# Kotlin 2.3.0 compatibility
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.jvm.internal.**
-keep class kotlin.reflect.jvm.internal.** { *; }

# OkHttp and Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }

# Sherpa-ONNX
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    private native <methods>;
}

# PersonalCenter - ViewModelFactory used by reflection
-keep class ai.openclaw.android.personalcenter.PersonalCenterViewModelFactory { *; }
-keep class ai.openclaw.android.personalcenter.PersonalCenterViewModel { *; }
-keep class ai.openclaw.android.personalcenter.** { *; }

# ==================== Bugly ====================
# Bugly SDK 混淆规则
-dontwarn com.tencent.bugly.**
-keep public class com.tencent.bugly.**{*;}
# 保持 native 方法不被混淆（nativecrashreport 依赖）
-keep class com.tencent.bugly.crashreport.protocols.** { *; }