package cp.player.app.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cp.player.app.AppModel
import cp.player.app.ui.model.PlaylistDetailScreenModel
import cp.player.core.BackendResult
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary

enum class HomeGeneratedPlaylistKind {
    Static,
    IntelligenceFromDaily,
}

class HomeGeneratedPlaylistScreen(
    private val playlist: PlaylistSummary,
    private val initialTracks: List<TrackSummary>,
    /**
     * 进入后自动播放的曲目下标；**null（默认）表示只浏览、不播放**。
     *
     * 只有「点击某一首曲目」的入口才传具体下标（`HomeScreen.onPlayDailyTrack`）；
     * 「打开歌单」的入口一律不传 —— 点开每日推荐就自动出声是打扰，用户此时多半
     * 只是想看看今天推荐了什么。原先默认值是 `0`，于是「打开日推」等价于「播放第 1 首」。
     */
    private val startIndex: Int? = null,
    private val kind: HomeGeneratedPlaylistKind = HomeGeneratedPlaylistKind.Static,
    /** 拉取心动歌曲的种子曲目 id（这类页面本身不携带曲目，种子必须由调用方传入）。 */
    private val seedTrackId: String? = null,
    /**
     * 心动模式的 `pid`（歌单上下文，约定传「我喜欢的音乐」收藏夹 id）。
     * 上游把 `pid=0` 当非法请求 —— 没有收藏夹时该入口拿不到推荐，属于正常降级。
     */
    private val seedPlaylistId: Long? = null,
    private val instanceToken: Long = cp.player.core.util.currentTimeMillis(),
) : Screen {
    override val key: ScreenKey =
        "HomeGeneratedPlaylist_${playlist.id}_${kind}_${seedTrackId}_${instanceToken}"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { PlaylistDetailScreenModel() }
        val sourceTracks by rememberUpdatedState(initialTracks)
        var autoPlayConsumed by rememberSaveable(key) { mutableStateOf(false) }
        val effectiveStartIndex = if (!autoPlayConsumed) startIndex else null
        LaunchedEffect(kind, playlist.id, seedTrackId, seedPlaylistId, sourceTracks.firstOrNull()?.id) {
            when (kind) {
                HomeGeneratedPlaylistKind.Static -> Unit
                HomeGeneratedPlaylistKind.IntelligenceFromDaily -> {
                    val seed = seedTrackId ?: sourceTracks.firstOrNull()?.id
                    if (seed == null) {
                        // 没有种子就没有请求可发：必须退出加载态并说明原因，
                        // 否则页面永远停在转圈（loadingOverride 只在首次 loadLocal 生效）。
                        cp.player.app.ui.util.UiEvents.notify(
                            "心动模式暂无推荐：需要有红心歌曲（登录并收藏过歌曲）"
                        )
                        model.loadLocal(playlist, emptyList(), loading = false, force = true)
                        return@LaunchedEffect
                    }
                    val result = try {
                        AppModel.musicRepository.getIntelligenceSongs(seed, seedPlaylistId ?: 0L)
                    } catch (e: Exception) {
                        BackendResult.Error(e.message ?: "获取心动歌曲失败", cause = e)
                    }
                    val tracks = (result as? BackendResult.Success)?.data.orEmpty()
                    if (tracks.isEmpty()) {
                        // 空结果必须给出原因：恒空会显得像按钮坏了，而真实原因
                        // 可能是音源不支持 / 上游报错 / 账号还没有红心歌曲。
                        cp.player.app.ui.util.UiEvents.notify(
                            when (result) {
                                is BackendResult.Unsupported -> "当前音源不支持心动模式"
                                is BackendResult.Error -> result.message
                                else -> "心动模式暂无推荐：需要有红心歌曲（登录并收藏过歌曲）"
                            }
                        )
                    }
                    // 拉完**原地**换数据，不再 navigator.replace 到新实例：
                    // Voyager 默认 Screen.key 是「类名」（getUniqueScreenKey，javap 核实），
                    // 与实例无关 ⇒ replace 出的新实例 key 相同，会**复用同一个**
                    // PlaylistDetailScreenModel；loadLocal 的 loadedPlaylistId 守卫
                    // 直接拦下更新，state 停在 tracks=[] + loading=true ⇒ 永久转圈。
                    model.loadLocal(playlist, tracks, loading = false, force = true)
                }
            }
        }
        PlaylistDetailContent(
            playlist = playlist,
            model = model,
            embedded = false,
            onEmbeddedBack = null,
            initialOverrideTracks = initialTracks,
            autoPlayIndex = effectiveStartIndex,
            isLocalPlaylist = true,
            // 心动模式要先按种子拉取再填充，拉取期间保持加载态，
            // 否则会先闪一屏「歌单暂无歌曲」。
            loadingOverride = kind != HomeGeneratedPlaylistKind.Static,
        )
    }
}
