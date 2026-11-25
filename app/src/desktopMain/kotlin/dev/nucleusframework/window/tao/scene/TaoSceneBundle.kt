/*
 * MIGRATION SHIM: Temporary compatibility layer for Compose 1.13.0-beta02 + Nucleus.
 * Upstream PR: https://github.com/NucleusFramework/Nucleus/pull/934
 *
 * TODO(migration): Once Nucleus publishes an official release containing PR #934,
 * safely delete this file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "KotlinRedundantDiagnosticSuppress")
package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformOutOfFrameExecutor
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.PlatformLayersComposeScene
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.window.ExperimentalNucleusApi
import dev.nucleusframework.window.tao.TaoRootsForTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.Canvas
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.CoroutineContext

/**
 * Bundles a [ComposeScene] with the [FrameRecomposer] that drives its frame
 * loop.
 *
 * Compose 1.12 split the old `ComposeScene.render(canvas, nanoTime)` call
 * (which used to own recomposition, layout, and draw) into three steps 鈥? * `FrameRecomposer.performFrame` + `ComposeScene.measureAndLayout` +
 * `ComposeScene.draw` 鈥?and replaced the scene factories' former
 * `coroutineContext` / `invalidate` parameters with a [FrameRecomposer] plus
 * separate `invalidateLayout` / `invalidateDraw` callbacks. Compose Desktop's
 * AWT path drives those three steps through `SingleComposeSceneRenderingScope`
 * (mirrored here by [TaoSceneRenderingScope]),
 * which swallows layout/draw invalidations raised during the in-flight frame
 * and re-arms [requestFrame] only if the scene is still dirty afterwards.
 * Driving the steps by hand (and wiring `invalidateLayout`/`invalidateDraw`
 * straight to [requestFrame]) can schedule a second frame while
 * `measureAndLayout` is still placing a tree that just remounted 鈥?Compose
 * 1.12's [androidx.compose.ui.spatial.RectManager] then throws
 * `LayoutNode not found in RectList` (nucleus-demo tab switches).
 *
 * Each bundle also carries a [RectManagerEdtGuard], which keeps the
 * RectManager's delayed dispatch off the AWT EDT (issue #551) 鈥?see that class
 * for the full story.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal class TaoSceneBundle(
    val scene: ComposeScene,
    val frameRecomposer: FrameRecomposer,
    private val renderingScope: TaoSceneRenderingScope,
    private val edtGuard: RectManagerEdtGuard,
    /** Owns [edtGuard]'s debounce wakeups; cancelled with the scene. */
    private val guardScope: CoroutineScope,
    /** Flipped first thing in [close] 鈥?see [TaoSceneExceptionRouter]. */
    private val closed: AtomicBoolean,
    /** Installed in the scene's coroutine context; also stores [exceptionHandler]. */
    private val exceptionRouter: TaoSceneExceptionRouter,
    /** The owner's coalescing frame scheduler; re-armed after a swallowed frame failure. */
    private val requestFrame: () -> Unit,
    /** Shared with the scene's invalidation callbacks 鈥?see [visualDirty]. */
    visualDirty: AtomicBoolean,
    /** Fed the scene's owners through its platform context 鈥?see [frameDamage]. */
    private val damageTracker: LayerDamageTracker,
    /** Handed to the scene through its platform context; drained by [renderingScope] before each frame. */
    private val outOfFrameExecutor: TaoOutOfFrameExecutor?,
) : AutoCloseable {
    /**
     * Set by the scene's `invalidateLayout` / `invalidateDraw` callbacks 鈥?i.e.
     * whenever the content actually needs a new layout/draw pass 鈥?and cleared
     * by the host once the frame is presented.
     *
     * Exists to tell a *visual* frame apart from a recomposer-only tick: any
     * snapshot write anywhere in the process (an animation in a sibling
     * window, a ViewModel update) wakes every scene's [Recomposer] through the
     * global apply observer, and the [FrameRecomposer]'s `invalidate` callback
     * schedules a frame before knowing whether this scene is affected. Such a
     * frame recomposes to nothing 鈥?no layout/draw invalidation fires 鈥?and a
     * host can then skip the present. Presenting those empty frames is what
     * made every visible window re-present at the animating window's rate,
     * each present parking the shared event-loop thread on VSync (LapseCompose
     * dashboard lag while the overlay window was visible).
     */
    val visualDirty: AtomicBoolean = visualDirty

    /**
     * Catches what user code throws in this scene: [render] covers layout and
     * draw, the [TaoSceneExceptionRouter] in the scene's coroutine context
     * covers recomposition and everything else the scene's coroutines run.
     * Together they are the one seam every Tao host and popup layer shares.
     *
     * Installed from
     * [dev.nucleusframework.window.tao.LocalWindowExceptionHandlerFactory] by
     * whoever owns the bundle; `null` lets the failure escape, as it always did.
     */
    var exceptionHandler: WindowExceptionHandler?
        get() = exceptionRouter.handler
        set(value) {
            exceptionRouter.handler = value
        }

    /**
     * Whether the scene can still recompose.
     *
     * `Idle` and `PendingWork` are the two states in which a recomposition loop
     * is actually running. Everything else means there is none: in particular
     * `Recomposer.processCompositionError` records an error state, which
     * `deriveStateLocked` immediately derives to `Inactive` 鈥?so a composition
     * failure flips this to false the moment it happens, before the throw has
     * even finished unwinding. Such a scene still draws its last tree and still
     * hit-tests, but no state change will ever reach the screen again, so it
     * must not be mistaken for a recovered one.
     */
    val isRecomposerAlive: Boolean
        get() =
            when ((frameRecomposer.compositionContext as? Recomposer)?.currentState?.value) {
                Recomposer.State.Idle, Recomposer.State.PendingWork -> true
                // Not a Recomposer 鈥?cannot happen today; assume usable.
                null -> true
                else -> false
            }

    /**
     * Painted over the scene at the end of every [render], on the same canvas
     * and in the same coordinates the scene drew in. This is where the dialog
     * scrims of native popup layers land 鈥?the owner window paints every
     * layer's scrim, each layer paints the scrims of the layers above it
     * (Compose Desktop's `onRenderOverlay`). `null` paints nothing.
     */
    var renderOverlay: ((Canvas) -> Unit)? = null

    /**
     * The window-pixel rectangle the frame being drawn changed since the
     * previous one, or `null` when it cannot be told (#755) 鈥?see
     * [LayerDamageTracker]. Call from [render]'s `beforeDraw`, every frame:
     * a frame drawn without asking makes the next answer `null`. Covers the
     * scene only: whatever else the caller paints 鈥?[renderOverlay] included 鈥?     * must repaint in full whenever it changes.
     */
    fun frameDamage(
        sceneWidth: Int,
        sceneHeight: Int,
    ): IntRect? = damageTracker.frameDamage(sceneWidth, sceneHeight)

    /** What damaged the last frame 鈥?see [LayerDamageTracker.damageSources]. */
    val damageSources: List<Pair<IntRect, String>>
        get() = damageTracker.damageSources

    /** Why the last [frameDamage] was `null` 鈥?see [LayerDamageTracker.fullFrameReason]. */
    val fullFrameReason: String?
        get() = damageTracker.fullFrameReason

    /**
     * Recomposes, lays out, and draws one frame into [canvas] 鈥?the drop-in
     * replacement for the pre-1.12 `scene.render(canvas.asComposeCanvas(), nanoTime)`.
     * [nanoTime] is fed to the recomposer's frame clock, so `withFrameNanos`
     * animations advance on the timestamp the caller paces to.
     *
     * [beforeDraw] runs once the frame is laid out and before anything is
     * drawn 鈥?the one point at which the frame's damage is known (#755), so
     * it is where a partial redraw clips [canvas] and clears what it repaints.
     */
    fun render(
        canvas: Canvas,
        nanoTime: Long,
        beforeDraw: ((Canvas) -> Unit)? = null,
    ) {
        if (closed.get()) return
        CpRenderProbe.tick()
        var swallowed = true
        exceptionHandler.catchExceptions {
            // Closed by its own recomposition: nothing was drawn, nothing to account for 鈥?the
            // same outcome as a frame whose exception the handler swallowed (#621).
            if (!renderingScope.render(scene, frameRecomposer, canvas, nanoTime, beforeDraw) { closed.get() }) {
                return@catchExceptions
            }
            // A layer changed where the damage tracker could not see it: the
            // next frame repaints it, so there has to be one.
            if (damageTracker.afterDraw()) requestFrame()
            renderOverlay?.invoke(canvas)
            edtGuard.afterFrame()
            swallowed = false
        }
        // The handler chose to continue: this frame's recording is incomplete,
        // so it is dropped and the scheduler re-armed. Without the re-arm the
        // scene can stay invalidated forever 鈥?the invalidation that produced
        // this frame was consumed by the pass that just aborted. Skipped once
        // the recomposer is gone: the next frame would be identical, so this
        // would be a repaint spin rather than a retry.
        if (swallowed) {
            // Whatever the failed pass recorded is not what the tracker saw.
            damageTracker.invalidate()
            if (isRecomposerAlive) requestFrame()
        }
    }

    /**
     * Recomposes and re-lays-out the scene now, without drawing.
     *
     * For the one case where a Compose state write has to reach the node tree
     * *between* two things that happen in the same turn, rather than on the
     * next frame: a press that dismisses a popup, which the scene then receives
     * (see `TaoComposeSceneHostLinux.onPointerButton`). Two frame rolls, because
     * the first one composes the nodes and only the second runs the effects they
     * launched 鈥?a `pointerInput` handler awaits from a coroutine, so a node
     * composed but not yet started would let the press through untouched.
     */
    fun composeAndLayoutNow() {
        exceptionHandler.catchExceptions {
            // A frame in all but the draw: what the previous one deferred runs first.
            outOfFrameExecutor?.onBeforeFrame()
            val nanoTime = System.nanoTime()
            frameRecomposer.performFrame(nanoTime)
            frameRecomposer.performFrame(nanoTime)
            scene.measureAndLayoutPastDisposedLayers()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        closed.set(true)
        guardScope.cancel()
        outOfFrameExecutor?.dispose()
        // A DisposableEffect onDispose throwing while the scene is being torn
        // down must not escalate to the app-fatal path (closing one window
        // would close the whole app 鈥?#622 review) nor skip
        // frameRecomposer.close().
        try {
            scene.close()
        } catch (t: Throwable) {
            bundleLogger.log(Level.SEVERE, "Unhandled exception during scene teardown", t)
        }
        frameRecomposer.close()
    }
}

private val bundleLogger: Logger = Logger.getLogger(TaoSceneBundle::class.java.name)

/**
 * Compose's `SingleComposeSceneRenderingScope`, with a hook between layout and
 * draw: swallows the layout/draw invalidations raised by the frame being
 * rendered, and schedules another frame only if the scene is still dirty
 * afterwards. Same semantics, step for step 鈥?see [TaoSceneBundle].
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoSceneRenderingScope(
    private val scheduleFrame: () -> Unit,
    /** Drained once the frame has started, so invalidations its work raises are the frame's own. */
    private val outOfFrameExecutor: TaoOutOfFrameExecutor? = null,
) {
    private var isRendering = false

    /** Wired to the scene's `invalidateLayout` / `invalidateDraw` callbacks. */
    fun onSceneInvalidation() {
        if (isRendering) return
        scheduleFrame()
    }

    fun render(
        scene: ComposeScene,
        frameRecomposer: FrameRecomposer,
        canvas: Canvas,
        nanoTime: Long,
        beforeDraw: ((Canvas) -> Unit)?,
        isClosed: () -> Boolean = { false },
    ): Boolean {
        check(!isRendering)
        isRendering = true
        try {
            outOfFrameExecutor?.onBeforeFrame()
            val frameStartNs = CpFrameTimingProbe.start()
            frameRecomposer.performFrame(nanoTime)
            val recompositionDoneNs = CpFrameTimingProbe.now()
            // The frame's own recomposition may close this scene (its window or popup left the
            // composition); laying it out then throws "RootNodeOwner is already disposed".
            if (isClosed()) return false
            // `scene.draw` applies the global snapshot again and lays out
            // before it draws: a write since the frame's apply would
            // invalidate a layer after [beforeDraw] measured the damage, and
            // that layer would be redrawn outside the repainted region with no
            // later frame to repair it (#755). Writes come from other threads
            // and, above all, from the layout itself 鈥?`onSizeChanged`,
            // `onGloballyPositioned`, `onTextLayout` storing what a draw reads 鈥?            // so the scene is applied and laid out once more after its layout,
            // as `scene.draw` would. Only writes landing during that second
            // pass or the walk itself can still race it: see
            // LayerDamageTracker.afterDraw.
            if (beforeDraw != null) Snapshot.sendApplyNotifications()
            scene.measureAndLayoutPastDisposedLayers()
            // A second layout pass is only needed when the first pass left the
            // scene invalidated. Static pages otherwise paid for two complete
            // measure/layout walks on every damage-tracked frame.
            if (beforeDraw != null && scene.hasInvalidations()) {
                Snapshot.sendApplyNotifications()
                scene.measureAndLayoutPastDisposedLayers()
            }
            val layoutDoneNs = CpFrameTimingProbe.now()
            beforeDraw?.invoke(canvas)
            scene.draw(canvas.asComposeCanvas())
            CpFrameTimingProbe.record(
                totalNs = CpFrameTimingProbe.now() - frameStartNs,
                recompositionNs = recompositionDoneNs - frameStartNs,
                layoutNs = layoutDoneNs - recompositionDoneNs,
            )
        } finally {
            isRendering = false
        }
        if (scene.hasInvalidations()) scheduleFrame()
        return true
    }
}

/**
 * [ComposeScene.measureAndLayout], past the layers the layout itself closes.
 *
 * `CanvasLayersComposeScene` lays out its owners from a copy of its list, taken before the main owner's layout. A popup
 * composed by a subcomposition (`BoxWithConstraints`, `SubcomposeLayout`) leaves it during that layout, and its owner,
 * disposed by then, is laid out next: "RootNodeOwner is already disposed" 鈥?a tab strip's overflow menu, open while
 * the window grows past the overflow. The closed layer is off the list by then, so the next pass lays out the rest.
 */
@OptIn(InternalComposeUiApi::class)
internal fun ComposeScene.measureAndLayoutPastDisposedLayers() {
    repeat(MAX_LAYOUT_PASSES - 1) {
        try {
            measureAndLayout()
            return
        } catch (e: IllegalArgumentException) {
            if (e.message != DISPOSED_OWNER_MESSAGE) throw e
        }
    }
    measureAndLayout()
}

// Compose's own message (RootNodeOwner.measureAndLayout's `require`): anything else is a real layout failure.
private const val DISPOSED_OWNER_MESSAGE = "RootNodeOwner is already disposed"

// One per layer a single layout can close, which is a handful at most.
private const val MAX_LAYOUT_PASSES = 8

/**
 * Creates a [CanvasLayersComposeScene] wired to a fresh [FrameRecomposer].
 * [requestFrame] is invoked whenever the scene needs to be repainted
 * (recomposition, relayout, redraw, or a pending animation frame); callers
 * funnel it into their coalescing frame scheduler.
 */
@OptIn(InternalComposeUiApi::class)
internal fun canvasLayersSceneBundle(
    coroutineContext: CoroutineContext,
    density: Density,
    layoutDirection: LayoutDirection,
    size: IntSize? = null,
    platformContext: PlatformContext,
    requestFrame: () -> Unit,
): TaoSceneBundle {
    ensureSkikoComposeBackend()
    RecompositionCounter.installIfEnabled()
    val closed = AtomicBoolean(false)
    // Every coroutine the scene owns carries the router: a recomposition
    // failure is offered to the window's handler first (#621) and falls
    // through to the app-fatal path (#622) when nothing swallows it.
    val exceptionRouter = TaoSceneExceptionRouter(closed)
    val sceneContext = coroutineContext + exceptionRouter
    val frameRecomposer = FrameRecomposer(sceneContext) { requestFrame() }
    val guardScope = CoroutineScope(sceneContext + SupervisorJob())
    val edtGuard = RectManagerEdtGuard(guardScope, requestFrame)
    // Starts true: the first frame must always present. See [TaoSceneBundle.visualDirty].
    val visualDirty = AtomicBoolean(true)
    val damageTracker = LayerDamageTracker()
    val outOfFrameExecutor = TaoOutOfFrameExecutor.create(guardScope, requestFrame)
    val renderingScope = TaoSceneRenderingScope(requestFrame, outOfFrameExecutor)
    val scene =
        CanvasLayersComposeScene(
            frameRecomposer = frameRecomposer,
            density = density,
            layoutDirection = layoutDirection,
            size = size,
            platformContext = platformContext.withOwnerListeners(edtGuard, damageTracker, outOfFrameExecutor),
            invalidateLayout = {
                visualDirty.set(true)
                renderingScope.onSceneInvalidation()
            },
            invalidateDraw = {
                visualDirty.set(true)
                renderingScope.onSceneInvalidation()
            },
        )
    return TaoSceneBundle(
        scene,
        frameRecomposer,
        renderingScope,
        edtGuard,
        guardScope,
        closed,
        exceptionRouter,
        requestFrame,
        visualDirty,
        damageTracker,
        outOfFrameExecutor,
    ).also { bundle -> exceptionRouter.sceneIsAlive = { bundle.isRecomposerAlive } }
}

/**
 * Creates a [PlatformLayersComposeScene] wired to a fresh [FrameRecomposer].
 * See [canvasLayersSceneBundle].
 */
@OptIn(InternalComposeUiApi::class)
internal fun platformLayersSceneBundle(
    coroutineContext: CoroutineContext,
    density: Density,
    layoutDirection: LayoutDirection,
    size: IntSize? = null,
    composeSceneContext: ComposeSceneContext,
    requestFrame: () -> Unit,
): TaoSceneBundle {
    ensureSkikoComposeBackend()
    RecompositionCounter.installIfEnabled()
    // See canvasLayersSceneBundle.
    val closed = AtomicBoolean(false)
    val exceptionRouter = TaoSceneExceptionRouter(closed)
    val sceneContext = coroutineContext + exceptionRouter
    val frameRecomposer = FrameRecomposer(sceneContext) { requestFrame() }
    val guardScope = CoroutineScope(sceneContext + SupervisorJob())
    val edtGuard = RectManagerEdtGuard(guardScope, requestFrame)
    // Starts true: the first frame must always present. See [TaoSceneBundle.visualDirty].
    val visualDirty = AtomicBoolean(true)
    val damageTracker = LayerDamageTracker()
    val outOfFrameExecutor = TaoOutOfFrameExecutor.create(guardScope, requestFrame)
    val renderingScope = TaoSceneRenderingScope(requestFrame, outOfFrameExecutor)
    val scene =
        PlatformLayersComposeScene(
            frameRecomposer = frameRecomposer,
            density = density,
            layoutDirection = layoutDirection,
            size = size,
            composeSceneContext =
                object : ComposeSceneContext by composeSceneContext {
                    override val platformContext: PlatformContext =
                        composeSceneContext.platformContext.withOwnerListeners(
                            edtGuard,
                            damageTracker,
                            outOfFrameExecutor,
                        )
                },
            invalidateLayout = {
                visualDirty.set(true)
                renderingScope.onSceneInvalidation()
            },
            invalidateDraw = {
                visualDirty.set(true)
                renderingScope.onSceneInvalidation()
            },
        )
    return TaoSceneBundle(
        scene,
        frameRecomposer,
        renderingScope,
        edtGuard,
        guardScope,
        closed,
        exceptionRouter,
        requestFrame,
        visualDirty,
        damageTracker,
        outOfFrameExecutor,
    ).also { bundle -> exceptionRouter.sceneIsAlive = { bundle.isRecomposerAlive } }
}

/**
 * Returns a [PlatformContext] view whose [PlatformContext.semanticsOwnerListener]
 * additionally registers every announced owner with [edtGuard] and
 * [damageTracker], which hands the scene [outOfFrameExecutor], and which lists its roots in [TaoRootsForTest].
 */
@OptIn(InternalComposeUiApi::class, ExperimentalNucleusApi::class)
private fun PlatformContext.withOwnerListeners(
    edtGuard: RectManagerEdtGuard,
    damageTracker: LayerDamageTracker,
    outOfFrameExecutor: TaoOutOfFrameExecutor?,
): PlatformContext =
    object : PlatformContext by this {
        override val semanticsOwnerListener: PlatformContext.SemanticsOwnerListener =
            damageTracker.wrapListener(edtGuard.wrapListener(this@withOwnerListeners.semanticsOwnerListener))
        override val outOfFrameExecutor: PlatformOutOfFrameExecutor? = outOfFrameExecutor
        override val rootForTestListener: PlatformContext.RootForTestListener =
            TaoRootsForTest.wrap(this@withOwnerListeners.rootForTestListener)
    }


/**
 * 本地帧率探针（默认关闭，仅诊断用）。
 *
 * 每次 [TaoSceneBundle.render] 调用记一帧，每 2 秒把实测帧率写到
 * `~/.cpplayer/fps-probe.log`。这跟 RTSS 统计的 Present 频率同源（render 之后
 * 紧接 host 的 present），但能由我自己读，避免"改完再让人家报数字"的低效循环。
 *
 * 打开方式：run 家族 JVM 加 `-Dcp.fps.probe=true`。生产 Native Image 不带这个
 * 属性，探针完全静默（连文件都不创建）。
 */
private object CpRenderProbe {
    private val enabled = System.getProperty("cp.fps.probe") == "true"
    private val frames = AtomicLong(0)
    private var windowStartNs = System.nanoTime()

    fun tick() {
        if (!enabled) return
        frames.incrementAndGet()
        val now = System.nanoTime()
        val elapsed = now - windowStartNs
        if (elapsed < 2_000_000_000L) return  // 每 2 秒一个窗口
        val n = frames.getAndSet(0)
        windowStartNs = now
        val fps = n * 1_000_000_000.0 / elapsed
        runCatching {
            val dir = java.io.File(System.getProperty("user.home"), ".cpplayer")
            dir.mkdirs()
            java.io.File(dir, "fps-probe.log").appendText(
                "fps=${String.format(java.util.Locale.US, "%.1f", fps)} " +
                    "frames=$n windowMs=${elapsed / 1_000_000}\n",
            )
        }
    }
}

/**
 * Optional phase timing for diagnosing low present rates.
 *
 * This deliberately measures only the Compose scene work. If these numbers stay
 * low while an external Present counter reports ~10 FPS, the stall is below this
 * layer (Skia flush, swap chain, driver, or window compositor).
 */
private object CpFrameTimingProbe {
    private val enabled = System.getProperty("cp.frame.timing") == "true"
    private val frames = AtomicLong(0)
    private val totalNs = AtomicLong(0)
    private val recompositionNs = AtomicLong(0)
    private val layoutNs = AtomicLong(0)
    private val maxTotalNs = AtomicLong(0)
    private var windowStartNs = System.nanoTime()

    fun start(): Long = if (enabled) System.nanoTime() else 0L

    fun now(): Long = if (enabled) System.nanoTime() else 0L

    fun record(totalNs: Long, recompositionNs: Long, layoutNs: Long) {
        if (!enabled) return
        frames.incrementAndGet()
        this.totalNs.addAndGet(totalNs)
        this.recompositionNs.addAndGet(recompositionNs)
        this.layoutNs.addAndGet(layoutNs)
        maxTotalNs.accumulateAndGet(totalNs) { a, b -> maxOf(a, b) }
        val now = System.nanoTime()
        val elapsed = now - windowStartNs
        if (elapsed < 2_000_000_000L) return
        val count = frames.getAndSet(0)
        val total = this.totalNs.getAndSet(0)
        val recomposition = this.recompositionNs.getAndSet(0)
        val layout = this.layoutNs.getAndSet(0)
        val max = maxTotalNs.getAndSet(0)
        windowStartNs = now
        if (count == 0L) return
        runCatching {
            val dir = java.io.File(System.getProperty("user.home"), ".cpplayer")
            dir.mkdirs()
            java.io.File(dir, "frame-timing.log").appendText(
                "frames=$count " +
                    "avgTotalMs=${"%.2f".format(java.util.Locale.US, total / count / 1_000_000.0)} " +
                    "avgRecomposeMs=${"%.2f".format(java.util.Locale.US, recomposition / count / 1_000_000.0)} " +
                    "avgLayoutMs=${"%.2f".format(java.util.Locale.US, layout / count / 1_000_000.0)} " +
                    "maxSceneMs=${"%.2f".format(java.util.Locale.US, max / 1_000_000.0)} " +
                    "windowMs=${elapsed / 1_000_000}\n",
            )
        }
    }
}
