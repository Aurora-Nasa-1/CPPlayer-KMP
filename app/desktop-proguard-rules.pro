# CPPlayer Compose Desktop ProGuard 规则与排除配置
#
# 针对桌面端 release 包的代码收缩、优化与排除。

# ---------------------------------------------------------------------------
# 1. 基础配置与警告抑制
# ---------------------------------------------------------------------------
-dontwarn **
-ignorewarnings
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# 2. 应用程序入口点
# ---------------------------------------------------------------------------
-keep public class cp.player.app.MainKt {
    public static void main(java.lang.String[]);
}

# ---------------------------------------------------------------------------
# 3. 业务代码与跨平台 Core 模块
# ---------------------------------------------------------------------------
-keep class cp.player.app.** { *; }
-keep class cp.player.core.** { *; }
-keepclassmembers class cp.player.** {
    java.lang.String getLoadError();
}

# ---------------------------------------------------------------------------
# 4. JNA (Java Native Access) 与系统集成 (Windows SMTC / Linux DBus / JMTC)
# ---------------------------------------------------------------------------
-keepclassmembers class * extends com.sun.jna.Library {
    public <methods>;
    public <fields>;
}
-keepclassmembers class * extends com.sun.jna.Structure {
    public <fields>;
}
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

-keep class io.github.selemba1000.** { *; }
-keep class org.freedesktop.dbus.** { *; }
-dontwarn io.github.selemba1000.**
-dontwarn org.freedesktop.dbus.**

# ---------------------------------------------------------------------------
# 5. QuickJS 脚本引擎 (原生 JNI 符号与方法)
# ---------------------------------------------------------------------------
-keep class com.dokar.quickjs.** { *; }
-keepclasseswithmembernames class com.dokar.quickjs.** { native <methods>; }

# ---------------------------------------------------------------------------
# 6. Kotlinx 序列化与协程
# ---------------------------------------------------------------------------
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory

# ---------------------------------------------------------------------------
# 7. Ktor (客户端与 CIO 服务端)
# ---------------------------------------------------------------------------
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ---------------------------------------------------------------------------
# 8. Sentry Core (桌面端监控)
# ---------------------------------------------------------------------------
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# ---------------------------------------------------------------------------
# 9. UI 框架与多平台组件 (Voyager, Coil, HypnoticCanvas, MaterialKolor)
# ---------------------------------------------------------------------------
-keep class cafe.adriel.voyager.** { *; }
-dontwarn cafe.adriel.voyager.**

-keep class coil3.** { *; }
-dontwarn coil3.**

-keep class com.mocharealm.accompanist.** { *; }
-keep class com.mikepenz.hypnoticcanvas.** { *; }
-keep class com.materialkolor.** { *; }

# ---------------------------------------------------------------------------
# 10. 平台差异与 Android 专有类排除 (排除桌面端不存在的传递依赖)
# ---------------------------------------------------------------------------
-dontwarn android.**
-dontwarn androidx.**
-dontwarn moe.shizuku.**
-dontwarn rikka.shizuku.**
-dontwarn com.xzakota.**
-dontwarn com.hchen.**
-dontwarn cn.lyric.**
-dontwarn io.github.proify.lyricon.**
-dontwarn org.lsposed.hiddenapibypass.**
-dontwarn sun.misc.**
-dontwarn java.lang.management.**
-dontwarn java.lang.instrument.**
