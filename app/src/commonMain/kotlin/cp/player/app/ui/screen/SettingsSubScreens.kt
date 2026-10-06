package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsConfirmItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.model.SONG_CACHE_CAPACITY_OPTIONS
import cp.player.app.ui.model.StorageSettingsModel
import cp.player.app.ui.model.formatBytes
import cp.player.app.ui.model.songCacheCapacityIndex
import cp.player.app.ui.theme.ColorSource
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.theme.description
import cp.player.app.ui.theme.displayName
import cp.player.app.ui.theme.isPlatformColorSourceAvailable
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.popOrNotify
import kotlin.math.roundToInt

/**
 * 外观与主题。
 *
 * ### 与重构前的差异
 *
 * 1. **状态只从 `AppModel` 的 StateFlow 读。** 旧版写的是
 *    `var themeMode by remember { mutableStateOf(AppModel.themeMode()) }` ——
 *    在页面里复制了一份持久值。别处（恢复默认、封面取色）改了它，这份副本不会同步，
 *    UI 会一直显示陈旧值。
 * 2. **「主题模式」「取色来源」改用分段控件。** 两者都是 3 选 1，走旧版的
 *    「下拉行 → 底部弹层」要「点开 + 点选」两次，而且当前值只能从副标题里猜；
 *    分段控件让三个选项直接可见，一次点击完成。
 * 3. **不可用的取色来源直接不显示**，而不是置灰。置灰的选项对用户是纯噪声 ——
 *    他既选不了，也看不出为什么。
 */
class AppearanceSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val themeMode by AppModel.themeModeFlow.collectAsState()
        val colorSource by AppModel.colorSourceFlow.collectAsState()
        val pureBlack by AppModel.pureBlackFlow.collectAsState()
        val bottomBarAutoHide by AppModel.bottomBarAutoHideFlow.collectAsState()
        val coverFlightAnimation by AppModel.coverFlightAnimationFlow.collectAsState()
        val fluidBackground by AppModel.fluidBackgroundFlow.collectAsState()
        val fontRoundness by AppModel.fontRoundnessFlow.collectAsState()
        val platformAvailable = isPlatformColorSourceAvailable()
        val defaultRoundness = cp.player.app.platform.defaultFontRoundness()

        val availableSources = remember(platformAvailable) {
            ColorSource.entries.filter { it != ColorSource.PLATFORM || platformAvailable }
        }

        // 拖动中的临时值：松手前只改显示，不写盘（桌面设置存储是全量回写，边拖边写打爆 IO）。
        // -1 表示不在拖动中。
        var draggingRoundness by remember { mutableIntStateOf(-1) }
        val displayedRoundness = if (draggingRoundness >= 0) draggingRoundness
        else fontRoundness ?: defaultRoundness

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                // ⚠️ 本组所有行的 `total` 必须**同为组内行数**（分段圆角按 index/total
                // 算首/中/末段）。此处曾出现 segmented 行写 `total = 3`、开关行写 `total = 4`
                // 的分叉 —— 加/减行时会错出圆角。增删行时一起改。
                SettingsSection(s.appearance.sectionLook) {
                    SettingsSegmentedItem(
                        title = s.appearance.themeMode,
                        options = ThemeMode.entries.map { it.displayName(s) },
                        selectedIndex = ThemeMode.entries.indexOf(themeMode).coerceAtLeast(0),
                        onSelect = { index ->
                            ThemeMode.entries.getOrNull(index)?.let(AppModel::setThemeMode)
                        },
                        index = 0,
                        total = 6,
                    )
                    SettingsSegmentedItem(
                        title = s.appearance.colorSource,
                        options = availableSources.map { it.displayName(s) },
                        selectedIndex = availableSources.indexOf(colorSource).coerceAtLeast(0),
                        onSelect = { index ->
                            availableSources.getOrNull(index)?.let(AppModel::setColorSource)
                        },
                        index = 1,
                        total = 6,
                    )
                    SettingsSwitchItem(
                        title = s.appearance.pureBlack,
                        subtitle = s.appearance.pureBlackNote,
                        checked = pureBlack,
                        onCheckedChange = AppModel::setPureBlack,
                        index = 2,
                        total = 6,
                    )
                    // 窄屏布局才有底栏；桌面宽屏走侧栏，这项开着也无副作用。
                    SettingsSwitchItem(
                        title = s.appearance.autoHideBottomBar,
                        subtitle = s.appearance.autoHideBottomBarNote,
                        checked = bottomBarAutoHide,
                        onCheckedChange = AppModel::setBottomBarAutoHide,
                        index = 3,
                        total = 6,
                    )
                    SettingsSwitchItem(
                        title = s.appearance.coverFlight,
                        subtitle = s.appearance.coverFlightNote,
                        checked = coverFlightAnimation,
                        onCheckedChange = AppModel::setCoverFlightAnimation,
                        index = 4,
                        total = 6,
                    )
                    // 与「封面飞行动画」同属「播放页的视觉特效」，所以放同一组而不是
                    // 播放设置页：用户找它时的心理位置是「界面长什么样」，不是「怎么播」。
                    SettingsSwitchItem(
                        title = s.appearance.fluidBackground,
                        subtitle = s.appearance.fluidBackgroundNote,
                        checked = fluidBackground,
                        onCheckedChange = AppModel::setFluidBackground,
                        index = 5,
                        total = 6,
                    )
                }
                SettingsSection(s.appearance.sectionFont) {
                    SettingsSliderItem(
                        title = s.appearance.fontRoundness,
                        subtitle = s.appearance.fontRoundnessNote(defaultRoundness),
                        value = displayedRoundness.toFloat(),
                        onValueChange = { draggingRoundness = it.toInt() },
                        valueRange = 0f..100f,
                        steps = 19,
                        onValueChangeFinished = {
                            AppModel.setFontRoundness(displayedRoundness)
                            draggingRoundness = -1
                        },
                        valueLabel = displayedRoundness.toString() +
                            if (fontRoundness == null && displayedRoundness == defaultRoundness) {
                                s.appearance.roundnessDefaultTag
                            } else {
                                ""
                            },
                        index = 0,
                        total = if (fontRoundness != null) 2 else 1,
                    )
                    // 「恢复默认」只在用户自定义过之后出现：默认状态下它是个死按钮。
                    if (fontRoundness != null) {
                        SettingsButtonItem(
                            text = s.appearance.resetPlatformDefault,
                            subtitle = s.appearance.resetPlatformDefaultNote,
                            icon = Icons.Filled.RestartAlt,
                            index = 1,
                            total = 2,
                            onClick = { AppModel.setFontRoundness(null) },
                        )
                    }
                }
                SettingsNote(s.appearance.roundnessNote)
                SettingsNote(colorSource.description(s, platformAvailable))
            }
        }

        CpRouteScaffold(
            title = s.appearance.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier -> body(pageModifier) }
    }
}

/**
 * 下载与存储（存储管理页）。
 *
 * ### 与初版的差异
 *
 * 初版只有「改下载目录 + 清缓存」两个动作，用户看不到任何数字 —— 「存储管理」名不副实。
 * 现在补上：
 *
 * 1. **下载区给出体量与条数**：已下载音乐一行显示「N 首 · 共 X」，点进去是下载管理页
 *    （那里能删文件）；占用数字取媒体库登记值，不做全盘扫描。
 * 2. **歌曲缓存区（桌面）**：无损流落盘是**磁盘占用最大的一块**（上限 2 GiB），
 *    此前既看不到也删不掉。现在给出「N 首 · 共 X / 上限 Y」，并区分三档清理力度 ——
 *    进明细页逐首删、清 30 天未播放、清空全部。后两者都要二次确认。
 * 3. **接口缓存区**：元数据的读透缓存，此前只有登出会清。补上条数与本次会话命中率，
 *    以及清理入口。命中率是这一层唯一能被用户看见的健康指标（它要么白占内存、
 *    要么令人「数据不更新」，两种毛病都只能靠计数发现）。
 * 4. **图片缓存区先给数字再给动作**：清理按钮的反馈带「释放了多少」（清理前后各读一次）。
 * 5. **桌面端可直达目录**：下载目录与缓存目录都能一键在文件管理器中打开。
 * 6. **失败不再谎报成功**：打开目录失败、删除失败、缓存清理失败都有对应提示。
 *
 * ### 一条贯穿全页的原则
 *
 * **「清理缓存」绝不删「已下载的音乐」**：前者是播放的副作用、可被 LRU 淘汰，
 * 后者是用户显式下载的资产。两者都是磁盘上的音频文件，用户极易混淆，
 * 因此每个清理动作的副标题与确认文案里都写死了这一句。
 */
class StorageSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // rememberScreenModel 是 Screen 的扩展函数，只能在 Content() 里调用（见 AGENTS.md）。
        val model = rememberScreenModel { StorageSettingsModel() }
        StorageSettingsContent(
            model = model,
            onBack = { navigator.popOrNotify() },
            onOpenDownloads = { navigator.push(DownloadsScreen()) },
            onOpenSongCache = { navigator.push(SongCacheScreen()) },
        )
    }
}

@Composable
private fun StorageSettingsContent(
    model: StorageSettingsModel,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSongCache: () -> Unit,
) {
    val state by model.state.collectAsState()
    val s = cpStrings()
    val downloadDir by AppModel.downloadDirFlow.collectAsState()
    val isAndroid = cp.player.app.platform.isAndroidPlatform()
    val pickDownloadDir = cp.player.app.platform.rememberDirectoryPicker { path ->
        if (!path.isNullOrBlank()) {
            AppModel.setDownloadDir(path)
            UiEvents.notify(s.storage.dirUpdated)
        }
    }
    // 从歌曲缓存明细页删完条目回来时，本页的数字必须是新的 ——
    // 回到这一页会重新进入组合，这个 effect 因此会重跑。
    LaunchedEffect(Unit) { model.refreshSongCache() }

    val body: @Composable (Modifier) -> Unit = { pageModifier ->
        SettingsPage(pageModifier) {
            // ---- 下载区：桌面 3 行（下载音乐 / 目录 / 打开目录），Android 2 行 ----
            val downloadRows = if (isAndroid) 2 else 3
            SettingsSection(s.storage.sectionDownload) {
                SettingsClickItem(
                    title = s.storage.downloadedMusic,
                    subtitle = when {
                        state.downloadedCount == 0 -> s.storage.downloadedMusicEmpty
                        else -> s.storage.downloadedMusicSummary(
                            state.downloadedCount,
                            formatBytes(state.downloadedBytes),
                        )
                    },
                    index = 0,
                    total = downloadRows,
                    icon = Icons.Filled.MusicNote,
                    onClick = onOpenDownloads,
                )
                SettingsClickItem(
                    title = s.storage.downloadDir,
                    subtitle = if (isAndroid) {
                        s.storage.downloadDirAndroid
                    } else {
                        downloadDir.ifBlank { s.storage.downloadDirDefault }
                    },
                    index = 1,
                    total = downloadRows,
                    icon = Icons.Filled.FolderOpen,
                    onClick = if (isAndroid) null else ({ pickDownloadDir() }),
                )
                if (!isAndroid) {
                    SettingsClickItem(
                        title = s.storage.openDir,
                        subtitle = s.storage.openDirNote,
                        index = 2,
                        total = downloadRows,
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = {
                            val target = downloadDir
                            val ok = target.isNotBlank() &&
                                cp.player.app.platform.openInFileManager(target)
                            if (!ok) {
                                UiEvents.notify(
                                    s.storage.openDirFailed(
                                        target.ifBlank { s.storage.dirUnset },
                                    ),
                                )
                            }
                        },
                    )
                }
            }

            // ---- 歌曲缓存区：无损流落盘（桌面才有） ----
            //
            // 整块按 `songCacheSupported` 显隐，而不是显示「0 首 / 共 0 B」：
            // 安卓端 ExoPlayer 能定位 HTTP FLAC、根本不落盘，摆一排恒为 0 的数字
            // 只会被当成 bug 报上来。判据来自 core（capacityBytes = 0 即不支持），
            // 不在这里重写一遍平台判断 —— 将来安卓真加了实现，这页自动就对了。
            if (state.songCacheSupported) {
                // 行数是**算出来的**而不是写死的：清空之后两个清理动作会消失，
                // 写死会让分段卡片的末段圆角落到一个不存在的行上（首/末段判据按
                // index/total 算，见 LegacyListItem.segmentCorners）。
                val hasSongCache = state.songCacheEntries > 0
                val songCacheRows = 2 + (if (hasSongCache) 2 else 0) + (if (isAndroid) 0 else 1)
                SettingsSection(s.storage.sectionSongCache) {
                    SettingsClickItem(
                        title = s.storage.cachedSongs,
                        subtitle = when {
                            !hasSongCache -> s.storage.cachedSongsEmpty
                            else -> s.storage.cachedSongsSummary(
                                state.songCacheEntries,
                                formatBytes(state.songCacheBytes),
                                formatBytes(state.songCacheCapacityBytes),
                            )
                        },
                        index = 0,
                        total = songCacheRows,
                        icon = Icons.Filled.MusicNote,
                        onClick = onOpenSongCache,
                    )
                    // 离散档位而不是滑条：容量是个「够用就好」的粗粒度决定，
                    // 滑条会让人以为要精确到 MB，还得解释「无损一首多大」。
                    SettingsSegmentedItem(
                        title = s.storage.cacheCapacity,
                        subtitle = s.storage.cacheCapacityNote,
                        options = SONG_CACHE_CAPACITY_OPTIONS.map { it.second },
                        selectedIndex = songCacheCapacityIndex(state.songCacheCapacityBytes),
                        onSelect = { i ->
                            SONG_CACHE_CAPACITY_OPTIONS.getOrNull(i)?.let { model.setSongCacheCapacity(it.first) }
                        },
                        index = 1,
                        total = songCacheRows,
                        icon = Icons.Filled.Storage,
                    )
                    // 没有缓存时**不渲染**这两个动作：`SettingsConfirmItem` 即使在
                    // enabled=false 时也仍然铺着 errorContainer，一行「看起来能点、
                    // 点了没反应」的红色按钮比没有这一行更糟。
                    if (hasSongCache) {
                        SettingsButtonItem(
                            text = s.storage.clearStaleCache,
                            subtitle = s.storage.clearStaleCacheNote,
                            index = 2,
                            total = songCacheRows,
                            icon = Icons.Filled.CleaningServices,
                            onClick = { model.clearSongCacheOlderThan(30) },
                        )
                        SettingsConfirmItem(
                            title = s.storage.clearSongCache,
                            subtitle = s.storage.clearSongCacheNote,
                            confirmTitle = s.storage.clearSongCacheConfirmTitle,
                            confirmMessage = s.storage.clearSongCacheConfirmMessage(
                                state.songCacheEntries,
                                formatBytes(state.songCacheBytes),
                            ),
                            onConfirm = { model.clearSongCache() },
                            index = 3,
                            total = songCacheRows,
                            icon = Icons.Filled.DeleteSweep,
                        )
                    }
                    if (!isAndroid) {
                        SettingsClickItem(
                            title = s.storage.openCacheDir,
                            subtitle = s.storage.openCacheDirNote,
                            index = songCacheRows - 1,
                            total = songCacheRows,
                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                            onClick = {
                                val target = AppModel.backend.songCache.cacheDirPath()
                                val ok = !target.isNullOrBlank() &&
                                    cp.player.app.platform.openInFileManager(target)
                                if (!ok) UiEvents.notify(s.storage.openCacheDirFailed)
                            },
                        )
                    }
                }
            }

            // ---- 接口缓存区：歌曲 / 歌单等元数据的读透缓存 ----
            SettingsSection(s.storage.sectionApiCache) {
                SettingsClickItem(
                    title = s.storage.apiCacheEntries,
                    subtitle = buildString {
                        append(
                            if (state.apiCacheEntries == 0) {
                                s.storage.apiCacheEmpty
                            } else {
                                s.storage.apiCacheEntryCount(state.apiCacheEntries)
                            },
                        )
                        state.apiCacheHitRate?.let { rate ->
                            append(s.storage.apiCacheHitRate((rate * 100).roundToInt()))
                        }
                    },
                    index = 0,
                    total = 2,
                    icon = Icons.Filled.Cached,
                    // 只读统计行：它没有「点进去看」的下一页。
                    onClick = null,
                )
                SettingsButtonItem(
                    text = s.storage.clearApiCache,
                    subtitle = s.storage.clearApiCacheNote,
                    index = 1,
                    total = 2,
                    icon = Icons.Filled.CleaningServices,
                    // 刻意**不**按「有没有条目」禁用：这是非破坏性动作，
                    // 空缓存时点一下得到一句「本来就是空的」比一个点了没反应的
                    // 灰行更有交代。
                    onClick = { model.clearApiCache() },
                )
            }

            // ---- 图片缓存区 ----
            SettingsSection(s.storage.sectionImageCache) {
                SettingsClickItem(
                    title = s.storage.imageCache,
                    subtitle = when {
                        state.imageCacheBytes < 0 -> s.storage.imageCacheMeasuring
                        else -> s.storage.imageCacheUsage(formatBytes(state.imageCacheBytes))
                    },
                    index = 0,
                    total = 2,
                    icon = Icons.Filled.PhotoLibrary,
                    onClick = null,
                )
                SettingsButtonItem(
                    text = s.storage.clearImageCache,
                    subtitle = s.storage.clearImageCacheNote,
                    index = 1,
                    total = 2,
                    icon = Icons.Filled.CleaningServices,
                    onClick = { model.clearImageCache() },
                )
            }

            SettingsNote(if (isAndroid) s.storage.noteAndroid else s.storage.noteDesktop)
        }
    }

    CpRouteScaffold(
        title = s.storage.screenTitle,
        onBack = onBack,
    ) { pageModifier -> body(pageModifier) }
}
