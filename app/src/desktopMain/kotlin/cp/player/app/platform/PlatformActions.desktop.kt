package cp.player.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.awt.Desktop
import java.net.URI

actual fun isAndroidPlatform(): Boolean = false
actual fun desktopPlatform(): String = when {
    System.getProperty("os.name", "").contains("win", ignoreCase = true) -> "windows"
    System.getProperty("os.name", "").contains("linux", ignoreCase = true) -> "linux"
    else -> "desktop"
}

/** 桌面端无「计费网络」概念，恒按非计费（WiFi）处理。 */
actual fun isNetworkMetered(): Boolean = false

/** 桌面端网络不变化，先发一次 false 后不再发射。 */
actual fun networkMeteredChanges(): Flow<Boolean> = flowOf(false)

/** 桌面端没有「系统圆滑度」一说，跟随字体文件自带的默认实例（ROND = 0，方正）。 */
actual fun defaultFontRoundness(): Int = 0

actual fun saveQrCodeToGallery(base64Image: String, fileName: String) {}

actual fun openTargetApp(packageName: String) {}

actual fun isPackageInstalled(packageName: String): Boolean = false

actual fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Exception) {}
}

actual fun downloadUpdate(url: String, fileName: String) = openUrl(url)

/**
 * 清空 Coil 的内存 / 磁盘缓存。
 *
 * 原先这里是 `= true` 的空实现 —— 设置页点了「清除图片缓存」会显示成功，
 * 实际什么都没清。加了封面取色之后这个谎言更明显：封面换不掉，主题就跟着换不掉。
 *
 * 同时清掉 [cp.player.app.ui.theme.CoverSeedCache]：它按封面 URL 缓存种子色，
 * 不清的话「清完缓存重新取色」的预期不成立。
 */
actual fun clearImageCache(): Boolean {
    cp.player.app.ui.theme.CoverSeedCache.clear()
    return runCatching {
        val loader = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        true
    }.getOrDefault(false)
}

actual fun imageCacheSizeBytes(): Long = runCatching {
    coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE).diskCache?.size ?: -1L
}.getOrDefault(-1L)

actual fun openInFileManager(path: String): Boolean = runCatching {
    val dir = java.io.File(path)
    if (!dir.isDirectory) return@runCatching false
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Desktop.getDesktop().open(dir)
        true
    } else {
        false
    }
}.getOrDefault(false)

actual fun requestMediaScanPermission() {
    // 桌面无需运行时媒体读取权限，空实现
}

actual fun setOnMediaPermissionGranted(callback: (() -> Unit)?) {
    // 桌面无授权流程，无需保存回调
}

/** 桌面无电池优化策略，按「已忽略」处理，设置页不引导。 */
actual fun isIgnoringBatteryOptimizations(): Boolean = true

actual fun requestIgnoreBatteryOptimizations() {
    // 桌面空操作
}

/**
 * 桌面端的「激进保活」是空操作。
 *
 * 桌面没有 Wi-Fi 省电丢组播包、也没有 LMK 按 `oomAdj` 杀后台进程这两件事 ——
 * 「待机」在桌面上是托盘常驻的问题（进程本来就活着），不需要持锁。
 * 恒返回 false 让设置页如实显示「本平台不适用」，而不是假装已生效。
 */
actual fun applyAggressiveStandby(enabled: Boolean) {
    // 桌面空操作
}

actual fun isAggressiveStandbyActive(): Boolean = false

/**
 * 桌面端用 Esc 承担安卓返回键的角色。
 *
 * 这里只负责「注册/注销」，真正的派发在窗口级按键回调里（见 `Main.kt`）——
 * 因为 Compose 的按键回调只能挂在 Window 上，组件内部拿不到全局按键流。
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val token = DesktopBackDispatcher.register { latest() }
        onDispose { DesktopBackDispatcher.unregister(token) }
    }
}

/**
 * 桌面端「关闭窗口时的行为」。
 *
 * 为什么要给用户一个改的地方：关窗确认框里勾了「不再提示」之后，选择就被记死了 ——
 * 没有这个入口，用户想把「直接退出」改回「最小化到托盘」只能去手改 prefs 文件。
 *
 * ⚠️ 选中项直接复用 [DesktopCloseBehavior] 的 `ordinal` 作为下标，
 * 所以选项列表的顺序**必须**与枚举声明顺序一致（ASK / TRAY / EXIT）。
 */
@Composable
actual fun PlatformCloseBehaviorSetting(index: Int, total: Int) {
    val strings = cpStrings().messageNotify
    var current by remember { mutableStateOf(DesktopCloseBehavior.load()) }
    SettingsSection(strings.closeBehaviorLabel) {
        SettingsSegmentedItem(
            title = strings.closeBehaviorLabel,
            subtitle = strings.closeBehaviorHint,
            options = listOf(
                strings.closeBehaviorAsk,
                strings.closeDialogMinimize,
                strings.closeDialogExit,
            ),
            selectedIndex = current.ordinal,
            onSelect = { selected ->
                val value = DesktopCloseBehavior.values().getOrElse(selected) { DesktopCloseBehavior.ASK }
                DesktopCloseBehavior.save(value)
                current = value
            },
            index = index,
            total = total,
        )
    }
}
