# CPPlayer Compose Desktop ProGuard 规则与排除配置
#
# 针对桌面端 release 包的代码收缩、优化与排除。
# 全面覆盖跨平台 JNI、JNA、ServiceLoader SPI、网络引擎、序列化与媒体控制链路。

# ---------------------------------------------------------------------------
# 1. 基础配置、警告抑制与资源适配
# ---------------------------------------------------------------------------
-dontwarn **
-ignorewarnings
-dontnote **
-keepattributes *Annotation*,AnnotationDefault,Exceptions,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保证 ServiceLoader 机制在 ProGuard 代码收缩时能够正确适配 SPI 服务提供者文件
-adaptresourcefilenames
-adaptresourcefilecontents META-INF/services/**

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
# 4. Java 序列化与枚举类型 (Voyager 页面返回栈、状态持久化与反射操作)
# ---------------------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** getEntries();
}

-keepnames class * implements java.io.Serializable

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---------------------------------------------------------------------------
# 5. 桌面音频播放引擎 (ComposeMediaPlayer & Nucleus Rodio Rust JNI / FileKit)
# ---------------------------------------------------------------------------
# 桌面音频链走 composemediaplayer-audio -> nucleus.rodio -> nucleus_rodio 原生动态库。
# 必须完整保留其类签名、JNI 原生方法以及底层回调监听，避免 UnsatisfiedLinkError 或 NoSuchMethodError。
-keep class io.github.kdroidfilter.composemediaplayer.** { *; }
-keep class dev.nucleusframework.** { *; }
-keepclasseswithmembernames class dev.nucleusframework.** { native <methods>; }
-keep class io.github.vinceglb.filekit.** { *; }
-dontwarn io.github.kdroidfilter.**
-dontwarn dev.nucleusframework.**
-dontwarn io.github.vinceglb.filekit.**

# ---------------------------------------------------------------------------
# 6. JNA (Java Native Access) 与系统集成 (Windows SMTC / COM / DWM / JnaProvider)
# ---------------------------------------------------------------------------
-keep interface * extends com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Library { *; }

-keep interface * extends com.sun.jna.Callback { *; }
-keepclassmembers class * implements com.sun.jna.Callback {
    public <methods>;
}

# JNA Structure 依赖反射读取字段排布 (public/protected/package-private)，必须完整保留所有字段
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
}

-keep class * extends com.sun.jna.IntegerType { *; }
-keep class * extends com.sun.jna.PointerType { *; }

-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# ---------------------------------------------------------------------------
# 7. Linux DBus / MPRIS 媒体控制集成 (JMTC & DBus-Java 5.x)
# ---------------------------------------------------------------------------
# JMTC 在 Linux 上依赖 com.github.hypfvieh:dbus-java-core 及 native-unixsocket 传输。
# DBus 传输实现依赖 ServiceLoader.load(ITransportProvider.class)，必须保留实现类。
-keep class io.github.selemba1000.** { *; }
-keep class org.freedesktop.dbus.** { *; }
-keep class com.github.hypfvieh.** { *; }
-dontwarn io.github.selemba1000.**
-dontwarn org.freedesktop.dbus.**
-dontwarn com.github.hypfvieh.**

# ---------------------------------------------------------------------------
# 8. QuickJS 脚本引擎 (原生 JNI 符号与方法)
# ---------------------------------------------------------------------------
-keep class com.dokar.quickjs.** { *; }
-keepclasseswithmembernames class com.dokar.quickjs.** { native <methods>; }

# ---------------------------------------------------------------------------
# 9. Kotlinx 序列化与协程
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
# 10. 网络栈 (Ktor 客户端/服务端 CIO、OkHttp、Okio)
# ---------------------------------------------------------------------------
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Ktor OkHttp Client 与 Coil OkHttp 引擎依赖 OkHttp/Okio
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase {
    <fields>;
    <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# 11. 日志框架 (SLF4J 2.x ServiceProvider)
# ---------------------------------------------------------------------------
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ---------------------------------------------------------------------------
# 12. Sentry Core (桌面端监控)
# ---------------------------------------------------------------------------
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# ---------------------------------------------------------------------------
# 13. UI 框架与多平台组件 (Voyager, Coil, HypnoticCanvas, MaterialKolor, QRose, Resources)
# ---------------------------------------------------------------------------
-keep class cafe.adriel.voyager.** { *; }
-dontwarn cafe.adriel.voyager.**

-keep class coil3.** { *; }
-dontwarn coil3.**

-keep class com.mocharealm.accompanist.** { *; }
-keep class com.mikepenz.hypnoticcanvas.** { *; }
-keep class com.materialkolor.** { *; }

# 二维码生成库 QRose
-keep class io.github.alexzhirkevich.qrose.** { *; }
-dontwarn io.github.alexzhirkevich.qrose.**

# Compose Multiplatform 运行时组件与资源加载器
-keep class org.jetbrains.compose.resources.** { *; }
-keep class org.jetbrains.compose.components.** { *; }

# ---------------------------------------------------------------------------
# 14. 平台差异与 Android 专有类排除 (排除桌面端不存在的传递依赖)
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
