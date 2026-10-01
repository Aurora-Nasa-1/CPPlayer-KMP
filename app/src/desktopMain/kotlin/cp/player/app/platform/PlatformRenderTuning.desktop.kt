package cp.player.app.platform

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection

/**
 * 「渲染后端」设置页（仅桌面端）。
 *
 * 用途是给 Windows 上「开 VRR 后刷新率被拉低 / 面板闪烁」这类问题留一个可切换的开关：
 * Compose Desktop 由 Skiko 绘制，Skiko 在 Windows 默认走 Direct3D 12，其
 * `Present(FLIP_DISCARD)` + `DwmFlush` 的出帧节奏会与 DWM 合成节奏（也就是 VRR 与
 * 系统帧节奏控制的作用对象）互相影响。换后端是上游给出的规避手段。
 *
 * 具体机制、取值来源与优先级见 [DesktopRenderTuning]。
 *
 * 版式走 [SettingsSection] / [SettingsClickItem]，与其他设置页一致
 * （原先的 `SettingsCard` 已被旧版分组体系取代）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun PlatformRenderTuningContent() {
    val navigator = LocalNavigator.currentOrThrow

    // 本地态只用于即时反馈；真正的持久化交给 DesktopRenderTuning。
    var backend by remember { mutableStateOf(DesktopRenderTuning.storedBackend()) }
    var vsync by remember { mutableStateOf(DesktopRenderTuning.storedVsyncOverride()) }

    // 上次启动被安全模式自动回退掉的后端；null 表示没发生过。
    var reverted by remember { mutableStateOf(DesktopRenderTuning.lastRevertedBackend()) }

    // 以 backend 为 key：点击选项会同时改本地态与持久化值，需要重算。
    val restartPending = remember(backend) { DesktopRenderTuning.isRestartPending() }

    val body: @Composable (Modifier) -> Unit = { pageModifier ->
        SettingsPage(pageModifier) {
            // 只有真的发生过回退才出现。放在最上面，是因为用户多半正是为了「上次选了之后起不来」才进来的。
            reverted?.let { failed ->
                SettingsNote(
                    text = "上次以「${failed.label}」启动时未能正常出帧（崩溃、窗口不出现或渲染卡死），" +
                        "已自动改回「自动」，以免你进不来这一页。",
                    color = MaterialTheme.colorScheme.error,
                )
                SettingsNote(
                    text = "该后端在本机可能不受支持，建议先更新显卡驱动再试。" +
                        "外部启动参数（-Dskiko.renderApi=…）不受此机制影响。",
                )
            }

            SettingsNote("改完需要重启应用才生效。若开 VRR / G-SYNC 后出现刷新率被拉低、动鼠标就顿挫或面板闪烁，先试「OpenGL」。")
            if (restartPending) {
                SettingsNote("已保存，重启应用后生效。", color = MaterialTheme.colorScheme.primary)
            }

            SettingsSection("渲染后端") {
                val options = DesktopRenderTuning.Backend.entries
                options.forEachIndexed { index, option ->
                    SettingsClickItem(
                        title = option.label,
                        subtitle = option.note,
                        index = index,
                        total = options.size,
                        onClick = {
                            backend = option
                            DesktopRenderTuning.storeBackend(option)
                            // 用户已重新做出选择，旧的回退警告就此作废。
                            DesktopRenderTuning.clearRevertNote()
                            reverted = null
                        },
                        trailingContent = {
                            RadioButton(selected = backend == option, onClick = null)
                        },
                    )
                }
            }

            SettingsNote("默认交给 Skiko 决定。仅在换后端后仍抖动时，才需要动这一项做对照。")
            SettingsSection("垂直同步") {
                listOf(
                    Triple("不干预", null as Boolean?, "交给 Skiko 决定"),
                    Triple("强制开启", true, "skiko.vsync.enabled=true"),
                    Triple("强制关闭", false, "skiko.vsync.enabled=false"),
                ).forEachIndexed { index, (label, value, note) ->
                    SettingsClickItem(
                        title = label,
                        subtitle = note,
                        index = index,
                        total = 3,
                        onClick = {
                            vsync = value
                            DesktopRenderTuning.storeVsyncOverride(value)
                        },
                        trailingContent = {
                            RadioButton(selected = vsync == value, onClick = null)
                        },
                    )
                }
            }

            SettingsSection("当前生效") {
                SettingsFieldGroup {
                    EffectiveLine("本次运行请求的后端", DesktopRenderTuning.requestedApiSummary())
                    EffectiveLine("Skiko 实际采用", DesktopRenderTuning.resolvedSkikoApi())
                    EffectiveLine(
                        "垂直同步",
                        when (DesktopRenderTuning.effectiveVsyncOverride()) {
                            null -> "未干预（Skiko 默认）"
                            else -> "skiko.vsync.enabled=${DesktopRenderTuning.effectiveVsyncOverride()}"
                        },
                    )
                    Text(
                        text = "「实际采用」才是对照实验的依据：Skiko 在本机不支持所选后端时会静默回退，请求值不一定等于实际值。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "安全模式：选了跑不起来的后端（启动即崩或窗口不出现）时，下次启动会自动改回「自动」，" +
                            "不会让你卡在进不去设置页的状态。若要手工干预，可用 " +
                            "-Dcp.player.renderApi=AUTO 启动，或删除下方文件里的 " +
                            "${DesktopRenderTuning.KEY_BACKEND} / ${DesktopRenderTuning.KEY_VSYNC} 两项。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = DesktopRenderTuning.storageHint(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    CpRouteScaffold(
        title = "渲染后端",
        onBack = { navigator.pop() },
    ) { pageModifier -> body(pageModifier) }
}

@Composable
private fun EffectiveLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
