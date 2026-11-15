package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.a2ui.A2uiAgent
import cp.player.app.ui.a2ui.A2uiSurfaceHost
import cp.player.app.ui.a2ui.A2uiTrackRef
import cp.player.app.ui.a2ui.eqPresetGains
import cp.player.app.ui.a2ui.rememberA2uiActionHandler
import cp.player.app.ui.a2ui.rememberA2uiRuntime
import cp.player.app.ui.theme.CpMotion
import cp.player.core.ai.AiConfigManager
import cp.player.core.playback.EqBand
import cp.player.core.playback.EqualizerConfig
import kotlinx.coroutines.launch

/**
 * 私人 DJ 台（A2UI）。
 *
 * ## 为什么外壳是原生 Compose，而不是整页交给 A2UI
 *
 * 官方 Basic Catalog 只提供 Text / Row / Column / Card / Button / Image 这些**通用**
 * 组件，没有 M3 Expressive 的连通按钮组、波形进度、morph 图形。把整页塞进 Surface
 * 的结果是一串默认描边卡片 + 同权重按钮，既不是 M3E，也和播放页其余部分不像同一个应用。
 *
 * 现在的分工：
 * - **外壳**（封面、主操作、队列、声场）用原生 M3 Expressive 组件，和播放页共用同一套
 *   形状、动效与间距令牌；
 * - **A2UI** 只负责「AI 生成的那段内容」（解读 / 编排结果），由官方 Catalog 绘制，
 *   按钮仍通过真实动作回到播放器。
 *
 * 这样既保留了 A2UI 的生成能力，又不会让生成结果决定整个弹层的版式。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LivingDjDeskSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val runtime = rememberA2uiRuntime()
    val s = cpStrings()
    val playback by AppModel.playback.state.collectAsState()
    val config by AiConfigManager.instance.configFlow.collectAsState()

    val track = remember(playback.currentTrack?.id, playback.queue) {
        playback.currentTrack?.let(A2uiTrackRef::from)
    }
    val queue = remember(playback.queue) { playback.queue.map(A2uiTrackRef::from) }

    val actionHandler = rememberA2uiActionHandler(
        onPrompt = { prompt -> scope.launch { runtime.generate(prompt) } },
    )

    // 打开时给模型一段「解读当前曲目」的初始内容；用户点按钮再重新生成。
    LaunchedEffect(track?.mediaId, config.enabled) {
        runtime.deleteAllSurfaces()
        if (config.enabled && track != null) {
            runtime.feed(
                A2uiAgent.djMessages(track, queue, s.ai).joinToString("\n"),
            )
        }
    }

    LegacyModalBottomSheet(
        onDismissRequest = onDismiss,
        skipPartiallyExpanded = true,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = CpSpacing.formMaxWidth)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DjDeskHero(track)

                if (track != null) {
                    DjDeskActions(
                        enabled = config.enabled,
                        onPlay = {
                            scope.launch { AppModel.playback.play(track.mediaId) }
                        },
                        onNext = {
                            scope.launch { AppModel.playback.addToQueue(track.mediaId) }
                        },
                        onSimilar = {
                            scope.launch {
                                runtime.generate(
                                    s.ai.djFindSimilarPrompt(track.title, track.artist),
                                )
                            }
                        },
                        onExplain = {
                            scope.launch {
                                runtime.generate(
                                    s.ai.djAskAiPrompt(track.title, track.artist),
                                )
                            }
                        },
                    )

                    DjDeskSoundField(
                        onPreset = { preset ->
                            eqPresetGains(preset)?.let { gains ->
                                val current = AppModel.audioEffect()
                                val bands = EqualizerConfig.DEFAULT_FREQUENCIES_HZ
                                    .zip(gains) { frequency, gain ->
                                        EqBand(gainDb = gain, frequencyHz = frequency)
                                    }
                                AppModel.setAudioEffect(
                                    current.copy(
                                        enabled = true,
                                        equalizer = current.equalizer.copy(
                                            enabled = true,
                                            bands = bands,
                                        ),
                                    ),
                                )
                            }
                        },
                    )
                }

                DjDeskAiSection(
                    runtime = runtime,
                    actionHandler = actionHandler,
                    enabled = config.enabled,
                    expressiveMotion = config.expressiveMotion,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DjDeskHero(track: A2uiTrackRef?) {
    val s = cpStrings()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.largeIncreased),
        ) {
            if (!track?.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CpCoverPlaceholder(modifier = Modifier.fillMaxSize(), corner = 20.dp)
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CpText(
                text = s.ai.djDeskTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            CpText(
                text = track?.title ?: s.ai.djEmptyNoTrack,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            track?.let { item ->
                CpText(
                    text = item.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                CpText(
                    text = listOfNotNull(item.album, formatTrackDuration(item.durationMs))
                        .joinToString(" · ")
                        .ifBlank { s.ai.djNowPlayingFallback },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DjDeskActions(
    enabled: Boolean,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onSimilar: () -> Unit,
    onExplain: () -> Unit,
) {
    val s = cpStrings()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onPlay,
            enabled = enabled,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            CpText(s.ai.djPlayNow, style = MaterialTheme.typography.titleMedium)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onNext,
                enabled = enabled,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f),
            ) {
                CpText(s.ai.djPlayNext, style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = onSimilar,
                enabled = enabled,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f),
            ) {
                CpText(s.ai.djFindSimilar, style = MaterialTheme.typography.labelLarge)
            }
        }
        TextButton(
            onClick = onExplain,
            enabled = enabled,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(6.dp))
            CpText(s.ai.djAskAi, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DjDeskSoundField(onPreset: (String) -> Unit) {
    val s = cpStrings()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CpText(
            text = s.ai.djSoundTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "vocal" to s.ai.djEqVocal,
                "night" to s.ai.djEqNight,
                "flat" to s.ai.djEqFlat,
            ).forEach { (preset, label) ->
                Button(
                    onClick = { onPreset(preset) },
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                ) {
                    CpText(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun DjDeskAiSection(
    runtime: cp.player.app.ui.a2ui.A2uiRuntime,
    actionHandler: cp.player.app.ui.a2ui.A2uiActionHandler,
    enabled: Boolean,
    expressiveMotion: Boolean,
) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                CpText(
                    text = runtime.statusLine.ifBlank { s.ai.djAiSectionTitle },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (runtime.isGenerating) {
                    CpLoadingIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            runtime.errorText?.let { message ->
                CpText(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = Int.MAX_VALUE,
                )
            }

            A2uiSurfaceHost(
                runtime = runtime,
                actionHandler = actionHandler,
                expressiveMotion = expressiveMotion,
                emptyContent = {
                    CpText(
                        text = if (enabled) s.ai.djEmptyNoTrack else s.ai.djEmptyDisabled,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = Int.MAX_VALUE,
                    )
                },
            )
        }
    }
}

private fun formatTrackDuration(durationMs: Long): String? {
    if (durationMs <= 0L) return null
    val totalSeconds = durationMs / 1_000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
