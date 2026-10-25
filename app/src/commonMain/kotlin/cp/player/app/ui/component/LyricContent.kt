package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.mocharealm.accompanist.lyrics.ui.composable.list.rememberLyricsLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalJapaneseFontFamily
import cp.player.app.ui.theme.containsKana
import cp.player.app.ui.theme.withJapaneseTypography
import cp.player.core.lyrics.RubySegment
import cp.player.core.lyrics.RubyText
import cp.player.core.playback.LyricsState
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.SyncedLyricLine
import kotlinx.coroutines.delay

/**
 * 注音（振り仮名）相对正文的字号比例。
 *
 * 0.25 = 正文的 1/4。日文排版里注音常见 1/2，但这里的正文已经放大到 28sp、
 * 且库把注音画在音节正上方（不占行高），1/4 才不会盖住上一行。
 */
private const val RubyFontRatio = 0.25f

/**
 * 歌词显示组件（KMP 版使用官方 accompanist-lyrics-ui 移植）。
 *
 * @param immersiveEnabled 兼容保留参数（已停用自动放大）。
 * @param onImmersiveChange 沉浸态变化回调。
 */
@Composable
fun LyricContent(
    state: PlaybackUiState,
    showTranslation: Boolean = true,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 60.dp, horizontal = 8.dp),
    /** 无歌词时的换源入口（跳「歌词来源」页）。null = 不显示入口。 */
    onChangeSource: (() -> Unit)? = null,
    immersiveEnabled: Boolean = false,
    onImmersiveChange: ((Boolean) -> Unit)? = null,
) {
    val lyricState = state.lyrics
    val lines = (lyricState as? LyricsState.Success)?.lines.orEmpty()

    if (lines.isEmpty()) {
        val s = cpStrings()
        val label = when (lyricState) {
            LyricsState.Loading -> s.player.lyricsLoading
            LyricsState.NoLyrics -> s.player.lyricsEmpty
            is LyricsState.Error -> s.player.lyricsError(lyricState.message)
            else -> s.player.lyricsIdle
        }
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // 「没词」是用户最想换源的时刻：把换源入口放在这里，而不是让他回设置里翻。
                if (lyricState is LyricsState.NoLyrics && onChangeSource != null) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onChangeSource) {
                        Text(s.lyricsPlugin.changeSourceAction)
                    }
                }
            }
        }
        return
    }

    // 整首歌词是不是日语：含假名即算（见 `JapaneseText.kt`）。决定用哪套字体与断行规则。
    val japaneseFamily = LocalJapaneseFontFamily.current
    val japaneseLyrics = remember(lines) { lines.any { it.text.containsKana() } }
    // 用户是否开启日文注音假名开关，以及歌词文本本身是否含有注音标记
    val showRubySetting by cp.player.app.AppModel.showRubyLyricsFlow.collectAsState()
    val wordSegmentation by cp.player.app.AppModel.lyricsWordSegmentationFlow.collectAsState()
    val rawHasRuby = remember(lines) { lines.any { RubyText.hasRuby(it.text) } }
    val hasRuby = rawHasRuby && showRubySetting

    val syncedLyrics = remember(lines, hasRuby, rawHasRuby) {
        val iLines: List<ISyncedLine> = lines.map { l ->
            val st = l.time.toInt()
            val en = (l.endTime ?: (l.time + 5000)).toInt()
            val segments = if (hasRuby) RubyText.parse(l.text) else emptyList()
            val ruby = segments.any { it.reading != null }
            when {
                // ① 有注音且开关开启：逐段切成音节，读音挂在音节上（库把它画在音节正上方）。
                ruby -> KaraokeLine.MainKaraokeLine(
                    syllables = rubySyllables(segments, st, en, l.words),
                    translation = l.translation ?: "",
                    alignment = KaraokeAlignment.Unspecified,
                    start = st,
                    end = en,
                    phonetic = "",
                    accompanimentLines = emptyList()
                )
                // ② 有逐字时间：沿用原来的逐字渲染（不显示罗马音）。若关闭了注音开关，剥离文本中标记。
                l.words.isNotEmpty() -> KaraokeLine.MainKaraokeLine(
                    syllables = formatSyllablesWithSpacing(l.words, l.text, rawHasRuby),
                    translation = l.translation ?: "",
                    alignment = KaraokeAlignment.Unspecified,
                    start = st,
                    end = en,
                    phonetic = "", // 不显示罗马音
                    accompanimentLines = emptyList()
                )
                // ③ 普通行：若关闭注音开关但歌词含注音标记，剥离标记显示纯正文。
                else -> SyncedLine(
                    content = if (rawHasRuby) RubyText.strip(l.text) else l.text,
                    translation = l.translation ?: "",
                    start = st,
                    end = en
                )
            }
        }
        SyncedLyrics(lines = iLines)
    }

    val listState = rememberLyricsLazyListState()

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
    val normalStyle = remember(currentTextStyle, japaneseLyrics, japaneseFamily) {
        currentTextStyle.copy(
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp,
            textMotion = TextMotion.Animated,
        ).let {
            if (japaneseLyrics) {
                if (wordSegmentation) it.withJapaneseTypography(japaneseFamily)
                else if (japaneseFamily != null) it.copy(fontFamily = japaneseFamily)
                else it
            } else it
        }
    }

    val accompanimentStyle = remember(currentTextStyle, japaneseLyrics, japaneseFamily, wordSegmentation) {
        currentTextStyle.copy(
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textMotion = TextMotion.Animated,
        ).let {
            if (japaneseLyrics) {
                if (wordSegmentation) it.withJapaneseTypography(japaneseFamily)
                else if (japaneseFamily != null) it.copy(fontFamily = japaneseFamily)
                else it
            } else it
        }
    }

    // 注音（振り仮名）的字号：正文的 **1/4**，位置由库画在音节上方。
    // 没有注音时这一档仍是「行级罗马音」的字号（正文的一半），两者不要混用同一个值。
    val basePhoneticStyle = MaterialTheme.typography.bodyMedium
    val phoneticTextStyle = remember(basePhoneticStyle, hasRuby, japaneseLyrics, japaneseFamily, wordSegmentation) {
        val size = if (hasRuby) RubyFontRatio else 0.5f
        basePhoneticStyle.copy(
            fontSize = (28 * size).sp,
            lineHeight = (28 * if (hasRuby) 0.32f else 0.7f).sp,
        ).let {
            if (japaneseLyrics) {
                if (wordSegmentation) it.withJapaneseTypography(japaneseFamily)
                else if (japaneseFamily != null) it.copy(fontFamily = japaneseFamily)
                else it
            } else it
        }
    }

    val currentTrackId = state.currentTrack?.id
    val lyricOffsetMs = if (!currentTrackId.isNullOrBlank()) {
        cp.player.app.ui.util.LyricOffsetManager.getOffset(currentTrackId)
    } else 0L
    var showOffsetDialog by remember { mutableStateOf(false) }

    // 读 [smoothPosition] 而不是直接读 positionSource：只有读 State 才能在绘制阶段
    // 登记观察，让库每帧重绘。同时叠加当前歌曲的歌词偏移量。见 [smoothPosition] 的注释。
    val currentPositionProvider = remember(smoothPosition, lyricOffsetMs) {
        { (smoothPosition.longValue + lyricOffsetMs).toInt() }
    }

    Box(modifier.fillMaxSize()) {
        KaraokeLyricsView(
            listState = listState,
            lyrics = syncedLyrics,
            currentPosition = currentPositionProvider,
            onLineClicked = { line -> onSeek((line.start.toLong() - lyricOffsetMs).coerceAtLeast(0L)) },
            onLinePressed = { },
            normalLineTextStyle = normalStyle,
            accompanimentLineTextStyle = accompanimentStyle,
            phoneticTextStyle = phoneticTextStyle,
            textColor = MaterialTheme.colorScheme.onSurface,
            showTranslation = showTranslation,
            showPhonetic = hasRuby,
            useBlurEffect = false,
            modifier = Modifier.fillMaxSize()
        )

        // 若当前曲目设置了歌词偏移，在右上角提供轻量提示胶囊
        if (lyricOffsetMs != 0L) {
            androidx.compose.material3.Surface(
                onClick = { showOffsetDialog = true },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    val sign = if (lyricOffsetMs > 0) "+" else ""
                    Text(
                        text = "偏移 $sign${lyricOffsetMs / 1000f}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val curTrack = state.currentTrack
    if (showOffsetDialog && curTrack != null) {
        LyricOffsetDialog(
            track = curTrack,
            onDismiss = { showOffsetDialog = false },
        )
    }
}

/**
 * 为逐字音节构建 [KaraokeSyllable] 列表，并确保英文/拉丁词间空格不丢失。
 *
 * AMLL / Apple Music TTML 等格式中，词间空格可能作为单独的 XML 节点或者保留在整行文本中。
 * 若 syllables 之间缺少空格，渲染器会直接将单词粘连（如 "See you again" 变成 "Seeyouagain"）。
 * 此处根据词自身尾随空格、整行文本 [lineText] 的空格分布以及词界进行自动对齐与补偿。
 */
private fun formatSyllablesWithSpacing(
    words: List<SyncedLyricLine.SyncedWord>,
    lineText: String,
    rawHasRuby: Boolean,
): List<KaraokeSyllable> {
    if (words.isEmpty()) return emptyList()
    val fullLine = lineText.trim()
    var searchCursor = 0

    return words.mapIndexedNotNull { index, w ->
        val wst = w.beginTime.toInt()
        val wen = w.endTime.toInt().coerceAtLeast(wst)
        if (w.text.isBlank()) return@mapIndexedNotNull null
        var text = if (rawHasRuby) RubyText.strip(w.text) else w.text

        // 若不是行尾最后一词，且当前词末尾尚未携带空格：
        if (index < words.lastIndex && !text.endsWith(" ")) {
            val trimmed = text.trim()
            val pos = fullLine.indexOf(trimmed, searchCursor)
            if (pos >= 0) {
                val afterPos = pos + trimmed.length
                searchCursor = afterPos
                if (afterPos < fullLine.length && fullLine[afterPos] == ' ') {
                    text = "$text "
                }
            } else {
                // 词末与下一词首若均为字母/数字（如英文词），自动补偿词间空格
                val nextText = words[index + 1].text.trimStart()
                val lastChar = text.lastOrNull()
                val nextFirstChar = nextText.firstOrNull()
                if (lastChar != null && nextFirstChar != null &&
                    ((lastChar in 'a'..'z' || lastChar in 'A'..'Z' || lastChar in '0'..'9') &&
                     (nextFirstChar in 'a'..'z' || nextFirstChar in 'A'..'Z' || nextFirstChar in '0'..'9'))
                ) {
                    text = "$text "
                }
            }
        }

        KaraokeSyllable(
            content = text,
            start = wst,
            end = wen,
            phonetic = "",
        )
    }
}

/**
 * 把注音分段切成逐字音节，并给每段算起止时间。
 *
 * ## 为什么注音行要走「逐音节」而不是行级文本
 *
 * 库把 `KaraokeSyllable.phonetic` 画在**该音节正上方**（见 `SyllableLayout.phoneticLayoutResult`
 * 的绘制偏移），这正是假名注音要的位置；行级 `phonetic` 则画在整行下方，是罗马音的槽位。
 * 所以注音必须落到音节上。
 *
 * ## 时间怎么来
 *
 * - 词级时间戳**可用**时（`words` 拼起来与去掉标记后的正文完全一致）按词内线性插值 ——
 *   逐字高亮与注音的位置都对得上；
 * - 否则按**字数均分**整行时长。注音歌词多半是普通 LRC（没有逐字时间），
 *   这时均分已经是最合理的近似，而且比「整行同一时刻亮起」更像逐字。
 *
 * ⚠️ 不能直接拿 `words` 当分段：注音标记会占字符位，词边界与去标记后的正文对不上，
 * 硬拼会错位。对不上就整行均分，绝不「大概对齐」。
 */
private fun rubySyllables(
    segments: List<RubySegment>,
    lineStart: Int,
    lineEnd: Int,
    words: List<SyncedLyricLine.SyncedWord>,
): List<KaraokeSyllable> {
    val visible = segments.filter { it.base.isNotEmpty() }
    val total = visible.sumOf { it.base.length }
    if (total == 0) return emptyList()
    val span = (lineEnd - lineStart).coerceAtLeast(1)

    val useWords = words.isNotEmpty() &&
        words.joinToString("") { it.text } == visible.joinToString("") { it.base }

    fun timeAt(offset: Int): Int {
        if (!useWords) return lineStart + span * offset / total
        var acc = 0
        for (w in words) {
            val len = w.text.length
            if (len > 0 && offset < acc + len) {
                val frac = (offset - acc).toFloat() / len
                return (w.beginTime + (w.endTime - w.beginTime) * frac).toInt()
            }
            acc += len
        }
        return lineEnd
    }

    var acc = 0
    return visible.map { seg ->
        val start = timeAt(acc)
        acc += seg.base.length
        val end = timeAt(acc).coerceAtLeast(start)
        KaraokeSyllable(
            content = seg.base,
            start = start,
            end = end,
            phonetic = seg.reading ?: "",
        )
    }
}

/**
 * 把「每 200 ms 才刷新一次的权威位置」外推成「每次读取都前进的平滑位置」。
 *
 * 这是一个**纯计算**的位置源：不持有 Compose 状态、不触发重组。库每帧调用
 * [currentMs] 时，它用「最新权威采样值 + 自该采样起经过的墙钟时间」现算。
 *
 * ## 为什么重锚点不能「硬跳」（本次修复的核心）
 *
 * 旧实现每次收到新采样就把锚点硬重置到采样值。但采样值是引擎在**轮询时刻**测的，
 * 锚点却要到「跨线程分发 → UI 重组」之后（晚 4~20 ms 且抖动）才生效——
 * 新锚点通常落后于已外推的值几~十几毫秒。[currentMs] 的单调不减约束把这回拉
 * 变成**停顿**：逐帧重绘没问题，但位置值每 200 ms 顿一下，逐字高亮「一格一格」。
 *
 * 现在的接法（音视频时钟同步的标准做法——**误差渐消**）：
 * - 锚点照常更新，但把「旧时间线此刻应有的值 − 新锚点」记为补偿量叠加在输出上，
 *   按指数衰减（时间常数 [DecayTauMs]）在 ~1 s 内归零。输出曲线**连续**
 *   （值与速率都连续，锚点生效的那一帧输出与不更新锚点时一致），
 *   代价只是速率在周期内波动 < ±10 %，肉眼无感；
 * - 偏差超过 [HardJumpThresholdMs]（seek / 换曲 / 长卡顿）仍立即跟随，
 *   恢复播放、首次采样同样立即跟随。
 *
 * ## 其余设计要点
 *
 * - **暂停即停**：`isPlaying = false` 时不外推，位置钉在权威值上。
 * - **单调不减**：外推值永不小于已返回过的值；权威值回退（seek）才允许跟随下降。
 * - 外推上限 [MaxExtrapolationMs]：引擎卡顿迟迟没有新采样时宁可短暂停住，
 *   也不让歌词跑在声音前面太多。
 * - 时间基准用单调钟（[nowNanos]，默认 [System.nanoTime]），不受系统时钟调整影响；
 *   可注入假时钟以便测试。
 *
 * 为什么**外推本身**必须是纯计算（而不是「一边外推一边把结果写回自己」）：
 * 1. 若让 Composable 直接读外推状态，整个歌词子树会**每帧重组**；而歌词本来只需要重绘。
 *    （所以 `LyricContent` 里是把外推结果**发布**到一个 State，且**只**在 provider 的
 *    lambda 里读它 —— 那是在绘制阶段执行，于是每帧只重绘、不重组。）
 * 2. 外推是**单向**的：只由 [update] 收权威值、只由 [currentMs] 出外推值，
 *    两者不互写，就没有「轮询边界上位置倒退再前进」的竞争。
 */
internal class SmoothPositionSource(
    /** 单调时钟读数（纳秒）。测试注入假时钟；生产用 [System.nanoTime]。 */
    private val nowNanos: () -> Long = System::nanoTime,
) {

    /** 最近一次权威采样值（毫秒）。 */
    private var authoritativeMs: Long = 0L

    /** 采到 [authoritativeMs] 时的单调时钟读数（纳秒）；0 表示尚未采过。 */
    private var sampledAtNanos: Long = 0L

    /** 是否处于播放态 —— 只有播放态才外推。 */
    private var playing: Boolean = false

    /** 已对外返回过的最大值，用于保证单调不减。 */
    private var lastReturnedMs: Long = Long.MIN_VALUE

    /**
     * 渐消补偿（毫秒，Double 以免每帧取整产生台阶）：叠加在「锚点外推值」上，
     * 按指数衰减到 0。收敛性：每个采样周期（~200 ms）误差衰减 e^(-200/300) ≈ 0.51，
     * 稳态误差 ≈ 2× 单周期链路抖动（~±10 ms），不发散。
     */
    private var correctionMs: Double = 0.0

    /**
     * 收录一次权威采样。由 Composable 在每次收到新 state 时调用。
     *
     * 同一个值重复调用（重组但位置没变）不会重置时间基准 —— 否则外推会被
     * 频繁的重组不断「归零」，进度条就永远走不动。
     */
    fun update(positionMs: Long, isPlaying: Boolean) {
        val positionChanged = positionMs != authoritativeMs
        val resuming = isPlaying && !playing
        if (sampledAtNanos != 0L && !positionChanged && !resuming && playing == isPlaying) return

        // 与「当前应输出的值」比较（含补偿），才是与输出连续性一致的语义。
        val bigJump = sampledAtNanos != 0L && positionChanged &&
            kotlin.math.abs(positionMs - rawExtrapolatedMs()) > HardJumpThresholdMs

        if (resuming || bigJump || sampledAtNanos == 0L) {
            // 恢复播放 / 大偏差（seek、换曲、长卡顿）/ 首次采样：立即跟随。
            correctionMs = 0.0
            authoritativeMs = positionMs
            sampledAtNanos = nowNanos()
            // 权威值回退（seek / 换曲）时放弃单调约束，允许跟随下降。
            if (positionMs < lastReturnedMs) lastReturnedMs = Long.MIN_VALUE
        } else if (positionChanged) {
            if (playing) {
                // 播放中的常规小步进：让输出**连续**地滑向新锚点。
                // 补偿 = 「旧时间线此刻应有的值」− 新锚点（含自上帧起的推进量）。
                // ⚠️ 不能用上一帧的输出当基准：update 与下一次读取几乎同刻发生
                // （elapsed≈0），那会让输出恰好停在上一帧的值上 —— 每 200ms 停一帧。
                // 用 rawExtrapolatedMs() 后，本帧（elapsed≈0）的输出与不更新锚点时
                // 完全一致，值与速率都连续，误差交给指数衰减去消。
                // clamp 只是防御；正常误差 ≤ 阈值才走到这里。
                correctionMs = (rawExtrapolatedMs() - positionMs)
                    .coerceIn(-HardJumpThresholdMs, HardJumpThresholdMs)
            }
            authoritativeMs = positionMs
            sampledAtNanos = nowNanos()
        }
        playing = isPlaying
    }

    /** 当前平滑位置（毫秒）。库每帧调用。 */
    fun currentMs(): Long {
        val smooth = rawExtrapolatedMs().roundToLong().coerceAtLeast(lastReturnedMs)
        lastReturnedMs = smooth
        return smooth
    }

    /**
     * 当前外推值（未取整、未做单调钳制）：
     * 暂停/未采样时钉在「max(权威值, 已返回值)」上；播放中为
     * `权威值 + 经过时间(钳到 [MaxExtrapolationMs]) + 渐消补偿`。
     */
    private fun rawExtrapolatedMs(): Double {
        if (!playing || sampledAtNanos == 0L) {
            return maxOf(authoritativeMs, lastReturnedMs).toDouble()
        }
        val elapsedMs = (nowNanos() - sampledAtNanos) / 1_000_000.0
        val clamped = elapsedMs.coerceIn(0.0, MaxExtrapolationMs.toDouble())
        val correction = correctionMs * kotlin.math.exp(-clamped / DecayTauMs)
        return authoritativeMs + clamped + correction
    }

    private companion object {
        /** 外推上限：200ms 轮询周期 + 100ms 余量。 */
        const val MaxExtrapolationMs = 300L

        /** 超过该偏差视为 seek / 换曲级跳变，立即跟随、不做渐消。 */
        const val HardJumpThresholdMs = 250.0

        /** 渐消时间常数：每 200ms 采样周期误差衰减约一半。 */
        const val DecayTauMs = 300.0
    }
}
