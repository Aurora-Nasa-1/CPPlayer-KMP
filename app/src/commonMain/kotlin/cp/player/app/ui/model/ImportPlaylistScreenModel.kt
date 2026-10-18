package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.repository.SearchType
import cp.player.app.ui.util.UiEvents
import cp.player.core.BackendResult
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import cp.player.core.playlist.PlaylistImportEntry
import cp.player.core.playlist.PlaylistImportParser
import cp.player.core.util.runCatchingExceptCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** 预览列表里的一行：曲目 + 是否勾选。 */
data class ImportTrackItem(
    val track: TrackSummary,
    val selected: Boolean = true,
)

/** 导入完成后的落点，供页面显示「打开歌单」。 */
data class PlaylistImportOutcome(
    val playlistId: Long,
    val playlistName: String,
    val added: Int,
    /** 有分片失败：已导入一部分，但不是全部。 */
    val partial: Boolean,
)

data class ImportPlaylistUiState(
    val raw: String = "",
    val analyzing: Boolean = false,
    val analyzed: Boolean = false,
    val sourcePlaylist: PlaylistSummary? = null,
    val tracks: List<ImportTrackItem> = emptyList(),
    val unmatched: List<String> = emptyList(),
    val name: String = "",
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val unsupportedLinks: Int = 0,
    val error: String? = null,
    val importing: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val outcome: PlaylistImportOutcome? = null,
) {
    val selectedCount: Int get() = tracks.count { it.selected }
}

/**
 * 外部歌单导入页的模型。
 *
 * ## 为什么解析与解析后的「补全」分开
 *
 * 纯文本行只有「歌名 + 歌手」，必须再走一次搜索才能拿到歌曲 id；而链接里的 id
 * 是确定的，只需要取详情。两件事的失败方式完全不同（搜索无结果 ≠ 链接失效），
 * 所以 [PlaylistImportParser] 只做文本形状识别，网络补全全部留在这里，
 * 未匹配的条目原样回传，让用户在预览里看见「哪几条没找到」。
 *
 * ## 并发
 *
 * 文本行按 [RESOLVE_BATCH] 条一批并发解析。**不做无限并发**：几百行一次全发出去
 * 会把音源接口打出限流，反而让整批都失败；批内并发又能把等待时间压到 1/4。
 */
class ImportPlaylistScreenModel(
    /** 仅供离屏预览 / 测试注入初始态；正常运行用默认空态。 */
    initialState: ImportPlaylistUiState = ImportPlaylistUiState(),
) : ScreenModel {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<ImportPlaylistUiState> = _state.asStateFlow()

    private var analyzeJob: Job? = null

    /** 编辑输入即作废上一轮结果 —— 否则「改了文本却导入了旧结果」不会有人发现。 */
    fun setRaw(text: String) {
        analyzeJob?.cancel()
        _state.update {
            it.copy(
                raw = text,
                analyzing = false,
                analyzed = false,
                tracks = emptyList(),
                unmatched = emptyList(),
                sourcePlaylist = null,
                error = null,
                outcome = null,
                progressDone = 0,
                progressTotal = 0,
                unsupportedLinks = 0,
            )
        }
    }

    fun setName(name: String) {
        _state.update { it.copy(name = name) }
    }

    fun toggleTrack(index: Int) {
        _state.update { s ->
            if (index !in s.tracks.indices) s
            else s.copy(
                tracks = s.tracks.mapIndexed { i, item ->
                    if (i == index) item.copy(selected = !item.selected) else item
                },
            )
        }
    }

    fun selectAll(selected: Boolean) {
        _state.update { s -> s.copy(tracks = s.tracks.map { it.copy(selected = selected) }) }
    }

    /** 解析 + 补全。调用前页面应保证 [ImportPlaylistUiState.raw] 非空。 */
    fun analyze(s: CpStrings) {
        val raw = _state.value.raw
        if (raw.isBlank()) {
            _state.update { it.copy(error = s.playlistImport.emptyInput) }
            return
        }
        val parsed = PlaylistImportParser.parse(raw)
        if (parsed.isEmpty) {
            val message = if (parsed.unsupportedLinks > 0) {
                s.playlistImport.noEntries + "\n" + s.playlistImport.unsupportedLinks(parsed.unsupportedLinks)
            } else {
                s.playlistImport.noEntries
            }
            _state.update {
                it.copy(
                    analyzed = false,
                    analyzing = false,
                    error = message,
                    unsupportedLinks = parsed.unsupportedLinks,
                )
            }
            return
        }

        analyzeJob?.cancel()
        analyzeJob = screenModelScope.launch {
            val pending = parsed.songLinkCount + parsed.queryCount
            _state.update {
                it.copy(
                    analyzing = true,
                    analyzed = false,
                    error = null,
                    tracks = emptyList(),
                    unmatched = emptyList(),
                    sourcePlaylist = null,
                    outcome = null,
                    progressDone = 0,
                    progressTotal = pending,
                    unsupportedLinks = parsed.unsupportedLinks,
                    name = it.name.ifBlank { parsed.nameHint.orEmpty() },
                )
            }
            try {
                val collected = mutableListOf<TrackSummary>()
                val unmatched = mutableListOf<String>()
                var source: PlaylistSummary? = null

                // 1) 歌单链接：整单取回（分页取全，详情里的 tracks 只作兜底）。
                parsed.playlistLinkId?.let { id ->
                    val (summary, tracks) = loadPlaylistTracks(id)
                    source = summary
                    collected += tracks
                    if (tracks.isEmpty()) unmatched += "playlist/$id"
                }

                // 2) 单曲链接 / 文本行：批内并发补全。
                val entries = parsed.entries.filterNot { it is PlaylistImportEntry.PlaylistLink }
                var done = 0
                entries.chunked(RESOLVE_BATCH).forEach { batch ->
                    val resolved = coroutineScope {
                        batch.map { entry ->
                            async(Dispatchers.IO) { entry to resolveEntry(entry) }
                        }.awaitAll()
                    }
                    resolved.forEach { (entry, track) ->
                        if (track != null && track.id.isNotBlank()) collected += track
                        else unmatched += describe(entry)
                    }
                    done += batch.size
                    _state.update { it.copy(progressDone = done) }
                }

                val deduped = collected.distinctBy { it.id }
                if (deduped.isEmpty()) {
                    _state.update {
                        it.copy(
                            analyzing = false,
                            analyzed = false,
                            error = s.playlistImport.noEntries,
                            unmatched = unmatched,
                            sourcePlaylist = source,
                        )
                    }
                    return@launch
                }

                _state.update {
                    it.copy(
                        analyzing = false,
                        analyzed = true,
                        sourcePlaylist = source,
                        tracks = deduped.map { track -> ImportTrackItem(track) },
                        unmatched = unmatched,
                        name = it.name.ifBlank {
                            parsed.nameHint ?: source?.name ?: s.playlistImport.defaultName
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(analyzing = false, error = s.playlistImport.analyzeFailed) }
            }
        }
    }

    fun importToNew(s: CpStrings) {
        val snapshot = _state.value
        if (snapshot.importing) return
        val ids = snapshot.tracks.filter { it.selected }.map { it.track.id }
        if (ids.isEmpty()) {
            UiEvents.notify(s.playlistImport.emptySelection)
            return
        }
        val name = snapshot.name.trim().ifBlank { s.playlistImport.defaultName }
        screenModelScope.launch {
            _state.update {
                it.copy(importing = true, importDone = 0, importTotal = ids.size, error = null, outcome = null)
            }
            val created = withContext(Dispatchers.IO) {
                runCatchingExceptCancellation {
                    AppModel.musicRepository.createPlaylist(name)
                }.getOrNull()
            }
            val newId = (created as? BackendResult.Success)?.data
            if (newId == null) {
                _state.update { it.copy(importing = false, error = s.playlistImport.importFailed) }
                UiEvents.notify(s.playlistImport.importFailed)
                return@launch
            }
            finishImport(newId, name, ids, s)
        }
    }

    fun importToExisting(target: PlaylistSummary, s: CpStrings) {
        val snapshot = _state.value
        if (snapshot.importing) return
        val ids = snapshot.tracks.filter { it.selected }.map { it.track.id }
        if (ids.isEmpty()) {
            UiEvents.notify(s.playlistImport.emptySelection)
            return
        }
        screenModelScope.launch {
            _state.update {
                it.copy(importing = true, importDone = 0, importTotal = ids.size, error = null, outcome = null)
            }
            finishImport(target.id, target.name, ids, s)
        }
    }

    /** 收藏来源歌单（整单订阅，不复制曲目）。 */
    fun subscribeSource(s: CpStrings) {
        val source = _state.value.sourcePlaylist ?: return
        screenModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatchingExceptCancellation {
                    AppModel.musicRepository.subscribePlaylist(source.id, true)
                }.getOrDefault(false)
            }
            UiEvents.notify(
                if (ok) s.playlistImport.subscribedSource else s.playlistImport.subscribeFailed,
            )
        }
    }

    private suspend fun finishImport(
        playlistId: Long,
        playlistName: String,
        ids: List<String>,
        s: CpStrings,
    ) {
        var added = 0
        var failedChunks = 0
        ids.chunked(ADD_CHUNK).forEach { chunk ->
            val ok = withContext(Dispatchers.IO) {
                runCatchingExceptCancellation {
                    AppModel.musicRepository.addTracksToPlaylist(playlistId, chunk)
                }.getOrDefault(false)
            }
            if (ok) added += chunk.size else failedChunks++
            _state.update { it.copy(importDone = added) }
        }

        if (added == 0) {
            _state.update { it.copy(importing = false, error = s.playlistImport.importFailed) }
            UiEvents.notify(s.playlistImport.importFailed)
            return
        }

        val partial = failedChunks > 0
        _state.update {
            it.copy(
                importing = false,
                outcome = PlaylistImportOutcome(playlistId, playlistName, added, partial),
            )
        }
        UiEvents.notify(
            if (partial) s.playlistImport.partialFailure
            else s.playlistImport.importSuccess(added, playlistName),
        )
    }

    // ============ 网络补全 ============

    private suspend fun resolveEntry(entry: PlaylistImportEntry): TrackSummary? = when (entry) {
        is PlaylistImportEntry.SongLink -> songById(entry.songId)
        is PlaylistImportEntry.TextQuery -> searchByText(entry.title, entry.artist)
        is PlaylistImportEntry.PlaylistLink -> null
    }

    private suspend fun songById(songId: String): TrackSummary? {
        val result = runCatchingExceptCancellation {
            AppModel.musicRepository.getSongDetailInfo(songId)
        }.getOrNull()
        val info = (result as? BackendResult.Success)?.data ?: return null
        return TrackSummary(
            id = info.songId.ifBlank { songId },
            name = info.name,
            artist = info.artist,
            album = info.album.ifBlank { null },
            coverUrl = null,
            durationMs = info.durationMs,
            mvId = info.mvId,
        )
    }

    private suspend fun searchByText(title: String, artist: String?): TrackSummary? {
        val keyword = if (artist.isNullOrBlank()) title else "$title $artist"
        val result = runCatchingExceptCancellation {
            AppModel.musicRepository.search(keyword, SearchType.SONG)
        }.getOrNull()
        val songs = (result as? BackendResult.Success)?.data?.songs.orEmpty()
        if (songs.isEmpty()) return null
        // 有歌手信息时优先取歌手命中的那条：搜「晴天」第一条未必是周杰伦，
        // 但搜「晴天 周杰伦」时正确结果几乎总在，命中歌手就基本可以确信。
        return if (!artist.isNullOrBlank()) {
            songs.firstOrNull { it.artist.contains(artist, ignoreCase = true) } ?: songs.first()
        } else {
            songs.first()
        }
    }

    /**
     * 取回歌单全部曲目。
     *
     * `playlist/detail` 只带前 1000 首（上游行为），所以以 `playlist/track/all` 分页为准；
     * 某些 Provider 不实现分页接口（返回 Unsupported），此时退回详情自带的 tracks。
     */
    private suspend fun loadPlaylistTracks(playlistId: Long): Pair<PlaylistSummary?, List<TrackSummary>> {
        val detail = runCatchingExceptCancellation {
            AppModel.musicRepository.getPlaylistDetail(playlistId)
        }.getOrNull()
        val detailData = (detail as? BackendResult.Success)?.data
        val collected = mutableListOf<TrackSummary>()
        var offset = 0
        while (offset < MAX_IMPORT_TRACKS) {
            val page = runCatchingExceptCancellation {
                AppModel.musicRepository.getPlaylistTracks(playlistId, TRACK_PAGE, offset)
            }.getOrNull()
            val data = (page as? BackendResult.Success)?.data ?: break
            if (data.tracks.isEmpty()) break
            collected += data.tracks
            offset += data.tracks.size
            if (!data.hasMore && data.tracks.size < TRACK_PAGE) break
        }
        if (collected.isEmpty()) collected += detailData?.tracks.orEmpty()
        return detailData?.summary to collected.distinctBy { it.id }
    }

    private fun describe(entry: PlaylistImportEntry): String = when (entry) {
        is PlaylistImportEntry.TextQuery ->
            if (entry.artist.isNullOrBlank()) entry.title else "${entry.title} - ${entry.artist}"
        is PlaylistImportEntry.SongLink -> "song/${entry.songId}"
        is PlaylistImportEntry.PlaylistLink -> "playlist/${entry.playlistId}"
    }

    private companion object {
        /** 批内并发数。 */
        const val RESOLVE_BATCH = 4

        /** 单次「加入歌单」请求的曲目数（上游对一次提交的数量有限制）。 */
        const val ADD_CHUNK = 200

        /** 歌单分页大小（与详情页一致）。 */
        const val TRACK_PAGE = 300

        /** 兜底上限，防止异常歌单把分页拉成死循环。 */
        const val MAX_IMPORT_TRACKS = 20_000
    }
}
