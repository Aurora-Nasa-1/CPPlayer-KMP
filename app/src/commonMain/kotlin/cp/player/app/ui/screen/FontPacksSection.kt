package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cp.player.app.AppModel
import cp.player.app.i18n.FontPackStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsSearchTarget
import cp.player.app.ui.model.formatBytes
import cp.player.app.ui.theme.AppFontFace
import cp.player.app.ui.theme.AppFontPack
import cp.player.app.ui.theme.FontPackPhase
import cp.player.app.ui.theme.FontPackStatus
import cp.player.app.ui.theme.downloadFontPack
import cp.player.app.ui.theme.platformSupportsFontPacks
import cp.player.app.ui.theme.rememberFontPackStatuses
import cp.player.app.ui.theme.removeFontPack

/**
 * 「字库」设置区：每种字体独立开关与中日文字体包下载。
 *
 * ## 独立开关与回退
 *
 * 每款字体（Google Sans Flex / Noto Sans JP / MiSans）均可单独开启或关闭：
 * - 关闭某款字体时，该脚本文字回退到系统字体渲染。
 * - 桌面端三款字体均随安装包内置，开关即时生效。
 * - Android 端中日文字体需先下载，下载就绪后即可自由开关，亦可随时删除以释放存储。
 */
@Composable
fun FontPacksSection(
    modifier: Modifier = Modifier,
    targetSettingId: String? = null,
) {
    val strings = cpStrings()
    val packs = strings.fontPacks
    val statuses = rememberFontPackStatuses()
    val supported = platformSupportsFontPacks()
    val android = isAndroidPlatform()
    val confirm = rememberConfirmState()
    val fontFaceEnabled by AppModel.fontFaceEnabledFlow.collectAsState()

    val japaneseStatus = statuses.firstOrNull { it.pack == AppFontPack.JAPANESE }
    val misansStatus = statuses.firstOrNull { it.pack == AppFontPack.MISANS }

    SettingsSection(packs.sectionTitle) {
        // 0. Google Sans Flex（两端内置，默认开启）
        val googleSansEnabled = fontFaceEnabled[AppFontFace.GOOGLE_SANS_FLEX] ?: true
        SettingsSwitchItem(
            title = packs.googleSansTitle,
            subtitle = packs.googleSansNote,
            checked = googleSansEnabled,
            onCheckedChange = { AppModel.setFontFaceEnabled(AppFontFace.GOOGLE_SANS_FLEX, it) },
            index = 0,
            total = 3,
            modifier = modifier.settingsSearchTarget("appearance_font_google_sans", targetSettingId),
        )

        // 1. Noto Sans JP（日文：桌面端内置，Android 下载）
        val notoEnabled = fontFaceEnabled[AppFontFace.NOTO_SANS_JP] ?: true
        val isJapaneseReady = !android || japaneseStatus?.phase == FontPackPhase.READY
        if (isJapaneseReady) {
            SettingsSwitchItem(
                title = packs.japaneseTitle,
                subtitle = if (android) "${packs.japaneseNote} · ${packs.stateInstalled}" else packs.japaneseNote,
                checked = notoEnabled,
                onCheckedChange = { AppModel.setFontFaceEnabled(AppFontFace.NOTO_SANS_JP, it) },
                index = 1,
                total = 3,
                modifier = modifier.settingsSearchTarget("appearance_font_noto_sans_jp", targetSettingId),
                leadingTrailingContent = if (android) {
                    {
                        IconButton(
                            onClick = {
                                confirm.request(
                                    title = packs.japaneseTitle,
                                    message = packs.deleteConfirm,
                                    confirmLabel = packs.actionDelete,
                                    dismissLabel = packs.cancel,
                                    destructive = true,
                                ) { removeFontPack(AppFontPack.JAPANESE) }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = packs.actionDelete,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else null,
            )
        } else {
            val status = japaneseStatus ?: FontPackStatus(AppFontPack.JAPANESE, FontPackPhase.MISSING)
            val actionable = supported && android && status.phase != FontPackPhase.DOWNLOADING
            SettingsClickItem(
                title = packs.japaneseTitle,
                subtitle = subtitleOf(status, packs),
                index = 1,
                total = 3,
                modifier = modifier.settingsSearchTarget("appearance_font_noto_sans_jp", targetSettingId),
                enabled = actionable,
                onClick = {
                    when (status.phase) {
                        FontPackPhase.MISSING, FontPackPhase.FAILED -> downloadFontPack(AppFontPack.JAPANESE)
                        else -> Unit
                    }
                },
                trailingContent = {
                    CpText(
                        text = trailingLabel(status, packs),
                        style = MaterialTheme.typography.labelLarge,
                        color = when (status.phase) {
                            FontPackPhase.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        }

        // 2. MiSans（中文：桌面端内置，Android 下载）
        val misansEnabled = fontFaceEnabled[AppFontFace.MISANS] ?: true
        val isMisansReady = !android || misansStatus?.phase == FontPackPhase.READY
        if (isMisansReady) {
            SettingsSwitchItem(
                title = packs.misansTitle,
                subtitle = if (android) "${packs.misansNote} · ${packs.stateInstalled}" else packs.misansNote,
                checked = misansEnabled,
                onCheckedChange = { AppModel.setFontFaceEnabled(AppFontFace.MISANS, it) },
                index = 2,
                total = 3,
                modifier = modifier.settingsSearchTarget("appearance_font_misans", targetSettingId),
                leadingTrailingContent = if (android) {
                    {
                        IconButton(
                            onClick = {
                                confirm.request(
                                    title = packs.misansTitle,
                                    message = packs.deleteConfirm,
                                    confirmLabel = packs.actionDelete,
                                    dismissLabel = packs.cancel,
                                    destructive = true,
                                ) { removeFontPack(AppFontPack.MISANS) }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = packs.actionDelete,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else null,
            )
        } else {
            val status = misansStatus ?: FontPackStatus(AppFontPack.MISANS, FontPackPhase.MISSING)
            val actionable = supported && android && status.phase != FontPackPhase.DOWNLOADING
            SettingsClickItem(
                title = packs.misansTitle,
                subtitle = subtitleOf(status, packs),
                index = 2,
                total = 3,
                modifier = modifier.settingsSearchTarget("appearance_font_misans", targetSettingId),
                enabled = actionable,
                onClick = {
                    when (status.phase) {
                        FontPackPhase.MISSING, FontPackPhase.FAILED -> downloadFontPack(AppFontPack.MISANS)
                        else -> Unit
                    }
                },
                trailingContent = {
                    CpText(
                        text = trailingLabel(status, packs),
                        style = MaterialTheme.typography.labelLarge,
                        color = when (status.phase) {
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
    FontPackPhase.READY -> strings.stateInstalled
    FontPackPhase.FAILED -> strings.actionRetry
}
