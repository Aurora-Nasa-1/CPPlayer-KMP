package cp.player.core.integration

import cp.player.core.BackendResult
import cp.player.core.control.LocalServerConfig
import cp.player.core.control.resolveAdvertisedHost
import cp.player.core.music.ArtistSummary
import cp.player.core.music.CPMediaId
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.SearchResult
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.playback.PlaybackUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * 对外契约的**用例层**。
 *
 * ### 边界纪律（由 `IntegrationBoundaryTest` 扫源码钉住）
 * 本文件**只能**依赖领域模型：[UnifiedMusicSource] / [PlaybackUiState] / [LocalServerConfig]。
 * **禁止**依赖 `MusicApiService`、`provider.*`、`cache.*`。
 * 理由：能拿到 raw JSON 时，最省事的写法就是透传 —— 一旦透传，对外契约就被绑死在
 * 单个音源实现上，而音源实现是会换的。
 *
 * ### 线程约定
 * `MusicBackend.backendScope` 跑在 `Dispatchers.Main`（Android 主线程 / 桌面 EDT），
 * 队列与播放状态在它上面串行读写 —— 所以：
 * - **读** `StateFlow.value` / 调 `suspend` 的 [UnifiedMusicSource]：安全。
 * - **写**（[playbackAction]）：**必须**回到控制线程，否则会与 UI 抢状态，
 *   症状是偶发跳歌 / 队列错乱且**难以复现**。
 *
 * 写路径的落地方式是 `withContext(controlDispatcher) { … }`：
 * 用 `withContext` 而不是 `launch`，是因为 HTTP 响应需要**等动作完成**后再返回
 * （`launch` 会让响应先于动作返回，集成方拿到的状态快照就是旧的）。
 * 用 `withContext(dispatcher)` 而不是 `withContext(backendScope.coroutineContext)`：
 * 后者会把请求协程的 Job 换掉，破坏结构化并发。
 */
class IntegrationService(
    private val source: () -> UnifiedMusicSource,
    private val playbackState: () -> StateFlow<PlaybackUiState>,
    private val playbackControl: () -> IntegrationPlaybackControl,
    private val controlDispatcher: CoroutineDispatcher,
    private val availableProviders: () -> List<IntegrationProviderInfo>,
    private val activeProviderId: () -> String?,
    private val loggedIn: () -> Boolean,
    private val config: () -> LocalServerConfig,
) {

    companion object {
        /**
         * 上游没有返回某一项时的措辞。
         *
         * **刻意不说「不存在」**：领域层把「不存在」与「取数失败」都收敛成 [BackendResult.Error]，
         * 用例层无从区分。假装知道是「不存在」等于编造结论。
         */
        const val UPSTREAM_NOT_RETURNED = "上游未返回该项"
    }

    // ============ 只读：meta / providers ============

    fun meta(): MetaDto {
        val cfg = config()
        val activeId = activeProviderId()
        val active = availableProviders().firstOrNull { it.id == activeId }

        return MetaDto(
            app = INTEGRATION_APP_NAME,
            apiVersion = INTEGRATION_API_VERSION,
            capabilities = IntegrationCapabilities.of(cfg),
            provider = active?.let { ProviderRefDto(id = it.id, name = it.name, version = it.version) },
            loggedIn = loggedIn(),
        )
    }

    fun providers(): ProvidersDto {
        val activeId = activeProviderId()
        return ProvidersDto(
            providers = availableProviders().map {
                ProviderDto(
                    id = it.id,
                    name = it.name,
                    version = it.version,
                    type = it.type,
                    active = it.id == activeId,
                )
            },
        )
    }

    // ============ 只读：search ============

    /**
     * 搜索。
     *
     * `limit` 是**本机截断**而不是上游分页：`UnifiedMusicSource.search` 没有分页参数，
     * 所以「截断」只能发生在这里。文档必须如实说明，否则集成方会以为拿到了全部结果。
     */
    suspend fun search(request: SearchRequestDto): IntegrationResult<SearchResponseDto> {
        if (request.keywords.isBlank()) {
            return IntegrationResult.Failure(FailureKind.BAD_REQUEST, "keywords 不能为空")
        }
        val type = request.type ?: SEARCH_TYPE_SONG
        if (type !in SUPPORTED_SEARCH_TYPES) {
            return IntegrationResult.Failure(FailureKind.BAD_REQUEST, "不支持的搜索类型：type=$type")
        }
        val limit = request.limit
        if (limit != null && limit <= 0) {
            return IntegrationResult.Failure(FailureKind.BAD_REQUEST, "limit 必须为正数，实际 $limit")
        }

        val streamFor = streamUrlResolver()

        return when (val result = source().search(request.keywords, type, null)) {
            is BackendResult.Success -> {
                val data = result.data
                IntegrationResult.Ok(
                    SearchResponseDto(
                        songs = data.songs.truncated(limit).map { it.toDto(streamFor) },
                        playlists = data.playlists.truncated(limit).map { it.toDto() },
                        artists = data.artists.truncated(limit).map { it.toDto() },
                    ),
                )
            }

            is BackendResult.Unsupported ->
                IntegrationResult.Failure(FailureKind.UNSUPPORTED, result.message)

            is BackendResult.Error ->
                IntegrationResult.Failure(FailureKind.UPSTREAM_FAILED, result.message)
        }
    }

    // ============ 只读：track ============

    suspend fun track(mediaId: String): IntegrationResult<TrackDto> =
        trackWith(mediaId, streamUrlResolver())

    private suspend fun trackWith(
        mediaId: String,
        streamFor: (String) -> String,
    ): IntegrationResult<TrackDto> {
        validateMediaId(mediaId)?.let { return it }

        return when (val result = source().getTrackDetail(mediaId)) {
            is BackendResult.Success ->
                IntegrationResult.Ok(result.data.toDto(streamFor))

            is BackendResult.Unsupported ->
                IntegrationResult.Failure(FailureKind.UNSUPPORTED, result.message)

            is BackendResult.Error ->
                IntegrationResult.Failure(FailureKind.UPSTREAM_FAILED, result.message)
        }
    }

    // ============ 只读：tracks（批量） ============

    /**
     * 批量曲目详情。
     *
     * 三条语义，都是**避免把批量端点变成放大器**所必需的：
     * 1. **去重**：重复 id 只向上游请求一次；
     * 2. **单项失败不整体失败**：响应仍是 200，失败项各自带 error；
     * 3. **顺序按请求**：集成方按位置取值，不能按上游返回顺序。
     */
    suspend fun tracks(mediaIds: List<String>): IntegrationResult<List<TrackBatchItemDto>> {
        if (mediaIds.isEmpty()) {
            return IntegrationResult.Failure(FailureKind.BAD_REQUEST, "mediaIds 不能为空")
        }
        if (mediaIds.size > MAX_TRACK_BATCH_SIZE) {
            return IntegrationResult.Failure(
                FailureKind.BAD_REQUEST,
                "单次最多 $MAX_TRACK_BATCH_SIZE 项，实际 ${mediaIds.size}",
            )
        }

        val streamFor = streamUrlResolver()

        // 先本机判死格式，合法的才送去上游；全部畸形时一次上游请求都不发
        val invalid = HashMap<String, String>()
        val validCanonical = LinkedHashMap<String, String>()
        mediaIds.forEach { raw ->
            val kind = validateMediaId(raw)
            if (kind != null) invalid[raw] = kind.message
            else validCanonical.putIfAbsent(raw, raw)
        }

        // 上游返回的是 TrackSummary，其 id 未必等于请求里的 id（大小写/规范化差异），
        // 所以按 id 建索引而不是按位置 —— 位置会因为缺失而错位。
        val found = if (validCanonical.isEmpty()) {
            emptyMap()
        } else {
            when (val result = source().getTrackDetails(validCanonical.keys.toList())) {
                is BackendResult.Success -> result.data
                    .filter { it.id.isNotBlank() }
                    .associateBy { it.id }

                // 能力缺失是**整批**的结论：换音源才有救，逐项报错只会让集成方
                // 把「这个音源做不到」误读成「这 N 首歌各自取不到」。
                is BackendResult.Unsupported -> return IntegrationResult.Failure(
                    FailureKind.UNSUPPORTED,
                    result.message,
                )

                // 临时故障则**逐项**报「上游未返回」：单项失败不整体失败，
                // 集成方仍能拿到同一批里取到的那些。
                is BackendResult.Error -> emptyMap()
            }
        }

        return IntegrationResult.Ok(
            mediaIds.map { raw ->
                invalid[raw]?.let { message ->
                    return@map TrackBatchItemDto(mediaId = raw, track = null, error = message)
                }
                val track = found[raw]
                if (track == null) {
                    TrackBatchItemDto(mediaId = raw, track = null, error = UPSTREAM_NOT_RETURNED)
                } else {
                    TrackBatchItemDto(mediaId = raw, track = track.toDto(streamFor), error = null)
                }
            },
        )
    }

    // ============ 只读：playback ============

    fun playback(): PlaybackDto = playbackDto(playbackState().value)

    /** 供事件流复用：把任意状态快照转成 DTO（不读当前值）。 */
    fun playbackDto(state: PlaybackUiState): PlaybackDto = state.toDto()

    fun playbackStates(): StateFlow<PlaybackUiState> = playbackState()

    // ============ 写：播控 ============

    /**
     * 播控写操作。
     *
     * 两道开关：`exposeDataApi` 由路由层闸门拦掉；`allowRemoteControl` 是**数据面内部**
     * 的第二道开关，只能在这里判 —— 闸门表达不了「面开了但能力没开」。
     */
    suspend fun playbackAction(action: String): IntegrationResult<PlaybackDto> {
        if (!config().allowRemoteControl) {
            return IntegrationResult.Failure(
                FailureKind.FACE_DISABLED,
                "未开放远程播控（allowRemoteControl=false）。集成方只能读，不能改播放状态。",
            )
        }

        val perform: suspend (IntegrationPlaybackControl) -> Unit = when (action) {
            PlaybackAction.PLAY -> { control -> control.play() }
            PlaybackAction.PAUSE -> { control -> control.pause() }
            PlaybackAction.NEXT -> { control -> control.next() }
            PlaybackAction.PREVIOUS -> { control -> control.previous() }
            else -> {
                val hint = PlaybackAction.DELIBERATELY_ABSENT[action]
                    ?: "不支持的动作：$action。可用动作：${PlaybackAction.SUPPORTED.joinToString(", ")}"
                return IntegrationResult.Failure(FailureKind.BAD_REQUEST, hint)
            }
        }

        val control = playbackControl()
        withContext(controlDispatcher) { perform(control) }

        // 返回**动作之后**的快照：服务端等动作执行完才返回，集成方不必再打一次 /playback
        return IntegrationResult.Ok(playback())
    }

    // ============ 内部 ============

    /**
     * 本机流地址解析器。
     *
     * **绝不能**下发上游签名 URL 或 cookie —— 只给本机 `/stream`，由服务端负责带上
     * 凭据去上游取字节。这是「响应里永远不会出现上游凭据」这条纪律的落点。
     */
    private fun streamUrlResolver(): (String) -> String {
        val cfg = config()
        // 绑定 0.0.0.0 时要广播成**真实网卡地址**：0.0.0.0 不是可连接地址，
        // 把它下发下去，集成方拿到的是一个自己连不上的 URL。
        val host = resolveAdvertisedHost(cfg.bindAddress)
        return { mediaId -> cfg.streamUrlFor(mediaId, host) }
    }

    /** @return 非法时返回失败结果，合法时返回 null。 */
    private fun validateMediaId(mediaId: String): IntegrationResult.Failure? {
        if (mediaId.isBlank()) {
            return IntegrationResult.Failure(FailureKind.BAD_REQUEST, "mediaId 不能为空")
        }
        if (!isValidMediaId(mediaId)) {
            return IntegrationResult.Failure(FailureKind.BAD_REQUEST, "mediaId 格式非法：$mediaId")
        }
        return null
    }

    /**
     * mediaId 是否是 `{providerId}://{resourceType}/{resourceId}`。
     *
     * 判据直接复用 [CPMediaId.parse]：**不在这里另写一份正则**。
     * 两份格式判定必然漂移，症状是「路由层放行的 id，下游 parse 时抛异常」——
     * 而这种异常会在最意想不到的地方（播放内核）冒出来。
     */
    private fun isValidMediaId(mediaId: String): Boolean {
        val parsed = runCatching { CPMediaId.parse(mediaId) }.getOrNull() ?: return false
        return parsed.providerId.isNotBlank() && parsed.resourceId.isNotBlank()
    }

    private fun <T> List<T>.truncated(limit: Int?): List<T> =
        if (limit == null || limit >= size) this else take(limit)

    private fun PlaybackUiState.toDto(): PlaybackDto = PlaybackDto(
        isPlaying = isPlaying,
        positionMs = positionMs,
        currentIndex = currentIndex,
        qualityLevel = qualityLevel,
        currentTrack = currentTrack?.toDto(streamUrlResolver()),
        queueLength = queue.size,
    )

    private fun TrackSummary.toDto(streamFor: (String) -> String): TrackDto = TrackDto(
        mediaId = id,
        name = name,
        artist = artist,
        album = album,
        coverUrl = coverUrl,
        durationMs = durationMs,
        // 没有可播 id 就别给地址：给一个必然 404 的地址比给 null 更糟
        streamUrl = id.takeIf { it.isNotBlank() }?.let(streamFor),
    )

    private fun PlaylistSummary.toDto(): PlaylistDto = PlaylistDto(
        id = id,
        name = name,
        coverUrl = coverUrl,
        trackCount = trackCount,
        creatorName = creatorName,
    )

    private fun ArtistSummary.toDto(): ArtistDto = ArtistDto(
        id = id,
        name = name,
        avatarUrl = avatarUrl,
    )
}

/**
 * 已加载音源（对外视图）。
 *
 * @property type 实现类型（`JNI` / `BINARY` / `WEBSOCKET` / `HTTP`）。
 */
data class IntegrationProviderInfo(
    val id: String,
    val name: String,
    val version: String,
    val type: String,
)

// ============ 结果类型 ============

/**
 * 用例层结果。
 *
 * 用 sealed 而不是抛异常：**每一种失败都对应一种 HTTP 处置**，
 * 让它们在类型里显式可见，调用方就漏不掉分支。
 */
sealed interface IntegrationResult<out T> {
    data class Ok<out T>(val value: T) : IntegrationResult<T>

    data class Failure(val kind: FailureKind, val message: String) : IntegrationResult<Nothing>
}

/**
 * 失败分类 → HTTP 状态码 + 对外错误码 的**唯一映射表**。
 *
 * 放在 `commonMain` 而不是路由层：`ktor-server-*` 只在 `jvmMain`，
 * 路由层没有 Ktor 类型可用；反过来把状态码写死在路由层，这张表就没法单测了。
 */
enum class FailureKind(val httpStatus: Int, val errorCode: String) {
    /** 客户端参数错误。**本机**就能判死，不该去碰上游。 */
    BAD_REQUEST(400, ApiErrorCodes.BAD_REQUEST),

    /** 令牌缺失或不匹配。 */
    UNAUTHORIZED(401, ApiErrorCodes.UNAUTHORIZED),

    /**
     * 端点存在但**当前配置下不可用**（开关关着）。
     *
     * 与 [NOT_FOUND] 的区别：这个是「引导用户去开开关」，那个是「还没实现，等升级」。
     */
    FACE_DISABLED(403, ApiErrorCodes.FACE_DISABLED),

    /**
     * 资源不存在。
     *
     * ⚠️ **当前无产出路径**：领域层把「不存在」与「取数失败」收敛成同一种
     * [BackendResult.Error]，用例层无从区分，所以实际表现是 [UPSTREAM_FAILED]。
     * Phase 4 补齐领域层的错误分类后启用。
     */
    NOT_FOUND(404, ApiErrorCodes.NOT_FOUND),

    /** 音源**能力缺失**（换音源），不是临时故障。 */
    UNSUPPORTED(501, ApiErrorCodes.UNSUPPORTED),

    /** 上游临时故障（可重试）。 */
    UPSTREAM_FAILED(502, ApiErrorCodes.UPSTREAM_FAILED),

    /** 未预期的内部错误。 */
    INTERNAL(500, ApiErrorCodes.INTERNAL),
    ;

    override fun toString(): String = "${name}(http=$httpStatus, code=$errorCode)"
}
