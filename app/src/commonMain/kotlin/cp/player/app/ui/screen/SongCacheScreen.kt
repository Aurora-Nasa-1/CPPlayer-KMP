package cp.player.app.ui.screen

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.model.SongCacheModel
import cp.player.app.ui.model.SongCacheUiState
import cp.player.app.ui.model.displayName
import cp.player.app.ui.model.formatBytes
import cp.player.app.ui.model.qualityLabel
import cp.player.app.ui.model.relativeTime
import cp.player.app.ui.util.popOrNotify
import cp.player.core.playback.SongCacheEntry

/**
 * 歌曲缓存明细页：**看看缓存里到底有什么，并删掉某几首**。
 *
 * ### 职责边界（刻意窄）
 *
 * 本页只做「浏览 / 搜索 / 删单条」。**批量清理不在这里** —— 它属于上一页
 * 「下载与存储」，那里已经有「清理图片缓存」这个先例。同一个破坏性动作开两个入口，
 * 只会让用户不确定哪个才是「真的全清了」。
 *
 * ### 为什么必须有搜索
 *
 * 缓存条目只会按「最近播放」排，而用户要找的是**某首具体的歌**（比如它播到一半就断，
 * 想删掉重下）。没有搜索就只能滚动翻找。关键字匹配曲名 / 歌手 / 音质 / 文件名，
 * 规则在 core 的 [SongCacheEntry.matches] 里，与数据本身同源。
 */
class SongCacheScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // rememberScreenModel 是 Screen 的扩展函数，只能在 Content() 里调用（见 AGENTS.md）。
        val model = rememberScreenModel { SongCacheModel() }
        SongCacheContent(model = model, onBack = { navigator.popOrNotify() })
    }
}

@Composable
private fun SongCacheContent(
    model: SongCacheModel,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsState()
    val s = cpStrings()

    CpRouteScaffold(title = s.songCache.screenTitle, onBack = onBack) { pageModifier ->
        if (!state.loading && !state.supported) {
            // 安卓走这一支：ExoPlayer 能定位 HTTP FLAC，本来就不落盘。
            // 给一句明确解释，比显示「0 首 / 共 0 B」强 —— 后者会被当成 bug 来报。
            SettingsPage(pageModifier) {
                SettingsNote(s.songCache.unsupported)
            }
        } else {
            SettingsLazyPage(
                pageModifier = pageModifier,
                header = { SongCacheHeader(state = state, onQueryChange = model::setQuery) },
            ) {
                when {
                    state.loading -> item {
                        SongCacheHint(s.songCache.measuring)
                    }
                    state.isEmptyCache -> item {
                        SongCacheHint(s.songCache.empty)
                    }
                    state.hasNoMatch -> item {
                        SongCacheHint(s.songCache.noMatch(state.query))
                    }
                    else -> itemsIndexed(
                        items = state.visibleEntries,
                        key = { _, entry -> entry.id },
                    ) { index, entry ->
                        SongCacheRow(
                            entry = entry,
                            index = index,
                            total = state.visibleEntries.size,
                            onDelete = { model.remove(entry, s) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 钉在列表上方的概览 + 搜索框。
 *
 * 概览用一行说清「几个 / 多大 / 上限」，搜索框紧随其后 —— 用户来这一页的目的
 * 基本只有两个：确认占了多大，以及找到某一首删掉，两者都在首屏。
 */
@Composable
private fun ColumnScope.SongCacheHeader(
    state: SongCacheUiState,
    onQueryChange: (String) -> Unit,
) {
    val s = cpStrings()
    Text(
        text = if (state.loading) {
            s.songCache.measuringShort
        } else if (state.isEmptyCache) {
            s.songCache.emptyShort
        } else if (state.capacityBytes > 0L) {
            s.songCache.summaryCapped(
                state.entries.size,
                formatBytes(state.totalBytes),
                formatBytes(state.capacityBytes),
            )
        } else {
            s.songCache.summary(state.entries.size, formatBytes(state.totalBytes))
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        singleLine = true,
        enabled = state.entries.isNotEmpty(),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (state.isFiltering) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = s.songCache.clearSearch)
                }
            }
        },
        placeholder = { Text(s.songCache.searchPlaceholder) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 一条缓存。
 *
 * 副标题按「歌手 · 音质 · 大小 · 最后播放」拼，**不做逐项省略**：
 * 这四个字段正是用户判断「这条该不该删」的依据（尤其是大小与最后播放时间）。
 * 老缓存没有索引记录时曲名显示为「未知曲目」，其余字段仍全部有效。
 */
@Composable
private fun SongCacheRow(
    entry: SongCacheEntry,
    index: Int,
    total: Int,
    onDelete: () -> Unit,
) {
    val s = cpStrings()
    val name = entry.displayName(s)
    val subtitle = buildList {
        entry.artist?.takeIf { it.isNotBlank() }?.let { add(it) }
        entry.qualityLevel?.takeIf { it.isNotBlank() }?.let { add(qualityLabel(it, s)) }
        add(formatBytes(entry.bytes))
        add(relativeTime(entry.lastAccessMs, s))
    }.joinToString(" · ")

    SettingsClickItem(
        title = name,
        subtitle = subtitle,
        index = index,
        total = total,
        // 行尾是独立可操作的删除按钮 —— 合并语义会让读屏用户点不到它。
        mergeSemantics = false,
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = s.songCache.deleteEntry(name))
            }
        },
    )
}

/**
 * 列表内的说明文字。
 *
 * 不用 [SettingsNote]：它自带一层 `formHorizontal` 内边距，而 [SettingsLazyPage]
 * 的列表已经有同值的内容内边距，套上去会左右各多缩进 16dp，
 * 和列表里的行对不齐。
 */
@Composable
private fun SongCacheHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = CpSpacing.formVertical),
    )
}
