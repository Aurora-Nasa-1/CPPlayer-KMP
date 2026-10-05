package cp.player.app.platform

import cp.player.core.util.defaultSettingsStorage

/**
 * 桌面端点窗口关闭按钮时的去向。
 *
 * ## 为什么需要这个开关
 *
 * 私信通知依赖**进程活着**：关掉窗口 = 进程退出 = 收不到任何提醒。
 * 但用户按 X 时想的多半是「先关掉，一会儿再看」—— 所以既不能一律退出（丢通知），
 * 也不能一律隐藏（用户以为已经关了，应用却赖在进程里）。
 *
 * 首次按 X 时问一次，用户勾「不再提示」就把选择记在这里。
 *
 * ## 默认 [ASK]
 *
 * 默认每次询问，而不是默认托盘或默认退出：这个选择有**外部可见的副作用**
 * （进程是否常驻、托盘里是否多一个图标），替用户默认掉不合适。
 */
enum class DesktopCloseBehavior {
    /** 每次按 X 都问。 */
    ASK,

    /** 隐藏窗口，进程留在托盘里继续收消息通知。 */
    TRAY,

    /** 直接退出进程。 */
    EXIT;

    companion object {
        /**
         * ⚠️ 与窗口尺寸共用 `cp_player_window` 命名空间，**不要**新建一个：
         * 桌面实现是「构造时全量读入 + 每次写入全量回写」，多一个 namespace 就多一份
         * 互不相识的快照；而这两件事都属于「窗口本身的状态」，放一起最自然。
         */
        private const val KEY = "desktop.close.behavior"

        private fun storage() = defaultSettingsStorage(namespace = "cp_player_window")

        fun load(): DesktopCloseBehavior {
            val raw = runCatching { storage().getString(KEY) }.getOrNull()
            return when (raw) {
                "tray" -> TRAY
                "exit" -> EXIT
                else -> ASK
            }
        }

        fun save(value: DesktopCloseBehavior) {
            val raw = when (value) {
                ASK -> "ask"
                TRAY -> "tray"
                EXIT -> "exit"
            }
            runCatching { storage().putString(KEY, raw) }
        }
    }
}
