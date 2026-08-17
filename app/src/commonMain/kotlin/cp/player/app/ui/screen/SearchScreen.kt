package cp.player.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.rememberScreenModel
import cp.player.app.AppModel
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.PageHeader
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.PlaylistItem
import cp.player.app.ui.model.SearchScreenModel
import cp.player.kmp.api.MusicApiMethod
import kotlinx.coroutines.launch

class SearchScreen : Screen {
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val model = rememberScreenModel { SearchScreenModel() }
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()
        val provider = AppModel.activeProviderId()
        val likedIds by AppModel.playback.likedIds.collectAsState()
        var selectedTrack by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<cp.player.kmp.music.TrackSummary?>(null)
        }
        var addToPlaylistTrack by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<cp.player.kmp.music.TrackSummary?>(null)
        }

        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = model::setQuery,
                modifier = Modifier.fillMaxWidth().padding(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    top = 12.dp,
                ),
                placeholder = { Text("搜索歌曲、歌手或专辑") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { model.search() }),
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(
                            onClick = model::clear,
                        ) { Icon(Icons.Rounded.Close, "清空") }
                    } else {
                        IconButton(onClick = { model.search() }) { Icon(Icons.Filled.Search, "搜索") }
                    }
                },
                shape = RoundedCornerShape(percent = 50),
            )
            Spacer(Modifier.height(8.dp))
            if (state.query.isNotBlank() || state.result != null) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val types = listOf(
                        MusicApiMethod.SEARCH_TYPE_SONG to "歌曲",
                        MusicApiMethod.SEARCH_TYPE_ALBUM to "专辑",
                        MusicApiMethod.SEARCH_TYPE_ARTIST to "歌手",
                        MusicApiMethod.SEARCH_TYPE_PLAYLIST to "歌单",
                    )
                    items(types.size) { index ->
                        val (type, label) = types[index]
                        FilterChip(
                            selected = state.searchType == type,
                            onClick = { model.selectSearchType(type) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            if (state.query.isNotBlank() && state.suggestions.isNotEmpty() && state.result == null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 3.dp,
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        state.suggestions.forEach { suggestion ->
                            Row(
                                Modifier.fillMaxWidth().clickable { model.search(suggestion) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Text(suggestion)
                            }
                        }
                    }
                }
            }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when {
                    state.loading -> StateSurface(Modifier.padding(20.dp)) {
                        ContentState(title = "正在搜索", message = "正在从当前音源查找内容", loading = true)
                    }
                    state.error != null -> StateSurface(Modifier.padding(20.dp)) {
                        ContentState(
                            title = "没有完成搜索",
                            message = state.error,
                            error = true,
                            actionLabel = "重试",
                            onAction = { model.search() },
                        )
                    }
                    state.result == null -> {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = CpSpacing.pageHorizontal),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (state.query.isBlank() && state.searchHistory.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SectionHeader(title = "最近搜索")
                                    TextButton(onClick = model::clearHistory) { Text("清空") }
                                }
                                state.searchHistory.forEach { keyword ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { model.search(keyword) }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(12.dp))
                                        Text(keyword)
                                    }
                                }
                            }
                            if (state.query.isBlank()) {
                                SectionHeader(title = "热门搜索")
                                state.hotSearches.forEachIndexed { index, hot ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { model.search(hot.keyword) }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            modifier = Modifier.width(28.dp),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (index < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(hot.keyword)
                                            if (hot.description.isNotBlank()) {
                                                Text(hot.description, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                            }
                                        }
                                        Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    }
                                }
                                ContentState(
                                    title = "发现下一首喜欢的音乐",
                                    message = "输入关键词后按搜索键",
                                    modifier = Modifier.padding(top = 20.dp),
                                )
                            }
                        }
                    }
                    state.result == null -> Unit
                    else -> {
                        val result = state.result!!
                        val count = when (state.searchType) {
                            MusicApiMethod.SEARCH_TYPE_SONG -> result.songs.size
                            MusicApiMethod.SEARCH_TYPE_ALBUM, MusicApiMethod.SEARCH_TYPE_PLAYLIST -> result.playlists.size
                            else -> result.artists.size
                        }
                        if (count == 0) {
                            ContentState(
                                title = "没有找到结果",
                                message = "试试更短的关键词或切换搜索类型",
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                item {
                                    SectionHeader(
                                        title = "搜索结果",
                                        supportingText = "$count 项",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                    )
                                }
                                when (state.searchType) {
                                    MusicApiMethod.SEARCH_TYPE_SONG -> itemsIndexed(result.songs, key = { _, track -> track.id }) { index, track ->
                                        SongItem(
                                            track = track, index = index, total = result.songs.size,
                                            onClick = { scope.launch { AppModel.playback.playQueue(result.songs.map { "$provider://song/${it.id}" }, index) } },
                                            onOptionsClick = { selectedTrack = track },
                                        )
                                    }
                                    MusicApiMethod.SEARCH_TYPE_ALBUM, MusicApiMethod.SEARCH_TYPE_PLAYLIST -> itemsIndexed(result.playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
                                        PlaylistItem(
                                            playlist = playlist,
                                            isOwner = false,
                                            onClick = { /* 详情页可从首页后续接入 */ },
                                            onOptionsClick = {},
                                        )
                                    }
                                    MusicApiMethod.SEARCH_TYPE_ARTIST -> itemsIndexed(result.artists, key = { _, artist -> artist.id }) { _, artist ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(12.dp))
                                            Text(artist.name, style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        selectedTrack?.let { track ->
            cp.player.app.ui.component.SongOptionsSheet(
                songName = track.name,
                artistName = track.artist,
                coverUrl = track.coverUrl,
                isFavorite = track.id in likedIds,
                isDownloaded = AppModel.isDownloaded(track.id),
                onDismiss = { selectedTrack = null },
                onPlay = {
                    scope.launch {
                        AppModel.playback.playQueue(listOf("$provider://song/${track.id}"), startIndex = 0)
                    }
                },
                onToggleFavorite = {
                    scope.launch {
                        val target = track.id !in likedIds
                        AppModel.playback.toggleFavoriteFor("$provider://song/${track.id}")
                        cp.player.app.ui.util.UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                    }
                },
                onAddToQueue = {
                    scope.launch { AppModel.playback.addToQueue("$provider://song/${track.id}") }
                    cp.player.app.ui.util.UiEvents.notify("已加入播放队列")
                },
                onAddToPlaylist = { addToPlaylistTrack = track },
                onDownload = { AppModel.downloadTrack(track) },
            )
        }

        addToPlaylistTrack?.let { track ->
            cp.player.app.ui.component.AddToPlaylistSheet(
                trackId = track.id,
                onDismiss = { addToPlaylistTrack = null },
            )
        }
    }
}
