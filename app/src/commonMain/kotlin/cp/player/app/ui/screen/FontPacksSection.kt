package cp.player.app.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cp.player.app.i18n.FontPackStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.model.formatBytes
import cp.player.app.ui.theme.AppFontPack
import cp.player.app.ui.theme.FontPackPhase
import cp.player.app.ui.theme.FontPackStatus
import cp.player.app.ui.theme.downloadFontPack
import cp.player.app.ui.theme.platformSupportsFontPacks
import cp.player.app.ui.theme.rememberFontPackStatuses
import cp.player.app.ui.theme.removeFontPack
import cp.player.app.ui.component.CpText

/**
 * 「字库」设置区：中日文字体的现状与下载。
 *
 * ## 为什么两端共用同一份 UI
 *
 * 桌面端字体**内置**、Android **下载**，看起来是两件事，但用户要看到的信息完全相同：
 * 一共几款字体、各自负责什么、现在能不能用、占多少体积。
 * 差别只在「最后一列能不能点」—— 桌面端 READY 且删除无意义（`removeFontPack` 是空实现），
 * 所以 `enabled` 直接按平台关掉，而不是再写一套桌面端专用的排版。
 * 两套排版的下场是两份文案漂移（本仓库在返回键上吃过一次）。
 *
 * ## 为什么删除也要二次确认
 *
 * 下载花的是用户的流量和时间，删掉是不可逆的（得重新下 30 MB）。
 * 按本仓库约定，破坏性操作只认 `CpConfirmHost` —— 哪怕只有一个入口。
 */
@Composable
fun FontPacksSection(modifier: Modifier = Modifier) {
    val strings = cpStrings()
    val packs = strings.fontPacks
    val statuses = rememberFontPackStatuses()
    val supported = platformSupportsFontPacks()
    val android = isAndroidPlatform()
    val confirm = rememberConfirmState()

    SettingsSection(packs.sectionTitle) {
        statuses.forEachIndexed { index, status ->
            // 桌面端：只展示（字体在安装包里，删了应用就没字体了）。
            // Android：未下载 / 失败 → 下载，已安装 → 删除（带确认）。
            val actionable = supported && android && status.phase != FontPackPhase.DOWNLOADING
            SettingsClickItem(
                title = titleOf(status.pack, packs),
                subtitle = subtitleOf(status, packs),
                index = index,
                total = statuses.size,
                modifier = modifier,
                enabled = actionable,
                onClick = {
                    when (status.phase) {
                        FontPackPhase.MISSING, FontPackPhase.FAILED -> downloadFontPack(status.pack)
                        FontPackPhase.READY -> confirm.request(
                            title = titleOf(status.pack, packs),
                            message = packs.deleteConfirm,
                            confirmLabel = packs.actionDelete,
                            dismissLabel = packs.cancel,
                            destructive = true,
                        ) { removeFontPack(status.pack) }
                        // 下载中点不动：这里再被点到说明状态刚好翻转，忽略即可。
                        FontPackPhase.DOWNLOADING -> Unit
                    }
                },
                trailingContent = {
                    CpText(
                        text = trailingLabel(status, packs),
                        style = MaterialTheme.typography.labelLarge,
                        color = when (status.phase) {
                            FontPackPhase.READY -> MaterialTheme.colorScheme.primary
                            FontPackPhase.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        }
    }
    SettingsNote(packs.intro)
    if (!supported) {
        SettingsNote(packs.unsupported, color = MaterialTheme.colorScheme.error)
    }
    if (android && supported) {
        SettingsNote(packs.sourceNote)
    }
    // 确认框宿主放在本段末尾：与页面其余弹层同级（见 AGENTS 里「一页多入口接同一个 state」）。
    CpConfirmHost(confirm)
}

private fun titleOf(pack: AppFontPack, strings: FontPackStrings): String = when (pack) {
    AppFontPack.JAPANESE -> strings.japaneseTitle
    AppFontPack.MISANS -> strings.misansTitle
}

/**
 * 第二行：负责什么 + 当前状态 / 体积。
 *
 * 体积用 [formatBytes] 而不是写死字符串：文件换了大小就跟着变，
 * 不会出现「显示 9.6 MB、实际下 20 MB」的错位。
 */
private fun subtitleOf(status: FontPackStatus, strings: FontPackStrings): String {
    val note = when (status.pack) {
        AppFontPack.JAPANESE -> strings.japaneseNote
        AppFontPack.MISANS -> strings.misansNote
    }
    return when (status.phase) {
        FontPackPhase.MISSING -> "$note · ${formatBytes(status.pack.sizeBytes)}"
        FontPackPhase.DOWNLOADING -> strings.stateDownloading((status.progress * 100).toInt())
        FontPackPhase.READY -> "$note · ${strings.stateInstalled}"
        FontPackPhase.FAILED -> strings.stateFailed(status.error ?: "")
    }
}

/** 行尾的动作 / 状态字。 */
private fun trailingLabel(status: FontPackStatus, strings: FontPackStrings): String = when (status.phase) {
    FontPackPhase.MISSING -> strings.actionDownload
    FontPackPhase.DOWNLOADING -> "${(status.progress * 100).toInt()}%"
    // 桌面端 READY 时这一列只是状态说明，不是按钮（`enabled` 已关）。
    FontPackPhase.READY -> strings.stateInstalled
    FontPackPhase.FAILED -> strings.actionRetry
}
