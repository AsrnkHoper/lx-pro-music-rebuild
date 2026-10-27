# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# WindowManager（可选反射依赖，运行时不会真的加载 missing class）
-dontwarn androidx.window.**
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

# QuickJS JNI
-keep class com.lxpro.source.script.quickjs.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers class com.lxpro.** { *** Companion; }
-keepclasseswithmembers class com.lxpro.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase

# Lyric Getter API（墨·状态栏歌词协议）：Xposed 模块按包名/类名/方法签名 hook，
# 混淆或裁剪任一成员都会让系统歌词失效，整包保留
-keep class cn.lyric.getter.api.** { *; }

# opencc4j（歌词繁简转换）：release 开 R8 时必须 keep 所有工具类，
# 否则 ZhConverterUtil.toTraditional() 被裁 → ClassNotFoundException → runCatching 吞掉 → 返回简体
-keep class com.github.houbb.opencc4j.** { *; }
-dontwarn com.github.houbb.opencc4j.**
# 字典资源 data/dictionary/*.txt 在 opencc4j jar 的 classpath 根路径，
# R8 不裁非 Android res/ 的 classpath 资源，无需额外 keep