package cp.player.app.platform

import cp.player.core.util.defaultSettingsStorage

/**
 * 窗口装饰路线的三态选择（建窗**之前**必须定下来，`undecorated` 不能事后补）。
 *
 * ## 取值来源（优先级从高到低，与 `DesktopRenderTuning` 同一范式）
 *
 * 1. JVM 启动参数 `-Dcp.player.windowDecor=…`（自救通道：万一 JBR 标题栏在某台机器上
 *    装不上——那时窗口会**保留系统标题栏**，很难看——用户无需进设置页就能切回）；
 * 2. 环境变量 `CPPLAYER_WINDOW_DECOR`（JavaExec / 打包产物都继承环境，等价于 1）；
 * 3. 持久化项 `~/.cpplayer/cp_player_prefs.properties` 的 `window_decor`
 *    （目前没有设置页入口，手工编辑即可；一旦写死，auto 的自动探测不再生效）；
 * 4. [Mode.AUTO]：JBR 可用（Windows/macOS + `WindowDecorations`，见 [JbrWindowChrome]）
 *    ⇒ JBR 路（系统边框 + 客户区盖过标题栏，原生贴边吸附 / Snap Layouts / 圆角白拿）；
 *    否则 ⇒ 无边框自绘路（`Undecorated` 纯 `WS_POPUP` + 手补圆角 / 最大化）。
 *
 * 取值只认 `auto` / `jbr` / `undecorated`（大小写不敏感，`jbr` 别名 `system`/`native`，
 * `undecorated` 别名 `borderless`/`off`）；非法值按 [Mode.AUTO] 处理并打日志。
 *
 * ⚠️ 本对象**只读**。设置页想写入时走 `defaultSettingsStorage().putString(KEY, …)`，
 * 注意 shared-storage 的多写者覆盖问题（见 `SETTINGS_REDESIGN.md` B9）。
 */
object WindowDecorChoice {

    /** 三种路线。 */
    enum class Mode { AUTO, JBR, UNDECORATED }

    /** 自救通道：JVM 系统属性与环境变量名。 */
    const val PROP: String = "cp.player.windowDecor"
    const val ENV: String = "CPPLAYER_WINDOW_DECOR"

    /** 持久化键（写入 `~/.cpplayer/cp_player_prefs.properties`）。 */
    const val KEY: String = "window_decor"

    /** 本次解析的实际来源，供启动日志说明（探测对象为 null 表示来源未知）。 */
    var lastSource: String? = null
        private set

    /** 解析本次运行的窗口装饰路线。异常一律吞掉退回 [Mode.AUTO]：装饰是外观，不能挡启动。 */
    fun resolve(): Mode = runCatching { resolveOrNull() }
        .onFailure {
            println("[WindowDecorChoice] 解析窗口装饰选择失败，按 auto 处理：$it")
        }
        .getOrDefault(Mode.AUTO) ?: Mode.AUTO

    private fun resolveOrNull(): Mode? {
        System.getProperty(PROP)?.takeIf { it.isNotBlank() }?.let { raw ->
            lastSource = "-D$PROP"
            return parse(raw)
        }
        System.getenv(ENV)?.takeIf { it.isNotBlank() }?.let { raw ->
            lastSource = "env $ENV"
            return parse(raw)
        }
        defaultSettingsStorage().getString(KEY)?.takeIf { it.isNotBlank() }?.let { raw ->
            lastSource = "$KEY（持久化）"
            return parse(raw)
        }
        lastSource = "默认（auto = JBR 可用则走 JBR 路）"
        return Mode.AUTO
    }

    private fun parse(raw: String): Mode = when (raw.trim().lowercase()) {
        "auto" -> Mode.AUTO
        "jbr", "system", "native" -> Mode.JBR
        "undecorated", "borderless", "off" -> Mode.UNDECORATED
        else -> {
            println("[WindowDecorChoice] 无法识别的窗口装饰值「$raw」（认 auto/jbr/undecorated），按 auto 处理")
            lastSource = "-D$PROP（值非法）"
            Mode.AUTO
        }
    }
}
