package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.core.util.currentTimeMillis
import cp.player.core.util.localDateTimeOf

/**
 * 睡眠定时对话框：预设时长 / 自定义时间 / 播完当前后暂停 / 取消已有定时。
 *
 * @param activeRemainingMs 当前生效中的定时剩余毫秒（null = 未设置）。
 * @param afterTrackActive  当前是否为"播完当前"模式。
 */
@Composable
fun SleepTimerDialog(
    activeRemainingMs: Long?,
    afterTrackActive: Boolean,
    onSelect: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 90 分钟此前只存在于设置页的下拉里，对话框没有 —— 统一到这里，两边共用一份。
    val options = listOf(15, 30, 45, 60, 90)
    val s = cpStrings()
    val activeMinutes = activeRemainingMs?.let { ((it + 30_000L) / 60_000L).toInt() }
    val isCustomActive = !afterTrackActive && activeMinutes != null && activeMinutes !in options
    var showCustomDialog by remember { mutableStateOf(false) }

    if (showCustomDialog) {
        SleepCustomTimeDialog(
            initialMinutes = if (isCustomActive) activeMinutes else null,
            onConfirm = { minutes ->
                showCustomDialog = false
                onSelect(minutes)
                onDismiss()
            },
            onDismiss = { showCustomDialog = false },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
            title = {
                CpText(
                    s.playback.timerDialogTitle,
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (activeRemainingMs != null || afterTrackActive) {
                        CpText(
                            // ⚠️ `+ 1` 留在文案函数里而不是这里：这是向上取整到分钟的
                            // 显示规则（中英都成立），但两边的措辞不同，调用方拼就散了。
                            text = if (afterTrackActive) {
                                s.playback.timerActiveAfterTrack()
                            } else {
                                s.playback.timerActiveInMinutes(activeRemainingMs!! / 60_000L)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    CpText(s.playback.timerPrompt, style = MaterialTheme.typography.bodyMedium)
                    // 分块换行：5 个预设 + 1 个自定义 chip，每行 3 个刚好排满 2 行。
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 45).forEach { minutes ->
                                CpToggleChip(
                                    checked = !afterTrackActive && (minutes == activeMinutes),
                                    onCheckedChange = { onSelect(minutes); onDismiss() },
                                    label = s.playback.timerMinutesChip(minutes),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(60, 90).forEach { minutes ->
                                CpToggleChip(
                                    checked = !afterTrackActive && (minutes == activeMinutes),
                                    onCheckedChange = { onSelect(minutes); onDismiss() },
                                    label = s.playback.timerMinutesChip(minutes),
                                )
                            }
                            CpToggleChip(
                                checked = isCustomActive,
                                onCheckedChange = { showCustomDialog = true },
                                label = if (isCustomActive) {
                                    s.playback.timerMinutesChip(activeMinutes!!)
                                } else {
                                    s.playback.timerCustomChip
                                },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CpToggleChip(
                            checked = afterTrackActive,
                            onCheckedChange = { onSelect(AppModel.sleepAfterTrack); onDismiss() },
                            label = s.playback.timerAfterTrackChip,
                        )
                    }
                }
            },
            confirmButton = {
                if (activeRemainingMs != null || afterTrackActive) {
                    TextButton(onClick = { onCancelTimer(); onDismiss() }) {
                        CpText(s.playback.timerCancel, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { CpText(s.playback.timerClose) }
            },
        )
    }
}

/**
 * 自定义睡眠定时对话框：支持 [TimePicker]（表盘）与 [TimeInput]（键盘输入）互相切换，
 * 提供「倒计时时长」与「指定时刻」两种设定维度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepCustomTimeDialog(
    initialMinutes: Int? = null,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = cpStrings()
    val now = remember { localDateTimeOf(currentTimeMillis()) }
    var isCountdownMode by remember { mutableStateOf(true) }
    var showDial by remember { mutableStateOf(true) }

    val defaultCountdownMinutes = (initialMinutes ?: 30).coerceIn(1, 24 * 60)
    val countdownState = rememberTimePickerState(
        initialHour = defaultCountdownMinutes / 60,
        initialMinute = defaultCountdownMinutes % 60,
        is24Hour = true,
    )

    val defaultTargetTotalM = now.hour * 60 + now.minute + (initialMinutes ?: 30)
    val atTimeState = rememberTimePickerState(
        initialHour = (defaultTargetTotalM / 60) % 24,
        initialMinute = defaultTargetTotalM % 60,
        is24Hour = true,
    )

    val activeState = if (isCountdownMode) countdownState else atTimeState

    val (canConfirm, targetMinutes, hintText) = if (isCountdownMode) {
        val totalM = activeState.hour * 60 + activeState.minute
        if (totalM > 0) {
            val endTotalM = (now.hour * 60 + now.minute + totalM) % (24 * 60)
            val endH = (endTotalM / 60).toString().padStart(2, '0')
            val endM = (endTotalM % 60).toString().padStart(2, '0')
            Triple(true, totalM, s.playback.timerCustomDurationHint(totalM, "$endH:$endM"))
        } else {
            Triple(false, 0, s.playback.timerPrompt)
        }
    } else {
        val targetH = activeState.hour
        val targetM = activeState.minute
        val nowTotalM = now.hour * 60 + now.minute
        val targetTotalM = targetH * 60 + targetM
        var diffM = targetTotalM - nowTotalM
        if (diffM <= 0) diffM += 24 * 60
        val targetHStr = targetH.toString().padStart(2, '0')
        val targetMStr = targetM.toString().padStart(2, '0')
        Triple(true, diffM, s.playback.timerCustomAtTimeHint("$targetHStr:$targetMStr", diffM.toLong()))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CpText(
                    text = s.playback.timerCustomDialogTitle,
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(
                    onClick = { showDial = !showDial },
                ) {
                    Icon(
                        imageVector = if (showDial) Icons.Filled.Keyboard else Icons.Filled.AccessAlarm,
                        contentDescription = if (showDial) {
                            s.playback.timerCustomToggleInput
                        } else {
                            s.playback.timerCustomToggleDial
                        },
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CpToggleChip(
                        checked = isCountdownMode,
                        onCheckedChange = { isCountdownMode = true },
                        label = s.playback.timerCustomModeDuration,
                    )
                    CpToggleChip(
                        checked = !isCountdownMode,
                        onCheckedChange = { isCountdownMode = false },
                        label = s.playback.timerCustomModeAtTime,
                    )
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showDial) {
                        TimePicker(state = activeState)
                    } else {
                        TimeInput(state = activeState)
                    }
                }

                CpText(
                    text = hintText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (canConfirm) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canConfirm) {
                        onConfirm(targetMinutes)
                    }
                },
                enabled = canConfirm,
            ) {
                CpText(s.playback.timerCustomConfirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                CpText(s.playback.timerClose)
            }
        },
    )
}
