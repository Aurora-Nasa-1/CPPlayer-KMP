package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.music.CPMediaId
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 播放队列与播放顺序协同管理组件。
 *
 * 职责：
 * 1. 维护播放队列条目列表与当前曲目指针（[currentIndex]）。
 * 2. 维护随机播放置换序列（[_order] 与 [_orderPos]）及合法性校验，保证单曲与循环规则计算（[computeNext] / [computePrev]）。
 * 3. 队列条目增、删、改、拖拽排序及输入 ID 去重。
 * 4. 队列后台异步懒解析元数据（[resolveQueueInBackground] 与 [resolveEntry]）。
 */
internal class PlaybackQueueManager(
    private val source: UnifiedMusicSource,
    private val scope: CoroutineScope,
    private val downloadedTrackResolver: (mediaId: String) -> TrackSummary? = { null },
) {
    /** 队列条目。mediaId 唯一；summary 可能尚未解析（懒解析）。 */
    data class Entry(val mediaId: String) {
        @Volatile var summary: TrackSummary? = null
    }

    private val _queue = mutableListOf<Entry>()
    private var _index = -1
    private var _repeat = RepeatMode.OFF
    private var _shuffle = false
    private var _order: List<Int>? = null
    private var _orderPos = -1
    private var _sourceId: String? = null

    private var resolveJob: Job? = null
    private var queueGeneration = 0

    val size: Int get() = _queue.size
    val isEmpty: Boolean get() = _queue.isEmpty()
    val isNotEmpty: Boolean get() = _queue.isNotEmpty()
    val currentIndex: Int get() = _index
    val sourceId: String? get() = _sourceId
    val repeatMode: RepeatMode get() = _repeat
    val shuffleEnabled: Boolean get() = _shuffle
    val currentEntry: Entry? get() = _queue.getOrNull(_index)
    val entries: List<Entry> get() = _queue.toList()

    fun getEntry(index: Int): Entry? = _queue.getOrNull(index)

    fun setModes(repeat: RepeatMode?, shuffle: Boolean?) {
        repeat?.let { _repeat = it }
        shuffle?.let { _shuffle = it }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeat = mode
    }

    fun toggleShuffle(): Boolean {
        _shuffle = !_shuffle
        _order = if (_shuffle) shuffledOrder(_queue.size) else null
        ensureValidOrder()
        return _shuffle
    }

    fun restoreSessionQueue(ids: List<String>, startIndex: Int, sourceId: String?): Pair<Boolean, Int> {
        val (dedupedIds, start) = dedupeQueueInput(ids, startIndex)
        if (_queue.isNotEmpty() || _index >= 0) return false to -1
        _queue.clear()
        dedupedIds.forEach { _queue.add(Entry(it)) }
        _order = if (_shuffle) shuffledOrder(_queue.size) else null
        _index = start.coerceIn(0, _queue.lastIndex)
        _orderPos = _order?.indexOf(_index) ?: _index
        _sourceId = sourceId
        return true to _index
    }

    fun replaceQueue(mediaIds: List<String>, startIndex: Int, sourceId: String?): Int {
        val (ids, start) = dedupeQueueInput(mediaIds, startIndex)
        _queue.clear()
        ids.forEach { _queue.add(Entry(it)) }
        _order = if (_shuffle) shuffledOrder(_queue.size) else null
        _index = start.coerceIn(0, _queue.lastIndex)
        _orderPos = _order?.indexOf(_index) ?: _index
        _sourceId = sourceId
        return _index
    }

    fun addToQueue(mediaId: String): Boolean {
        val canonicalId = CPMediaId.toCanonicalMediaId(mediaId)
        if (_queue.any { it.mediaId == canonicalId }) return false
        _queue.add(Entry(canonicalId))
        if (_shuffle) {
            _order = (_order ?: (0 until _queue.lastIndex).toList()) + _queue.lastIndex
        }
        ensureValidOrder()
        return true
    }

    fun addNextToQueue(mediaId: String): Int? {
        val canonicalId = CPMediaId.toCanonicalMediaId(mediaId)
        if (_index in _queue.indices && _queue[_index].mediaId == canonicalId) return null
        val existing = _queue.indexOfFirst { it.mediaId == canonicalId }
        if (existing >= 0) {
            _queue.removeAt(existing)
            _order = _order?.filter { it != existing }?.map { if (it > existing) it - 1 else it }
            if (_index > existing) _index -= 1
            if (_shuffle && _order != null) _orderPos = _order!!.indexOf(_index).coerceAtLeast(0)
        }
        val insertAt = if (_index in _queue.indices) _index + 1 else _queue.size
        _queue.add(insertAt, Entry(canonicalId))
        if (_shuffle) {
            val base = _order
            if (base != null) {
                val shifted = base.map { if (it >= insertAt) it + 1 else it }
                _order = shifted.take(_orderPos + 1) + insertAt + shifted.drop(_orderPos + 1)
            } else {
                _order = if (_index in _queue.indices) {
                    listOf(_index, insertAt) + (_queue.indices.toList() - _index - insertAt)
                } else {
                    shuffledOrder(_queue.size)
                }
            }
        }
        ensureValidOrder()
        return insertAt
    }

    data class RemoveResult(
        val wasCurrent: Boolean,
        val isQueueEmpty: Boolean,
        val newIndex: Int,
    )

    fun removeQueueItem(index: Int): RemoveResult? {
        if (index !in _queue.indices) return null
        val current = index == _index
        _queue.removeAt(index)
        if (_queue.isEmpty()) {
            _index = -1
            _order = null
            _orderPos = -1
            return RemoveResult(wasCurrent = current, isQueueEmpty = true, newIndex = -1)
        }
        _order = (_order?.filter { it != index }?.map { if (it > index) it - 1 else it })
            ?.let { if (_shuffle) it else null }
        if (current) {
            _index = (_order?.getOrNull(_orderPos) ?: _index).coerceIn(0, _queue.lastIndex)
            _orderPos = _order?.indexOf(_index) ?: _index.coerceAtLeast(0)
        } else if (_index > index) {
            _index -= 1
        }
        return RemoveResult(wasCurrent = current, isQueueEmpty = false, newIndex = _index)
    }

    fun clear() {
        resolveJob?.cancel()
        queueGeneration++
        _queue.clear()
        _index = -1
        _order = null
        _orderPos = -1
        _sourceId = null
    }

    fun moveQueueItem(from: Int, to: Int) {
        if (from == to) return
        if (from !in _queue.indices || to !in _queue.indices) return
        val item = _queue.removeAt(from)
        _queue.add(to, item)
        _index = when (_index) {
            from -> to
            in (from + 1)..to -> _index - 1
            in to..<from -> _index + 1
            else -> _index
        }
        if (_shuffle) {
            val current = _index
            if (current in _queue.indices) {
                _order = listOf(current) + (_queue.indices.toList() - current)
                    .shuffled(Random(System.nanoTime()))
            } else {
                _order = shuffledOrder(_queue.size)
            }
        }
        ensureValidOrder()
    }

    fun playAt(index: Int): Boolean {
        if (index !in _queue.indices) return false
        _index = index
        _orderPos = _order?.indexOf(index) ?: index
        return true
    }

    fun computeNext(autoAdvance: Boolean): Int? {
        if (_queue.isEmpty()) return null
        ensureValidOrder()
        if (_shuffle && _order != null) {
            val order = _order!!
            val nextPos = _orderPos + 1
            return if (nextPos <= order.lastIndex) order[nextPos]
            else if (_repeat == RepeatMode.ALL) order[0]
            else null
        }
        val next = _index + 1
        return when {
            next <= _queue.lastIndex -> next
            _repeat == RepeatMode.ALL -> 0
            else -> null
        }
    }

    fun computePrev(): Int? {
        if (_queue.isEmpty()) return null
        ensureValidOrder()
        if (_shuffle && _order != null) {
            val order = _order!!
            val prevPos = _orderPos - 1
            return if (prevPos >= 0) order[prevPos]
            else if (_repeat == RepeatMode.ALL) order.last()
            else null
        }
        val prev = _index - 1
        return if (prev >= 0) prev else if (_repeat == RepeatMode.ALL) _queue.lastIndex else null
    }

    fun advanceTo(nextIndex: Int) {
        _index = nextIndex
        _orderPos = _order?.indexOf(nextIndex) ?: nextIndex
    }

    fun resolveQueueInBackground(
        startFrom: Int,
        skipStartEntry: Boolean = false,
        onCurrentTrackUpdated: (TrackSummary) -> Unit,
        onQueueStructureChanged: () -> Unit,
    ) {
        resolveJob?.cancel()
        val gen = ++queueGeneration

        val toResolveIndices = _queue.indices
            .filter { !skipStartEntry || it != startFrom }
            .sortedBy { if (it == startFrom) 0 else 1 + kotlin.math.abs(it - startFrom) }
        val toResolveEntries = toResolveIndices.filter { _queue.getOrNull(it)?.summary == null }
        if (toResolveEntries.isEmpty()) return

        resolveJob = scope.launch {
            var localChanged = false
            for (idx in toResolveEntries) {
                val entry = _queue.getOrNull(idx) ?: continue
                if (entry.summary == null) {
                    val downloaded = downloadedTrackResolver(entry.mediaId)
                    if (downloaded != null) {
                        entry.summary = downloaded
                        if (idx == _index) {
                            onCurrentTrackUpdated(downloaded)
                        }
                        localChanged = true
                    }
                }
            }
            if (localChanged) onQueueStructureChanged()
            val remainingEntries = toResolveEntries.filter { _queue.getOrNull(it)?.summary == null }
            if (remainingEntries.isEmpty()) return@launch

            for (chunk in remainingEntries.chunked(50)) {
                if (queueGeneration != gen) return@launch
                val mediaIds = chunk.mapNotNull { _queue.getOrNull(it)?.mediaId }
                if (mediaIds.isEmpty()) continue
                val result = (source.getTrackDetails(mediaIds) as? cp.player.core.BackendResult.Success)?.data ?: emptyList()
                val map = result.associateBy { it.id }
                if (queueGeneration != gen) return@launch

                var changed = false
                for (idx in _queue.indices) {
                    val entry = _queue[idx]
                    val summary = map[entry.mediaId]
                    if (summary != null && entry.summary == null) {
                        entry.summary = summary
                        if (idx == _index) {
                            onCurrentTrackUpdated(summary)
                        }
                        changed = true
                    }
                }
                if (changed) onQueueStructureChanged()
            }
        }
    }

    suspend fun resolveEntry(
        index: Int,
        onCurrentTrackUpdated: (TrackSummary) -> Unit,
        onQueueStructureChanged: () -> Unit,
    ): BackendResult<TrackSummary> {
        val entry = _queue.getOrNull(index)
            ?: return BackendResult.Error("Queue entry no longer exists")
        entry.summary?.let { return BackendResult.Success(it) }
        val downloaded = downloadedTrackResolver(entry.mediaId)
        val result = downloaded?.let { BackendResult.Success(it) } ?: source.getTrackDetail(entry.mediaId)
        val summary = (result as? BackendResult.Success)?.data
        if (summary != null) {
            entry.summary = summary
            if (index == _index) {
                onCurrentTrackUpdated(summary)
            }
            onQueueStructureChanged()
            return BackendResult.Success(summary)
        }
        // A concurrent batch resolver may have filled this entry while the single request was in flight.
        entry.summary?.let { return BackendResult.Success(it) }
        return result
    }

    fun cancel() {
        resolveJob?.cancel()
        queueGeneration++
    }

    private fun shuffledOrder(size: Int): List<Int> {
        return (0 until size).shuffled(Random(System.nanoTime()))
    }

    private fun dedupeQueueInput(mediaIds: List<String>, startIndex: Int): Pair<List<String>, Int> {
        val canonical = mediaIds.map { CPMediaId.toCanonicalMediaId(it) }
        val unique = canonical.distinct()
        if (unique.size == canonical.size) return canonical to startIndex
        val selected = canonical.getOrNull(startIndex.coerceIn(0, canonical.lastIndex))
        val remapped = selected?.let { unique.indexOf(it) } ?: -1
        val start = if (remapped >= 0) remapped else startIndex.coerceIn(0, unique.lastIndex)
        return unique to start
    }

    private fun orderIsValid(order: List<Int>): Boolean {
        if (order.size != _queue.size) return false
        val seen = BooleanArray(_queue.size)
        for (i in order) {
            if (i !in _queue.indices || seen[i]) return false
            seen[i] = true
        }
        return true
    }

    private fun ensureValidOrder() {
        if (!_shuffle || _queue.isEmpty()) {
            _order = null
            _orderPos = _index
            return
        }
        val order = _order
        if (order == null || !orderIsValid(order)) {
            _order = shuffledOrder(_queue.size)
        }
        _orderPos = _order!!.indexOf(_index)
    }
}
