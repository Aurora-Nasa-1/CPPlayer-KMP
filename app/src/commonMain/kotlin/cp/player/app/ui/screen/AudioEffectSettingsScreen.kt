package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
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
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

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
                        SettingsSection("发烧音效与硬件增强") {
                            SettingsClickItem(
                                title = "开启 FlickEngine Hi-Res 发烧内核",
                                subtitle = "启用专属发烧架构以解锁 64 位浮点 5 段参数均衡器 (PEQ) 与空间环绕增强",
                                index = 0,
                                total = 1,
                                onClick = {
                                    navigator.push(AudioEngineSettingsScreen())
                                },
                                trailingContent = {
                                    CpText(
                                        text = "前往设置",
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
                            onBandGainChange = { bandIndex, gainDb ->
                                draft = draft.copy(
                                    equalizer = eq.copy(
                                        bands = eq.bands.replaceAt(
                                            bandIndex,
                                            eq.bands[bandIndex].copy(gainDb = gainDb),
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

                        AnimatedVisibility(visible = showFrequencyTuning) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                eq.bands.forEachIndexed { bandIndex, band ->
                                    SettingsSliderItem(
                                        title = "频段 ${bandIndex + 1} 中心频率",
                                        subtitle = "${formatHz(band.frequencyHz)} (${formatGain(band.gainDb)} dB)",
                                        value = band.frequencyHz,
                                        onValueChange = { hz ->
                                            draft = draft.copy(
                                                equalizer = eq.copy(
                                                    bands = eq.bands.replaceAt(
                                                        bandIndex,
                                                        band.copy(frequencyHz = hz),
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
 * 响应曲线可视化 Card：根据 5 段增益绘制平滑的贝塞尔响应曲线。
 */
@Composable
private fun EqualizerCurveCard(
    bands: List<EqBand>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val containerBg = settingsRowContainer()
    val disabledAlpha = if (enabled) 1.0f else 0.45f

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

            // 0 dB 参考虚线
            drawLine(
                color = outlineVariant.copy(alpha = 0.8f * disabledAlpha),
                start = Offset(0f, midY),
                end = Offset(width, midY),
                strokeWidth = 1.dp.toPx(),
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

            if (bands.isEmpty()) return@Canvas

            // 计算 5 个频点的 X, Y
            val count = bands.size
            val points = bands.mapIndexed { index, band ->
                val x = (index + 0.5f) * (width / count)
                // gainDb: -12..+12 -> Y: height..0
                val normalizedGain = ((band.gainDb - EqBand.EQ_GAIN_MIN_DB) /
                    (EqBand.EQ_GAIN_MAX_DB - EqBand.EQ_GAIN_MIN_DB)).coerceIn(0f, 1f)
                val y = height * (1f - normalizedGain)
                Offset(x, y)
            }

            // 平滑曲线 Path
            val curvePath = Path()
            val fillPath = Path()

            curvePath.moveTo(0f, points.first().y)
            fillPath.moveTo(0f, midY)
            fillPath.lineTo(0f, points.first().y)

            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val cx = (p0.x + p1.x) / 2f
                curvePath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                fillPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            }

            curvePath.lineTo(width, points.last().y)
            fillPath.lineTo(width, points.last().y)
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

            // 绘制控制锚点
            points.forEach { pt ->
                drawCircle(
                    color = containerBg,
                    radius = 4.dp.toPx(),
                    center = pt,
                )
                drawCircle(
                    color = if (enabled) primaryColor else primaryColor.copy(alpha = 0.5f),
                    radius = 3.dp.toPx(),
                    center = pt,
                )
            }
        }

        // 标尺读数
        CpText(
            text = "+12 dB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.TopStart),
        )
        CpText(
            text = "0 dB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.CenterStart),
        )
        CpText(
            text = "-12 dB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.BottomStart),
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

        // 垂直滑杆槽 (高度 140dp, 宽度 24dp)
        BoxWithConstraints(
            modifier = Modifier
                .width(28.dp)
                .height(130.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(trackBgColor)
                .pointerInput(bandIndex, enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd = { onGainChangeFinished() },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val pxRange = 130.dp.toPx()
                            val deltaDb = -(dragAmount / pxRange) * (EqBand.EQ_GAIN_MAX_DB - EqBand.EQ_GAIN_MIN_DB)
                            val nextDb = (band.gainDb + deltaDb).coerceIn(EqBand.EQ_GAIN_MIN_DB, EqBand.EQ_GAIN_MAX_DB)
                            // 精度 0.1dB
                            val rounded = (nextDb * 10f).roundToInt() / 10f
                            onGainChange(rounded)
                        },
                    )
                }
                .pointerInput(bandIndex, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = {
                            onGainChange(0f)
                            onGainChangeFinished()
                        },
                        onTap = { offset ->
                            val pxRange = 130.dp.toPx()
                            val ratio = 1f - (offset.y / pxRange).coerceIn(0f, 1f)
                            val nextDb = EqBand.EQ_GAIN_MIN_DB + ratio * (EqBand.EQ_GAIN_MAX_DB - EqBand.EQ_GAIN_MIN_DB)
                            val rounded = (nextDb * 10f).roundToInt() / 10f
                            onGainChange(rounded)
                            onGainChangeFinished()
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
