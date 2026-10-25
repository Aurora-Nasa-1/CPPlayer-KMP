package cp.player.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 安卓端字体包仓库：下载 / 校验 / 落盘 / 按字重建家族。
 *
 * ## 文件放哪
 *
 * `filesDir/fonts/<fileName>`。用 `filesDir` 而不是 `cacheDir`：
 * 这是**用户显式下载、期望长期有效**的东西，被系统在存储紧张时清掉等于白下 30 MB。
 * 代价是卸载应用才会释放 —— 与「设置页能删」配套，用户有退路。
 *
 * ## 为什么不用现成的网络库
 *
 * 只有一个 GET，不需要连接池 / 拦截器 / 重试策略。`HttpURLConnection` 是平台自带的，
 * 少一条依赖就少一个 R8 keep 规则和一处版本漂移面（本仓库在 coil 上已经吃过一版兼容性亏）。
 *
 * ## ⚠️ 语义要点
 *
 * - 下载**先写 `.part` 再 rename**：中途失败 / 被杀进程不会留下一个「看起来装好了」的半个文件。
 * - 落地前**校验魔数与体积**：GitHub 出错时会返回 HTML 错误页（200 + text/html），
 *   不校验的话那 500 字节会被当成字体，之后每次渲染都抛异常。
 * - 家族对象**缓存**：`Font(file)` 每次新建都会让 `TextStyle` 换身份，整棵树重组。
 */
internal object AndroidFontPacks {

    private const val DirName = "fonts"
    private const val TempSuffix = ".part"
    private const val ChunkBytes = 64 * 1024

    /** 进度上报的最小间隔（字节）：每 256 KB 更新一次状态，避免高频重组。 */
    private const val ProgressStepBytes = 256 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _statuses = MutableStateFlow(initialStatuses())
    val statuses: StateFlow<List<FontPackStatus>> = _statuses.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    /** 已构建好的家族（key = pack）；缺失即表示未安装 / 构建失败。 */
    private val familyCache = HashMap<AppFontPack, FontFamily>()

    /** 绑定 Application Context 并刷新一次真实状态（幂等）。 */
    fun attach(context: Context) {
        val application = context.applicationContext
        if (appContext === application) return
        appContext = application
        refreshFromDisk()
    }

    /** 按磁盘现状刷新状态。安装 / 删除后都走这里，避免两处各写一份判定。 */
    private fun refreshFromDisk() {
        val directory = directoryOf() ?: return
        familyCache.keys.retainAll { pack -> fontFile(directory, pack).isFile }
        _statuses.value = AppFontPack.entries.map { pack ->
            val file = fontFile(directory, pack)
            if (file.isFile && file.length() > 0L) {
                FontPackStatus(pack, FontPackPhase.READY, progress = 1f)
            } else {
                FontPackStatus(pack, FontPackPhase.MISSING)
            }
        }
    }

    /** 取一款已安装字体对应的家族；未安装 / 平台不支持时返回 null。 */
    fun familyOf(pack: AppFontPack): FontFamily? {
        if (!supportsVariationAxes()) return null
        familyCache[pack]?.let { return it }
        val directory = directoryOf() ?: return null
        val file = fontFile(directory, pack)
        if (!file.isFile || file.length() == 0L) return null
        val built = runCatching {
            FontFamily(
                AppFontWeights.map { (weight, fontWeight) ->
                    // 位置参数：这是 Android 侧 `Font(File, …)` 的 actual，
                    // 具名参数与桌面端不一致会编译不过（见 Font.kt 的同类注释）。
                    Font(
                        file,
                        fontWeight,
                        FontStyle.Normal,
                        FontVariation.Settings(FontVariation.weight(weight)),
                    )
                },
            )
        }.getOrNull() ?: return null
        familyCache[pack] = built
        return built
    }

    /** 开始下载（幂等：正在下载 / 已安装时直接返回）。 */
    fun download(pack: AppFontPack) {
        val current = _statuses.value.firstOrNull { it.pack == pack }
        if (current?.phase == FontPackPhase.DOWNLOADING || current?.phase == FontPackPhase.READY) return
        val context = appContext ?: run {
            update(pack) { FontPackStatus(pack, FontPackPhase.FAILED, error = "no application context") }
            return
        }
        if (!supportsVariationAxes()) {
            update(pack) { FontPackStatus(pack, FontPackPhase.FAILED, error = "SDK ${Build.VERSION.SDK_INT} < 26") }
            return
        }
        update(pack) { FontPackStatus(pack, FontPackPhase.DOWNLOADING, progress = 0f) }
        scope.launch {
            val result = runCatching { downloadBlocking(context, pack) }
            if (result.isSuccess) {
                refreshFromDisk()
            } else {
                val message = result.exceptionOrNull()?.message ?: "download failed"
                update(pack) { FontPackStatus(pack, FontPackPhase.FAILED, error = message) }
            }
        }
    }

    /** 删除已安装的字体包（文件不存在也算成功）。 */
    fun remove(pack: AppFontPack) {
        val directory = directoryOf()
        if (directory != null) {
            fontFile(directory, pack).delete()
            File(directory, pack.fileName + TempSuffix).delete()
        }
        familyCache.remove(pack)
        refreshFromDisk()
    }

    /**
     * 下载并落地 [pack]。
     *
     * ⚠️ 调用方必须保证不在主线程（[download] 已在 IO 作用域里调用）。
     */
    private suspend fun downloadBlocking(context: Context, pack: AppFontPack) = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, DirName).apply { mkdirs() }
        if (!directory.isDirectory) throw IOException("cannot create $directory")
        val temp = File(directory, pack.fileName + TempSuffix)
        temp.delete()

        val connection = (URL(pack.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            // GitHub 对没有 UA 的请求偶发 403。
            setRequestProperty("User-Agent", "CPPlayer-Android-FontPack")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: pack.sizeBytes
            var received = 0L
            var reported = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(ChunkBytes)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        if (received - reported >= ProgressStepBytes) {
                            reported = received
                            val progress = (received.toFloat() / total).coerceIn(0f, 1f)
                            update(pack) { FontPackStatus(pack, FontPackPhase.DOWNLOADING, progress = progress) }
                        }
                    }
                }
            }
            // 体积：允许 10% 偏差（release 资源偶尔重打包），明显不完整就直接判失败。
            if (received < pack.sizeBytes * 9 / 10) {
                throw IOException("incomplete: $received / ${pack.sizeBytes} bytes")
            }
            if (!looksLikeFont(temp)) throw IOException("not a font file (HTTP error page?)")
            val target = fontFile(directory, pack)
            if (target.exists() && !target.delete()) throw IOException("cannot replace $target")
            if (!temp.renameTo(target)) {
                // 少数文件系统上 rename 会失败，退化成复制。
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    /** 只认真字体文件的魔数：`0x00010000` / `true` / `ttcf` / `OTTO`。 */
    private fun looksLikeFont(file: File): Boolean {
        if (file.length() < 12) return false
        val header = ByteArray(4)
        file.inputStream().use { it.read(header) }
        fun byteAt(index: Int) = header[index].toInt() and 0xFF
        return when {
            byteAt(0) == 0x00 && byteAt(1) == 0x01 && byteAt(2) == 0x00 && byteAt(3) == 0x00 -> true
            String(header, Charsets.US_ASCII) in setOf("true", "ttcf", "OTTO", "typ1") -> true
            else -> false
        }
    }

    private fun directoryOf(): File? = appContext?.let { File(it.filesDir, DirName) }

    private fun fontFile(directory: File, pack: AppFontPack) = File(directory, pack.fileName)

    /** 可变字体按字重实例化需要 `Typeface.Builder`（API 26）。 */
    private fun supportsVariationAxes(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private fun update(pack: AppFontPack, transform: (FontPackStatus) -> FontPackStatus) {
        _statuses.value = _statuses.value.map { if (it.pack == pack) transform(it) else it }
    }

    private fun initialStatuses(): List<FontPackStatus> =
        AppFontPack.entries.map { FontPackStatus(it, FontPackPhase.MISSING) }
}

@Composable
actual fun rememberAppFontFamilies(): AppFontFamilies {
    val context = LocalContext.current
    AndroidFontPacks.attach(context)
    val statuses by AndroidFontPacks.statuses.collectAsState()
    // 状态（未下载 / 已安装 / 删除）变了才重建：家族对象内部有缓存，
    // 这里只是把「哪些可用」重新取一遍。
    return remember(statuses) {
        AppFontFamilies(
            japanese = AndroidFontPacks.familyOf(AppFontPack.JAPANESE),
            misans = AndroidFontPacks.familyOf(AppFontPack.MISANS),
        )
    }
}

@Composable
actual fun rememberFontPackStatuses(): List<FontPackStatus> {
    val context = LocalContext.current
    AndroidFontPacks.attach(context)
    val statuses by AndroidFontPacks.statuses.collectAsState()
    return statuses
}

actual fun platformSupportsFontPacks(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

actual fun downloadFontPack(pack: AppFontPack) = AndroidFontPacks.download(pack)

actual fun removeFontPack(pack: AppFontPack) = AndroidFontPacks.remove(pack)
