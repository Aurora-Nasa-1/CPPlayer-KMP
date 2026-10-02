package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import cp.player.core.playback.LyricsState
import cp.player.core.playback.PlaybackUiState

/**
 * 歌词显示组件（KMP 版使用官方 accompanist-lyrics-ui 移植）。
 */
@Composable
fun LyricContent(
    state: PlaybackUiState,
    showTranslation: Boolean = true,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 60.dp, horizontal = 8.dp),
) {
    val lyricState = state.lyrics
    val lines = (lyricState as? LyricsState.Success)?.lines.orEmpty()

    if (lines.isEmpty()) {
        val label = when (lyricState) {
            LyricsState.Loading -> "歌词加载中…"
            LyricsState.NoLyrics -> "暂无歌词"
            is LyricsState.Error -> "歌词获取失败：${lyricState.message}"
            else -> "等待曲目开始播放后展示歌词"
        }
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val syncedLyrics = remember(lines) {
        val iLines: List<ISyncedLine> = lines.map { l ->
            val st = l.time.toInt()
            val en = (l.endTime ?: (l.time + 5000)).toInt()
            if (l.words.isNotEmpty()) {
                KaraokeLine.MainKaraokeLine(
                    syllables = l.words.mapNotNull { w ->
                        val st = w.beginTime.toInt()
                        val en = w.endTime.toInt().coerceAtLeast(st)
                        if (w.text.isBlank()) return@mapNotNull null
                        com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable(
                            content = w.text,
                            start = st,
                            end = en,
                            phonetic = ""
                        )
                    },
                    translation = l.translation ?: "",
                    alignment = com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment.Unspecified,
                    start = st,
                    end = en,
                    phonetic = l.romanization ?: "",
                    accompanimentLines = emptyList()
                )
            } else {
                SyncedLine(
                    content = l.text,
                    translation = l.translation ?: "",
                    start = st,
                    end = en
                )
            }
        }
        SyncedLyrics(lines = iLines)
    }

    val listState = rememberLazyListState()

    // ⚠️ 逐字（Karaoke）动画的帧率**完全由这个位置源决定**。这里有两件事要同时做对，
    // 缺一件就只剩 5 Hz：
    //
    // ① **位置要平滑**：见 [SmoothPositionSource]。
    // ② **位置要被 Compose 观察到**：见下方 [smoothPosition] 与那段 ticker。
    //
    // 库的 `KaraokeLyricsView` 把 `currentPosition` 当**每帧回调**用：逐字渐变、
    // 行滚动弹簧、呼吸点全部在 `drawLyricsLine` 里按该值算进度。它本来是为
    // 「音频帧级位置」设计的（上游 Android 版本直接喂 ExoPlayer 的毫秒位置）。
    val positionSource = remember { SmoothPositionSource() }
    positionSource.update(state.positionMs, state.isPlaying)

    // 「可被 Compose 观察到的」平滑位置。
    //
    // ⚠️ **为什么必须有一个 State，光有 ① 不够**：库自己**不会主动请求重绘**。
    // Compose 的重绘条件是「上一次绘制里读过的 State 变了」—— provider 是个普通
    // lambda，若它读的是纯字段（[SmoothPositionSource] 就是），绘制阶段就**没有任何
    // State 被登记观察**，Compose 判定这个节点永远不必重画。
    // 于是歌词只在 `state.positionMs` 变化（200 ms 一次，**5 Hz**）时重绘一次，
    // 逐字高亮就「一格一格跳」。这是本问题**真正的根因**。
    //
    // 上游 Android 版本正常，是因为它的 provider 里读的是 ExoPlayer 推进的
    // `State<Long>` —— 绘制阶段读 State 会登记观察者，位置一变即重绘。
    val smoothPosition = remember { mutableLongStateOf(state.positionMs) }

    // 播放中逐帧把外推位置写进 [smoothPosition]。
    //
    // ⚠️ 写成「每帧写 State」而不是「每帧重组」：这个 State **只在 provider 的 lambda
    // 里被读**，而 lambda 在**绘制阶段**执行 ⇒ 每帧只触发**重绘**，不触发重组。
    // 反过来若让 Composable 自己读它，就是每帧重组整棵歌词子树（含文本测量），
    // 代价高一个数量级。
    //
    // 用 `withFrameNanos`（逐帧时钟）而不是 `delay(16)`：后者与显示刷新率不同步，
    // 会稳定丢 / 叠帧，反而更抖。
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) {
            // 暂停：立刻把位置钉到权威值上，不要停在最后一次外推的结果。
            smoothPosition.longValue = positionSource.currentMs()
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { smoothPosition.longValue = positionSource.currentMs() }
        }
    }

    val currentTextStyle = MaterialTheme.typography.headlineMedium
    val normalStyle = remember(currentTextStyle) {
        currentTextStyle.copy(
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp,
            textMotion = TextMotion.Animated,
        )
    }

    val accompanimentStyle = remember(currentTextStyle) {
        currentTextStyle.copy(
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textMotion = TextMotion.Animated,
        )
    }

    val phoneticTextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    // 读 [smoothPosition] 而不是直接读 positionSource：只有读 State 才能在绘制阶段
    // 登记观察，让库每帧重绘。见 [smoothPosition] 的注释。
    val currentPositionProvider = remember {
        { smoothPosition.longValue.toInt() }
    }

    KaraokeLyricsView(
        listState = listState,
        lyrics = syncedLyrics,
        currentPosition = currentPositionProvider,
        onLineClicked = { line -> onSeek(line.start.toLong()) },
        onLinePressed = { },
        normalLineTextStyle = normalStyle,
        accompanimentLineTextStyle = accompanimentStyle,
        phoneticTextStyle = phoneticTextStyle,
        textColor = MaterialTheme.colorScheme.onSurface,
        showTranslation = showTranslation,
        showPhonetic = true,
        useBlurEffect = false,
        modifier = modifier.fillMaxSize()
    )
}

/**
 * 把「每 200 ms 才刷新一次的权威位置」外推成「每次读取都前进的平滑位置」。
 *
 * 这是一个**纯计算**的位置源：不持有 Compose 状态、不触发重组。库每帧调用
 * [currentMs] 时，它用「最新权威采样值 + 自该采样起经过的墙钟时间」现算。
 *
 * 为什么**外推本身**必须是纯计算（而不是「一边外推一边把结果写回自己」）：
 * 1. 若让 Composable 直接读外推状态，整个歌词子树会**每帧重组**；而歌词本来只需要重绘。
 *    （所以 `LyricContent` 里是把外推结果**发布**到一个 State，且**只**在 provider 的
 *    lambda 里读它 —— 那是在绘制阶段执行，于是每帧只重绘、不重组。）
 * 2. 若外推值与「权威值定时回写」**竞争同一个状态**，轮询边界上会先写权威值、下一帧
 *    又从新锚点外推，等于每 200 ms 位置倒退一次再前进 —— 观感是周期性顿挫，
 *    比完全不外推还差。这里外推是**单向**的：只由 [update] 收权威值、只由 [currentMs]
 *    出外推值，两者不互写，就没有这个竞争。
 *
 * 设计要点：
 * - **单调不减**：外推值永远不小于已返回过的值，杜绝任何向后的抖动。权威值若回退
 *   （seek / 换曲）才允许跟随下降，并立即重新锚定。
 * - **暂停即停**：`isPlaying = false` 时不外推，位置钉在权威值上。
 * - 时间基准用 [System.nanoTime]（单调钟），不受系统时钟调整影响。
 */
internal class SmoothPositionSource {

    /** 最近一次权威采样值（毫秒）。 */
    private var authoritativeMs: Long = 0L

    /** 采到 [authoritativeMs] 时的单调时钟读数（纳秒）；0 表示尚未采过。 */
    private var sampledAtNanos: Long = 0L

    /** 是否处于播放态 —— 只有播放态才外推。 */
    private var playing: Boolean = false

    /** 已对外返回过的最大值，用于保证单调不减。 */
    private var lastReturnedMs: Long = Long.MIN_VALUE

    /**
     * 收录一次权威采样。由 Composable 在每次收到新 state 时调用。
     *
     * 同一个值重复调用（重组但位置没变）不会重置时间基准 —— 否则外推会被
     * 频繁的重组不断「归零」，进度条就永远走不动。
     */
    fun update(positionMs: Long, isPlaying: Boolean) {
        val changed = positionMs != authoritativeMs || !playing && isPlaying
        authoritativeMs = positionMs
        playing = isPlaying
        if (changed || sampledAtNanos == 0L) {
            sampledAtNanos = System.nanoTime()
            // 权威值回退（seek / 换曲）时放弃单调约束，允许跟随下降。
            if (positionMs < lastReturnedMs) lastReturnedMs = Long.MIN_VALUE
        }
    }

    /** 当前平滑位置（毫秒）。库每帧调用。 */
    fun currentMs(): Long {
        val base =
            if (!playing || sampledAtNanos == 0L) {
                authoritativeMs
            } else {
                val elapsedMs = (System.nanoTime() - sampledAtNanos) / 1_000_000L
                // 外推上限：一个轮询周期（200ms）+ 余量。若引擎卡顿导致迟迟没有新采样，
                // 外推值会越跑越远；钳住它，宁可短暂停住也不让歌词跑在声音前面太多。
                authoritativeMs + elapsedMs.coerceIn(0L, MaxExtrapolationMs)
            }
        val monotonic = base.coerceAtLeast(lastReturnedMs)
        lastReturnedMs = monotonic
        return monotonic
    }

    private companion object {
        /** 外推上限：200ms 轮询周期 + 100ms 余量。 */
        const val MaxExtrapolationMs = 300L
    }
}