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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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
import cp.player.app.ui.model.SearchScreenModel
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
            Spacer(Modifier.height(12.dp))

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
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (state.suggestions.isNotEmpty()) {
                                SectionHeader(title = "搜索建议")
                                state.suggestions.forEach { suggestion ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { model.search(suggestion) }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(12.dp))
                                        Text(suggestion)
                                    }
                                }
                            } else {
                                SectionHeader(title = "热门搜索")
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    state.hotSearches.forEach { keyword ->
                                        SuggestionChip(
                                            onClick = { model.search(keyword) },
                                            label = { Text(keyword) }
                                        )
                                    }
                                }
                                ContentState(
                                    title = "发现下一首喜欢的音乐",
                                    message = "输入关键词后按搜索键",
                                    modifier = Modifier.padding(top = 32.dp),
                                )
                            }
                        }
                    }
                    state.result!!.songs.isEmpty() -> ContentState(
                        title = "没有找到结果",
                        message = "试试更短的关键词或检查拼写",
                        modifier = Modifier.padding(top = 32.dp),
                    )
                    else -> {
                        val songs = state.result!!.songs
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                bottom = 32.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            item {
                                SectionHeader(
                                    title = "搜索结果",
                                    supportingText = "${songs.size} 首歌曲",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                )
                            }
                            itemsIndexed(songs, key = { _, track -> track.id }) { index, track ->
                                SongItem(
                                    track = track,
                                    index = index,
                                    total = songs.size,
                                    onClick = {
                                        scope.launch {
                                            AppModel.playback.playQueue(
                                                songs.map { "$provider://song/${it.id}" },
                                                startIndex = index,
                                            )
                                        }
                                    },
                                    onOptionsClick = { selectedTrack = track },
                                )
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
                isFavorite = track.id in likedIds,
                isDownloaded = false,
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
