package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.i18n.CpStrings
import cp.player.app.presentation.songcache.SongCacheContract
import cp.player.app.presentation.songcache.SongCachePresenter
import cp.player.app.presentation.songcache.displayName as presenterDisplayName
import cp.player.app.ui.util.UiEvents
import cp.player.core.playback.SongCacheEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// 兼容现有 UI 引用的类型别名
typealias SongCacheUiState = cp.player.app.presentation.songcache.SongCacheUiState

/** 歌曲缓存明细页的 Voyager 适配层（Thin Adapter）。 */
class SongCacheModel : ScreenModel, SongCacheContract {

    private val presenter = SongCachePresenter(
        scope = screenModelScope,
        notify = UiEvents::notify,
    )

    override val state: StateFlow<SongCacheUiState> get() = presenter.state
    override val scope: CoroutineScope get() = presenter.scope

    override fun setQuery(value: String) = presenter.setQuery(value)
    override fun refresh() = presenter.refresh()
    override fun convertAll(strings: CpStrings) = presenter.convertAll(strings)
    override fun convert(entry: SongCacheEntry, strings: CpStrings) = presenter.convert(entry, strings)
    override fun remove(entry: SongCacheEntry, strings: CpStrings) = presenter.remove(entry, strings)
}

internal fun qualityLabel(level: String, strings: CpStrings): String =
    cp.player.app.presentation.songcache.qualityLabel(level, strings)

internal fun relativeTime(
    ms: Long,
    strings: CpStrings,
    now: Long = cp.player.core.util.currentTimeMillis(),
): String = cp.player.app.presentation.songcache.relativeTime(ms, strings, now)

internal fun SongCacheEntry.displayName(strings: CpStrings): String =
    presenterDisplayName(strings)

internal val SONG_CACHE_CAPACITY_OPTIONS: List<Pair<Long, String>>
    get() = cp.player.app.presentation.songcache.SONG_CACHE_CAPACITY_OPTIONS

internal fun songCacheCapacityIndex(capacityBytes: Long): Int =
    cp.player.app.presentation.songcache.songCacheCapacityIndex(capacityBytes)

internal fun isCustomSongCacheCapacity(capacityBytes: Long): Boolean =
    cp.player.app.presentation.songcache.isCustomSongCacheCapacity(capacityBytes)
