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
