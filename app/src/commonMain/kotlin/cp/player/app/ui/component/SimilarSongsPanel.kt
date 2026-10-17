package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import cp.player.app.ui.theme.LocalIsDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.util.resized
import cp.player.core.BackendResult
import cp.player.core.music.CPMediaId
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.launch

/**
 * 相似歌曲面板 —— 播放页共享组件，桌面播放页（`DesktopPlayerScreen` 的「相似」页签）
 * 与窄屏播放页（`PlayerScreen` 的第 4 个 pager 页）用同一份实现。
 *
 * ### 数据
 * 种子是**当前正在播放的这首歌**（不是首页那种「今日推荐第一首」）。队列里的曲目 id
 * 是完整 mediaId（如 `netease://song/123`），接口要裸数字 id，这里统一经
 * [CPMediaId.parse] 解析。走 `AppModel.musicRepository.getSimilarSongs`，缓存层已就绪；
 * 切歌（种子变化）自动重新拉取，同一种子不重复请求。
 *
 * 音源不支持时（`BackendResult.Unsupported`，如咪咕）给出「不支持」说明，
 * 不当作故障弹错误。
 *
 * ### 播放行为
 * 与旧版播放页一致：点任何一首 = **整个相似列表替换当前队列**，从被点的那首开始放。
 */
@Composable
fun SimilarSongsPanel(
    seedTrackId: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val seed = remember(seedTrackId) {
        runCatching { CPMediaId.parse(seedTrackId.orEmpty()).resourceId }
            .getOrDefault(seedTrackId.orEmpty())
    }

    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var unsupported by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf<List<TrackSummary>>(emptyList()) }
    var fetchedFor by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val isDark = LocalIsDarkTheme.current
    val toMediaId = { id: String ->
        if (id.contains("://")) id
        else "${AppModel.activeProviderId()}://song/$id"
    }

    LaunchedEffect(seed, refreshToken) {
        if (seed.isBlank()) return@LaunchedEffect
        val alreadyFetched = fetchedFor == seed &&
            (tracks.isNotEmpty() || errorMessage != null || unsupported)
        if (alreadyFetched) return@LaunchedEffect
        loading = true
        errorMessage = null
        unsupported = false
        val result = try {
            AppModel.musicRepository.getSimilarSongs(seed)
        } catch (e: Exception) {
            BackendResult.Error(e.message ?: "获取相似歌曲失败", cause = e)
        }
        when (result) {
            is BackendResult.Success -> {
                tracks = result.data
                unsupported = false
                errorMessage = null
            }
            is BackendResult.Unsupported -> {
                tracks = emptyList()
                unsupported = true
            }
            is BackendResult.Error -> {
                tracks = emptyList()
                errorMessage = result.message
            }
        }
        fetchedFor = seed
        loading = false
    }

    Column(modifier.fillMaxSize()) {
        // 顶部通透呼吸感标题栏（无厚重边框，轻量通透）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Column {
                    Text(
                        "相似歌曲",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        if (tracks.isNotEmpty()) "${tracks.size} 首相似好歌 · 点击即播" else "基于在播歌曲探索推荐",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            if (tracks.isNotEmpty()) {
                Surface(
                    onClick = {
                        scope.launch {
                            AppModel.playback.playQueue(
                                tracks.map { toMediaId(it.id) },
                                startIndex = 0,
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "全部播放",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        when {
            // 拉取中且还没有旧数据：整页 loading。
            loading && tracks.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CpLoadingIndicator(Modifier.size(40.dp))
                }
            }
            unsupported -> {
                PanelMessage(
                    title = "当前音源暂不支持相似歌曲",
                    message = "切换到支持该功能的音源后再试试",
                )
            }
            errorMessage != null && tracks.isEmpty() -> {
                PanelMessage(
                    title = "获取相似歌曲失败",
                    message = errorMessage,
                ) {
                    Button(onClick = { refreshToken++ }, modifier = Modifier.padding(top = 16.dp)) {
                        Text("重试")
                    }
                }
            }
            tracks.isEmpty() && (fetchedFor == seed || seed.isBlank()) -> {
                PanelMessage(
                    title = if (seed.isBlank()) "没有正在播放的歌曲" else "没有找到相似歌曲",
                    message = if (seed.isBlank()) "播放一首歌后再来看相似推荐" else "换一首歌再试试",
                )
            }
            else -> {
                LazyScrollColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(tracks) { index, item ->
                        val playingSeed = item.id == seed
                        Surface(
                            onClick = {
                                CoverFlight.play(item.id, item.coverUrl)
                                AppModel.playTrackClicked(toMediaId(item.id)) {
                                    AppModel.playback.playQueue(
                                        tracks.map { toMediaId(it.id) },
                                        startIndex = index,
                                    )
                                }
                            },
                            shape = MaterialTheme.shapes.medium,
                            color = if (playingSeed) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.5f else 0.7f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(modifier = Modifier.size(46.dp)) {
                                    SimilarArtwork(item.coverUrl)
                                    if (playingSeed) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clip(MaterialTheme.shapes.medium)
                                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Filled.GraphicEq,
                                                contentDescription = "当前播放",
                                                tint = androidx.compose.ui.graphics.Color.White,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (playingSeed) FontWeight.Bold else FontWeight.Medium,
                                        color = if (playingSeed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        item.artist,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            AppModel.playback.addNextToQueue(toMediaId(item.id))
                                        }
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = "下一首播放",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarArtwork(url: String?) {
    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = url.resized(180),
            contentDescription = null,
            modifier = Modifier.size(46.dp).clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop,
        )
    } else {
        CpCoverPlaceholder(
            modifier = Modifier.size(46.dp),
            corner = 12.dp,
            animated = false,
        )
    }
}

/** 面板内的空态 / 错误态：图标 + 标题 + 说明 + 可选动作（如重试）。 */
@Composable
private fun PanelMessage(
    title: String,
    message: String?,
    content: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        content()
    }
}
