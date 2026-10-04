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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
) {
    val seed = remember(seedTrackId) {
        runCatching { CPMediaId.parse(seedTrackId.orEmpty()).resourceId }
            .getOrDefault(seedTrackId.orEmpty())
    }

    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var unsupported by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf<List<TrackSummary>>(emptyList()) }
    // 按种子去重：面板随 pager 翻页而离开重组，回来时不要重新请求。
    // 切歌 → seed 变化 → LaunchedEffect 重启 → fetchedFor 不匹配 → 重新拉取。
    var fetchedFor by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // ⚠️ 惰性取 provider：`AppModel.activeProviderId()` 会取 MusicBackend 单例，
    // 未 init() 时直接抛异常（离屏渲染实测）。放进 lambda 里只在真正点播时才读，
    // 组合期零依赖 —— 组件因此可以在无后端的预览/测试环境里独立渲染。
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
        Text(
            "基于当前播放的这首歌推荐 · 点击任意一首替换播放队列",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
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
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
                            color = if (playingSeed) MaterialTheme.colorScheme.secondaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(24.dp),
                                )
                                SimilarArtwork(item.coverUrl)
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(
                                        item.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (playingSeed) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    Text(
                                        item.artist,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
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
            modifier = Modifier.size(42.dp).clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            Modifier.size(42.dp).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
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
