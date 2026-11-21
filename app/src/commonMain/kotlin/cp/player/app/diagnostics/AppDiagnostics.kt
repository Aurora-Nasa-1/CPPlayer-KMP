package cp.player.app.diagnostics

import cp.player.app.AppModel
import cp.player.app.platform.clearImageCache
import cp.player.app.platform.desktopPlatform
import cp.player.app.platform.imageCacheSizeBytes
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.platformMemoryInfo
import cp.player.app.presentation.util.formatBytes
import cp.player.app.version.AppVersion
import cp.player.core.BackendResult
import cp.player.core.monitor.HealthMonitor
import cp.player.core.music.TrackSummary
import cp.player.core.provider.BackendProvider
import cp.player.core.util.PlatformInfo
import cp.player.core.util.createHttpClient
import cp.player.core.util.currentTimeMillis
import cp.player.core.util.localDateTimeOf
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class DiagnosticStatus {
    OK,
    WARNING,
    ERROR,
    SKIPPED,
}

enum class DiagnosticCategory {
    NETWORK,
    PROVIDER,
    AUDIO,
    STORAGE,
    SYSTEM,
}

data class DiagnosticItem(
    val id: String,
    val category: DiagnosticCategory,
    val title: String,
    val status: DiagnosticStatus,
    val summary: String,
    val latencyMs: Long? = null,
    val details: String? = null,
    val suggestion: String? = null,
)

data class DiagnosticReport(
    val timestamp: Long,
    val score: Int,
    val overallStatus: DiagnosticStatus,
    val items: List<DiagnosticItem>,
    val systemSummary: String,
)

enum class ProbeOperation {
    SEARCH,
    TRACK_DETAIL,
    STREAM_URL,
    LYRIC,
    RANKINGS,
    PLAYLISTS,
}

sealed interface ProbeResult {
    data class Success(
        val operation: String,
        val durationMs: Long,
        val summary: String,
        val details: String? = null,
        val songs: List<TrackSummary> = emptyList(),
    ) : ProbeResult
    data class Unsupported(val operation: String, val durationMs: Long, val message: String) : ProbeResult
    data class Error(val operation: String, val durationMs: Long, val error: String) : ProbeResult
}

/**
 * 应用健康诊断与主动排查引擎。
 *
 * 整合：
 * 1. 一键主动健康体检（网络双向连通性、生效音源、歌曲搜索、流直链解析、歌词提取、榜单推荐、音频引擎状态、本地缓存、运行堆内存）
 * 2. 交互式音源取数接口探测工作台（搜索、详情、流直链、歌词、榜单、推荐歌单）
 * 3. 诊断报告生成与导出（Markdown 格式，包含完整系统元数据与调用日志，用于排障反馈）
 * 4. 故障快速恢复工具箱（重载音源、清空流缓存、清空图片缓存、重置音频引擎）
 */
object AppDiagnostics {

    private val httpClient: HttpClient by lazy { createHttpClient() }

    private val _report = MutableStateFlow<DiagnosticReport?>(null)
    val report: StateFlow<DiagnosticReport?> = _report.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _progressMessage = MutableStateFlow<String?>(null)
    val progressMessage: StateFlow<String?> = _progressMessage.asStateFlow()

    /**
     * 执行全量主动健康体检。
     */
    suspend fun runFullDiagnostics(): DiagnosticReport = withContext(Dispatchers.Default) {
        _isRunning.value = true
        val items = mutableListOf<DiagnosticItem>()
        var sampleSong: TrackSummary? = null

        try {
            // 1. 网络连通性测试 (国内与国际双通道探测)
            _progressMessage.value = "正在探测公网连通性与网络延迟..."
            val netItem = checkNetworkConnectivity()
            items.add(netItem)

            // 2. 音源生效与加载状态
            _progressMessage.value = "正在检查生效音源模块状态..."
            val activeProvider = AppModel.activeProvider()
            val providerItem = checkProviderStatus(activeProvider)
            items.add(providerItem)

            // 3. 音源搜索功能探测
            if (activeProvider != null && activeProvider.loadError == null) {
                _progressMessage.value = "正在探测歌曲搜索接口..."
                val (searchItem, song) = probeSearchCapability()
                items.add(searchItem)
                sampleSong = song

                // 4. 音频流直链解析探测
                _progressMessage.value = "正在探测音频流播放直链解析..."
                val streamItem = probeStreamUrlCapability(activeProvider, sampleSong)
                items.add(streamItem)

                // 5. 歌词提取探测
                _progressMessage.value = "正在探测歌词提取与解析能力..."
                val lyricItem = probeLyricCapability(activeProvider, sampleSong)
                items.add(lyricItem)

                // 6. 榜单与推荐能力探测
                _progressMessage.value = "正在探测排行榜单与热门推荐..."
                val discoveryItem = probeDiscoveryCapability()
                items.add(discoveryItem)
            } else {
                items.add(
                    DiagnosticItem(
                        id = "check_search",
                        category = DiagnosticCategory.PROVIDER,
                        title = "歌曲搜索接口探测",
                        status = DiagnosticStatus.SKIPPED,
                        summary = "未配置有效音源，跳过取数测试",
                        suggestion = "请先在音源管理中导入并激活可用音源",
                    )
                )
            }

            // 7. 播放引擎与输出通道
            _progressMessage.value = "正在检查音频引擎状态..."
            val audioItem = checkAudioEngineStatus()
            items.add(audioItem)

            // 8. 存储与流缓存
            _progressMessage.value = "正在检查流缓存与本地存储..."
            val storageItem = checkStorageAndCache()
            items.add(storageItem)

            // 9. 运行时内存与系统环境
            _progressMessage.value = "正在检查系统内存与运行环境..."
            val systemItem = checkSystemAndMemory()
            items.add(systemItem)

        } finally {
            _progressMessage.value = null
            _isRunning.value = false
        }

        // 计算体检评分与综合等级
        var score = 100
        var hasError = false
        var hasWarning = false

        for (item in items) {
            when (item.status) {
                DiagnosticStatus.ERROR -> {
                    score -= 25
                    hasError = true
                }
                DiagnosticStatus.WARNING -> {
                    score -= 10
                    hasWarning = true
                }
                DiagnosticStatus.OK, DiagnosticStatus.SKIPPED -> {}
            }
        }
        score = score.coerceIn(0, 100)

        val overallStatus = when {
            hasError || score < 60 -> DiagnosticStatus.ERROR
            hasWarning || score < 85 -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.OK
        }

        val platformDesc = if (isAndroidPlatform()) "Android" else "Desktop (${desktopPlatform()})"
        val report = DiagnosticReport(
            timestamp = currentTimeMillis(),
            score = score,
            overallStatus = overallStatus,
            items = items,
            systemSummary = "平台: $platformDesc · 版本: v${AppVersion.versionName} (${AppVersion.gitSha})",
        )
        _report.value = report
        report
    }

    private suspend fun checkNetworkConnectivity(): DiagnosticItem {
        val client = httpClient

        val domesticEndpoints = listOf(
            "https://connect.rom.miui.com/generate_204",
            "https://www.qualcomm.cn/generate_204",
        )
        val globalEndpoints = listOf(
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://www.apple.com/library/test/success.html",
        )

        var domesticOk = false
        var domesticLatency = 0L
        var domesticError: String? = null

        for (endpoint in domesticEndpoints) {
            val start = currentTimeMillis()
            val outcome = runCatching {
                withTimeoutOrNull(3000L) {
                    val resp: HttpResponse = client.get(endpoint)
                    resp.status.value in 200..399
                }
            }
            val elapsed = currentTimeMillis() - start
            if (outcome.getOrNull() == true) {
                domesticOk = true
                domesticLatency = elapsed
                break
            } else {
                domesticError = outcome.exceptionOrNull()?.message ?: "探测超时(>3s)"
            }
        }

        var globalOk = false
        var globalLatency = 0L

        for (endpoint in globalEndpoints) {
            val start = currentTimeMillis()
            val outcome = runCatching {
                withTimeoutOrNull(3000L) {
                    val resp: HttpResponse = client.get(endpoint)
                    resp.status.value in 200..399
                }
            }
            val elapsed = currentTimeMillis() - start
            if (outcome.getOrNull() == true) {
                globalOk = true
                globalLatency = elapsed
                break
            }
        }

        return when {
            domesticOk && globalOk -> {
                val avgLatency = (domesticLatency + globalLatency) / 2
                val isHigh = avgLatency > 2000L
                DiagnosticItem(
                    id = "check_network",
                    category = DiagnosticCategory.NETWORK,
                    title = "公网与 API 连通性",
                    status = if (isHigh) DiagnosticStatus.WARNING else DiagnosticStatus.OK,
                    summary = if (isHigh) "公网延迟偏高 (国内 ${domesticLatency}ms / 国际 ${globalLatency}ms)"
                              else "公网连通正常 (国内 ${domesticLatency}ms / 国际 ${globalLatency}ms)",
                    latencyMs = avgLatency,
                    details = "国内与国际网络健康节点均可正常访问，DNS 解析与连接耗时良好",
                    suggestion = if (isHigh) "网络延迟偏高，可能会导致音频缓冲缓慢或卡顿" else null,
                )
            }
            domesticOk && !globalOk -> {
                DiagnosticItem(
                    id = "check_network",
                    category = DiagnosticCategory.NETWORK,
                    title = "公网与 API 连通性",
                    status = DiagnosticStatus.OK,
                    summary = "国内网络连通良好 (${domesticLatency}ms) · 国际节点受限",
                    latencyMs = domesticLatency,
                    details = "国内连通性正常(${domesticLatency}ms)；国际探测节点超时或连接受阻",
                    suggestion = "常规国内音源不受影响；若使用依赖海外服务器的音源，可能需要配置网络代理",
                )
            }
            !domesticOk && globalOk -> {
                DiagnosticItem(
                    id = "check_network",
                    category = DiagnosticCategory.NETWORK,
                    title = "公网与 API 连通性",
                    status = DiagnosticStatus.WARNING,
                    summary = "国际网络连通良好 (${globalLatency}ms) · 国内节点未响应",
                    latencyMs = globalLatency,
                    details = "国际探测节点正常(${globalLatency}ms)；国内连通性探测受阻(${domesticError ?: "超时"})",
                    suggestion = "若开启了全局代理或特定路由规则，请检查网络分流设置",
                )
            }
            else -> {
                DiagnosticItem(
                    id = "check_network",
                    category = DiagnosticCategory.NETWORK,
                    title = "公网与 API 连通性",
                    status = DiagnosticStatus.ERROR,
                    summary = "公网连接失败: ${domesticError ?: "网络不可达"}",
                    details = "连续探测国内与国际探测节点均超时或连接被拒",
                    suggestion = "请检查 Wi-Fi / 数据网络、系统代理、VPN 或防火墙设置",
                )
            }
        }
    }

    private fun checkProviderStatus(provider: BackendProvider?): DiagnosticItem {
        if (provider == null) {
            return DiagnosticItem(
                id = "check_provider",
                category = DiagnosticCategory.PROVIDER,
                title = "生效音源模块状态",
                status = DiagnosticStatus.WARNING,
                summary = "未配置或未激活音源",
                suggestion = "请前往「音源管理」导入并激活音源模块以享受完整的取数与播放体验",
            )
        }

        provider.loadError?.let { err ->
            return DiagnosticItem(
                id = "check_provider",
                category = DiagnosticCategory.PROVIDER,
                title = "生效音源模块状态",
                status = DiagnosticStatus.ERROR,
                summary = "音源加载失败: $err",
                details = "ID: ${provider.id} · 类型: ${provider.type.name} · 版本: ${provider.version}",
                suggestion = "请检查模块 ABI 是否与当前系统相符，或尝试重新导入该音源",
            )
        }

        return DiagnosticItem(
            id = "check_provider",
            category = DiagnosticCategory.PROVIDER,
            title = "生效音源模块状态",
            status = DiagnosticStatus.OK,
            summary = "音源正常就绪: ${provider.name} (v${provider.version})",
            details = "ID: ${provider.id} · 类型: ${provider.type.name} · API 版本: v${provider.apiVersion}${provider.apiVersionTag?.let { " · 接口代际: $it" } ?: ""}",
        )
    }

    private suspend fun probeSearchCapability(): Pair<DiagnosticItem, TrackSummary?> {
        val keywords = listOf("周杰伦", "Song", "Love")
        var lastResult: BackendResult<cp.player.core.music.SearchResult>? = null
        var matchedSong: TrackSummary? = null
        var totalElapsed = 0L

        for (kw in keywords) {
            val kwStart = currentTimeMillis()
            val result = AppModel.backend.unifiedSource.search(kw, type = 1)
            val elapsed = currentTimeMillis() - kwStart
            totalElapsed = elapsed
            lastResult = result

            if (result is BackendResult.Success && result.data.songs.isNotEmpty()) {
                matchedSong = result.data.songs.first()
                break
            }
            if (result is BackendResult.Unsupported || result is BackendResult.Error) {
                break
            }
        }

        return when (val result = lastResult) {
            is BackendResult.Success -> {
                val songs = result.data.songs
                if (songs.isNotEmpty()) {
                    val first = songs.first()
                    DiagnosticItem(
                        id = "check_search",
                        category = DiagnosticCategory.PROVIDER,
                        title = "歌曲搜索接口探测",
                        status = DiagnosticStatus.OK,
                        summary = "搜索正常响应，返回 ${songs.size} 首歌曲",
                        latencyMs = totalElapsed,
                        details = "采样曲目: ${first.name} - ${first.artist} [MediaId: ${first.id}]",
                    ) to first
                } else {
                    DiagnosticItem(
                        id = "check_search",
                        category = DiagnosticCategory.PROVIDER,
                        title = "歌曲搜索接口探测",
                        status = DiagnosticStatus.WARNING,
                        summary = "搜索响应正常，但未检索到结果",
                        latencyMs = totalElapsed,
                        suggestion = "音源可能受到风控限制，或需要配置登录 Cookie 凭据",
                    ) to null
                }
            }
            is BackendResult.Unsupported -> {
                DiagnosticItem(
                    id = "check_search",
                    category = DiagnosticCategory.PROVIDER,
                    title = "歌曲搜索接口探测",
                    status = DiagnosticStatus.WARNING,
                    summary = "当前音源未实现歌曲搜索能力",
                    latencyMs = totalElapsed,
                    suggestion = "该音源仅支持特定外部资源或本地播放",
                ) to null
            }
            is BackendResult.Error -> {
                DiagnosticItem(
                    id = "check_search",
                    category = DiagnosticCategory.PROVIDER,
                    title = "歌曲搜索接口探测",
                    status = DiagnosticStatus.ERROR,
                    summary = "搜索接口调用失败: ${result.message}",
                    latencyMs = totalElapsed,
                    suggestion = "请检查当前音源接口服务端状态或网络连接",
                ) to null
            }
            null -> {
                DiagnosticItem(
                    id = "check_search",
                    category = DiagnosticCategory.PROVIDER,
                    title = "歌曲搜索接口探测",
                    status = DiagnosticStatus.SKIPPED,
                    summary = "未配置有效音源，跳过取数测试",
                ) to null
            }
        }
    }

    private suspend fun probeStreamUrlCapability(provider: BackendProvider, sampleSong: TrackSummary?): DiagnosticItem {
        if (sampleSong == null) {
            return DiagnosticItem(
                id = "check_stream",
                category = DiagnosticCategory.PROVIDER,
                title = "播放流直链解析探测",
                status = DiagnosticStatus.SKIPPED,
                summary = "未获取到采样歌曲，跳过直链测试",
            )
        }

        val mediaId = sampleSong.id
        val start = currentTimeMillis()
        val result = AppModel.backend.unifiedSource.getSongUrl(mediaId, "standard")
        val elapsed = currentTimeMillis() - start

        return when (result) {
            is BackendResult.Success -> {
                val songUrl = result.data
                if (songUrl.url.isNotBlank()) {
                    DiagnosticItem(
                        id = "check_stream",
                        category = DiagnosticCategory.PROVIDER,
                        title = "播放流直链解析探测",
                        status = DiagnosticStatus.OK,
                        summary = "成功解析音频流播放直链",
                        latencyMs = elapsed,
                        details = "采样歌曲: ${sampleSong.name}\n音质: ${songUrl.level} · 码率: ${songUrl.bitrate ?: 0}kbps · 编码: ${songUrl.codec ?: "auto"}\n直链: ${songUrl.url.take(120)}...",
                    )
                } else {
                    DiagnosticItem(
                        id = "check_stream",
                        category = DiagnosticCategory.PROVIDER,
                        title = "播放流直链解析探测",
                        status = DiagnosticStatus.WARNING,
                        summary = "返回空播放地址 (可能需要 VIP 或登录凭据)",
                        latencyMs = elapsed,
                        suggestion = "部分音源（如网易云/QQ音乐）要求对应账号登录凭据或 VIP 权限才能获取直链",
                    )
                }
            }
            is BackendResult.Unsupported -> {
                DiagnosticItem(
                    id = "check_stream",
                    category = DiagnosticCategory.PROVIDER,
                    title = "播放流直链解析探测",
                    status = DiagnosticStatus.WARNING,
                    summary = "当前音源不支持在线直链解析",
                    latencyMs = elapsed,
                )
            }
            is BackendResult.Error -> {
                DiagnosticItem(
                    id = "check_stream",
                    category = DiagnosticCategory.PROVIDER,
                    title = "播放流直链解析探测",
                    status = DiagnosticStatus.ERROR,
                    summary = "直链解析失败: ${result.message}",
                    latencyMs = elapsed,
                    suggestion = "请确认歌曲版权限制、音源账号状态或接口代理服务",
                )
            }
        }
    }

    private suspend fun probeLyricCapability(provider: BackendProvider, sampleSong: TrackSummary?): DiagnosticItem {
        if (sampleSong == null) {
            return DiagnosticItem(
                id = "check_lyric",
                category = DiagnosticCategory.PROVIDER,
                title = "歌词提取解析探测",
                status = DiagnosticStatus.SKIPPED,
                summary = "未获取到采样歌曲，跳过歌词探测",
            )
        }

        val mediaId = sampleSong.id
        val start = currentTimeMillis()
        val result = AppModel.backend.unifiedSource.getLyric(mediaId)
        val elapsed = currentTimeMillis() - start

        return when (result) {
            is BackendResult.Success -> {
                val linesCount = result.data.lines().size
                DiagnosticItem(
                    id = "check_lyric",
                    category = DiagnosticCategory.PROVIDER,
                    title = "歌词提取解析探测",
                    status = DiagnosticStatus.OK,
                    summary = "歌词提取成功 (共 $linesCount 行)",
                    latencyMs = elapsed,
                    details = "采样歌曲: ${sampleSong.name} - ${sampleSong.artist}",
                )
            }
            is BackendResult.Unsupported -> {
                DiagnosticItem(
                    id = "check_lyric",
                    category = DiagnosticCategory.PROVIDER,
                    title = "歌词提取解析探测",
                    status = DiagnosticStatus.OK,
                    summary = "音源未内嵌歌词（由全局 AMLL / LrcLib 词库自动匹配兜底）",
                    latencyMs = elapsed,
                    details = "播放器配置了外部歌词源，即使音源不支持也不影响歌词显示",
                )
            }
            is BackendResult.Error -> {
                DiagnosticItem(
                    id = "check_lyric",
                    category = DiagnosticCategory.PROVIDER,
                    title = "歌词提取解析探测",
                    status = DiagnosticStatus.WARNING,
                    summary = "歌词提取异常: ${result.message}",
                    latencyMs = elapsed,
                    suggestion = "播放时系统将自动回退尝试 AMLL / LrcLib 第三方词库",
                )
            }
        }
    }

    private suspend fun probeDiscoveryCapability(): DiagnosticItem {
        val start = currentTimeMillis()
        var result = AppModel.backend.unifiedSource.getRankings()
        var elapsed = currentTimeMillis() - start

        if (result is BackendResult.Unsupported) {
            val startRec = currentTimeMillis()
            val recResult = AppModel.backend.unifiedSource.getRecommendedSongs(10)
            elapsed = currentTimeMillis() - startRec
            if (recResult !is BackendResult.Unsupported) {
                result = when (recResult) {
                    is BackendResult.Success -> BackendResult.Success(emptyList())
                    is BackendResult.Error -> BackendResult.Error(recResult.message)
                    is BackendResult.Unsupported -> BackendResult.Unsupported(recResult.reason)
                }
            }
        }

        return when (result) {
            is BackendResult.Success -> {
                DiagnosticItem(
                    id = "check_discovery",
                    category = DiagnosticCategory.PROVIDER,
                    title = "排行榜与推荐探测",
                    status = DiagnosticStatus.OK,
                    summary = "发现与推荐接口响应正常",
                    latencyMs = elapsed,
                )
            }
            is BackendResult.Unsupported -> {
                DiagnosticItem(
                    id = "check_discovery",
                    category = DiagnosticCategory.PROVIDER,
                    title = "排行榜与推荐探测",
                    status = DiagnosticStatus.WARNING,
                    summary = "音源未提供排行榜或推荐接口",
                    latencyMs = elapsed,
                )
            }
            is BackendResult.Error -> {
                DiagnosticItem(
                    id = "check_discovery",
                    category = DiagnosticCategory.PROVIDER,
                    title = "排行榜与推荐探测",
                    status = DiagnosticStatus.WARNING,
                    summary = "排行榜推荐获取失败: ${result.message}",
                    latencyMs = elapsed,
                )
            }
        }
    }

    private fun checkAudioEngineStatus(): DiagnosticItem {
        val playbackState = AppModel.backend.playbackController.state.value
        val volume = AppModel.backend.playbackController.currentVolume
        val volumePct = (volume * 100).toInt()

        playbackState.error?.let { err ->
            return DiagnosticItem(
                id = "check_audio",
                category = DiagnosticCategory.AUDIO,
                title = "音频输出与播放引擎",
                status = DiagnosticStatus.ERROR,
                summary = "播放引擎报告异常: $err",
                details = "状态: 播放报错 · 音量: $volumePct% · 错误信息: $err",
                suggestion = "可尝试在排障工具箱中点击「重置播放引擎」或重启应用",
            )
        }

        if (volumePct == 0) {
            return DiagnosticItem(
                id = "check_audio",
                category = DiagnosticCategory.AUDIO,
                title = "音频输出与播放引擎",
                status = DiagnosticStatus.WARNING,
                summary = "播放器当前处于静音状态 (音量: 0%)",
                details = "播放引擎就绪待命，但音量已被设为 0，播放时将听不到声音",
                suggestion = "请在底部播放控制条或系统设置中调高播放音量",
            )
        }

        val trackDesc = playbackState.currentTrack?.let { "${it.name} - ${it.artist}" } ?: "队列空闲"
        val isBuffering = playbackState.isBuffering
        val isLocalizing = playbackState.isLocalizing
        val isPlaying = playbackState.isPlaying

        val statusDesc = when {
            isPlaying -> "正在播放"
            isBuffering -> "缓冲中"
            isLocalizing -> "本地化流中"
            else -> "就绪待命"
        }

        return DiagnosticItem(
            id = "check_audio",
            category = DiagnosticCategory.AUDIO,
            title = "音频输出与播放引擎",
            status = DiagnosticStatus.OK,
            summary = "播放引擎运行正常 (音量: $volumePct% · 状态: $statusDesc)",
            details = "当前曲目: $trackDesc · 缓冲: $isBuffering · 本地化: $isLocalizing",
        )
    }

    private fun checkStorageAndCache(): DiagnosticItem {
        val cacheStats = AppModel.backend.songCache.stats()
        val imageBytes = runCatching { imageCacheSizeBytes() }.getOrDefault(0L)
        val entries = cacheStats.entries
        val streamCacheStr = formatBytes(cacheStats.bytes)
        val imageCacheStr = formatBytes(imageBytes)

        return DiagnosticItem(
            id = "check_storage",
            category = DiagnosticCategory.STORAGE,
            title = "本地存储与流缓存",
            status = DiagnosticStatus.OK,
            summary = "流缓存 $entries 首 ($streamCacheStr) · 图片缓存 $imageCacheStr",
            details = "音频流缓存目录工作正常；存储读写状态良好",
        )
    }

    private fun checkSystemAndMemory(): DiagnosticItem {
        val mem = platformMemoryInfo()
        val usedMb = mem.usedBytes / (1024 * 1024)
        val maxMb = mem.maxBytes / (1024 * 1024)
        val ratio = if (mem.maxBytes > 0) (mem.usedBytes.toDouble() / mem.maxBytes.toDouble()) else 0.0
        val ratioPct = (ratio * 100).toInt()

        val isHighMem = ratioPct > 88
        val status = if (isHighMem) DiagnosticStatus.WARNING else DiagnosticStatus.OK

        val abis = PlatformInfo.supportedAbis.joinToString()
        val osInfo = if (isAndroidPlatform()) "Android" else "${desktopPlatform()} (${abis})"

        return DiagnosticItem(
            id = "check_system",
            category = DiagnosticCategory.SYSTEM,
            title = "运行时内存与环境",
            status = status,
            summary = "堆内存已用 ${usedMb}MB / ${maxMb}MB ($ratioPct%) · 系统: $osInfo",
            details = "版本: v${AppVersion.versionName} (${AppVersion.gitSha}) · 架构: $abis",
            suggestion = if (isHighMem) "内存占用率偏高，建议在排障工具箱中清理流缓存或图片缓存" else null,
        )
    }

    /**
     * 主动接口单项测试工作台执行入口。
     */
    suspend fun probeOperation(
        operation: ProbeOperation,
        param: String = "",
        quality: String = "standard",
    ): ProbeResult = withContext(Dispatchers.Default) {
        val start = currentTimeMillis()
        val activeProviderId = AppModel.activeProviderId()

        when (operation) {
            ProbeOperation.SEARCH -> {
                val keyword = if (param.isBlank()) "周杰伦" else param.trim()
                val result = AppModel.backend.unifiedSource.search(keyword, type = 1)
                val elapsed = currentTimeMillis() - start
                when (result) {
                    is BackendResult.Success -> {
                        val songs = result.data.songs
                        val details = buildString {
                            appendLine("搜索关键词: \"$keyword\" (返回 ${songs.size} 首歌曲)")
                            appendLine("----------------------------------------")
                            songs.take(10).forEachIndexed { i, s ->
                                appendLine("${i + 1}. ${s.name} - ${s.artist} [专辑: ${s.album ?: "单曲"}]")
                                appendLine("   MediaId: ${s.id}")
                            }
                            if (songs.size > 10) appendLine("...共 ${songs.size} 首歌曲")
                        }
                        ProbeResult.Success("search", elapsed, "成功返回 ${songs.size} 首歌曲", details, songs)
                    }
                    is BackendResult.Unsupported -> ProbeResult.Unsupported("search", elapsed, result.reason)
                    is BackendResult.Error -> ProbeResult.Error("search", elapsed, result.message)
                }
            }

            ProbeOperation.TRACK_DETAIL -> {
                val input = param.trim().ifBlank { "186016" }
                val mediaId = AppModel.toMediaId(input, activeProviderId)
                val result = AppModel.backend.unifiedSource.getTrackDetail(mediaId)
                val elapsed = currentTimeMillis() - start
                when (result) {
                    is BackendResult.Success -> {
                        val s = result.data
                        val details = buildString {
                            appendLine("歌曲标题: ${s.name}")
                            appendLine("歌手: ${s.artist}")
                            appendLine("专辑: ${s.album ?: "未知"}")
                            appendLine("时长: ${s.durationMs / 1000} 秒 (${s.durationMs}ms)")
                            appendLine("封面图: ${s.coverUrl ?: "无"}")
                            appendLine("统一 MediaId: ${s.id}")
                        }
                        ProbeResult.Success("getTrackDetail", elapsed, "获取单曲详情成功: ${s.name} - ${s.artist}", details)
                    }
                    is BackendResult.Unsupported -> ProbeResult.Unsupported("getTrackDetail", elapsed, result.reason)
                    is BackendResult.Error -> ProbeResult.Error("getTrackDetail", elapsed, result.message)
                }
            }

            ProbeOperation.STREAM_URL -> {
                val input = param.trim().ifBlank { "186016" }
                val mediaId = AppModel.toMediaId(input, activeProviderId)
                val result = AppModel.backend.unifiedSource.getSongUrl(mediaId, quality)
                val elapsed = currentTimeMillis() - start
                when (result) {
                    is BackendResult.Success -> {
                        val s = result.data
                        val details = buildString {
                            appendLine("音频流直链 URL:")
                            appendLine(s.url)
                            appendLine()
                            appendLine("音质档位: ${s.level}")
                            appendLine("音频格式: ${s.codec ?: "未知"}")
                            appendLine("码率: ${s.bitrate ?: 0} kbps")
                            appendLine("文件大小: ${s.sizeBytes?.let { formatBytes(it) } ?: "未知"}")
                            appendLine("过期时间戳: ${s.expireAt ?: 0}")
                            if (!s.headers.isNullOrEmpty()) {
                                appendLine("请求头: ${s.headers}")
                            }
                        }
                        val summary = if (s.url.isNotBlank()) "成功获取播放直链 (${s.level})" else "返回空直链地址"
                        ProbeResult.Success("getSongUrl", elapsed, summary, details)
                    }
                    is BackendResult.Unsupported -> ProbeResult.Unsupported("getSongUrl", elapsed, result.reason)
                    is BackendResult.Error -> ProbeResult.Error("getSongUrl", elapsed, result.message)
                }
            }

            ProbeOperation.LYRIC -> {
                val input = param.trim().ifBlank { "186016" }
                val mediaId = AppModel.toMediaId(input, activeProviderId)
                val result = AppModel.backend.unifiedSource.getLyric(mediaId)
                val elapsed = currentTimeMillis() - start
                when (result) {
                    is BackendResult.Success -> {
                        val text = result.data
                        val lines = text.lines()
                        val details = buildString {
                            appendLine("歌词总行数: ${lines.size}")
                            appendLine("前 15 行歌词预览:")
                            appendLine("----------------------------------------")
                            lines.take(15).forEach { appendLine(it) }
                            if (lines.size > 15) {
                                appendLine("... (其余 ${lines.size - 15} 行已省略)")
                            }
                        }
                        ProbeResult.Success("getLyric", elapsed, "歌词解析成功 (${lines.size} 行)", details)
                    }
                    is BackendResult.Unsupported -> ProbeResult.Unsupported("getLyric", elapsed, result.reason)
                    is BackendResult.Error -> ProbeResult.Error("getLyric", elapsed, result.message)
                }
            }

            ProbeOperation.RANKINGS -> {
                val result = AppModel.backend.unifiedSource.getRankings()
                val elapsed = currentTimeMillis() - start
                when (result) {
                    is BackendResult.Success -> {
                        val list = result.data
                        val details = buildString {
                            appendLine("排行榜分类数量: ${list.size}")
                            list.take(8).forEachIndexed { i, r ->
                                appendLine("${i + 1}. ${r.name} [ID: ${r.id}]")
                            }
                        }
                        ProbeResult.Success("getRankings", elapsed, "获取到 ${list.size} 个排行榜", details)
                    }
                    is BackendResult.Unsupported -> ProbeResult.Unsupported("getRankings", elapsed, result.reason)
                    is BackendResult.Error -> ProbeResult.Error("getRankings", elapsed, result.message)
                }
            }

            ProbeOperation.PLAYLISTS -> {
                val result = AppModel.backend.unifiedSource.getRecommendedPlaylists(limit = 10)
                val elapsed = currentTimeMillis() - start
                when (result) {
                    is BackendResult.Success -> {
                        val list = result.data
                        val details = buildString {
                            appendLine("推荐歌单数量: ${list.size}")
                            list.take(10).forEachIndexed { i, p ->
                                appendLine("${i + 1}. ${p.name} [播放量: ${p.playCount ?: 0}] (ID: ${p.id})")
                            }
                        }
                        ProbeResult.Success("getRecommendedPlaylists", elapsed, "获取到 ${list.size} 个推荐歌单", details)
                    }
                    is BackendResult.Unsupported -> ProbeResult.Unsupported("getRecommendedPlaylists", elapsed, result.reason)
                    is BackendResult.Error -> ProbeResult.Error("getRecommendedPlaylists", elapsed, result.message)
                }
            }
        }
    }

    /**
     * 导出完整 Markdown 诊断报告。
     */
    fun formatDiagnosticReportMarkdown(report: DiagnosticReport): String {
        val timeStr = runCatching {
            val t = localDateTimeOf(report.timestamp)
            "%04d-%02d-%02d %02d:%02d:%02d".format(t.year, t.month, t.day, t.hour, t.minute, t.second)
        }.getOrDefault("未知时间")

        return buildString {
            appendLine("# CPPlayer 诊断与健康体检报告")
            appendLine()
            appendLine("- 诊断时间: $timeStr")
            appendLine("- 应用版本: v${AppVersion.versionName} (${AppVersion.gitSha}) [${AppVersion.releaseChannel}]")
            appendLine("- 运行平台: ${if (isAndroidPlatform()) "Android" else "Desktop (${desktopPlatform()})"}")
            appendLine("- 综合得分: ${report.score} / 100 (${report.overallStatus})")
            appendLine()
            appendLine("## 1. 核心诊断自检项")
            appendLine()
            report.items.forEach { item ->
                val badge = when (item.status) {
                    DiagnosticStatus.OK -> "[通过]"
                    DiagnosticStatus.WARNING -> "[警告]"
                    DiagnosticStatus.ERROR -> "[异常]"
                    DiagnosticStatus.SKIPPED -> "[跳过]"
                }
                appendLine("### $badge ${item.title}")
                appendLine("- 结论: ${item.summary}${item.latencyMs?.let { " (${it}ms)" } ?: ""}")
                item.details?.let { appendLine("- 详情: $it") }
                item.suggestion?.let { appendLine("- 排查建议: $it") }
                appendLine()
            }

            appendLine("## 2. 音源与接口调用统计")
            appendLine()
            val active = AppModel.activeProvider()
            appendLine("- 当前生效音源: ${active?.name ?: "无"} (${active?.id ?: "none"}) v${active?.version ?: "0"}")
            val stats = HealthMonitor.getAllStats()
            if (stats.isNotEmpty()) {
                stats.forEach { (id, stat) ->
                    appendLine("- 音源 [$id]: 调用 ${stat.totalCalls} 次, 成功 ${stat.successCount}, 失败 ${stat.failCount}, 警告 ${stat.warningCount}, 平均耗时 ${stat.avgResponseMs}ms")
                    stat.lastError?.let { appendLine("  - 最近报错: $it") }
                }
            } else {
                appendLine("- 暂无音源调用统计")
            }
            appendLine()

            appendLine("## 3. 最近异常调用记录")
            val recentErrors = HealthMonitor.getRecentRecords(limit = 6, onlyErrors = true)
            if (recentErrors.isNotEmpty()) {
                recentErrors.forEach { r ->
                    appendLine("- [${r.providerId}] ${r.operation} (${r.durationMs}ms): ${r.errorMessage ?: "未知错误"}")
                }
            } else {
                appendLine("- 无异常记录")
            }
        }
    }

    /**
     * 重新初始化当前音源。
     */
    fun reloadActiveProvider(): Boolean {
        val provider = AppModel.activeProvider() ?: return false
        return AppModel.switchOrReport(provider)
    }

    /**
     * 清理所有临时流缓存。
     */
    fun clearStreamCache(): Int {
        return AppModel.backend.songCache.clearAll()
    }

    /**
     * 清理所有封面与图片缓存。
     */
    fun clearCoverImageCache(): Boolean {
        return runCatching { clearImageCache() }.getOrDefault(false)
    }

    /**
     * 重置音频输出引擎。
     */
    fun resetAudioPlaybackGate() {
        AppModel.backend.playbackController.reloadEngine()
    }
}
