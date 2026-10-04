package cp.player.core.listentogether

import cp.player.core.BackendResult
import cp.player.core.api.ApiResponseCodes
import cp.player.core.api.MusicApiMethod
import cp.player.core.api.MusicApiService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * 「一起听」的网易云实现 —— 经 [MusicApiService] 调 `listentogether/...` 系列端点。
 *
 * ### 与 Provider 的关系
 * 所有调用都经 `MusicApiService → ProviderManager → BackendProvider`，
 * 因此**能力映射是免费的**：其它音源只要在 `manifest.json` 的 `apiMap` 里把
 * `listentogether/...` 标成 `"unsupported"`，这里就会收到 `code = -1`，
 * 自动进入 [isSupported] = false 的路径，UI 据此置灰入口。
 *
 * 这就是为什么**不硬编码音源 id 白名单**：白名单会在音源改名 / 新增时静默失效，
 * 而 `apiMap` 是音源自己声明的、单一事实来源。
 *
 * ### 关于响应里的 `NMTID`
 * 实测每个响应都带回一个新的 `NMTID` cookie。本实现**不持久化**它：
 * 鉴权靠的是 cookie 里的 `MUSIC_U`，`NMTID` 是网易云的匿名跟踪 id，
 * 不参与鉴权。刻意不写回，是为了避免在「谁该拥有 cookie 写权限」这件事上
 * 制造第二处写点（cookie 归属 `ProviderCookieStorage`）。
 */
class NeteaseListenTogetherBackend(
    private val api: MusicApiService,
) : ListenTogetherBackend {

    override val providerId: String = NETEASE_PROVIDER_ID

    /**
     * 能力探测结果。
     *
     * 初值为 true（在被证否之前先当作支持），一旦任何一次调用收到
     * `code = -1`（`apiMap` 映射为 `"unsupported"` 时 ProviderManager 的返回）就永久置否。
     * **不做预检请求**：多打一次网络只为问「你支持吗」不值得，
     * 而且预检成功也挡不住后续失败（能力可能随音源版本变化）。
     */
    private var unsupported: Boolean = false

    override fun isSupported(): Boolean = !unsupported

    override suspend fun membership(): BackendResult<ListenTogetherMembership> =
        call(MusicApiMethod.LISTEN_TOGETHER_STATUS).map(::parseMembership)

    override suspend fun createRoom(): BackendResult<ListenTogetherRoom> =
        call(MusicApiMethod.LISTEN_TOGETHER_ROOM_CREATE)
            .map { root ->
                val info = root.jsonObject["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?.obj("roomInfo")
                    ?: return@map null
                parseRoom(info)
            }
            .flatMapMissing("建房响应里没有 roomInfo")

    override suspend fun accept(roomId: String, inviterId: Long): BackendResult<Unit> =
        call(
            MusicApiMethod.LISTEN_TOGETHER_ACCEPT,
            mapOf("roomId" to roomId, "inviterId" to inviterId.toString()),
        ).map { }

    override suspend fun end(roomId: String): BackendResult<Unit> =
        call(MusicApiMethod.LISTEN_TOGETHER_END, mapOf("roomId" to roomId)).map { }

    override suspend fun snapshot(roomId: String): BackendResult<RoomSnapshot> =
        call(MusicApiMethod.LISTEN_TOGETHER_SYNC_PLAYLIST, mapOf("roomId" to roomId))
            .map(::parseSnapshot)

    override suspend fun heartbeat(
        roomId: String,
        songId: String,
        playStatus: String,
        progressMs: Long,
    ): BackendResult<Unit> = call(
        MusicApiMethod.LISTEN_TOGETHER_HEARTBEAT,
        mapOf(
            "roomId" to roomId,
            "songId" to songId,
            "playStatus" to playStatus,
            // 实测回读是数字，这里发**字符串**也能被接受（上游自己解析）
            "progress" to progressMs.toString(),
        ),
    ).map { }

    override suspend fun reportPlayCommand(
        roomId: String,
        commandType: String,
        playStatus: String,
        progressMs: Long,
        formerSongId: String?,
        targetSongId: String?,
        clientSeq: Long,
    ): BackendResult<Unit> = call(
        MusicApiMethod.LISTEN_TOGETHER_PLAY_COMMAND,
        mapOf(
            "roomId" to roomId,
            "commandType" to commandType,
            "playStatus" to playStatus,
            "progress" to progressMs.toString(),
            "formerSongId" to formerSongId.orEmpty(),
            "targetSongId" to targetSongId.orEmpty(),
            "clientSeq" to clientSeq.toString(),
        ),
    ).map { }

    override suspend fun reportPlaylist(
        roomId: String,
        userId: Long,
        version: Long,
        songIds: List<String>,
    ): BackendResult<Unit> {
        // 上游把 randomList / displayList 按逗号切分（`split(',')`），
        // 所以这里必须传**逗号串**而不是 JSON 数组 —— 传数组会变成一整条 id。
        val joined = songIds.joinToString(",")
        return call(
            MusicApiMethod.LISTEN_TOGETHER_SYNC_LIST,
            mapOf(
                "roomId" to roomId,
                "commandType" to "REPLACE",
                "userId" to userId.toString(),
                "version" to version.toString(),
                "randomList" to joined,
                "displayList" to joined,
            ),
        ).map { }
    }

    // ======================== 内部 ========================

    /**
     * 统一调用与错误归一。
     *
     * 三条纪律：
     * 1. **网络异常不抛出** —— 收敛成 [BackendResult.Error]，同步引擎靠返回值分支，
     *    不让异常穿透到轮询循环里（一次抖动不该终结整个会话）；
     * 2. **`code = -1` 单独识别** —— 那是 `apiMap = "unsupported"` 的信号，
     *    不是故障，必须走 [BackendResult.Unsupported]；
     * 3. **上游失败时把 `code` 原样带出** —— 实测 `accept` 对坏 roomId 返回
     *    `API error (code=488)`，丢掉 code 就没法区分「参数错」与「网络错」。
     */
    private suspend fun call(
        method: String,
        params: Map<String, String> = emptyMap(),
    ): BackendResult<JsonElement> {
        if (unsupported) return BackendResult.Unsupported(UNSUPPORTED_MESSAGE)

        val root = try {
            api.callApi(method, params)
        } catch (e: Throwable) {
            return BackendResult.Error("一起听请求失败：${e.message}", cause = e)
        }

        val obj = runCatching { root.jsonObject }.getOrNull()
            ?: return BackendResult.Error("一起听响应不是 JSON 对象")

        val code = obj.long("code")?.toInt()
        if (code == UNSUPPORTED_CODE) {
            unsupported = true
            return BackendResult.Unsupported(UNSUPPORTED_MESSAGE)
        }
        if (code != null && !ApiResponseCodes.isSuccess(code)) {
            val msg = obj.str("msg").orEmpty().ifEmpty { obj.str("message").orEmpty() }
            return BackendResult.Error(
                message = msg.ifEmpty { "一起听接口失败（code=$code）" },
                code = code,
            )
        }
        return BackendResult.Success(root)
    }

    private inline fun <T, R> BackendResult<T>.map(transform: (T) -> R): BackendResult<R> =
        when (this) {
            is BackendResult.Success -> BackendResult.Success(transform(data))
            is BackendResult.Error -> this
            is BackendResult.Unsupported -> this
        }

    /**
     * 解析出 null 时转成 Error。
     *
     * 把「响应结构变了」与「操作失败」分开：前者是**契约漂移**，
     * 静默当成失败会让排查时找不到真正的原因（上游改了字段名，
     * 表现却是「建房总是失败」）。
     */
    private fun <T> BackendResult<T?>.flatMapMissing(what: String): BackendResult<T> =
        when (this) {
            is BackendResult.Success ->
                data?.let { BackendResult.Success(it) } ?: BackendResult.Error(what)
            is BackendResult.Error -> this
            is BackendResult.Unsupported -> this
        }

    companion object {
        /** 内置网易云模块的 id。仅用于本类的自我标识，**不用于能力白名单**。 */
        const val NETEASE_PROVIDER_ID = "cp_api"

        /** `ProviderManager` 在 `apiMap` 标 `"unsupported"` 时返回的 code。 */
        private const val UNSUPPORTED_CODE = -1

        const val UNSUPPORTED_MESSAGE = "当前音源不支持一起听"
    }
}
