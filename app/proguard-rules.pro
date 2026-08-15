# Add project specific ProGuard rules here.

# Preserve line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# JNI / native: keep all native method names and any class that declares them.
# LiteRT-LM loads native (.so) code that resolves Java/Kotlin symbols by their
# original names via JNI. Obfuscating or stripping them causes crashes and
# slow/failed engine initialization.
# ---------------------------------------------------------------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# LiteRT-LM (com.google.ai.edge.litertlm) - keep everything. The Engine,
# EngineConfig, Backend, Conversation, Sampler/Conversation config and the
# Kotlin Companion factories (e.g. Contents.Companion.of) are referenced
# reflectively and from native code.
# ---------------------------------------------------------------------------
-keep class com.google.ai.edge.** { *; }
-keep interface com.google.ai.edge.** { *; }
-keepclassmembers class com.google.ai.edge.** { *; }
-dontwarn com.google.ai.edge.**

# Underlying LiteRT / TensorFlow Lite runtime and GPU delegate classes loaded
# by the native engine. Keeping these lets GPU init succeed instead of failing
# slowly and falling back to CPU.
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-keep class com.google.android.gms.tflite.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.android.gms.tflite.**

# ---------------------------------------------------------------------------
# Kotlin metadata / coroutines - LiteRT-LM is Kotlin based and uses coroutines
# for the async streaming callbacks (sendMessageAsync). Preserve metadata so
# Companion objects, data classes and suspend machinery keep working.
# ---------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$Companion { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.**

# Keep enum values()/valueOf() used by config classes (e.g. Backend variants).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
