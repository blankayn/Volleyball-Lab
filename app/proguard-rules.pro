# MediaPipe Tasks loads task graphs and calculator options reflectively from the
# packaged .task bundle. R8 cannot see those call sites, so the classes must be
# kept explicitly or pose detection dies only in release builds.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**

# AutoValue types used across the MediaPipe Tasks API surface.
-keep class * extends com.google.auto.value.AutoValue { *; }

# kotlinx.serialization keeps generated serializers on the companion; without
# this the type-safe navigation routes fail to deserialize in release builds.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.volleyballlab.player.** {
    *** Companion;
}
-keepclasseswithmembers class com.volleyballlab.player.** {
    kotlinx.serialization.KSerializer serializer(...);
}
