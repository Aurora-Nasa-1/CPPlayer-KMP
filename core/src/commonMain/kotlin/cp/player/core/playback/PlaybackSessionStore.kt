package cp.player.core.playback

import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 播放会话与播放模式持久化存储协同组件。
 *
 * 职责：
 * 1. 播放模式（[RepeatMode] 与随机开关）的持久化与读取回显。
 * 2. 上次播放会话（队列 mediaId 列表、当前选中下标、音源 sourceId、进度 positionMs）的
 *    JSON 编码与解码。
 * 3. 串行化派发到 [Dispatchers.IO] 的写盘，避免在主线程/EDT 进行全量设置回写引发丢帧。
 */
internal class PlaybackSessionStore(
    private val settings: SettingsStorage?,
    private val scope: CoroutineScope,
) {
    private val persistMutex = Mutex()

    /**
     * 「上次播放」快照的内存形态（[PlaybackSessionSettings.KEY_LAST_SESSION] 的解析结果）。
     */
    data class Session(
        val ids: List<String>,
        val index: Int,
        val sourceId: String?,
        val positionMs: Long,
    )

    /** 开关是否开启。无存储（最小装配路径 / 测试）时恒为 false，保持零存储副作用。 */
    fun isKeepLastPlaybackEnabled(): Boolean =
        settings?.let { PlaybackSessionSettings.keepLastPlayback(it) } ?: false

    /** 构造期把持久化的播放模式读回；缺失或非法键保持 null。 */
    fun restorePlaybackModes(): Pair<RepeatMode?, Boolean?> {
        val storage = settings ?: return null to null
        return runCatching {
            val repeat = storage.getString(KEY_REPEAT_MODE)
                ?.let { raw -> RepeatMode.entries.firstOrNull { it.name == raw } }
            val shuffle = storage.getString(KEY_SHUFFLE_ENABLED)
                ?.toBooleanStrictOrNull()
            repeat to shuffle
        }.getOrDefault(null to null)
    }

    /** 变更后立即落盘；存储失败静默（持久化不影响播放）。 */
    fun persistPlaybackModes(repeat: RepeatMode, shuffle: Boolean) {
        val storage = settings ?: return
        runCatching {
            storage.putString(KEY_REPEAT_MODE, repeat.name)
            storage.putString(KEY_SHUFFLE_ENABLED, shuffle.toString())
        }
    }

    /** 读取并解析保存的上次会话快照；无存储、未开启开关或快照损坏时返回 null。 */
    fun readSession(): Session? {
        val storage = settings ?: return null
        if (!isKeepLastPlaybackEnabled()) return null
        val raw = storage.getString(PlaybackSessionSettings.KEY_LAST_SESSION) ?: return null
        return decodeSession(raw)
    }

    /**
     * 把当前会话落盘；失败静默。
     *
     * 队列为空 / 无当前曲目时**删除**快照 —— 「清空队列」也是一种用户意图，
     * 下次启动不该把已清掉的队列又摆回来。开关关闭时同样删除，避免关掉后
     * 旧快照一直躺着、用户再打开开关时恢复到一份早已过期的队列。
     *
     * 快照决定在调用线程同步做完（极廉价，且语义是「此刻」），实际写盘派发到 IO，
     * 并在调用点立即按序进入共享 Mutex 排队。
     */
    fun persistSession(snapshotSupplier: () -> Session?) {
        val storage = settings ?: return
        val remove = !isKeepLastPlaybackEnabled()
        val snapshot = if (remove) null else snapshotSupplier()
        val encoded = if (snapshot == null) null else encodeSession(snapshot)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writeSessionInOrder(storage, remove || encoded == null, encoded)
        }
    }

    /**
     * [persistSession] 的同步版本：只在 [PlaybackController.release] 用。
     * 释放是最后一次写快照的机会，异步派发到 IO 可能赶不上 backendScope 随后的取消。
     * 锁在 IO 写入完成时释放，不能等恢复到可能正被 runBlocking 阻塞的 UI/EDT dispatcher。
     */
    fun persistSessionBlocking(snapshotSupplier: () -> Session?) {
        val storage = settings ?: return
        val remove = !isKeepLastPlaybackEnabled()
        val snapshot = if (remove) null else snapshotSupplier()
        val encoded = if (snapshot == null) null else encodeSession(snapshot)
        runBlocking {
            writeSessionInOrder(storage, remove || encoded == null, encoded)
        }
    }

    /**
     * 先在调用顺序上取得锁，再在 IO context 中写入并释放锁。
     * 若在 IO 写完后才切回 scope dispatcher 解锁，release() 从该单线程 dispatcher
     * 调用时会因 runBlocking 阻塞它而与正在等待恢复的协程互锁。
     */
    private suspend fun writeSessionInOrder(storage: SettingsStorage, remove: Boolean, encoded: String?) {
        persistMutex.lock()
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            try {
                writeSession(storage, remove, encoded)
            } finally {
                persistMutex.unlock()
            }
        }
    }

    private fun writeSession(storage: SettingsStorage, remove: Boolean, encoded: String?) {
        runCatching {
            if (remove || encoded == null) {
                storage.remove(PlaybackSessionSettings.KEY_LAST_SESSION)
            } else {
                storage.putString(PlaybackSessionSettings.KEY_LAST_SESSION, encoded)
            }
        }
    }

    /** 编码为 JSON。 */
    fun encodeSession(session: Session): String {
        val obj = JsonObject(
            mapOf(
                "sourceId" to (session.sourceId?.let { JsonPrimitive(it) } ?: JsonNull),
                "index" to JsonPrimitive(session.index),
                "positionMs" to JsonPrimitive(session.positionMs),
                "ids" to JsonArray(session.ids.map { JsonPrimitive(it) }),
            ),
        )
        return obj.toString()
    }

    /** 解析 [encodeSession] 写出的 JSON；任何不合法内容都返回 null（宁可恢复失败，不要崩）。 */
    fun decodeSession(raw: String): Session? {
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val ids = (obj["ids"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (ids.isEmpty()) return null
        val index = (obj["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val positionMs = (obj["positionMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        val sourceId = (obj["sourceId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return Session(
            ids = ids,
            index = index.coerceIn(0, ids.lastIndex),
            sourceId = sourceId,
            positionMs = positionMs.coerceAtLeast(0L),
        )
    }

    companion object {
        /** 持久化键：循环模式（[RepeatMode] 枚举名）。 */
        const val KEY_REPEAT_MODE = "playback_repeat_mode"

        /** 持久化键：随机播放开关。 */
        const val KEY_SHUFFLE_ENABLED = "playback_shuffle_enabled"

        /** 播放期间每 N 秒刷新一次「保留上次播放」快照的进度。 */
        const val SESSION_SAVE_INTERVAL_S = 5
    }
}
