# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# QuickJS JNI
-keep class com.lxpro.source.script.quickjs.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.lxpro.** { *** Companion; }
-keepclasseswithmembers class com.lxpro.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase