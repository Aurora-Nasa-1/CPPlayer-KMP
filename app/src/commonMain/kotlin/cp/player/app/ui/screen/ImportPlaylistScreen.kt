package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpLinearProgress
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.PlaylistPickerSheet
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.settingsRowContainer
import cp.player.app.ui.model.ImportPlaylistScreenModel
import cp.player.app.ui.model.ImportPlaylistUiState
import cp.player.app.ui.util.popOrNotify
import cp.player.core.music.PlaylistSummary
import cp.player.app.ui.component.CpText

/**
 * 外部歌单导入页。
 *
 * 输入 → 识别 → 勾选 → 落盘四段，全部在同一页完成：
 * 粘贴文本后立刻能看到「哪些歌匹配到了、哪些没有」，而不是点完导入才知道失败。
 * 识别结果默认全选，用户可以取消个别条目；未匹配的条目单独列在底部，不会被静默吞掉。
 *
 * 入口在「我的 → 曲库」标题行（[cp.player.app.ui.screen.LibraryScreen]）。
 */
class ImportPlaylistScreen : Screen {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { ImportPlaylistScreenModel() }
        val navigator = LocalNavigator.currentOrThrow
        ImportPlaylistContent(
            model = model,
            onBack = { navigator.popOrNotify() },
            onOpenPlaylist = { summary -> navigator.push(PlaylistDetailScreen(summary)) },
        )
    }
}

/**
 * 页面正文。导航动作由调用方注入 —— 离屏预览 / 测试可以传空实现，
 * 不必伪造一个 Voyager Navigator。
 */
@Composable
internal fun ImportPlaylistContent(
    model: ImportPlaylistScreenModel,
    onBack: () -> Unit,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
) {
    val s = cpStrings()
    val state by model.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var showPlaylistPicker by remember { mutableStateOf(false) }

    CpRouteScaffold(
        title = s.playlistImport.screenTitle,
        onBack = onBack,
    ) { modifier ->
        SettingsLazyPage(modifier) {
            item {
                SettingsSection(s.playlistImport.inputTitle) {
                    InputCard(
                        state = state,
                        onRawChange = model::setRaw,
                        onPaste = {
                            val text = runCatching { clipboard.getText()?.text }.getOrNull()
                            if (!text.isNullOrBlank()) model.setRaw(text)
                        },
                        onAnalyze = { model.analyze(s) },
                    )
                }
            }

            item { SettingsNote(s.playlistImport.inputNote) }

            if (state.analyzing) {
                item { AnalyzingCard(state) }
            }

            state.error?.let { message ->
                item { SettingsNote(message, emphasis = SettingsNoteEmphasis.ERROR) }
            }

            if (state.analyzed) {
                item { ResultSummary(state) }

                item {
                    SettingsSection(s.playlistImport.resultTitle) {
                        NameCard(state, onNameChange = model::setName)
                        ImportActions(
                            state = state,
                            onImportNew = { model.importToNew(s) },
                            onImportExisting = { showPlaylistPicker = true },
                        )
                    }
                }

                item {
                    PreviewHeader(
                        onSelectAll = { model.selectAll(true) },
                        onDeselectAll = { model.selectAll(false) },
                    )
                }

                itemsIndexed(state.tracks, key = { _, item -> item.track.id }) { index, item ->
                    SongItem(
                        track = item.track,
                        onClick = { model.toggleTrack(index) },
                        showIndex = true,
                        index = index,
                        total = state.tracks.size,
                        selectionMode = true,
                        isSelected = item.selected,
                    )
                }

                if (state.unmatched.isNotEmpty()) {
                    item {
                        CpText(
                            s.playlistImport.unmatchedTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(state.unmatched) { line ->
                        CpText(
                            "· $line",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }

                state.outcome?.let { outcome ->
                    item {
                        OutcomeCard(
                            state = state,
                            onOpen = {
                                onOpenPlaylist(
                                    PlaylistSummary(
                                        id = outcome.playlistId.toLongOrNull() ?: 0L,
                                        resourceId = outcome.playlistId,
                                        name = outcome.playlistName,
                                        coverUrl = state.sourcePlaylist?.coverUrl,
                                        trackCount = outcome.added,
                                        creatorName = AppModel.userProfileFlow.value?.nickname,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (showPlaylistPicker) {
        PlaylistPickerSheet(
            title = s.playlistImport.appendTitle,
            onDismiss = { showPlaylistPicker = false },
            onSelected = { target ->
                showPlaylistPicker = false
                model.importToExisting(target, s)
            },
        )
    }
}

@Composable
private fun InputCard(
    state: ImportPlaylistUiState,
    onRawChange: (String) -> Unit,
    onPaste: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = settingsRowContainer(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = state.raw,
                onValueChange = onRawChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                placeholder = {
                    CpText(
                        s.playlistImport.inputPlaceholder,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 5,
                maxLines = 10,
                shape = MaterialTheme.shapes.medium,
                enabled = !state.importing,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onPaste, enabled = !state.importing) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    CpText(s.playlistImport.pasteFromClipboard)
                }
                TextButton(
                    onClick = { onRawChange("") },
                    enabled = state.raw.isNotEmpty() && !state.importing,
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    CpText(s.playlistImport.clear)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onAnalyze,
                    enabled = state.raw.isNotBlank() && !state.analyzing && !state.importing,
                    shape = MaterialTheme.shapes.large,
                ) {
                    CpText(s.playlistImport.analyze)
                }
            }
        }
    }
}

@Composable
private fun AnalyzingCard(state: ImportPlaylistUiState) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CpLoadingIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                CpText(
                    if (state.progressTotal > 0) {
                        s.playlistImport.progress(state.progressDone, state.progressTotal)
                    } else {
                        s.playlistImport.analyzing
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.progressTotal > 0) {
                CpLinearProgress(
                    progress = state.progressDone.toFloat() / state.progressTotal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ResultSummary(state: ImportPlaylistUiState) {
    val s = cpStrings()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CpText(
            s.playlistImport.recognized(state.tracks.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        state.sourcePlaylist?.let { source ->
            CpText(
                s.playlistImport.fromPlaylist(source.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.unmatched.isNotEmpty()) {
            CpText(
                s.playlistImport.unmatchedCount(state.unmatched.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.unsupportedLinks > 0) {
            CpText(
                s.playlistImport.unsupportedLinks(state.unsupportedLinks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun NameCard(state: ImportPlaylistUiState, onNameChange: (String) -> Unit) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = settingsRowContainer(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CpText(
                s.playlistImport.nameLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { CpText(s.playlistImport.namePlaceholder) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                enabled = !state.importing,
            )
            if (state.importing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CpLoadingIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    CpText(
                        s.playlistImport.importProgress(state.importDone, state.importTotal),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.importTotal > 0) {
                    CpLinearProgress(
                        progress = state.importDone.toFloat() / state.importTotal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportActions(
    state: ImportPlaylistUiState,
    onImportNew: () -> Unit,
    onImportExisting: () -> Unit,
) {
    val s = cpStrings()
    val enabled = state.selectedCount > 0 && !state.importing
    Surface(
        shape = MaterialTheme.shapes.large,
        color = settingsRowContainer(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onImportNew,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                CpText(s.playlistImport.importToNew)
            }
            FilledTonalButton(
                onClick = onImportExisting,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                CpText(s.playlistImport.importToExisting)
            }
            if (state.selectedCount == 0) {
                CpText(
                    s.playlistImport.emptySelection,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    val s = cpStrings()
    // 标题行与说明分成两行：窄屏（400dp 上下）把「曲目预览 + 说明 + 两个按钮」塞进
    // 一行时，说明会被挤成两行且末字单独掉到下一行（离屏渲染实测）。
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CpText(
                s.playlistImport.previewTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll) { CpText(s.playlistImport.selectAll) }
            TextButton(onClick = onDeselectAll) { CpText(s.playlistImport.deselectAll) }
        }
        CpText(
            s.playlistImport.previewHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OutcomeCard(state: ImportPlaylistUiState, onOpen: () -> Unit) {
    val s = cpStrings()
    val outcome = state.outcome ?: return
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(Modifier.weight(1f)) {
                CpText(
                    s.playlistImport.importSuccess(outcome.added, outcome.playlistName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (outcome.partial) {
                    CpText(
                        s.playlistImport.partialFailure,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            TextButton(onClick = onOpen) { CpText(s.playlistImport.openPlaylist) }
        }
    }
}
