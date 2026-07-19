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
}