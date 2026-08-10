package cp.player.kmp.util

import cp.player.kmp.util.PlatformContext

/**
 * 平台特定信息抽象（仅 ABI 与模块目录两个差异点）。
 *
 * - Android：[Build.SUPPORTED_ABIS] + `Context.filesDir/modules`
 * - Desktop：固定 ABI 列表 + `~/.kmp-pro/modules`
 */
expect object PlatformInfo {
    /** 当前平台支持的 ABI 列表（按优先级） */
    val supportedAbis: List<String>

    /**
     * 模块根目录路径。
     * @param context 平台上下文（Android 用以取 filesDir；Desktop 忽略）
     */
    fun modulesDirectory(context: PlatformContext): String

    /**
     * 平台默认下载目录（不存在时创建）。
     * @param context 平台上下文（Android 用以取外部文件目录；Desktop 忽略）
     */
    fun downloadsDirectory(context: PlatformContext): String

    /**
     * 应用数据目录（存放 downloads.json 等元数据，与 [modulesDirectory] 同级风格）。
     * @param context 平台上下文（Android 用以取 filesDir；Desktop 忽略）
     */
    fun dataDirectory(context: PlatformContext): String
}