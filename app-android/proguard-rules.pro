# CPPlayer 安卓 release 的 R8 规则。
#
# 总原则：**只 keep 有真实反射/JNI 绑定的点**。Compose / media3 / Coil / Ktor /
# OkHttp / Voyager 等 AAR 均自带 consumer rules，这里不重复。
# 每条规则都对应一次真实风险盘点（2026-10-04，开 R8 时逐点核实过）。

# ---------------------------------------------------------------------------
# 1. JNI（最高风险点，缺了必崩）
# ---------------------------------------------------------------------------
# JniProvider 的 external fun 走 JNI 符号名绑定（native 侧导出
# `Java_cp_player_core_provider_JniProvider_nativeCallApi` 等）。
# R8 重命名类或方法 ⇒ UnsatisfiedLinkError，音源模块整体失联。
# 类名、方法名、签名必须原样保留。
-keep class cp.player.core.provider.JniProvider { *; }

# ---------------------------------------------------------------------------
# 2. 按方法名的运行时反射
# ---------------------------------------------------------------------------
# MusicBackend.kt / ModuleManager.kt：`provider.javaClass.getMethod("getLoadError")`
# —— 按名字找方法。R8 重命名 getLoadError ⇒ NoSuchMethodException。
# 反射走的是实例的 javaClass（类名无所谓），所以 keepclassmembers 保住方法名即可。
-keepclassmembers class cp.player.** {
    java.lang.String getLoadError();
}

# SyncDiscovery.jvm.kt 的 Class.forName("android.os.Build") 找的是系统类，
# R8 不会重命名 android.**，无需规则。

# ---------------------------------------------------------------------------
# 3. kotlinx.serialization（官方规则，包名替换为本项目 cp.player.**）
# ---------------------------------------------------------------------------
# @Serializable 广布于 sync 协议 / 一起听模型 / 账号存储 / 更新检查等，
# 编译期生成的 `$$serializer` 与 `Companion.serializer()` 是反射入口。
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class cp.player.**$$serializer { *; }
-keepclassmembers class cp.player.** {
    *** Companion;
}
-keepclasseswithmembers class cp.player.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# 4. Voyager 返回栈的 Java 序列化
# ---------------------------------------------------------------------------
# Voyager 在安卓上用 Java 序列化保存返回栈（锁屏/切后台触发）。
# proguard-android-optimize.txt 已含 Serializable 通用规则
# （serialVersionUID / writeObject / readObject），这里无需重复；
# core 领域模型实现的 cp.player.core.util.JavaSerializable 沿用默认规则即可。

# ---------------------------------------------------------------------------
# 6. 安卓上不存在的 JDK 类（Ktor）
# ---------------------------------------------------------------------------
# Ktor 的 io.ktor.util.debug.IntellijIdeaDebugDetector 引用 java.lang.management.*
# （JVM 专有，安卓没有）。这只为「IDE 里挂调试器」检测服务，安卓运行时路径
# 根本走不到，-dontwarn 安全。首次开 R8 时由 missing_rules.txt 生成（2026-10-04）。
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# ---------------------------------------------------------------------------
# 5. 崩溃可定位性：保留行号（retrace 可映射回源行）
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# 7. 安卓上不存在的 JDK 类（Rhino —— 歌词插件的 JS 运行时）
# ---------------------------------------------------------------------------
# `core` 的 jvmMain 用 Mozilla Rhino 跑 Lyrico 插件（androidMain 依赖 jvmMain，
# 所以安卓同样带这份运行时）。Rhino 的 JavaToJSONConverters 在**静态初始化**里
# 建「任意 Java 对象 → JSON」的转换表，其中 JavaBean 那一格引用了 java.beans.*
# （JDK 专有，安卓没有，核心库脱糖也不含它）。
# R8 把「引用了不存在的类」当**硬错误**（不是 warning）⇒ minifyReleaseWithR8 直接
# 失败（2026-10-07 v1.4.7 首发即挂，报 5 条 Missing class java.beans.*）。
#
# 我们的宿主只把 **JS 值** 转成字符串（RhinoPluginRuntime 里 Context.toString），
# 从不把 Java Bean 喂进 JSON —— 该分支运行时走不到，-dontwarn 安全。
# ⚠️ 真要让插件序列化 Java 对象时，这里得换成真正的依赖（不是加 keep 能解决的）。
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# ---------------------------------------------------------------------------
# 8. 歌词对外投放的第三方 SDK
# ---------------------------------------------------------------------------
# 这三个 AAR 都是**跨进程 / 被外部进程按名字找**的契约类，混淆掉任何一个都会
# 变成「编译能过、运行时对方收不到」——最难查的一类问题。
#
#  - 词幕（Lyricon）：AIDL 生成的 Stub/Proxy 类名与 Parcelable 字段名参与跨进程封送；
#    另外它的 Provider 模型（Song / RichLyricLine / LyricWord）会被词幕侧反序列化。
#  - SuperLyric：`com.hchen.superlyricapi.**` 是发布者/接收方共用的契约，且
#    SuperLyricHelper 通过隐藏的 `android.os.ServiceManager` 找系统服务。
#  - Lyric Getter：`cn.lyric.getter.api.**` 会被 Xposed 模块按**类名+成员名** hook，
#    改名等于把 hook 点弄丢。
#
# ⚠️ 这三条是「协议名字」而不是「实现细节」，改动它们前先确认对方进程也能跟着改。
-keep class io.github.proify.lyricon.** { *; }
-dontwarn io.github.proify.lyricon.**
-keep class com.hchen.superlyricapi.** { *; }
-dontwarn com.hchen.superlyricapi.**
-keep class cn.lyric.getter.api.** { *; }
-dontwarn cn.lyric.getter.api.**
# SuperLyricApi 引用了隐藏框架类（见 LyriconBridge 的注释）；缺失只是该路径不可用，
# 不是必需依赖。
-dontwarn android.os.ServiceManager

# HyperOS 焦点通知：载荷由 focus-api 生成 JSON（`miui.focus.*` 键），
# 模型类参与 kotlinx.serialization 的序列化 ⇒ 保留其 serializer 与字段名。
-keep class com.xzakota.hyper.notification.** { *; }
-keepclassmembers class com.xzakota.hyper.notification.** {
    *** Companion;
    *** serializer(...);
}
-dontwarn com.xzakota.hyper.notification.**

# 超级岛前台服务由 AndroidManifest 按全限定名实例化，必须保留。
-keep class cp.player.core.lyricpush.XiaomiSuperIslandLyricService { *; }
