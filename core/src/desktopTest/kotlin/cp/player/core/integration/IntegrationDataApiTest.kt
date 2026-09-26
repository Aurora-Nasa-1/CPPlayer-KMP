package cp.player.core.integration

import cp.player.core.BackendResult
import cp.player.core.control.LocalServer
import cp.player.core.control.LocalServerConfig
import cp.player.core.control.StreamTarget
import cp.player.core.control.createLocalServer
import cp.player.core.music.MusicResult
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.SearchResult
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.QueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.HttpURLConnection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 数据面的端到端冒烟测试：**真的起一个 Ktor 服务**，用 HTTP 打进去。
 *
 * 单元测试（[IntegrationRoutingTest] / [IntegrationServiceTest]）钉的是规则与映射，
 * 这里钉的是「规则确实接到了引擎上」，以及两条最容易写错的性质：
 *
 * 1. 路由级开关（`exposeDataApi` / `exposeStream`）改完**立刻生效**，不需要重启监听端口
 *    —— 如果哪天有人把配置改成构造期捕获，
 *    `开关改完立刻生效且不需要重启端口` 会立刻失败。
 * 2. 媒体面与数据面**互不影响**：关掉一个不会连带关掉另一个。
 */
class IntegrationDataApiTest {

    private var server: LocalServer? = null

    /**
     * 服务运行期间被读取的配置。
     *
     * 测试就地改它来模拟「用户在设置页拨开关」——服务不重建、端口不重绑。
     */
    @Volatile
    private var liveConfig: LocalServerConfig = LocalServerConfig()

    /**
     * 播放状态流。
     *
     * 事件流（SSE）测试就地改它来触发推送 —— 与 [liveConfig] 同一个套路：
     * 服务不重建，只有**数据**变了。
     */
    private val livePlayback = MutableStateFlow(PlaybackUiState())

    /**
     * 收到的播控动作，按调用顺序。
     *
     * 用列表而不是计数器：播控动作**顺序**有意义（连点两次 next 与点一次不同），
     * 而「多调了一次」这类 bug 只有看到完整序列才能定位。
     */
    private val controlCalls = mutableListOf<String>()

    private val recordingControl = object : IntegrationPlaybackControl {
        override suspend fun play() = record(PlaybackAction.PLAY) {
            livePlayback.value = livePlayback.value.copy(isPlaying = true)
        }

        override suspend fun pause() = record(PlaybackAction.PAUSE) {
            livePlayback.value = livePlayback.value.copy(isPlaying = false)
        }

        override suspend fun next() = record(PlaybackAction.NEXT) { }

        override suspend fun previous() = record(PlaybackAction.PREVIOUS) { }

        /**
         * 记录 + 就地改状态。
         *
         * 改状态不是为了模拟播放器，而是为了让「响应体是**动作之后**的快照」
         * 这条性质在 HTTP 层可断言 —— 只记录的话，动作前后状态一样，断言不出区别。
         */
        private fun record(action: String, apply: () -> Unit) {
            controlCalls += action
            apply()
        }
    }

    @AfterTest
    fun tearDown() {
        server?.stop()
    }

    // ============ 默认关闭 ============

    @Test
    fun `数据面未开放时返回 403 且不影响媒体面`() {
        val port = start(exposeDataApi = false)

        val meta = get("http://127.0.0.1:$port${IntegrationRoutes.META}?token=secret")
        assertEquals(403, meta.code, "数据面默认关闭：应 403 而不是 200")
        assertTrue(meta.body.contains("\"code\":\"face_disabled\""), "错误码应为 face_disabled: ${meta.body}")

        val providers = get("http://127.0.0.1:$port${IntegrationRoutes.PROVIDERS}?token=secret")
        assertEquals(403, providers.code, "providers 与 meta 受同一开关控制")

        // 两面独立：数据面关着，媒体面照旧。上游是死地址，所以这里期望 502 而不是 403。
        val stream = get("http://127.0.0.1:$port/stream?mediaId=demo&token=secret")
        assertTrue(stream.code != 403, "关掉数据面不该连带关掉媒体面（实际 ${stream.code}）")
    }

    @Test
    fun `面未开放时令牌正确也还是 403`() {
        val port = start(exposeDataApi = false)
        val res = get(
            url = "http://127.0.0.1:$port${IntegrationRoutes.META}",
            bearer = "Bearer secret",
        )
        assertEquals(403, res.code, "判定顺序必须是『先看面、再看令牌』，不能因为令牌对就放行")
    }

    // ============ 开关运行时生效 ============

    @Test
    fun `开关改完立刻生效且不需要重启端口`() {
        val port = start(exposeDataApi = false)

        val before = get("http://127.0.0.1:$port${IntegrationRoutes.META}?token=secret")
        assertEquals(403, before.code)

        // 模拟用户在设置页打开数据面：只改配置，不 stop/start
        liveConfig = liveConfig.copy(exposeDataApi = true)

        val after = get("http://127.0.0.1:$port${IntegrationRoutes.META}?token=secret")
        assertEquals(200, after.code, "开关应立刻生效；若这里失败，说明配置被构造期捕获了")
        assertTrue(after.body.contains("\"apiVersion\":$INTEGRATION_API_VERSION"), "响应体: ${after.body}")
    }

    @Test
    fun `关掉媒体面不影响数据面`() {
        val port = start(exposeDataApi = true)

        assertEquals(200, get("http://127.0.0.1:$port${IntegrationRoutes.META}?token=secret").code)

        liveConfig = liveConfig.copy(exposeStream = false)

        val stream = get("http://127.0.0.1:$port/stream?mediaId=demo&token=secret")
        assertEquals(403, stream.code, "媒体面应被关掉")
        assertTrue(stream.body.contains("\"code\":\"face_disabled\""), "响应体: ${stream.body}")

        assertEquals(
            200,
            get("http://127.0.0.1:$port${IntegrationRoutes.META}?token=secret").code,
            "关媒体面不该连带关掉数据面",
        )
    }

    // ============ 鉴权 ============

    @Test
    fun `令牌校验覆盖 Bearer 与 query 两种方式`() {
        val port = start(exposeDataApi = true)
        val meta = "http://127.0.0.1:$port${IntegrationRoutes.META}"

        assertEquals(401, get(meta).code, "没有令牌应 401")
        assertEquals(401, get("$meta?token=wrong").code, "令牌错误应 401")
        assertEquals(401, get(meta, bearer = "Bearer wrong").code, "Bearer 错误应 401")
        assertEquals(401, get(meta, bearer = "Basic dXNlcjpwYXNz").code, "非 Bearer 方案应 401")

        assertEquals(200, get("$meta?token=secret").code, "?token= 兼容路径应放行")
        assertEquals(200, get(meta, bearer = "Bearer secret").code, "Authorization Bearer 应放行")
    }

    @Test
    fun `health 不受任何开关影响`() {
        val port = start(exposeDataApi = false)
        liveConfig = liveConfig.copy(exposeStream = false)

        val health = get("http://127.0.0.1:$port/health")
        assertEquals(200, health.code, "health 是探活端点，必须始终可用")
        assertTrue(health.body.contains("\"status\":\"ok\""), "响应体: ${health.body}")
    }

    // ============ meta / providers 内容 ============

    @Test
    fun `meta 返回版本 能力 当前音源与登录态`() {
        val port = start(exposeDataApi = true)
        val res = get("http://127.0.0.1:$port${IntegrationRoutes.META}?token=secret")

        assertEquals(200, res.code)
        assertTrue(res.body.contains("\"app\":\"CPPlayer\""), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"apiVersion\":1"), "响应体: ${res.body}")
        assertTrue(
            res.body.contains(
                "\"capabilities\":[\"meta\",\"providers\",\"search\",\"track\",\"trackBatch\",\"playback\",\"events\",\"stream\"]"
            ),
            "capabilities 必须如实反映已实现能力: ${res.body}",
        )
        assertTrue(res.body.contains("\"provider\":{\"id\":\"netease\""), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"loggedIn\":true"), "响应体: ${res.body}")
        // 凭据绝不能出现在响应里
        assertTrue(!res.body.contains("cookie", ignoreCase = true), "响应体不该出现 cookie: ${res.body}")
    }

    @Test
    fun `providers 列出已加载音源并标记当前活跃项`() {
        val port = start(exposeDataApi = true)
        val res = get("http://127.0.0.1:$port${IntegrationRoutes.PROVIDERS}?token=secret")

        assertEquals(200, res.code)
        assertTrue(res.body.contains("\"id\":\"netease\""), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"id\":\"local\""), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"type\":\"JNI\""), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"active\":true"), "活跃音源应被标记: ${res.body}")
        assertEquals(
            1,
            Regex("\"active\":true").findAll(res.body).count(),
            "最多一个音源为活跃: ${res.body}",
        )
        // apiMap / 模块路径 / cookie 属于内部信息，不对外
        assertTrue(!res.body.contains("apiMap"), "不该暴露 apiMap: ${res.body}")
    }

    // ============ POST /search ============

    @Test
    fun `search 返回曲目并带本机流地址`() {
        val port = start(exposeDataApi = true)
        val res = post(
            url = "http://127.0.0.1:$port${IntegrationRoutes.SEARCH}?token=secret",
            body = """{"keywords":"周杰伦","type":1}""",
        )

        assertEquals(200, res.code, "响应体: ${res.body}")
        assertTrue(res.body.contains("\"mediaId\":\"netease://song/1\""), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"name\":\"晴天\""), "响应体: ${res.body}")
        assertTrue(
            res.body.contains("/stream?mediaId=netease%3A%2F%2Fsong%2F1&token=secret"),
            "必须给本机流地址（含令牌）: ${res.body}",
        )
        assertTrue(res.body.contains("\"playlists\":[]"), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"artists\":[]"), "响应体: ${res.body}")
    }

    @Test
    fun `search 的音源不支持映射为 501`() {
        val port = start(exposeDataApi = true)
        // 假音源只支持单曲搜索（type=1），type=1000 走 Unsupported 分支
        val res = post(
            url = "http://127.0.0.1:$port${IntegrationRoutes.SEARCH}?token=secret",
            body = """{"keywords":"x","type":1000}""",
        )

        assertEquals(501, res.code, "音源能力缺失应是 501，不是 502（集成方该换音源而不是重试）")
        assertTrue(res.body.contains("\"code\":\"unsupported\""), "响应体: ${res.body}")
    }

    @Test
    fun `search 的非法参数返回 400`() {
        val port = start(exposeDataApi = true)
        val url = "http://127.0.0.1:$port${IntegrationRoutes.SEARCH}?token=secret"

        assertEquals(400, post(url, """{"keywords":"   "}""").code, "空白关键词应 400")
        assertEquals(400, post(url, """{"keywords":"x","type":7}""").code, "不支持的 type 应 400")
        assertEquals(400, post(url, """{"keywords":"x","limit":0}""").code, "非正 limit 应 400")
        assertEquals(400, post(url, """{"keywords":"x","limit":0}""".replace("0", "-1")).code, "负 limit 应 400")
        assertEquals(400, post(url, """{"type":1}""").code, "缺 keywords 应 400")
        assertEquals(400, post(url, """{"keywords":}""").code, "坏 JSON 应 400")
        assertEquals(400, post(url, "").code, "空请求体应 400")
    }

    @Test
    fun `search 容忍请求体里的未知字段`() {
        val port = start(exposeDataApi = true)
        // v2 客户端给 v1 服务端多发字段不该报错 —— 这是「v1 内只做加法」的另一半
        val res = post(
            url = "http://127.0.0.1:$port${IntegrationRoutes.SEARCH}?token=secret",
            body = """{"keywords":"x","type":1,"someV2Field":true}""",
        )
        assertEquals(200, res.code, "响应体: ${res.body}")
    }

    // ============ GET /tracks/{mediaId} ============

    @Test
    fun `track 路径参数中的 mediaId 能正确解码`() {
        val port = start(exposeDataApi = true)
        // mediaId 含 :// 与 /，必须 URL 编码
        val encoded = java.net.URLEncoder.encode("netease://song/1", "UTF-8")
        val res = get("http://127.0.0.1:$port${IntegrationRoutes.API_PREFIX}/tracks/$encoded?token=secret")

        assertEquals(200, res.code, "路径参数解码失败会让这条 404。响应体: ${res.body}")
        assertTrue(res.body.contains("\"mediaId\":\"netease://song/1\""), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"name\":\"晴天\""), "响应体: ${res.body}")
    }

    @Test
    fun `track 的畸形 mediaId 返回 400`() {
        val port = start(exposeDataApi = true)
        val res = get("http://127.0.0.1:$port${IntegrationRoutes.API_PREFIX}/tracks/not-a-media-id?token=secret")
        assertEquals(400, res.code, "响应体: ${res.body}")
        assertTrue(res.body.contains("\"code\":\"bad_request\""), "响应体: ${res.body}")
    }

    @Test
    fun `track 的未知曲目返回 502`() {
        val port = start(exposeDataApi = true)
        val encoded = java.net.URLEncoder.encode("netease://song/999", "UTF-8")
        val res = get("http://127.0.0.1:$port${IntegrationRoutes.API_PREFIX}/tracks/$encoded?token=secret")
        // 领域层不区分「不存在」与「取数失败」，所以这里是 502 而不是 404。
        // 这个断言与 IntegrationServiceTest 的同名结论一致，改契约时两处会一起失败。
        assertEquals(502, res.code, "响应体: ${res.body}")
        assertTrue(res.body.contains("\"code\":\"upstream_failed\""), "响应体: ${res.body}")
    }

    // ============ POST /tracks/batch ============

    @Test
    fun `batch 单项失败不影响整体且顺序与请求一致`() {
        val port = start(exposeDataApi = true)
        val res = post(
            url = "http://127.0.0.1:$port${IntegrationRoutes.TRACK_BATCH}?token=secret",
            body = """{"mediaIds":["netease://song/1","bad-id","netease://song/999"]}""",
        )

        assertEquals(200, res.code, "批量端点不该因为单项失败而整体失败: ${res.body}")
        // 顺序必须是请求顺序
        val first = res.body.indexOf("netease://song/1")
        val second = res.body.indexOf("bad-id")
        val third = res.body.indexOf("netease://song/999")
        assertTrue(first in 0..<second && second < third, "顺序错乱: ${res.body}")

        assertEquals(1, Regex("\"track\":\\{\"mediaId\"").findAll(res.body).count(), "只有一项成功: ${res.body}")
        assertEquals(2, Regex("\"error\":\"").findAll(res.body).count(), "两项应带逐项错误: ${res.body}")
    }

    @Test
    fun `batch 空数组与超限返回 400`() {
        val port = start(exposeDataApi = true)
        val url = "http://127.0.0.1:$port${IntegrationRoutes.TRACK_BATCH}?token=secret"

        assertEquals(400, post(url, """{"mediaIds":[]}""").code)

        val tooMany = (0..MAX_TRACK_BATCH_SIZE).joinToString(",") { "\"netease://song/$it\"" }
        assertEquals(400, post(url, """{"mediaIds":[$tooMany]}""").code, "超限应 400，避免被当成放大器")
    }

    // ============ GET /playback ============

    @Test
    fun `playback 返回只读播放状态`() {
        val port = start(exposeDataApi = true, playback = playingState())
        val res = get("http://127.0.0.1:$port${IntegrationRoutes.PLAYBACK}?token=secret")

        assertEquals(200, res.code, "响应体: ${res.body}")
        assertTrue(res.body.contains("\"isPlaying\":true"), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"positionMs\":42000"), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"qualityLevel\":\"lossless\""), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"queueLength\":2"), "响应体: ${res.body}")
        assertTrue(res.body.contains("\"currentTrack\":{\"mediaId\":\"netease://song/1\""), "响应体: ${res.body}")
    }

    @Test
    fun `没有播放内容时 playback 不报错`() {
        val port = start(exposeDataApi = true)
        val res = get("http://127.0.0.1:$port${IntegrationRoutes.PLAYBACK}?token=secret")

        assertEquals(200, res.code, "没在播不是错误")
        assertTrue(res.body.contains("\"currentTrack\":null"), "显式 null 便于集成方发现字段: ${res.body}")
        assertTrue(res.body.contains("\"queueLength\":0"), "响应体: ${res.body}")
    }

    // ============ POST /playback/{action} ============

    @Test
    fun `播控端点把动作转给播放器并返回动作后的状态`() {
        // 起始状态是「没在播」，动作是 play —— 于是响应体里 isPlaying 必须变成 true。
        // 若实现返回的是动作**前**的快照，这条会失败。
        val port = start(exposeDataApi = true, allowRemoteControl = true)

        val res = post("http://127.0.0.1:$port/api/v1/playback/play?token=secret", "")

        assertEquals(200, res.code, "响应体: ${res.body}")
        assertEquals(listOf(PlaybackAction.PLAY), controlCalls, "动作应恰好转发一次")
        assertTrue(res.body.contains("\"isPlaying\":true"), "响应体应是动作**后**的快照: ${res.body}")
    }

    @Test
    fun `未开放远程播控时 403 且不触碰播放器`() {
        // 数据面开着、播控关着 —— 这是「面开了但能力没开」的那一格
        val port = start(exposeDataApi = true, allowRemoteControl = false)

        val res = post("http://127.0.0.1:$port/api/v1/playback/pause?token=secret", "")

        assertEquals(403, res.code, "响应体: ${res.body}")
        assertTrue(res.body.contains("\"code\":\"face_disabled\""), "响应体: ${res.body}")
        assertEquals(emptyList(), controlCalls, "被拒的请求绝不能碰播放器")
    }

    @Test
    fun `stop 未实现返回 400 并指路 pause`() {
        val port = start(exposeDataApi = true, allowRemoteControl = true)

        val res = post("http://127.0.0.1:$port/api/v1/playback/stop?token=secret", "")

        assertEquals(400, res.code, "stop 是刻意不实现的，应 400 而不是 200/404: ${res.body}")
        assertEquals(emptyList(), controlCalls, "stop 绝不能悄悄退化成 clearQueue")
        assertTrue(res.body.contains("pause"), "错误信息要指路 pause: ${res.body}")
    }

    @Test
    fun `未知动作返回 400 并列出可用动作`() {
        val port = start(exposeDataApi = true, allowRemoteControl = true)

        val res = post("http://127.0.0.1:$port/api/v1/playback/shuffle?token=secret", "")

        assertEquals(400, res.code, "响应体: ${res.body}")
        assertTrue(res.body.contains(PlaybackAction.PLAY), "错误信息应列出可用动作: ${res.body}")
        assertEquals(emptyList(), controlCalls)
    }

    // ============ GET /events（SSE） ============

    /**
     * 事件流的核心性质，同时是**丢事件竞态**的回归守卫。
     *
     * 客户端在收到快照后**立刻**改状态 —— 这正好落在「服务端读 `states.value` 写快照」
     * 与「服务端真正订阅状态流」之间的那个窗口里。曾经的实现是
     * 「手动写快照 + `drop(1)` 跳过重放」，在这个窗口里发生的状态变化会被
     * 连着重放一起丢掉，于是这条测试的第二段断言失败（首帧到、心跳到、中间那次变化没有）。
     *
     * 所以**不要**把这段「收到快照后马上改状态」改成「先 sleep 一下再改」——
     * 那会把窗口绕过去，测试照样绿，而线上的丢事件照旧。
     */
    @Test
    fun `事件流先发快照再推变化`() {
        val port = start(exposeDataApi = true, playback = playingState())

        openSse("http://127.0.0.1:$port${IntegrationRoutes.EVENTS}?token=secret").use { sse ->
            assertEquals(200, sse.code)
            assertTrue(
                sse.contentType.orEmpty().startsWith("text/event-stream"),
                "Content-Type 应为 text/event-stream，实际 ${sse.contentType}",
            )

            // 1) 连上就先给一份快照，集成方不必额外打一次 /playback
            sse.awaitDataLine { it.contains("\"isPlaying\":true") }

            // 2) 状态变了要推 —— 只改数据，不重连、不重启端口
            livePlayback.value = livePlayback.value.copy(isPlaying = false, positionMs = 43_000L)

            // 推得**立刻**到，不是等下一次心跳才捎带出来：
            // 超时给 5 秒（心跳是 15 秒），这样「靠心跳蹭出来」也会失败。
            val pushed = sse.awaitDataLine { it.contains("\"isPlaying\":false") }
            assertTrue(pushed.contains("\"positionMs\":43000"), "推送应是最新状态: $pushed")
        }
    }

    @Test
    fun `事件流与数据面受同一开关控制`() {
        val port = start(exposeDataApi = false)

        openSse("http://127.0.0.1:$port${IntegrationRoutes.EVENTS}?token=secret").use { sse ->
            assertEquals(403, sse.code, "关掉数据面时事件流也必须拒绝，否则等于留了个后门")
        }
    }

    @Test
    fun `事件流也要校验令牌`() {
        val port = start(exposeDataApi = true)

        openSse("http://127.0.0.1:$port${IntegrationRoutes.EVENTS}").use { sse ->
            assertEquals(401, sse.code)
        }
    }

    // ============ 脚手架 ============

    private fun start(
        exposeDataApi: Boolean,
        token: String = "secret",
        playback: PlaybackUiState = PlaybackUiState(),
        allowRemoteControl: Boolean = false,
    ): Int {
        val port = freePort()
        liveConfig = LocalServerConfig(
            enabled = true,
            bindAddress = LocalServerConfig.BIND_LOOPBACK,
            streamPort = port,
            accessToken = token,
            exposeStream = true,
            exposeDataApi = exposeDataApi,
            allowRemoteControl = allowRemoteControl,
        )
        livePlayback.value = playback
        controlCalls.clear()

        val service = IntegrationService(
            source = { StubSource },
            playbackState = { livePlayback },
            playbackControl = { recordingControl },
            // 测试里用不了 Dispatchers.Main（core 的 desktopTest 不带 coroutines-swing），
            // 这里用 Unconfined 只求把 HTTP 接线跑通。
            // 「写必须回控制线程」这条**线程规则**由 IntegrationPlaybackControlTest 单独钉，
            // 那里用 ManualDispatcher 精确断言 —— 在 Unconfined 上是断言不出来的。
            controlDispatcher = Dispatchers.Unconfined,
            availableProviders = {
                listOf(
                    IntegrationProviderInfo("netease", "NeteaseCloudMusicApi", "1.2.0", "JNI"),
                    IntegrationProviderInfo("local", "本地音乐", "1.0.0", "BINARY"),
                )
            },
            activeProviderId = { "netease" },
            loggedIn = { true },
            config = { liveConfig },
        )

        val local = createLocalServer(
            config = liveConfig,
            // 上游指向一个必然连不上的端口：媒体面能走到转发逻辑就够，不需要真音源
            resolveStreamUrl = { mediaId ->
                if (mediaId == null) null else StreamTarget(url = "http://127.0.0.1:1/never")
            },
            integration = createIntegrationRoutes(service),
            activeConfig = { liveConfig },
        )
        server = local
        local.start()
        waitUntilReady("http://127.0.0.1:$port/health")
        return port
    }

    private fun playingState() = PlaybackUiState(
        currentTrack = trackOf("netease://song/1", "晴天"),
        currentIndex = 0,
        queue = listOf(
            QueueItem("netease://song/1", "晴天", "周杰伦", null, null, 1000L),
            QueueItem("netease://song/2", "七里香", "周杰伦", null, null, 1000L),
        ),
        isPlaying = true,
        positionMs = 42_000L,
        qualityLevel = "lossless",
    )

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    /**
     * SSE 测试客户端。
     *
     * ### 为什么用裸 socket 而不是 [HttpURLConnection]
     * 事件流**永远不结束**。`HttpURLConnection` 要先把响应头读出来才知道怎么给 body，
     * 而它拿到 `Transfer-Encoding: chunked` 后是**按需**解 chunk 的 —— 一旦它的
     * 内部缓冲策略变化，测试就会表现成「连上了但一行都收不到」，而且看不出是
     * 服务端没发还是客户端没读。裸 socket 把这两件事分开：**收到多少字节**是
     * 一个可以直接断言的量。
     *
     * 请求用 **HTTP/1.0**：HTTP/1.1 无 `Content-Length` 的响应会被 Ktor 套上
     * chunked 编码，裸 socket 就得自己解 chunk 长度行；HTTP/1.0 下服务端直接用
     * 「连接关闭」定界，字节流就是正文本身。
     */
    private class SseStream(url: String) : AutoCloseable {

        private val queue = java.util.concurrent.LinkedBlockingQueue<String>()

        /**
         * 读取线程的异常。
         *
         * **必须留住**：吞掉的话「服务端没发」与「客户端读挂了」在断言处长得一模一样
         * （都是「一行都没收到」），而这两者的修法完全不同。
         */
        @Volatile
        private var pumpError: Throwable? = null

        /** 已从 socket 收到的正文字节数。**这是「服务端到底发没发」的唯一硬证据。** */
        @Volatile
        var bytesRead: Int = 0
            private set

        /** 原始正文开头若干字符（ISO-8859-1 逐字节映射，不做解码），失败时打出来看线上形状。 */
        @Volatile
        var rawBody: String = ""
            private set

        private val socket: java.net.Socket

        val code: Int

        val contentType: String?

        private val pump: Thread

        init {
            val uri = java.net.URI(url)
            socket = java.net.Socket(uri.host, uri.port).apply { soTimeout = 30_000 }
            val target = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            socket.getOutputStream().apply {
                write(
                    (
                        "GET $target HTTP/1.0\r\n" +
                            "Host: ${uri.host}:${uri.port}\r\n" +
                            "Accept: text/event-stream\r\n" +
                            "Accept-Encoding: identity\r\n" +
                            "\r\n"
                        ).toByteArray(Charsets.ISO_8859_1)
                )
                flush()
            }

            // 刻意**不**用 buffered()/BufferedReader：正文要按原始字节计数（[bytesRead]），
            // 任何预读都会把「服务端发了多少」这个证据搅浑。响应头只有几行，逐字节读无所谓。
            val input = socket.getInputStream()

            code = (readAsciiLine(input) ?: error("服务端没有返回状态行"))
                .split(" ")
                .getOrNull(1)
                ?.toIntOrNull()
                ?: error("状态行无法解析")

            var ct: String? = null
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("Content-Type", ignoreCase = true)) {
                    ct = line.substring(colon + 1).trim()
                }
            }
            contentType = ct

            pump = Thread({
                try {
                    val buffer = ByteArray(1024)
                    val pending = StringBuilder()
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytesRead += n
                        if (rawBody.length < 600) rawBody += String(buffer, 0, n, Charsets.ISO_8859_1)
                        pending.append(String(buffer, 0, n, Charsets.UTF_8))
                        while (true) {
                            val nl = pending.indexOf("\n")
                            if (nl < 0) break
                            queue.put(pending.substring(0, nl).trimEnd('\r'))
                            pending.delete(0, nl + 1)
                        }
                    }
                } catch (t: Throwable) {
                    if (t !is InterruptedException) pumpError = t
                }
            }, "sse-reader").apply { isDaemon = true; start() }
        }

        /**
         * 等到一行满足条件的 `data:` 负载。
         *
         * 失败时把**已收到的行**、**已收到的字节数**与读取线程的异常一并报出来：
         * 只报「超时」的话，分不清是「服务端没发」（字节数为 0）还是
         * 「发了但客户端没解出来」（字节数不为 0）。
         */
        fun awaitDataLine(timeoutMs: Long = 5_000, predicate: (String) -> Boolean): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            val seen = mutableListOf<String>()
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    error("等不到满足条件的事件；已收到行: $seen；已收到字节: $bytesRead；原始正文: ${rawBody.replace("\r", "\\r")}；读取线程异常: $pumpError")
                }
                val line = queue.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: error("等不到满足条件的事件；已收到行: $seen；已收到字节: $bytesRead；原始正文: ${rawBody.replace("\r", "\\r")}；读取线程异常: $pumpError")
                seen += line
                if (line.startsWith("data: ") && predicate(line.removePrefix("data: "))) return line
            }
        }

        override fun close() {
            runCatching { socket.close() }
            pump.interrupt()
        }

        /** 逐字节读一行（`\r\n` 或 `\n` 结尾）。只用于响应头，见上面不缓冲的理由。 */
        private fun readAsciiLine(input: java.io.InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }
    }

    private fun openSse(url: String): SseStream = SseStream(url)

    private fun waitUntilReady(url: String, attempts: Int = 50) {
        repeat(attempts) {
            if (runCatching { get(url).code == 200 }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        error("服务未在预期时间内就绪: $url")
    }

    private data class Response(val code: Int, val body: String)

    private fun get(url: String, bearer: String? = null): Response = request(url, method = "GET", bearer = bearer)

    private fun post(url: String, body: String, bearer: String? = null): Response =
        request(url, method = "POST", bearer = bearer, body = body)

    private fun request(url: String, method: String, bearer: String?, body: String? = null): Response {
        val conn = (java.net.URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = 5_000
            bearer?.let { setRequestProperty("Authorization", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val bytes = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes() ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
        runCatching { conn.disconnect() }
        return Response(code, String(bytes, Charsets.UTF_8))
    }

    /** 数据端点测试用的假音源：只认 `netease://song/1`，只支持单曲搜索。 */
    private object StubSource : UnifiedMusicSource {

        override suspend fun getTrackDetail(mediaId: String): MusicResult<TrackSummary> =
            if (mediaId == "netease://song/1") {
                BackendResult.Success(trackOf(mediaId, "晴天"))
            } else {
                BackendResult.Error("Song not found: $mediaId")
            }

        override suspend fun getTrackDetails(mediaIds: List<String>): MusicResult<List<TrackSummary>> =
            BackendResult.Success(mediaIds.filter { it == "netease://song/1" }.map { trackOf(it, "晴天") })

        override suspend fun getSongUrl(mediaId: String, level: String): MusicResult<SongUrl> =
            BackendResult.Error("本假音源不提供播放地址")

        override suspend fun search(
            keywords: String,
            type: Int,
            providers: List<String>?,
        ): MusicResult<SearchResult> = if (type == SEARCH_TYPE_SONG) {
            BackendResult.Success(
                SearchResult(
                    songs = listOf(trackOf("netease://song/1", "晴天")),
                    playlists = emptyList(),
                    artists = emptyList(),
                )
            )
        } else {
            BackendResult.Unsupported("本假音源只支持单曲搜索（type=$SEARCH_TYPE_SONG）")
        }

        override suspend fun getUserPlaylists(providerId: String, uid: Long): MusicResult<List<PlaylistSummary>> =
            BackendResult.Error("本假音源不提供歌单")
    }

}

/**
 * 放在**文件级**而不是类成员：`StubSource` 是嵌套 `object`，
 * 嵌套类默认不是 `inner`，拿不到外部类成员 —— 放类里会报
 * `Outer class of non-inner class cannot be used as receiver`。
 */
private fun trackOf(id: String, name: String) = TrackSummary(
    id = id,
    name = name,
    artist = "周杰伦",
    album = "叶惠美",
    coverUrl = "https://cover.example/x.jpg",
    durationMs = 269_000L,
)
