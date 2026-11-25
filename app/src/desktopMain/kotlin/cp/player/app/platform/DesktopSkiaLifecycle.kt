package cp.player.app.platform

import cp.player.app.AppModel
import cp.player.core.provider.HostTrafficPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Graphics
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桌面端 Skia / DirectContext / Surface 生命周期管理：
 * 1. 首帧绑定后立即收紧 `DirectContext` GPU 资源缓存上限与 `Graphics` 字形缓存上限；
 * 2. 渲染期间每 1.5s 主动回收超过 5 秒未使用的离屏纹理（`performDeferredCleanup(5000)`），
 *    并在连续静止 > 5 秒时清理 scratch 纹理与临时字形缓存（核心帧路径 0 分配）；
 * 3. 2K/4K 分辨率关闭硬件 MSAA 时，同步下发宿主层 sampleCount=0 配置（若宿主暴露）；
 * 4. 最小化到托盘时销毁渲染 Surface / RenderTarget、清空 GPU/字形/图片/业务缓存并触发堆回收。
 */
internal object DesktopSkiaLifecycle {

    private const val CLEANUP_POLL_INTERVAL_NS = 1_500_000_000L
    private const val IDLE_EVICT_NS = 5_000_000_000L
    private const val IDLE_EVICT_MS = 5_000L
    private const val FONT_CACHE_LIMIT_BYTES = 8 * 1024 * 1024
    private const val FONT_CACHE_COUNT_LIMIT = 2048

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val limitsApplied = AtomicBoolean(false)
    private val contextConfigured = AtomicBoolean(false)

    @Volatile
    private var cachedDirectContext: DirectContext? = null

    @Volatile
    private var attachedTaoWindow: Any? = null

    @Volatile
    private var attachedNucleusWindow: Any? = null

    @Volatile
    private var attachedFrameRequester: (() -> Unit)? = null

    @Volatile
    private var inTray = false

    // 仅供渲染线程读写的原始 long 计时器（避免装箱与原子对象分配）
    private var lastCleanupPollNs = 0L
    private var lastActiveDrawNs = 0L
    private var lastIdlePurgeNs = 0L

    // ============ 反射方法缓存（仅首次解析一次，热路径 0 分配） ============

    private val canvasRecordingContextMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { Canvas::class.java.getMethod("getRecordingContext") }.getOrNull()
    }

    private val setResourceCacheLimitMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        findSingleArgMethod(DirectContext::class.java, "setResourceCacheLimit")
    }

    private val performDeferredCleanupMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        findSingleArgMethod(DirectContext::class.java, "performDeferredCleanup")
    }

    private val purgeUnlockedResourcesMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            DirectContext::class.java.getMethod("purgeUnlockedResources", Boolean::class.javaPrimitiveType)
        }.getOrElse {
            runCatching { DirectContext::class.java.getMethod("purgeUnlockedResources") }.getOrNull()
        }
    }

    private val flushAndSubmitMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            DirectContext::class.java.getMethod("flushAndSubmit", Boolean::class.javaPrimitiveType)
        }.getOrElse {
            runCatching { DirectContext::class.java.getMethod("flushAndSubmit") }.getOrNull()
        }
    }

    private val freeGpuResourcesMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { DirectContext::class.java.getMethod("freeGpuResources") }.getOrNull()
    }

    private val setFontCacheLimitMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        findSingleArgMethod(Graphics::class.java, "setFontCacheLimit")
    }

    private val setFontCacheCountLimitMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        findSingleArgMethod(Graphics::class.java, "setFontCacheCountLimit")
    }

    private val purgeFontCacheMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { Graphics::class.java.getMethod("purgeFontCache") }.getOrNull()
    }

    private val purgeAllCachesMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { Graphics::class.java.getMethod("purgeAllCaches") }.getOrNull()
    }

    fun attachWindow(taoWindow: Any?, nucleusWindow: Any?, requestFrame: (() -> Unit)? = null) {
        if (taoWindow != null) attachedTaoWindow = taoWindow
        if (nucleusWindow != null) attachedNucleusWindow = nucleusWindow
        if (requestFrame != null) attachedFrameRequester = requestFrame
        applyGlobalSkiaLimitsOnce()
    }

    fun registerFrameRequester(requestFrame: () -> Unit) {
        if (attachedFrameRequester == null) {
            attachedFrameRequester = requestFrame
        }
    }

    /**
     * 每帧绘制结束钩子（运行在渲染线程）。
     * 正常渲染帧只执行整型比较，保证热路径 0 堆分配。
     */
    fun onAfterFrame(canvas: Canvas, hadVisualWork: Boolean) {
        if (inTray) return
        applyGlobalSkiaLimitsOnce()

        val ctx = obtainDirectContext(canvas)
        if (ctx != null && contextConfigured.compareAndSet(false, true)) {
            applyDirectContextBudget(ctx)
        }

        val nowNs = System.nanoTime()
        if (lastActiveDrawNs == 0L) lastActiveDrawNs = nowNs
        if (lastCleanupPollNs == 0L) lastCleanupPollNs = nowNs
        if (lastIdlePurgeNs == 0L) lastIdlePurgeNs = nowNs

        if (hadVisualWork) {
            lastActiveDrawNs = nowNs
        }

        if (nowNs - lastCleanupPollNs >= CLEANUP_POLL_INTERVAL_NS) {
            lastCleanupPollNs = nowNs
            if (ctx != null) {
                invokeDeferredCleanup(ctx, IDLE_EVICT_MS)
            }
            if (nowNs - lastActiveDrawNs >= IDLE_EVICT_NS && nowNs - lastIdlePurgeNs >= IDLE_EVICT_NS) {
                lastIdlePurgeNs = nowNs
                if (ctx != null) {
                    invokePurgeUnlocked(ctx, scratchOnly = true)
                }
                invokeStaticNoArg(purgeFontCacheMethod)
            }
        }
    }

    /**
     * 最小化到托盘：销毁/释放渲染 Surface 与 GPU 资源、清理临时字形与图片缓存并压缩堆存活集。
     */
    fun onMinimizeToTray(taoWindow: Any? = attachedTaoWindow, nucleusWindow: Any? = attachedNucleusWindow) {
        inTray = true
        HostTrafficPolicy.setInBackgroundTray(true)
        if (taoWindow != null) attachedTaoWindow = taoWindow
        if (nucleusWindow != null) attachedNucleusWindow = nucleusWindow

        // 1. 尝试销毁宿主持有的渲染 Surface / RenderTarget / SwapChain，并释放 DirectContext GPU 资源
        releaseRenderTargetsAndGpuResources(attachedTaoWindow, attachedNucleusWindow)

        // 2. 清理 Skia 全局字体与资源缓存
        invokeStaticNoArg(purgeFontCacheMethod)
        invokeStaticNoArg(purgeAllCachesMethod)

        // 3. 清理应用层图片内存缓存与业务 SWR / 歌词冗余缓存
        trimDesktopImageMemoryCache()
        TrayCoverPainter.trimToCurrent()
        AppModel.trimMemoryForBackground()

        // 4. 后台轻微延迟执行一次 GC，配合 Serial GC / Shenandoah 将空闲页还给 OS
        bgScope.launch {
            delay(150)
            if (inTray) {
                System.gc()
            }
        }
    }

    /**
     * 从托盘恢复窗口：重新允许渲染并唤醒首帧，必要时触发宿主重建 Surface。
     */
    fun onRestoreFromTray(taoWindow: Any? = attachedTaoWindow, nucleusWindow: Any? = attachedNucleusWindow) {
        inTray = false
        HostTrafficPolicy.setInBackgroundTray(false)
        val nowNs = System.nanoTime()
        lastActiveDrawNs = nowNs
        lastCleanupPollNs = nowNs
        lastIdlePurgeNs = nowNs

        restoreSurfaceIfNeeded(taoWindow ?: attachedTaoWindow, nucleusWindow ?: attachedNucleusWindow)
        attachedFrameRequester?.invoke()
    }

    private fun applyGlobalSkiaLimitsOnce() {
        if (!limitsApplied.compareAndSet(false, true)) return
        invokeStaticNumber(setFontCacheLimitMethod, FONT_CACHE_LIMIT_BYTES)
        invokeStaticNumber(setFontCacheCountLimitMethod, FONT_CACHE_COUNT_LIMIT)
    }

    private fun obtainDirectContext(canvas: Canvas): DirectContext? {
        cachedDirectContext?.let { return it }
        val resolved = runCatching {
            canvasRecordingContextMethod?.invoke(canvas) as? DirectContext
        }.getOrNull() ?: resolveDirectContextFromHosts(attachedTaoWindow, attachedNucleusWindow)
        if (resolved != null) {
            cachedDirectContext = resolved
        }
        return resolved
    }

    private fun applyDirectContextBudget(ctx: DirectContext) {
        val limit = DesktopRenderTuning.gpuCacheLimitBytes()
        invokeSingleNumber(setResourceCacheLimitMethod, ctx, limit)
        if (DesktopRenderTuning.isMsaaDisabled()) {
            // 尽量同步告知宿主/图层关闭 MSAA（若其暴露对应开关）
            disableHostMsaaIfExposed(attachedTaoWindow, attachedNucleusWindow)
        }
    }

    private fun releaseRenderTargetsAndGpuResources(taoWindow: Any?, nucleusWindow: Any?) {
        // 优先调用宿主暴露的 Surface 销毁或 GPU 清空钩子
        val hosts = collectSceneHosts(taoWindow, nucleusWindow)
        for (host in hosts) {
            invokeZeroArgIfPresent(
                host,
                "destroySurface",
                "disposeSurface",
                "releaseSurface",
                "purgeGpuResourceCacheAtRest",
            )
            // 若宿主直接持有 Surface / BackendRenderTarget 字段，关闭并置空（由恢复时重建）
            closeAndNullFieldIfPresent(host, "surface", "renderSurface", "skiaSurface")
            closeAndNullFieldIfPresent(host, "renderTarget", "backendRenderTarget")
        }

        val ctx = cachedDirectContext ?: resolveDirectContextFromHosts(taoWindow, nucleusWindow)
        if (ctx != null) {
            runCatching {
                flushAndSubmitMethod?.let { m ->
                    if (m.parameterCount == 1) m.invoke(ctx, true) else m.invoke(ctx)
                }
            }
            invokePurgeUnlocked(ctx, scratchOnly = false)
            runCatching { freeGpuResourcesMethod?.invoke(ctx) }
        }
    }

    private fun restoreSurfaceIfNeeded(taoWindow: Any?, nucleusWindow: Any?) {
        val hosts = collectSceneHosts(taoWindow, nucleusWindow)
        for (host in hosts) {
            invokeZeroArgIfPresent(
                host,
                "ensureSurface",
                "recreateSurface",
                "needRedraw",
                "invalidate",
                "requestFrame",
            )
        }
        contextConfigured.set(false)
    }

    private fun disableHostMsaaIfExposed(taoWindow: Any?, nucleusWindow: Any?) {
        val hosts = collectSceneHosts(taoWindow, nucleusWindow)
        for (host in hosts) {
            runCatching {
                val m = host.javaClass.methods.firstOrNull {
                    (it.name == "setSampleCount" || it.name == "setMsaaSampleCount") && it.parameterCount == 1
                }
                if (m != null) m.invoke(host, 0)
            }
        }
    }

    private fun collectSceneHosts(vararg roots: Any?): List<Any> {
        val result = ArrayList<Any>(4)
        for (root in roots) {
            if (root == null) continue
            result.add(root)
            for (fieldName in HOST_FIELD_NAMES) {
                val child = readFieldQuietly(root, fieldName) ?: continue
                if (result.none { it === child }) result.add(child)
            }
        }
        return result
    }

    private fun resolveDirectContextFromHosts(vararg roots: Any?): DirectContext? {
        for (host in collectSceneHosts(*roots)) {
            for (name in CONTEXT_FIELD_NAMES) {
                val v = readFieldQuietly(host, name)
                if (v is DirectContext) return v
            }
            for (name in CONTEXT_METHOD_NAMES) {
                val v = runCatching { host.javaClass.getMethod(name).invoke(host) }.getOrNull()
                if (v is DirectContext) return v
            }
        }
        return null
    }

    private fun readFieldQuietly(target: Any, name: String): Any? = runCatching {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val f = runCatching { cls.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return@runCatching f.get(target)
            }
            cls = cls.superclass
        }
        null
    }.getOrNull()

    private fun closeAndNullFieldIfPresent(target: Any, vararg names: String) {
        for (name in names) {
            runCatching {
                var cls: Class<*>? = target.javaClass
                var found: Field? = null
                while (cls != null) {
                    found = runCatching { cls.getDeclaredField(name) }.getOrNull()
                    if (found != null) break
                    cls = cls.superclass
                }
                if (found != null) {
                    found.isAccessible = true
                    val current = found.get(target)
                    if (current is AutoCloseable) {
                        runCatching { current.close() }
                        // 仅当字段非 final 时置空，避免 IllegalAccessException
                        if ((found.modifiers and java.lang.reflect.Modifier.FINAL) == 0) {
                            found.set(target, null)
                        }
                    }
                }
            }
        }
    }

    private fun invokeZeroArgIfPresent(target: Any, vararg names: String) {
        for (name in names) {
            runCatching {
                val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?: target.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
                        ?.apply { isAccessible = true }
                m?.invoke(target)
            }
        }
    }

    private fun invokeDeferredCleanup(ctx: DirectContext, msNotUsed: Long) {
        val m = performDeferredCleanupMethod ?: return
        runCatching {
            val param = m.parameterTypes[0]
            when (param) {
                Long::class.javaPrimitiveType, java.lang.Long::class.java -> m.invoke(ctx, msNotUsed)
                Int::class.javaPrimitiveType, java.lang.Integer::class.java -> m.invoke(ctx, msNotUsed.toInt())
            }
        }
    }

    private fun invokePurgeUnlocked(ctx: DirectContext, scratchOnly: Boolean) {
        val m = purgeUnlockedResourcesMethod ?: return
        runCatching {
            if (m.parameterCount == 1) m.invoke(ctx, scratchOnly) else m.invoke(ctx)
        }
    }

    private fun invokeSingleNumber(m: Method?, target: Any, value: Long) {
        if (m == null) return
        runCatching {
            val param = m.parameterTypes[0]
            when (param) {
                Long::class.javaPrimitiveType, java.lang.Long::class.java -> m.invoke(target, value)
                Int::class.javaPrimitiveType, java.lang.Integer::class.java -> m.invoke(target, value.toInt())
            }
        }
    }

    private fun invokeStaticNumber(m: Method?, value: Int) {
        if (m == null) return
        runCatching {
            val param = m.parameterTypes[0]
            when (param) {
                Long::class.javaPrimitiveType, java.lang.Long::class.java -> m.invoke(null, value.toLong())
                Int::class.javaPrimitiveType, java.lang.Integer::class.java -> m.invoke(null, value)
            }
        }
    }

    private fun invokeStaticNoArg(m: Method?) {
        if (m == null) return
        runCatching { m.invoke(null) }
    }

    private fun findSingleArgMethod(clazz: Class<*>, name: String): Method? =
        clazz.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 && (
                it.parameterTypes[0] == Long::class.javaPrimitiveType ||
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
                )
        }

    private val HOST_FIELD_NAMES = arrayOf("sceneHost", "host", "skiaLayer", "layer", "renderer", "renderDelegate")
    private val CONTEXT_FIELD_NAMES = arrayOf("directContext", "context", "grContext")
    private val CONTEXT_METHOD_NAMES = arrayOf("getDirectContext", "getContext")
}
