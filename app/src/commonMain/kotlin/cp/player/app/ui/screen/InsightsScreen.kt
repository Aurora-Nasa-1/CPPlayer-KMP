package cp.player.app.ui.screen

import cp.player.app.ui.component.CpText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.recent.isRecentTrackPlayable
import cp.player.app.recent.recentPlaybackPlan
import cp.player.app.recent.recentPlayableTracks
import cp.player.app.recent.recentTrackProviderId
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpConnectedChoice
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpBreakpoints
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.ListeningCalendar
import cp.player.app.ui.component.ListeningCalendarLegend
import cp.player.app.ui.component.MonthListeningCalendar
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.formatDurationCompact
import cp.player.app.ui.component.formatDurationShort
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.util.popOrNotify
import cp.player.app.platform.shareText
import cp.player.core.insights.Insights
import cp.player.core.insights.RankItem
import cp.player.core.util.localDateTimeOf
import kotlinx.coroutines.launch

/**
 * 听歌习惯页（「听歌报告」）。
 *
 * ### 为什么它把「我的」与「最近播放」并进来
 * 三块内容本来分散在三个入口：「我的」的聆听统计只是一行死数字、「最近播放」是独立整页。
 * 而它们回答的其实是同一类问题（我听多久 / 我怎么听 / 我听了什么），
 * 拆成三处会让用户为了「看一眼总时长」先进设置、再回退、再进另一个列表。
 * 现在收敛成一页三 tab：**概览 / 习惯 / 最近**。
 *
 * ### 为什么不用新 tab 代替「我的」
 * `MAIN_TABS` 的三个 Screen 是会话级单例，动 tab 列表要同步牵动 `MainScreen` 标题栏、
 * `App.kt` Esc 兜底、`MainScreen` 消费这三处返回链判据。习惯页是「低频深看」，
 * 走 push 进内容层栈更合适，也让入口保持两个（仪表盘的「聆听统计」与「最近播放」两张卡）。
 */
class InsightsScreen(private val initialTab: Int = TAB_OVERVIEW) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        InsightsContent(
            initialTab = initialTab,
            onBack = { navigator.popOrNotify() },
        )
    }

    companion object {
        const val TAB_OVERVIEW = 0
        const val TAB_HABITS = 1
        const val TAB_RECENT = 2
    }
}

/**
 * tab 标签做成函数而不是顶层 `val`：顶层属性在**类加载时**求值，那一刻还没有语言状态
 * ⇒ 标签会永远停在一种语言上（编译不报错、出图也看不出，见 I18N.md §5.9）。
 */
private fun tabLabels(s: CpStrings) = listOf(
    s.insights.tabOverview,
    s.insights.tabHabits,
    s.insights.tabRecent,
)

@Composable
private fun InsightsContent(initialTab: Int, onBack: () -> Unit) {
    val navigator = LocalNavigator.currentOrThrow
    val s = cpStrings()
    val summary by AppModel.insightsSummaryFlow.collectAsState()
    val daily by AppModel.dailyInsightsFlow.collectAsState()
    val records by AppModel.listeningRecordsFlow.collectAsState()
    val writeError by AppModel.insightsWriteErrorFlow.collectAsState()
    val green by AppModel.heatmapGreenFlow.collectAsState()
    val scrobbleState by AppModel.scrobbleHub.state.collectAsState()
    // 「最近」tab 的状态在这里收集，而不是在 `recentTab` 内部 —— 后者是
    // `LazyListScope` 扩展（非 @Composable），在里面读 collectAsState 编译不过。
    val recentTracks by AppModel.recentTracksFlow.collectAsState()
    val recentScope = rememberCoroutineScope()
    val activeProvider by AppModel.activeProviderFlow.collectAsState()
    val recentProvider = activeProvider?.id ?: AppModel.activeProviderId()
    val providerNames = remember(activeProvider) {
        runCatching { AppModel.availableProviders().associate { it.id to it.name } }
            .getOrDefault(emptyMap())
    }
    val recentPlayable = remember(recentTracks, recentProvider) {
        recentPlayableTracks(recentTracks, recentProvider)
    }
    val recentHasForeign = remember(recentTracks, recentProvider) {
        recentTracks.any { !isRecentTrackPlayable(it, recentProvider) }
    }
    val recentSourceLabelOf: (cp.player.core.music.TrackSummary) -> String = { track ->
        val sourceId = recentTrackProviderId(track, recentProvider)
        if (sourceId == "local") {
            s.insights.recentLocalSource
        } else {
            providerNames[sourceId]?.takeIf { it.isNotBlank() } ?: sourceId
        }
    }
    // 与独立整页同一套口径（HomeScreen 里是 maxWidth >= 900.dp）。
    // 列数在 `recentTab` 里没法自己算（LazyListScope 扩展不是 @Composable），从外面传。
    val recentColumns = if (LocalIsExpanded.current) 2 else 1
    val recentToMediaId: (String) -> String = { id ->
        cp.player.core.music.CPMediaId.toCanonicalMediaId(id, providerId = recentProvider)
    }

    // 「今天」只取一次：日历墙与聚合必须用同一个判据，否则跨零点时
    // 会出现「墙上有今天这一格、但今日时长显示为 0」这种自相矛盾的画面。
    val todayKey = remember { AppModel.todayKey() }
    val scale = remember(daily, todayKey) { Insights.heatScaleOf(daily, todayKey) }

    val labels = tabLabels(s)
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, labels.lastIndex)) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    var recentSelection by remember { mutableStateOf<cp.player.core.music.TrackSummary?>(null) }
    // 「更多」弹层里的二级弹层目标必须由**本层**持有：弹层先回调动作、再自行关闭，
    // 宿主随之卸载，状态放宿主里会被一起丢掉（详见 RecentPlaysOptionsHost 的 KDoc）。
    var recentAddToPlaylist by remember { mutableStateOf<cp.player.core.music.TrackSummary?>(null) }
    var recentAddToLocalPlaylist by remember { mutableStateOf<String?>(null) }
    var recentShowInfo by remember { mutableStateOf<cp.player.core.music.TrackSummary?>(null) }
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val localFavoritePaths by AppModel.localMedia.favorites.paths.collectAsState()
    val confirm = rememberConfirmState()

    CpRouteScaffold(title = s.insights.screenTitle, onBack = onBack) { pageModifier ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyScrollColumn(
                modifier = pageModifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    top = CpSpacing.pageTop,
                    // 宽屏的小播放器浮在底部，末尾留一条安全带。
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            ) {
                item {
                    InsightsTabs(selected = tab, labels = labels, onSelect = { tab = it })
                }
                item { Spacer(Modifier.height(12.dp)) }

                if (writeError != null) {
                    item {
                        StateSurface {
                            ContentState(
                                title = s.insights.writeErrorTitle,
                                // 写入失败的原因原样透出（与诊断页的约定一致，不冒充本地化文案）。
                                message = writeError,
                                error = true,
                            )
                        }
                    }
                }

                when (tab) {
                    InsightsScreen.TAB_OVERVIEW -> overviewTab(
                        strings = s,
                        summary = summary,
                        daily = daily,
                        scale = scale,
                        todayKey = todayKey,
                        green = green,
                        records = records,
                        selectedDay = selectedDay,
                        scrobbleState = scrobbleState,
                        onOpenScrobble = { navigator.push(ScrobbleSettingsScreen()) },
                        onSelectDay = { selectedDay = if (selectedDay == it) null else it },
                        onToggleGreen = { AppModel.setHeatmapGreen(!green) },
                        onClear = {
                            confirm.request(
                                title = s.insights.clearTitle,
                                message = s.insights.clearMessage,
                                confirmLabel = s.insights.clearConfirmLabel,
                                destructive = true,
                                onConfirm = { AppModel.clearListeningRecords() },
                            )
                        },
                    )

                    InsightsScreen.TAB_HABITS -> habitsTab(strings = s, summary = summary)
                    else -> recentTab(
                        strings = s,
                        recentTracks = recentTracks,
                        playableTracks = recentPlayable,
                        hasForeignTracks = recentHasForeign,
                        columns = recentColumns,
                        scope = recentScope,
                        toMediaId = recentToMediaId,
                        sourceLabelOf = recentSourceLabelOf,
                        playableOf = { isRecentTrackPlayable(it, recentProvider) },
                        activeProviderId = recentProvider,
                        onSelectionChange = { recentSelection = it },
                        likedIds = likedIds,
                        localFavoritePaths = localFavoritePaths,
                        onAddToPlaylist = { recentAddToPlaylist = it },
                        onAddToLocalPlaylist = { recentAddToLocalPlaylist = it },
                        onShowInfo = { recentShowInfo = it },
                    )
                }
            }
        }
    }
    CpConfirmHost(confirm)
    RecentPlaysOptionsHost(
        track = recentSelection,
        onDismiss = { recentSelection = null },
        onAddToPlaylist = { recentAddToPlaylist = it },
        onShowInfo = { recentShowInfo = it },
        onAddToLocalPlaylist = { recentAddToLocalPlaylist = it },
    )
    recentAddToPlaylist?.let { track ->
        val targetId = track.originalMediaId ?: track.id
        cp.player.app.ui.component.AddToPlaylistSheet(
            trackIds = listOf(targetId),
            onDismiss = { recentAddToPlaylist = null },
        )
    }
    recentAddToLocalPlaylist?.let { path ->
        cp.player.app.ui.component.AddToLocalPlaylistDialog(
            trackPaths = listOf(path),
            onDismiss = { recentAddToLocalPlaylist = null },
        )
    }
    recentShowInfo?.let { track ->
        cp.player.app.ui.component.SongDetailInfoDialog(
            track = track,
            onDismiss = { recentShowInfo = null },
        )
    }
}

/**
 * 「最近」tab。
 *
 * ⚠️ 这里**只铺行、列表由本页的 `LazyScrollColumn` 承载**，绝不能
 * `item { RecentPlaysScreen(embedded = true).Content() }` 那样嵌一整页 ——
 * `LazyColumn` 的 item 高度无界，内层滚动容器拿到 `maxHeight = Infinity` 会直接抛
 * `IllegalStateException`（离屏探针实测）。行本身复用 [recentPlaysRows]，
 * 与独立整页共用同一份渲染逻辑。
 *
 * 状态（列表 / scope / provider）由 [InsightsContent] 这个 `@Composable` 收集后当参数传进来：
 * `LazyListScope` 的扩展函数不是 `@Composable`，在里面读 `collectAsState` 编译不过。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.recentTab(
    strings: CpStrings,
    recentTracks: List<cp.player.core.music.TrackSummary>,
    playableTracks: List<cp.player.core.music.TrackSummary>,
    hasForeignTracks: Boolean,
    columns: Int,
    scope: kotlinx.coroutines.CoroutineScope,
    toMediaId: (String) -> String,
    sourceLabelOf: (cp.player.core.music.TrackSummary) -> String,
    playableOf: (cp.player.core.music.TrackSummary) -> Boolean,
    activeProviderId: String,
    onSelectionChange: (cp.player.core.music.TrackSummary?) -> Unit,
    likedIds: Set<String>,
    localFavoritePaths: Set<String>,
    onAddToPlaylist: (cp.player.core.music.TrackSummary) -> Unit,
    onAddToLocalPlaylist: (String) -> Unit,
    onShowInfo: (cp.player.core.music.TrackSummary) -> Unit,
) {
    if (recentTracks.isEmpty()) {
        item {
            StateSurface {
                ContentState(
                    title = strings.insights.recentEmptyTitle,
                    message = strings.insights.recentEmptyNote,
                )
            }
        }
        return
    }

    item {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = strings.library.recentPlays,
                supportingText = strings.insights.recentCount(recentTracks.size),
                modifier = Modifier.weight(1f),
            )
            if (playableTracks.isNotEmpty()) {
                TextButton(
                    onClick = {
                        playableTracks.firstOrNull()?.let { CoverFlight.play(it.id, it.coverUrl) }
                        scope.launch {
                            AppModel.playback.playQueue(
                                playableTracks.map { toMediaId(it.id) },
                                startIndex = 0,
                            )
                        }
                    },
                ) {
                    CpText(strings.library.playAll)
                }
            }
        }
    }

    if (hasForeignTracks) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                CpText(
                    strings.insights.recentOtherSourceNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }

    recentPlaysRows(
        tracks = recentTracks,
        columns = columns,
        onPlay = { index ->
            val track = recentTracks[index]
            val plan = recentPlaybackPlan(recentTracks, activeProviderId, track.id)
            if (plan == null) {
                cp.player.app.ui.util.UiEvents.notify(strings.insights.recentOtherSourceBlocked)
            } else {
                CoverFlight.play(track.id, track.coverUrl)
                AppModel.playTrackClicked(toMediaId(track.id)) {
                    AppModel.playback.playQueue(
                        plan.tracks.map { toMediaId(it.id) },
                        startIndex = plan.startIndex,
                    )
                }
            }
        },
        onOptions = onSelectionChange,
        sourceLabelOf = sourceLabelOf,
        playableOf = playableOf,
        contextMenu = { track ->
            val playable = playableOf(track)
            val isLocal = track.id.startsWith("local://")
            val cloudId = track.originalMediaId ?: (if (!isLocal) track.id else null)
            val cloudFavId = cloudId?.let { runCatching { cp.player.core.music.CPMediaId.parse(it).resourceId }.getOrDefault(it) }
            val isCloudFav = cloudFavId != null && cloudFavId in likedIds
            val localPath = if (isLocal) track.id.removePrefix("local://audio/").removePrefix("local://video/") else null
            val isLocalFav = localPath != null && localPath in localFavoritePaths
            val isFav = if (cloudId != null) isCloudFav else isLocalFav

            songContextMenuItems(
                SongMenuActions(
                    onPlay = if (playable) {
                        {
                            CoverFlight.play(track.id, track.coverUrl)
                            scope.launch {
                                val plan = recentPlaybackPlan(recentTracks, activeProviderId, track.id)
                                    ?: return@launch
                                AppModel.playback.playQueue(
                                    plan.tracks.map { toMediaId(it.id) },
                                    startIndex = plan.startIndex,
                                )
                            }
                        }
                    } else null,
                    isFavorite = isFav,
                    onToggleFavorite = if (playable && cloudId != null) {
                        {
                            scope.launch {
                                val target = cloudFavId !in likedIds
                                AppModel.playback.toggleFavoriteFor(cloudId)
                                cp.player.app.ui.util.UiEvents.notify(if (target) "已收藏到云端" else "已取消云端收藏")
                            }
                        }
                    } else if (playable && localPath != null) {
                        {
                            val now = AppModel.localMedia.favorites.toggle(localPath)
                            cp.player.app.ui.util.UiEvents.notify(if (now) "已添加到本地喜欢" else "已从本地喜欢移除")
                        }
                    } else null,
                    favoriteLabel = if (cloudId != null) {
                        if (isCloudFav) "取消云端收藏" else "云端收藏"
                    } else {
                        if (isLocalFav) "取消喜欢" else "喜欢"
                    },
                    onAddToQueue = if (playable) {
                        {
                            scope.launch { AppModel.playback.addToQueue(toMediaId(track.id)) }
                            cp.player.app.ui.util.UiEvents.notify(strings.library.queuedToPlay)
                        }
                    } else null,
                    onPlayNext = if (playable) {
                        {
                            scope.launch { AppModel.playback.addNextToQueue(toMediaId(track.id)) }
                            cp.player.app.ui.util.UiEvents.notify(strings.library.playNext)
                        }
                    } else null,
                    isDownloaded = cloudId != null && (isLocal || AppModel.isDownloaded(cloudId)),
                    onDownload = if (playable && cloudId != null && !isLocal) {
                        { AppModel.downloadTrack(track) }
                    } else null,
                    onAddToPlaylist = if (playable && cloudId != null) {
                        { onAddToPlaylist(track) }
                    } else null,
                    onAddToLocalPlaylist = if (playable && localPath != null) {
                        { onAddToLocalPlaylist(localPath) }
                    } else null,
                    onOpenInFileManager = if (localPath != null) {
                        { cp.player.app.platform.openInFileManager(localPath) }
                    } else null,
                    onShare = if (cloudId != null) {
                        { shareText(songShareText(track)) }
                    } else null,
                    onShowInfo = { onShowInfo(track) },
                )
            )
        },
    )
}

// ============================================================
// 分段控件
// ============================================================

@Composable
private fun InsightsTabs(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    CpConnectedChoice(
        selectedIndex = selected,
        labels = labels,
        onSelect = onSelect,
    )
}

// ============================================================
// 概览
// ============================================================

private fun androidx.compose.foundation.lazy.LazyListScope.overviewTab(
    strings: CpStrings,
    summary: cp.player.core.insights.InsightsSummary,
    daily: List<cp.player.core.insights.DailyAgg>,
    scale: cp.player.core.insights.HeatScale,
    todayKey: String,
    green: Boolean,
    records: List<cp.player.core.insights.PlayRecord>,
    selectedDay: String?,
    scrobbleState: cp.player.core.scrobble.ScrobbleHubState,
    onOpenScrobble: () -> Unit,
    onSelectDay: (String) -> Unit,
    onToggleGreen: () -> Unit,
    onClear: () -> Unit,
) {
    // 外部服务打卡同步状态卡片（Last.fm 等）
    item {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { onOpenScrobble() },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.CloudSync,
                        contentDescription = null,
                        tint = if (scrobbleState.lastFmConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        CpText(
                            text = if (scrobbleState.lastFmConnected) {
                                strings.scrobble.insightsSyncConnected(
                                    scrobbleState.lastFmUsername.orEmpty(),
                                    scrobbleState.todayScrobbledCount,
                                )
                            } else {
                                strings.scrobble.insightsSyncDisconnected
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (scrobbleState.pendingQueueSize > 0) {
                            CpText(
                                text = strings.scrobble.pendingQueueCount(scrobbleState.pendingQueueSize),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                TextButton(onClick = onOpenScrobble) {
                    CpText(strings.scrobble.insightsGoToSettings)
                }
            }
        }
    }

    item { Spacer(Modifier.height(12.dp)) }

    item {
        MetricGrid(
            listOf(
                // 指标卡在窄屏两列、宽屏三列 ⇒ 这里仍用**极短**格式。
                // 用 `formatDurationShort`（「2 小时 41 分」）在窄屏两列下也会被裁掉分钟，
                // 看起来像统计漏了。
                formatDurationCompact(summary.todayMs, strings) to strings.insights.metricToday,
                formatDurationCompact(summary.weekMs, strings) to strings.insights.metricWeek,
                formatDurationCompact(summary.totalMs, strings) to strings.insights.metricTotal,
                strings.insights.daysLabel(summary.currentStreak) to strings.insights.metricStreak,
                strings.insights.daysLabel(summary.longestStreak) to strings.insights.metricLongestStreak,
                strings.insights.tracksLabel(summary.uniqueTrackCount) to strings.insights.metricUniqueTracks,
            ),
        )
    }

    item { Spacer(Modifier.height(20.dp)) }

    item {
        SectionHeader(
            title = strings.insights.calendarTitle,
            supportingText = strings.insights.calendarNote,
        )
    }
    item { Spacer(Modifier.height(8.dp)) }

    if (daily.isEmpty()) {
        item {
            StateSurface {
                ContentState(
                    title = strings.insights.calendarEmptyTitle,
                    message = strings.insights.calendarEmptyNote,
                )
            }
        }
    } else {
        item {
            InsightCard {
                // 年视图 53 列 ≈ 690dp，放不进手机屏宽 ⇒ 窄屏换月历视图。
                // 两者共用同一份 daily 与同一套分档，绝不允许各算一套（否则同一天在两端颜色不同）。
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= CpBreakpoints.medium) {
                        val gap = 3.dp
                        // 单元格宽度按可用宽度反算，但**封顶 16dp**：不封顶时 1400dp 页面上
                        // 单格会到 22dp 以上，整面墙变成一坨方块、失去「密度感」。
                        val cell = ((maxWidth - gap * 52) / 53).coerceIn(5.dp, 16.dp)
                        ListeningCalendar(
                            daily = daily,
                            scale = scale,
                            todayKey = todayKey,
                            classicGreen = green,
                            cellSize = cell,
                            gap = gap,
                            modifier = Modifier.align(Alignment.TopCenter),
                            onDayClick = onSelectDay,
                        )
                    } else {
                        val now = remember { localDateTimeOf(cp.player.core.util.currentTimeMillis()) }
                        MonthListeningCalendar(
                            daily = daily,
                            scale = scale,
                            year = now.year,
                            month = now.month,
                            todayKey = todayKey,
                            classicGreen = green,
                            onDayClick = onSelectDay,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ListeningCalendarLegend(classicGreen = green)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onToggleGreen) {
                        CpText(strings.insights.paletteLabel(green))
                    }
                }
            }
        }
    }

    selectedDay?.let { day ->
        item { Spacer(Modifier.height(12.dp)) }
        item {
            SelectedDayCard(dayKey = day, records = records, strings = strings)
        }
    }

    if (summary.topArtists.isNotEmpty()) {
        item { Spacer(Modifier.height(20.dp)) }
        item {
            SectionHeader(
                title = strings.insights.topArtistsTitle,
                supportingText = strings.insights.rankedByPlayTime,
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
        item {
            InsightCard(spacing = 8.dp) {
                summary.topArtists.forEach { artist ->
                    RankRow(artist, summary.topArtists.first().playedMs, strings)
                }
            }
        }
    }

    if (daily.isNotEmpty()) {
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onClear) { CpText(strings.insights.clearTitle) }
            }
        }
    }
}

/** 某一天的明细：当天听过什么。点击日历格后展开，让日历墙成为**导航入口**而不是装饰。 */
@Composable
private fun SelectedDayCard(
    dayKey: String,
    records: List<cp.player.core.insights.PlayRecord>,
    strings: CpStrings,
) {
    // 日期键形如 `yyyy-MM-dd`，直接拆比再走一次日期库便宜，也不会引入时区换算的边界。
    val parts = remember(dayKey) { dayKey.split('-') }
    val dayLabel = remember(parts, strings) {
        val y = parts.getOrNull(0).orEmpty()
        val m = parts.getOrNull(1)?.trimStart('0').orEmpty()
        val d = parts.getOrNull(2)?.trimStart('0').orEmpty()
        strings.insights.dateTitle(y, m, d)
    }
    val dayRecords = remember(records, dayKey) {
        records.filter { Insights.dateKeyOf(it.startedAt) == dayKey }.sortedBy { it.startedAt }
    }
    val totalMs = dayRecords.sumOf { it.playedMs }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CpText(
                dayLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            CpText(
                strings.insights.daySummary(formatDurationShort(totalMs, strings), dayRecords.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dayRecords.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    CpText(
                        r.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    CpText(
                        formatDurationShort(r.playedMs, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ============================================================
// 习惯
// ============================================================

private fun androidx.compose.foundation.lazy.LazyListScope.habitsTab(
    summary: cp.player.core.insights.InsightsSummary,
    strings: CpStrings,
) {
    item {
        MetricGrid(
            listOf(
                percent(summary.completedCount, summary.playCount) to strings.insights.metricCompletionRate,
                percent(summary.skippedCount, summary.playCount) to strings.insights.metricSkipRate,
                "${summary.newTrackCount} ${strings.insights.tracksLabel(summary.newTrackCount)}" to strings.insights.metricNewTracks,
            ),
        )
    }
    item { Spacer(Modifier.height(20.dp)) }
    item {
        SectionHeader(title = strings.insights.hourlyTitle, supportingText = strings.insights.hourlyNote)
    }
    item { Spacer(Modifier.height(8.dp)) }
    item {
        InsightCard {
            BarChart(
                values = summary.hourBuckets,
                labels = (0..23).map { if (it % 6 == 0) it.toString() else "" },
            )
        }
    }
    item { Spacer(Modifier.height(20.dp)) }
    item {
        SectionHeader(title = strings.insights.weekdayTitle, supportingText = strings.insights.weekdayNote)
    }
    item { Spacer(Modifier.height(8.dp)) }
    item {
        InsightCard {
            BarChart(
                values = summary.weekdayBuckets,
                labels = strings.insights.calendar.weekdayLabels,
            )
        }
    }
    if (summary.topTracks.isNotEmpty()) {
        item { Spacer(Modifier.height(20.dp)) }
        item {
            SectionHeader(title = strings.insights.topTracksTitle, supportingText = strings.insights.rankedByPlayTime)
        }
        item { Spacer(Modifier.height(8.dp)) }
        item {
            InsightCard(spacing = 8.dp) {
                summary.topTracks.forEach { track ->
                    RankRow(track, summary.topTracks.first().playedMs, strings)
                }
            }
        }
    }
}

private fun percent(part: Int, total: Int): String =
    if (total <= 0) "—" else "${(part * 100f / total).toInt()}%"

// ============================================================
// 小组件
// ============================================================

/**
 * 听歌报告的统一卡片。
 *
 * 报告页的图表、排行、日历都落在同一档 surfaceContainerLow + extraLarge 圆角上，
 * 避免「有的块是裸内容、有的块是卡片」造成的节奏断裂。
 */
@Composable
private fun InsightCard(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * 指标卡网格。
 *
 * 三列在手机上每张只有约 100dp，`headlineSmall` 的数字会被截成「2 小…」。
 * 这里按可用宽度降到两列，让六张卡排成 3×2；宽屏仍保持 3×2 的紧凑节奏。
 */
@Composable
private fun MetricGrid(metrics: List<Pair<String, String>>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 560.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            metrics.chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { (value, label) ->
                        MetricCard(value, label, Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CpText(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            CpText(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                // 兜底省略号由 CpText 自己接管：溢出时桌面端悬停可看全，
                // 这里不再手写 `overflow`（CpText 固定用 Ellipsis）。
            )
        }
    }
}

/** 排行行：横条长度按最大值归一。 */
@Composable
private fun RankRow(item: RankItem, maxMs: Long, strings: CpStrings) {
    val fraction = if (maxMs <= 0L) 0f else (item.playedMs.toFloat() / maxMs).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CpText(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            CpText(
                formatDurationShort(item.playedMs, strings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * 极简柱状图。
 *
 * 刻意不引图表库：这里只需要「若干等宽柱子」这一种形态，
 * 引一个图表库会为它带来主题适配、版本冲突与包体三份成本。
 */
@Composable
private fun BarChart(values: List<Long>, labels: List<String>) {
    val max = values.maxOrNull() ?: 0L
    Row(
        Modifier.fillMaxWidth().height(112.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { index, v ->
            val fraction = if (max <= 0L) 0f else (v.toFloat() / max).coerceIn(0.02f, 1f)
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((84 * fraction).dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary),
                )
                CpText(
                    labels.getOrElse(index) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 离屏预览夹具：只组合报告页的「卡片节奏」骨架（指标 / 图表 / 排行）。
 *
 * 真实页面依赖 `AppModel`，在 desktopTest 的离屏场景里会拉起后端初始化；
 * 这里用同一批私有组件与同一组间距常量出图，专门核对卡片圆角、内边距与区块间距。
 */
@Composable
internal fun InsightsReportPreview() {
    val strings = CpStrings.zh
    Column(
        Modifier.fillMaxWidth().padding(CpSpacing.pageHorizontal),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
    ) {
        InsightsTabs(
            selected = 0,
            labels = listOf(
                strings.insights.tabOverview,
                strings.insights.tabHabits,
                strings.insights.tabRecent,
            ),
            onSelect = {},
        )
        Spacer(Modifier.height(12.dp))
        MetricGrid(
            listOf(
                "2.7 小时" to strings.insights.metricToday,
                "12.1 小时" to strings.insights.metricWeek,
                "286 小时" to strings.insights.metricTotal,
                "18 天" to strings.insights.metricStreak,
                "42 天" to strings.insights.metricLongestStreak,
                "1,284 首" to strings.insights.metricUniqueTracks,
            ),
        )
        Spacer(Modifier.height(20.dp))
        SectionHeader(
            title = strings.insights.hourlyTitle,
            supportingText = strings.insights.hourlyNote,
        )
        Spacer(Modifier.height(8.dp))
        InsightCard {
            BarChart(
                values = listOf(0, 2, 5, 8, 12, 18, 24, 30, 36, 28, 20, 16, 22, 30, 34, 28, 24, 20, 18, 16, 12, 8, 4, 2),
                labels = (0..23).map { if (it % 6 == 0) it.toString() else "" },
            )
        }
        Spacer(Modifier.height(20.dp))
        SectionHeader(
            title = strings.insights.topArtistsTitle,
            supportingText = strings.insights.rankedByPlayTime,
        )
        Spacer(Modifier.height(8.dp))
        InsightCard(spacing = 8.dp) {
            listOf(
                RankItem("a", "椎名林檎", 28_000_000L, 128),
                RankItem("b", "宇多田ヒカル", 21_000_000L, 96),
                RankItem("c", "東京事変", 15_000_000L, 72),
            ).forEach { item ->
                RankRow(item, 28_000_000L, strings)
            }
        }
    }
}
