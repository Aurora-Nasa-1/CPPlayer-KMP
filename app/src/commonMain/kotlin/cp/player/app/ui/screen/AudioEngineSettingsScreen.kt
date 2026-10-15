package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cp.player.app.ui.component.VipActivationDialog
import cp.player.core.license.VipLicenseManager
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.AppModel
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.settingsRowContainer
import cp.player.core.playback.PlaybackSessionSettings

/**
 * 「音频输出与硬件独占」独立设置页。
 *
 * 专为发烧级音频回放（Hi-Res Audio、WASAPI Exclusive 独占直通、Bit-Perfect 源码输出）设计。
 * 集中管理系统标准引擎与 FlickEngine 专有引擎的切换、硬件采样率自适应与 DSD 母带解码。
 */
class AudioEngineSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val audioEngine by AppModel.audioEngineFlow.collectAsState()
        val isFlickEngine = audioEngine == PlaybackSessionSettings.ENGINE_FLICK_HIRES

        val vipInfo by VipLicenseManager.licenseState.collectAsState()
        var showVipDialog by remember { mutableStateOf(false) }

        if (showVipDialog) {
            VipActivationDialog(
                onDismiss = { showVipDialog = false },
                onSuccess = {
                    AppModel.setAudioEngine(PlaybackSessionSettings.ENGINE_FLICK_HIRES)
                },
            )
        }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                // 顶部状态指示卡片
                EngineStatusBanner(isFlickEngine = isFlickEngine)

                SettingsSection("音频输出内核架构") {
                    val engineOptions = listOf(
                        "系统标准引擎 (默认)",
                        "FlickEngine 发烧引擎 (专有 / VIP)",
                    )
                    val engineSubtitle = if (isFlickEngine) {
                        if (isAndroidPlatform()) {
                            "Oboe 低延迟直通 · USB DAC 源码输出 · DSD 软解已就绪"
                        } else {
                            "WASAPI Exclusive 硬件独占直通 · Bit-Perfect 源码输出"
                        }
                    } else {
                        if (isAndroidPlatform()) {
                            "AndroidX Media3 (ExoPlayer) 标准混音通道"
                        } else {
                            "系统混音共享输出 (轻量兼容，受 Windows 混音器影响)"
                        }
                    }

                    SettingsDropdownItem(
                        title = "当前生效内核",
                        subtitle = engineSubtitle,
                        options = engineOptions,
                        selectedIndex = if (isFlickEngine) 1 else 0,
                        onSelect = { index ->
                            if (index == 1) {
                                if (!vipInfo.isActivated) {
                                    showVipDialog = true
                                } else {
                                    AppModel.setAudioEngine(PlaybackSessionSettings.ENGINE_FLICK_HIRES)
                                }
                            } else {
                                AppModel.setAudioEngine(PlaybackSessionSettings.ENGINE_STANDARD)
                            }
                        },
                        index = 0,
                        total = 1,
                    )
                }

                SettingsNote("提示：切换内核后，当前歌曲将在切歌或重新播放时无缝切换至所选内核。")

                if (isFlickEngine) {
                    SettingsSection("FlickEngine 发烧特性与硬件直通") {
                        SettingsClickItem(
                            title = if (isAndroidPlatform()) "Oboe / AAudio 直通" else "WASAPI Exclusive 独占模式",
                            subtitle = if (isAndroidPlatform()) {
                                "绕过 Android AudioFlinger 重采样，直通底层音频 HAL / USB DAC"
                            } else {
                                "完全绕过 Windows 系统混音器与音频增强，由播放器直接接管声卡终端"
                            },
                            index = 0,
                            total = 4,
                            onClick = {},
                            trailingContent = {
                                Text(
                                    text = "已启用",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )

                        SettingsClickItem(
                            title = "Bit-Perfect 位完美源码输出",
                            subtitle = "声卡硬件采样率根据音轨物理规格自动重协商（44.1k/48k/96k/192kHz 自适应）",
                            index = 1,
                            total = 4,
                            onClick = {},
                            trailingContent = {
                                Text(
                                    text = "自动自适应",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )

                        SettingsClickItem(
                            title = "DSD 母带级原生软解",
                            subtitle = "支持 DSF / DFF 容器，支持 DSD64 / DSD128 高规格直接解码播放",
                            index = 2,
                            total = 4,
                            onClick = {},
                            trailingContent = {
                                Text(
                                    text = "内置支持",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )

                        SettingsClickItem(
                            title = "64位浮点 DSP 音效管线",
                            subtitle = "内置 5段参数均衡器 (PEQ) 与立体声空间环绕，可前往音效设置中调节",
                            index = 3,
                            total = 4,
                            onClick = {
                                navigator?.push(AudioEffectSettingsScreen())
                            },
                            trailingContent = {
                                Text(
                                    text = "前往调节",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    }

                    SettingsNote(
                        "在独占模式下，声卡将独占输出通道，播放期间其他应用程序的声音（如系统提示音）将被静音，以确保 0 混音劣化与极致信噪比。",
                        emphasis = SettingsNoteEmphasis.INFO,
                    )
                } else {
                    SettingsSection("关于 FlickEngine 发烧内核") {
                        SettingsClickItem(
                            title = "为什么需要独立发烧内核？",
                            subtitle = "系统默认混音器会强制将所有音频重采样至固定的采样率，并叠加数字混音算法。FlickEngine 绕过系统中间层，提供硬件原生音质。",
                            index = 0,
                            total = 1,
                            onClick = {
                                if (!vipInfo.isActivated) {
                                    showVipDialog = true
                                } else {
                                    AppModel.setAudioEngine(PlaybackSessionSettings.ENGINE_FLICK_HIRES)
                                }
                            },
                            trailingContent = {
                                Text(
                                    text = if (vipInfo.isActivated) "一键启用" else "解锁特权",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    }
                }

                SettingsSection("VIP 发烧特权授权") {
                    SettingsClickItem(
                        title = "特权授权状态",
                        subtitle = if (vipInfo.isActivated) "已激活：${vipInfo.planName} (${vipInfo.licenseKey ?: "永久授权"})" else "未激活 (需输入有效卡密解锁专有发烧特性)",
                        index = 0,
                        total = 1,
                        onClick = {
                            showVipDialog = true
                        },
                        trailingContent = {
                            Text(
                                text = if (vipInfo.isActivated) "更换卡密" else "激活特权",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }
        }

        CpRouteScaffold(
            title = "音频输出与硬件独占",
            onBack = { navigator?.pop() },
            content = body,
        )
    }
}

@Composable
private fun EngineStatusBanner(isFlickEngine: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = settingsRowContainer(),
        modifier = Modifier.fillMaxWidth().padding(bottom = CpSpacing.listRowGap),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = if (isFlickEngine) Icons.Filled.Memory else Icons.Filled.Info,
                contentDescription = null,
                tint = if (isFlickEngine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isFlickEngine) "FlickEngine Hi-Res 发烧引擎运行中" else "当前使用系统默认共享音频通道",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isFlickEngine) {
                        "原生 64 位浮点架构 · 独占模式已就绪 · 支持 DSD / FLAC / Hi-Res 无损直出"
                    } else {
                        "适合日常日常轻量听歌，兼容所有系统级音效与后台并发播放"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isFlickEngine) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "VIP / Hi-Res",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
