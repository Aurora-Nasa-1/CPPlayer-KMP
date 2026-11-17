package cp.player.app

import cp.player.core.util.DesktopDataDir
import kotlin.system.exitProcess

/**
 * Velopack 的「快速退出钩子」参数。
 *
 * 与 `app/build.gradle.kts` 里的 `packageWindowsVelopack` 是一对。那边用
 * `--skipVeloAppCheck` 跳过了 vpk 打包期的静态检查（「你没集成 Velopack SDK」），
 * **但运行期契约照旧存在** —— 安装器用自己的 `Update.exe` 以这些参数拉起主 exe，
 * 并要求进程「尽快退出」：
 *
 * | 参数 | 时机 | 时限 |
 * |------|------|------|
 * | `--veloapp-install <ver>` | 解包完成、安装尚未结束 | 30s |
 * | `--veloapp-uninstall <ver>` | 卸载之前 | 30s |
 * | `--veloapp-obsolete <ver>` | 覆盖升级前，跑在**旧版本**上 | 15s |
 * | `--veloapp-updated <ver>` | 覆盖升级后，跑在**新版本**上 | 15s |
 *
 * 超时的处理是**安装器直接杀掉进程**。被杀 = 非 0 退出码 = 安装器认为这一步失败，
 * 于是弹出它自己的「安装未完成」(Install Partially Succeeded) 提示 —— 而**应用
 * 其实已经装好了**，快捷方式、卸载项都写完了。
 *
 * 这正是此前 Windows 安装包的症状：双击 `Setup.exe` → CPPlayer 起来了（那其实就是
 * 钩子进程，只是它不知道自己是钩子，照样开了启动画面和主窗口）→ 半分钟后被杀 →
 * 报「安装未完成」→ 安装器再把应用正常启动一次（带 `VELOPACK_FIRSTRUN=true`）。
 * 用户看到的就是「启动一次、退出、报错、又启动一次」。
 *
 * ## 各钩子要做什么
 *
 * 官方要求钩子里**不能有任何 UI、不能长耗时**。安装 / 升级没有注册表写入、
 * 没有需要预热的解压；快捷方式与卸载项都归 Velopack 管。
 * **卸载**则要清掉应用自己的数据根目录 `~/.cpplayer` 与历史目录 `~/.kmp-pro`，
 * 否则重装后会继承旧设置、账号、插件、缓存和本地曲库索引。清理走
 * [DesktopDataDir.deleteAllData]，失败也只记日志，仍必须以 0 退出 —— 非 0 会被
 * 安装器当成卸载失败。
 *
 * ⚠️ **判据必须放在 `main()` 的第一行，不能放进 [runDesktopApp]**：`main()` 必须在任何
 * 框架初始化或建窗前执行钩子检测，确保 Velopack 安装/升级过程秒级响应。
 *
 * ⚠️ 也**不要**改成 `return`：`main` 返回只结束当前线程，Swing EDT / 托盘 / 音频这些
 * 非守护线程还在（钩子路径走不到那儿，但将来有人把这段挪位置就会踩）。
 * `exitProcess(0)` 才是「立刻、以成功码」退出的可靠写法。
 */
internal const val VELOPACK_UNINSTALL_ARG = "--veloapp-uninstall"

internal val VelopackHookArgs: Set<String> = setOf(
    "--veloapp-install",
    "--veloapp-obsolete",
    "--veloapp-updated",
    VELOPACK_UNINSTALL_ARG,
)

/**
 * 识别出的钩子调用：参数本身 + 紧随其后的版本号（取不到时为空串）。
 *
 * 单独拆出来是因为 [exitIfVelopackHook] 会 `exitProcess`，在测试里没法直接调 ——
 * 而「哪四条参数算钩子、版本号怎么取」恰恰是最容易写错、又最难在真机上复现的部分。
 */
internal data class VelopackHook(val arg: String, val version: String)

/**
 * 若 [args] 里带着 Velopack 钩子参数，返回它；否则返回 null。
 *
 * 按**全等**匹配单个参数，不做前缀/子串匹配：应用自己的参数未来可能出现
 * `--veloapp-*` 之外的形态，子串匹配会误伤。
 */
internal fun velopackHookOf(args: Array<String>): VelopackHook? {
    val index = args.indexOfFirst { it in VelopackHookArgs }
    if (index < 0) return null
    return VelopackHook(args[index], args.getOrNull(index + 1).orEmpty())
}

/**
 * 卸载钩子的数据清理。其它钩子原样返回，不做任何 I/O。
 *
 * 代码与 [exitIfVelopackHook] 分开是为了能在测试里注入替身 —— 后者会 `exitProcess`，
 * 而「只有卸载才清数据」与「清理失败也继续退出」这两条都不能靠真机卸载来验证。
 */
internal fun clearDataForVelopackHook(
    hook: VelopackHook,
    deleteAllData: () -> Boolean = { DesktopDataDir.deleteAllData() },
) {
    if (hook.arg != VELOPACK_UNINSTALL_ARG) return
    val deleted = runCatching(deleteAllData).getOrElse {
        System.err.println("[CPPlayer] Velopack 卸载前清理数据异常：${it.message}")
        false
    }
    if (deleted) {
        println("[CPPlayer] Velopack 卸载前已清除应用数据")
    } else {
        System.err.println("[CPPlayer] Velopack 卸载前未能完全清除应用数据")
    }
}

/**
 * 若本次进程是被 Velopack 安装器当成钩子拉起的，立即以 0 退出；否则原样返回。
 *
 * 必须在任何 UI / 初始化之前调用（理由见 [VelopackHookArgs]）。
 *
 * 一处判断，四处受益：装新包、覆盖升级（旧版本 + 新版本各一次）、卸载 ——
 * 少一条都会在对应链路上重现「安装未完成」/「卸载未完成」。
 */
internal fun exitIfVelopackHook(args: Array<String>) {
    val hook = velopackHookOf(args) ?: return
    clearDataForVelopackHook(hook)
    println("[CPPlayer] Velopack 钩子 ${hook.arg} ${hook.version.ifEmpty { "?" }} → 跳过启动流程，立即退出")
    System.out.flush()
    // 退出码必须是 0：非 0 会被安装器当成这一步失败，照样弹「安装未完成」。
    exitProcess(0)
}
