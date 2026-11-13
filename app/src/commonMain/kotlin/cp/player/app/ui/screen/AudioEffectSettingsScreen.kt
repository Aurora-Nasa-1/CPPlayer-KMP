package cp.player.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.settingsRowContainer
import cp.player.app.ui.util.popOrNotify
import cp.player.core.playback.AudioEffectConfig
import cp.player.core.playback.AudioEffectPreset
import cp.player.core.playback.EqBand
import cp.player.core.playback.EqualizerConfig
import cp.player.core.playback.LevelingConfig
import cp.player.core.playback.MixerConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 音效设置页（Material 3 Expressive 参数均衡器 / 声道平衡 / 响度均衡）。
 *
 * 包含：
 * 1. **M3E 响应曲线可视化看板**：实时渲染 5 段 PEQ 响应曲线及增益波形；
 * 2. **风格预设胶囊行**：平直、流行、摇滚、人声、低音增强、高音增强等快速切换；
 * 3. **5 段垂直推子调音台**：触控推子直接调节各频段增益（-12dB ~ +12dB），双击归零；
 * 4. **参数频点微调折叠面板**：支持逐段调整中心频点（Hz）；
 * 5. **混音与响度均衡**：声道平衡调节与音量归一化/防爆音控制。
 */
class AudioEffectSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()

        val capabilities = remember { AppModel.audioEffectCapabilities() }
        val supported = capabilities.supportsAny

        val persisted by AppModel.audioEffectFlow.collectAsState()
        var draft by remember { mutableStateOf(persisted) }
        var showFrequencyTuning by remember { mutableStateOf(false) }

        fun commit(next: AudioEffectConfig) {
            val normalized = next.copy(enabled = next.anyFeatureEnabled)
            draft = normalized
            AppModel.setAudioEffect(normalized)
        }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                if (!supported) {
                    SettingsNote(
                        s.audioEffect.unsupportedNote,
                        emphasis = SettingsNoteEmphasis.ERROR,
                    )
                    if (!isAndroidPlatform()) {
                        // ⚠️ 桌面端音效**不是**「开个内核就解锁」的东西：PEQ / 声道平衡
                        // 本来就在 FlickEngine 的 DSP 管线里，`supported == false` 只有一种原因
                        // —— 原生库没加载起来（此时连播放都没声音）。
                        // 旧文案写「开启 FlickEngine Hi-Res 发烧内核」，会让人以为换个开关就能用。
                        SettingsSection("音频内核状态") {
                            SettingsClickItem(
                                title = "FlickEngine 原生音频库未就绪",
                                subtitle = "桌面端的解码、音效与输出全部由 FlickEngine 提供；库缺失时音效与播放都不可用。"
                                    + "请确认安装完整，或到音频输出页查看引擎状态。",
                                index = 0,
                                total = 1,
                                onClick = {
                                    navigator.push(AudioEngineSettingsScreen())
                                },
                                trailingContent = {
                                    CpText(
                                        text = "查看状态",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                        }
                    }
                }

                // ---------------- 1. Material 3 Expressive 均衡器 ----------------
                if (capabilities.supportsEqualizer) {
                    val eq = draft.equalizer
                    val currentSelectedPreset = currentPreset(eq.bands)

                    SettingsSection(s.audioEffect.sectionEqualizer) {
                        // 开关与重置平直
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .background(settingsRowContainer())
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                CpText(
                                    text = s.audioEffect.equalizerEnabled,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                CpText(
                                    text = if (eq.enabled) "已启用 · 5 段参数均衡生效中" else s.audioEffect.equalizerEnabledNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        commit(draft.copy(equalizer = eq.copy(bands = AudioEffectPreset.FLAT.bands)))
                                    },
                                    enabled = eq.bands != AudioEffectPreset.FLAT.bands,
                                ) {
                                    Icon(
                                        Icons.Filled.RestartAlt,
                                        contentDescription = "重置平直",
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    CpText("平直", style = MaterialTheme.typography.labelMedium)
                                }
                                androidx.compose.material3.Switch(
                                    checked = eq.enabled,
                                    onCheckedChange = { on ->
                                        commit(draft.copy(equalizer = eq.copy(enabled = on)))
                                    },
                                )
                            }
                        }

                        // 曲线看板 Card
                        EqualizerCurveCard(
                            bands = eq.bands,
                            enabled = eq.enabled,
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                        )

                        // 预设过滤胶囊行
                        EqualizerPresetChipsRow(
                            selectedPreset = currentSelectedPreset,
                            s = s,
                            onSelectPreset = { preset ->
                                commit(draft.copy(equalizer = eq.copy(bands = preset.bands)))
                            },
                        )

                        // 5 段推子调音台 Console
                        EqualizerFaderConsole(
                            bands = eq.bands,
                            enabled = eq.enabled,
                            // ⚠️ 不能用上面捕获的 `eq` 快照：拖拽回调会被手势协程长期持有，
                            // 闭包里那份 `eq` 停留在**协程启动时**的频段表，拿它 copy 会把本次
                            // 会话里别的频段改动整批回滚（拖一个推子把其余四段清平）。
                            // 一律从 `draft` 现读现写。
                            onBandGainChange = { bandIndex, gainDb ->
                                val current = draft.equalizer
                                draft = draft.copy(
                                    equalizer = current.copy(
                                        bands = current.bands.replaceAt(
                                            bandIndex,
                                            current.bands[bandIndex].copy(gainDb = gainDb),
                                        ),
                                    ),
                                )
                            },
                            onBandGainChangeFinished = { commit(draft) },
                        )

                        // 高级频点微调（PEQ 参数微调）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { showFrequencyTuning = !showFrequencyTuning }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                CpText(
                                    text = "高级：中心频点微调 (PEQ)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                CpText(
                                    text = "逐段调整频段滤波截止频率",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = if (showFrequencyTuning) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }

                        // The root Navigator already animates pages inside SharedTransitionLayout.
                        // Keep this small, static control group out of a second nested subcomposition.
                        if (showFrequencyTuning) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                eq.bands.forEachIndexed { bandIndex, band ->
                                    SettingsSliderItem(
                                        title = "频段 ${bandIndex + 1} 中心频率",
                                        subtitle = "${formatHz(band.frequencyHz)} (${formatGain(band.gainDb)} dB)",
                                        value = band.frequencyHz,
                                        onValueChange = { hz ->
                                            // 同推子：回调里现读 `draft`，别用外层捕获的 `eq`/`band`
                                            // 快照，否则连续拖动时会把上一帧的频率改动覆盖掉。
                                            val current = draft.equalizer
                                            val target = current.bands.getOrNull(bandIndex) ?: band
                                            draft = draft.copy(
                                                equalizer = current.copy(
                                                    bands = current.bands.replaceAt(
                                                        bandIndex,
                                                        target.copy(frequencyHz = hz),
                                                    ),
                                                ),
                                            )
                                        },
                                        onValueChangeFinished = { commit(draft) },
                                        valueRange = EqBand.EQ_FREQ_MIN_HZ..EqBand.EQ_FREQ_MAX_HZ,
                                        steps = 0,
                                        valueLabel = formatHz(band.frequencyHz),
                                        index = bandIndex,
                                        total = eq.bands.size,
                                        enabled = true,
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------------- 2. 声道平衡 / 混音 ----------------
                if (capabilities.supportsBalance) {
                    val mixer = draft.mixer
                    SettingsSection(s.audioEffect.sectionMixer) {
                        SettingsSliderItem(
                            title = s.audioEffect.balance,
                            subtitle = s.audioEffect.balanceNote,
                            value = mixer.balance,
                            onValueChange = { value ->
                                draft = draft.copy(mixer = mixer.copy(balance = value))
                            },
                            onValueChangeFinished = { commit(draft) },
                            valueRange = MixerConfig.BALANCE_MIN..MixerConfig.BALANCE_MAX,
                            steps = 0,
                            valueLabel = balanceLabel(s, mixer.balance),
                            index = 0,
                            total = 1,
                            enabled = true,
                        )
                    }
                }

                // ---------------- 3. 音量归一化与防爆音 ----------------
                if (capabilities.supportsLeveling) {
                    val leveling = draft.leveling
                    SettingsSection(s.audioEffect.sectionLeveling) {
                        SettingsSwitchItem(
                            title = s.audioEffect.levelingEnabled,
                            subtitle = s.audioEffect.levelingEnabledNote,
                            checked = leveling.enabled,
                            onCheckedChange = { on ->
                                commit(draft.copy(leveling = leveling.copy(enabled = on)))
                            },
                            index = 0,
                            total = 3,
                        )
                        SettingsSliderItem(
                            title = s.audioEffect.levelingTarget,
                            subtitle = s.audioEffect.levelingTargetNote,
                            value = leveling.targetLevelDb,
                            onValueChange = { db ->
                                draft = draft.copy(
                                    leveling = leveling.copy(targetLevelDb = db),
                                )
                            },
                            onValueChangeFinished = { commit(draft) },
                            valueRange = LevelingConfig.TARGET_LEVEL_MIN_DB..LevelingConfig.TARGET_LEVEL_MAX_DB,
                            steps = 0,
                            valueLabel = s.audioEffect.gainLabel(
                                formatGain(leveling.targetLevelDb),
                            ),
                            index = 1,
                            total = 3,
                            enabled = leveling.enabled,
                        )
                        SettingsSwitchItem(
                            title = s.audioEffect.preventClipping,
                            subtitle = s.audioEffect.preventClippingNote,
                            checked = leveling.preventClipping,
                            onCheckedChange = { on ->
                                commit(draft.copy(leveling = leveling.copy(preventClipping = on)))
                            },
                            index = 2,
                            total = 3,
                        )
                    }
                }

                if (supported) SettingsNote(s.audioEffect.levelingNote)
            }
        }

        CpRouteScaffold(
            title = s.audioEffect.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier -> body(pageModifier) }
    }
}

// ============================================================ M3 Expressive 均衡器组件

/**
 * 响应曲线可视化 Card：按**真实幅频响应**绘制 5 段 PEQ 的合成曲线。
 *
 * ⚠️ 历史坑（2026-11-10 修）：第一版把 5 个频段的增益点按**下标均分**横轴
 * （`x = (index + 0.5) * width / count`），再用三次贝塞尔连起来 —— `frequencyHz`
 * 根本没参与绘制。于是不管把中心频率调到哪里，曲线与锚点都纹丝不动，
 * 用户看到的永远是「那 5 个点」，会以为频点调节无效。
 *
 * 现在：横轴是 20 Hz ~ 20 kHz 的**对数刻度**，纵轴是各段峰值双二阶**级联后的响应**，
 * 锚点横坐标 = 该段频点，纵坐标 = 该频点的合成响应（因此锚点永远贴在曲线上）。
 */
@Composable
internal fun EqualizerCurveCard(
    bands: List<EqBand>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val containerBg = settingsRowContainer()
    val disabledAlpha = if (enabled) 1.0f else 0.45f

    // 合成响应按对数频点取样，只与频段参数有关、与画布尺寸无关 ⇒ 只在拖动结束后重算。
    val sampledDb = remember(bands) { samplePeqResponse(bands, CURVE_SAMPLE_COUNT) }
    // 每段锚点：(横向 0..1, 该频点上的合成响应 dB)。
    val markers = remember(bands) {
        bands.map { band ->
            peqFrequencyRatio(band.frequencyHz) to peqResponseDb(bands, band.frequencyHz)
        }
    }

    // 频率轴刻度文字：对数轴下 100 / 1k / 10k 才是等距的三档。
    val measurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        fontSize = 9.sp,
    )
    val tickLabels = remember(measurer, tickStyle) {
        FREQUENCY_TICKS.map { (hz, text) -> hz to measurer.measure(text, tickStyle) }
    }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(containerBg)
            .border(1.dp, outlineVariant.copy(alpha = 0.5f), MaterialTheme.shapes.large)
            .padding(12.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val midY = height / 2f

            // 对数频率网格 + 刻度文字
            tickLabels.forEach { (hz, layout) ->
                val x = peqFrequencyRatio(hz) * width
                drawLine(
                    color = outlineVariant.copy(alpha = 0.16f * disabledAlpha),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1.dp.toPx(),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (x - layout.size.width / 2f)
                            .coerceIn(0f, (width - layout.size.width).coerceAtLeast(0f)),
                        height - layout.size.height,
                    ),
                )
            }

            // 0 dB 参考虚线
            drawLine(
                color = outlineVariant.copy(alpha = 0.8f * disabledAlpha),
                start = Offset(0f, midY),
                end = Offset(width, midY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                    0f,
                ),
            )

            // +12dB 与 -12dB 极值线
            drawLine(
                color = outlineVariant.copy(alpha = 0.25f * disabledAlpha),
                start = Offset(0f, 0f),
                end = Offset(width, 0f),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = outlineVariant.copy(alpha = 0.25f * disabledAlpha),
                start = Offset(0f, height),
                end = Offset(width, height),
                strokeWidth = 1.dp.toPx(),
            )

            if (sampledDb.size < 2) return@Canvas

            val lastIndex = sampledDb.size - 1
            // 采样点 → 画布坐标。多段叠加可能超过 ±12 dB，绘制时夹紧在网格内
            // （网格刻度就是推子量程，不能为了一条极端曲线改刻度）。
            fun xAt(index: Int): Float = width * index / lastIndex
            fun yOfDb(db: Float): Float {
                val ratio = ((db - EqBand.EQ_GAIN_MIN_DB) /
                    (EqBand.EQ_GAIN_MAX_DB - EqBand.EQ_GAIN_MIN_DB)).coerceIn(0f, 1f)
                return height * (1f - ratio)
            }

            val curvePath = Path()
            val fillPath = Path()
            curvePath.moveTo(0f, yOfDb(sampledDb[0]))
            fillPath.moveTo(0f, midY)
            fillPath.lineTo(0f, yOfDb(sampledDb[0]))
            for (i in 1..lastIndex) {
                val x = xAt(i)
                val y = yOfDb(sampledDb[i])
                curvePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            fillPath.lineTo(width, midY)
            fillPath.close()

            // 渐变填充阴影
            val fillBrush = Brush.verticalGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.28f * disabledAlpha),
                    primaryColor.copy(alpha = 0.05f * disabledAlpha),
                ),
                startY = 0f,
                endY = height,
            )
            drawPath(fillPath, brush = fillBrush)

            // 绘制主响应曲线
            drawPath(
                path = curvePath,
                color = if (enabled) primaryColor else primaryColor.copy(alpha = 0.4f),
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                ),
            )

            // 控制锚点：横坐标是**该段的频点**（对数刻度），改频点时锚点会跟着滑动。
            markers.forEach { (ratio, db) ->
                val center = Offset(ratio * width, yOfDb(db))
                drawCircle(
                    color = containerBg,
                    radius = 4.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    color = if (enabled) primaryColor else primaryColor.copy(alpha = 0.5f),
                    radius = 3.dp.toPx(),
                    center = center,
                )
            }
        }

        // 标尺读数。
        // ⚠️ 必须带 containerBg 底色：曲线与 0 dB 虚线会从文字底下穿过，
        // 不加底色时「0 dB」正好被曲线压住（出图里就是一团糊）。
        CpText(
            text = "+12 dB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.TopStart).background(containerBg),
        )
        CpText(
            text = "0 dB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.CenterStart).background(containerBg),
        )
        CpText(
            text = "-12 dB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.BottomStart).background(containerBg),
        )
    }
}

/**
 * 风格预设水平滚动胶囊行。
 */
@Composable
private fun EqualizerPresetChipsRow(
    selectedPreset: AudioEffectPreset,
    s: cp.player.app.i18n.CpStrings,
    onSelectPreset: (AudioEffectPreset) -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PRESET_ORDER.forEach { preset ->
            val isSelected = selectedPreset == preset
            FilterChip(
                selected = isSelected,
                onClick = { onSelectPreset(preset) },
                label = {
                    CpText(
                        text = presetName(s, preset),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                shape = androidx.compose.foundation.shape.CircleShape,
            )
        }
        if (selectedPreset == AudioEffectPreset.CUSTOM) {
            FilterChip(
                selected = true,
                onClick = {},
                label = {
                    CpText(
                        text = s.audioEffect.presetCustom,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                shape = androidx.compose.foundation.shape.CircleShape,
            )
        }
    }
}

/**
 * 5 段垂直推子调音台 Console。
 */
@Composable
private fun EqualizerFaderConsole(
    bands: List<EqBand>,
    enabled: Boolean,
    onBandGainChange: (index: Int, gainDb: Float) -> Unit,
    onBandGainChangeFinished: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(settingsRowContainer())
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bands.forEachIndexed { index, band ->
            VerticalFaderColumn(
                bandIndex = index,
                band = band,
                enabled = enabled,
                onGainChange = { gain -> onBandGainChange(index, gain) },
                onGainChangeFinished = onBandGainChangeFinished,
            )
        }
    }
}

/**
 * 单个垂直增益推子列：顶部 dB 读数 + 竖向滑杆槽 + 底部频点标签。
 */
@Composable
private fun VerticalFaderColumn(
    bandIndex: Int,
    band: EqBand,
    enabled: Boolean,
    onGainChange: (Float) -> Unit,
    onGainChangeFinished: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackBgColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = if (enabled) primaryColor else primaryColor.copy(alpha = 0.4f)
    val isNonZero = abs(band.gainDb) > 0.05f

    // ⚠️ `pointerInput` 的 block **只在 key 变化时重启**，而 `detectVerticalDragGestures`
    // 内部是 `awaitEachGesture` 死循环 —— 手势协程启动那一刻捕获的值会被一直用到天荒地老，
    // 之后重组再传新 lambda 也进不去（同 class 的 handler 不触发 reset）。
    // 原来 `onVerticalDrag` 里直接读 `band.gainDb`，读到的是启动时的快照 ⇒ 每一帧都
    // 「旧增益 + 本帧位移」重算，拇指抖一下就下一帧被覆盖回去 ＝ 拖不动 / 拖了原地弹回。
    // 所有会变的值一律走 ref 现读。
    val liveGainDb = rememberUpdatedState(band.gainDb)
    val liveOnGainChange = rememberUpdatedState(onGainChange)
    val liveOnGainChangeFinished = rememberUpdatedState(onGainChangeFinished)
    // 一次拖拽的起点与累计位移；放 remember 里，手势被重启后仍从同一起点续算。
    val dragSession = remember { FaderDragSession() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 增益数值
        CpText(
            text = formatGain(band.gainDb),
            style = MaterialTheme.typography.labelSmall,
            color = if (isNonZero && enabled) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 垂直滑杆槽 (高 130dp, 宽 28dp)
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(130.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(trackBgColor)
                .pointerInput(bandIndex, enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = {
                            // 量程按**实测高度**换算（写死 130.dp 在外层尺寸变化后会漂）
                            dragSession.begin(liveGainDb.value, size.height.toFloat())
                        },
                        onDragEnd = {
                            dragSession.reset()
                            liveOnGainChangeFinished.value()
                        },
                        onDragCancel = { dragSession.reset() },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            liveOnGainChange.value(dragSession.dragBy(dragAmount))
                        },
                    )
                }
                .pointerInput(bandIndex, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = {
                            liveOnGainChange.value(0f)
                            liveOnGainChangeFinished.value()
                        },
                        onTap = { offset ->
                            liveOnGainChange.value(faderGainAtY(offset.y, size.height.toFloat()))
                            liveOnGainChangeFinished.value()
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val midY = h / 2f

                // 中心 0dB 基准刻度线
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(4.dp.toPx(), midY),
                    end = Offset(w - 4.dp.toPx(), midY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // 计算当前拇指 Y 坐标
                val normalized = ((band.gainDb - EqBand.EQ_GAIN_MIN_DB) /
                    (EqBand.EQ_GAIN_MAX_DB - EqBand.EQ_GAIN_MIN_DB)).coerceIn(0f, 1f)
                val thumbY = h * (1f - normalized)

                // 从 0dB 向当前值画柱状填充条
                if (abs(thumbY - midY) > 1f) {
                    drawLine(
                        color = activeColor.copy(alpha = 0.7f),
                        start = Offset(w / 2f, midY),
                        end = Offset(w / 2f, thumbY),
                        strokeWidth = 6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // 滑块拇指 Thumb (胶囊/圆钮)
                drawCircle(
                    color = activeColor,
                    radius = 9.dp.toPx(),
                    center = Offset(w / 2f, thumbY),
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = Offset(w / 2f, thumbY),
                )
            }
        }

        // 底部频点标签 Badge
        Box(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            CpText(
                text = formatHz(band.frequencyHz),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ============================================================ 辅助计算与常量

private val PRESET_ORDER = listOf(
    AudioEffectPreset.FLAT,
    AudioEffectPreset.POP,
    AudioEffectPreset.ROCK,
    AudioEffectPreset.VOCAL,
    AudioEffectPreset.BASS_BOOST,
    AudioEffectPreset.TREBLE_BOOST,
)

private fun presetName(s: cp.player.app.i18n.CpStrings, preset: AudioEffectPreset): String =
    when (preset) {
        AudioEffectPreset.FLAT -> s.audioEffect.presetFlat
        AudioEffectPreset.POP -> s.audioEffect.presetPop
        AudioEffectPreset.ROCK -> s.audioEffect.presetRock
        AudioEffectPreset.VOCAL -> s.audioEffect.presetVocal
        AudioEffectPreset.BASS_BOOST -> s.audioEffect.presetBassBoost
        AudioEffectPreset.TREBLE_BOOST -> s.audioEffect.presetTrebleBoost
        AudioEffectPreset.CUSTOM -> s.audioEffect.presetCustom
    }

private fun currentPreset(bands: List<EqBand>): AudioEffectPreset =
    PRESET_ORDER.firstOrNull { preset ->
        preset.bands.size == bands.size &&
            preset.bands.zip(bands).all { (expected, actual) ->
                abs(expected.gainDb - actual.gainDb) < GAIN_EPSILON &&
                    abs(expected.frequencyHz - actual.frequencyHz) < FREQ_EPSILON_HZ
            }
    } ?: AudioEffectPreset.CUSTOM

private const val GAIN_EPSILON = 0.05f
private const val FREQ_EPSILON_HZ = 1f

private fun List<EqBand>.replaceAt(index: Int, band: EqBand): List<EqBand> =
    mapIndexed { i, existing -> if (i == index) band else existing }

// ------------------------------------------------------------ PEQ 响应曲线换算

/** 曲线取样点数：128 个对数频点，相邻两点不足画布宽度 1%，肉眼即连续。 */
private const val CURVE_SAMPLE_COUNT = 128

/**
 * 绘制用的 Q。
 *
 * 本仓的 PEQ **没有 Q 字段**（Android `DynamicsProcessing.EqBand` 不支持运行时改 Q，
 * 见 [EqBand] 的 KDoc），Rust 引擎的 `PeqBand::default()` 与 Android 侧都按 `Q = 1` 跑，
 * 所以曲线也取 1：显示的形状与实际听到的一致，不会为了一条好看的曲线说谎。
 */
internal const val PEQ_CURVE_Q = 1f

/** 绘制用的采样率（48 kHz）。曲线形状对采样率的敏感度低于 1 dB，取常见值即可。 */
private const val PEQ_SAMPLE_RATE = 48000f

/** 频率轴刻度：对数轴下这三档才是等距的，也正好覆盖低/中/高三段。 */
private val FREQUENCY_TICKS = listOf(100f to "100 Hz", 1000f to "1 kHz", 10000f to "10 kHz")

/**
 * 频率 → 横向比例（0..1），**对数刻度**。
 *
 * 线性刻度会把 20~200 Hz 这段挤成一个像素 —— 而人耳对频率的感知本来就是对数关系。
 */
internal fun peqFrequencyRatio(hz: Float): Float {
    val lo = log10(EqBand.EQ_FREQ_MIN_HZ)
    val hi = log10(EqBand.EQ_FREQ_MAX_HZ)
    val clamped = hz.coerceIn(EqBand.EQ_FREQ_MIN_HZ, EqBand.EQ_FREQ_MAX_HZ)
    return ((log10(clamped) - lo) / (hi - lo)).coerceIn(0f, 1f)
}

/**
 * 单段峰值双二阶（RBJ peaking EQ）在 [hz] 处的幅频响应（dB）。
 *
 * 系数公式与引擎同源（`native/flick-player-engine/src/audio/equalizer.rs` 的
 * `peaking_coeffs`），Q 见 [PEQ_CURVE_Q]。用双二阶而不是「增益点连线」：
 * 只有这样才能看到频点移动、以及相邻段叠加出的真实形状。
 */
internal fun peakingBandResponseDb(
    hz: Float,
    centerHz: Float,
    gainDb: Float,
    q: Float = PEQ_CURVE_Q,
    sampleRate: Float = PEQ_SAMPLE_RATE,
): Float {
    if (abs(gainDb) < 1e-3f) return 0f
    val f0 = centerHz.coerceIn(EqBand.EQ_FREQ_MIN_HZ, EqBand.EQ_FREQ_MAX_HZ)
        .coerceAtMost(sampleRate / 2f - 1f)
    val a = 10f.pow(gainDb / 40f)
    val w0 = 2f * PI.toFloat() * f0 / sampleRate
    val cosW0 = cos(w0)
    val alpha = sin(w0) / (2f * q)
    // 未归一化系数；分子分母同除 a0 不影响幅频响应，省掉两次除法。
    val b0 = 1f + alpha * a
    val b1 = -2f * cosW0
    val b2 = 1f - alpha * a
    val a0 = 1f + alpha / a
    val a1 = -2f * cosW0
    val a2 = 1f - alpha / a

    val w = 2f * PI.toFloat() * hz / sampleRate
    val cosW = cos(w)
    val sinW = sin(w)
    val cos2W = cos(2f * w)
    val sin2W = sin(2f * w)

    val numRe = b0 + b1 * cosW + b2 * cos2W
    val numIm = -(b1 * sinW + b2 * sin2W)
    val denRe = a0 + a1 * cosW + a2 * cos2W
    val denIm = -(a1 * sinW + a2 * sin2W)
    val num = numRe * numRe + numIm * numIm
    val den = denRe * denRe + denIm * denIm
    if (den <= 1e-12f) return 0f
    // 20 * log10(|H|) == 10 * log10(|H|^2)
    return 10f * log10(num / den)
}

/** 全部频段级联后的合成响应（dB）：单通道串行经过每一段，幅度相乘 ⇒ dB 相加。 */
internal fun peqResponseDb(bands: List<EqBand>, hz: Float): Float {
    var sum = 0f
    bands.forEach { sum += peakingBandResponseDb(hz, it.frequencyHz, it.gainDb) }
    return sum
}

/**
 * 在 20 Hz ~ 20 kHz 的对数刻度上取 [count] 个点的合成响应（dB）。
 *
 * 返回值与画布尺寸无关：第 `i` 个点对应横向 `i / (count - 1)`。
 */
internal fun samplePeqResponse(bands: List<EqBand>, count: Int): FloatArray {
    if (count <= 0) return FloatArray(0)
    if (count == 1) {
        return floatArrayOf(
            peqResponseDb(bands, sqrt(EqBand.EQ_FREQ_MIN_HZ * EqBand.EQ_FREQ_MAX_HZ)),
        )
    }
    val lo = log10(EqBand.EQ_FREQ_MIN_HZ)
    val hi = log10(EqBand.EQ_FREQ_MAX_HZ)
    return FloatArray(count) { index ->
        val t = index / (count - 1).toFloat()
        peqResponseDb(bands, 10f.pow(lo + (hi - lo) * t))
    }
}

// ------------------------------------------------------------ 推子拖拽换算

/**
 * 推子拖拽的增益换算：以 [startGainDb] 为起点，累计位移 [accumulatedPx]（向下为正）
 * 折算成 dB；[trackPx] 的轨道高度对应整个 ±[EqBand.EQ_GAIN_MAX_DB] 量程。
 *
 * 必须是「起点 + 累计位移」，而不是「上一帧增益 + 本帧位移」：后者只要有一帧回写被
 * 合并/丢弃，误差就永久留在状态里、越拖越偏；而且拿状态当起点必然要读手势闭包里的
 * 陈旧快照（见 [VerticalFaderColumn] 上的注释）。
 */
internal fun faderGainFromDrag(startGainDb: Float, accumulatedPx: Float, trackPx: Float): Float {
    if (trackPx <= 0f) return quantizeGainDb(startGainDb)
    val spanDb = EqBand.EQ_GAIN_MAX_DB - EqBand.EQ_GAIN_MIN_DB
    return quantizeGainDb(startGainDb - accumulatedPx / trackPx * spanDb)
}

/** 点击轨道时按绝对纵坐标定位增益（顶部 = +[EqBand.EQ_GAIN_MAX_DB] dB）。 */
internal fun faderGainAtY(y: Float, trackPx: Float): Float {
    if (trackPx <= 0f) return 0f
    val spanDb = EqBand.EQ_GAIN_MAX_DB - EqBand.EQ_GAIN_MIN_DB
    val ratio = 1f - (y / trackPx).coerceIn(0f, 1f)
    return quantizeGainDb(EqBand.EQ_GAIN_MIN_DB + ratio * spanDb)
}

/** 增益量化到 0.1dB 并夹在合法量程内（负数不产生 `-0.0`）。 */
internal fun quantizeGainDb(db: Float): Float {
    val clamped = db.coerceIn(EqBand.EQ_GAIN_MIN_DB, EqBand.EQ_GAIN_MAX_DB)
    return (clamped * 10f).roundToInt() / 10f
}

/**
 * 一次推子拖拽的会话状态：起点增益 + 累计位移 + 轨道像素高度。
 *
 * 放 `remember` 里而不是直接当组合变量：`pointerInput` 的手势协程会被重启
 * （key 变化 / 节点重挂），重启后仍拿到同一个对象，累计量不会中途清零。
 */
private class FaderDragSession {
    private var startGainDb = 0f
    private var accumulatedPx = 0f
    private var trackPx = 1f

    fun begin(gainDb: Float, trackPx: Float) {
        startGainDb = gainDb
        accumulatedPx = 0f
        this.trackPx = trackPx.coerceAtLeast(1f)
    }

    fun dragBy(deltaPx: Float): Float {
        accumulatedPx += deltaPx
        return faderGainFromDrag(startGainDb, accumulatedPx, trackPx)
    }

    fun reset() {
        accumulatedPx = 0f
    }
}

private fun formatHzNumber(hz: Float): String =
    if (hz >= 1000f) {
        val k = hz / 1000f
        if (abs(k - k.roundToInt()) < 0.05f) "${k.roundToInt()}" else round1(k)
    } else {
        "${hz.roundToInt()}"
    }

private fun formatHz(hz: Float): String =
    if (hz >= 1000f) "${formatHzNumber(hz)} kHz" else "${formatHzNumber(hz)} Hz"

private fun formatGain(db: Float): String {
    val tenths = (db * 10f).roundToInt()
    val sign = if (tenths > 0) "+" else ""
    return "$sign${round1(db)}"
}

private fun round1(value: Float): String {
    val scaled = (value * 10f).roundToInt()
    val whole = scaled / 10
    val frac = abs(scaled % 10)
    return "$whole.$frac"
}

private fun balanceLabel(s: cp.player.app.i18n.CpStrings, balance: Float): String {
    val percent = (abs(balance) * 100f).roundToInt()
    return when {
        percent < 1 -> s.audioEffect.balanceCenter
        balance < 0f -> "${s.audioEffect.balanceLeft} $percent%"
        else -> "${s.audioEffect.balanceRight} $percent%"
    }
}
