package io.github.lengsukq.opaldesk.compositor

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.ui.OpalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Hardware-accelerated `TextureView` (`GL_TEXTURE_EXTERNAL_OES`) client surface host for a single
 * isolated VirtualDisplay window, paired with seamless LRU hibernation snapshot fallback.
 *
 * Because `TextureView` participates directly in the parent RenderNode tree (unlike `SurfaceView`
 * holes), moving, clipping, rounding corners, and scaling the window happens in strict 0-frame-lag
 * lockstep with the integrated macOS window chrome at native display refresh rate (60–165Hz).
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun WindowSurfaceHost(
    window: CompositorWindow,
    headerHeightPx: Int,
    onTouchFocusRequested: () -> Unit,
    occludedByFullscreen: Boolean = false,
    resizeCommitToken: Long = 0L,
    resizeCommitShrink: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val hostCtx = LocalContext.current
    val profileState by ProfileConfigStore.stateFlow.collectAsState()
    val cropDp = profileState.workingConfig.appTopCropDps[window.pkg] ?: 0
    val cropTopDisplayPx = if (cropDp > 0) {
        val dpi = window.contentDpi.coerceAtLeast(120)
        val maxCrop = (window.contentHeight * 0.14f).roundToInt().coerceAtLeast(0)
        (cropDp * (dpi / 160f)).roundToInt().coerceIn(0, maxCrop)
    } else {
        0
    }

    val currentWin by rememberUpdatedState(window)
    val currentHeaderPx by rememberUpdatedState(headerHeightPx)
    val currentFocusCb by rememberUpdatedState(onTouchFocusRequested)
    val currentCropTopPx by rememberUpdatedState(cropTopDisplayPx)

    var hibernateBitmap by remember(window.windowId) { mutableStateOf<Bitmap?>(null) }
    var hasRenderedLiveFrame by remember(window.windowId) { mutableStateOf(false) }
    var surfaceContainer by remember(window.windowId) { mutableStateOf<WindowSurfaceContainerView?>(null) }
    var resizeBridgeGen by remember(window.windowId) { mutableLongStateOf(0L) }
    var resizeBridgeSettleMs by remember(window.windowId) { mutableLongStateOf(ResizeBridgePolicy.SHRINK_MIN_SETTLE_MS) }
    var lastHandledCommitToken by remember(window.windowId) { mutableLongStateOf(resizeCommitToken) }
    var lastCommittedContentSize by remember(window.windowId) {
        mutableStateOf(window.contentWidth to window.contentHeight)
    }
    val liveFrameCounter = remember(window.windowId) { AtomicLong(0L) }
    val lastHoverEventTime = remember(window.windowId) { AtomicLong(-1L) }

    LaunchedEffect(window.windowId, window.isHibernated, window.snapshotVersion, occludedByFullscreen) {
        if (window.hasSnapshot) {
            val bmp = withContext(Dispatchers.IO) {
                BridgeClient.fetchWindowSnapshotBitmap(window.windowId, window.snapshotVersion)
            }
            if (bmp != null && !bmp.isRecycled) {
                hibernateBitmap = bmp
            }
        }
        if (window.isHibernated || occludedByFullscreen) {
            hasRenderedLiveFrame = false
            surfaceContainer?.clearResizeBridgeImmediately()
        }
    }

    // Schedule settle/fade-out whenever CompositorWindowNode synchronously armed the native bridge overlay
    LaunchedEffect(resizeCommitToken) {
        if (resizeCommitToken != lastHandledCommitToken) {
            lastHandledCommitToken = resizeCommitToken
            if (hasRenderedLiveFrame && !window.isHibernated && !occludedByFullscreen) {
                val container = surfaceContainer
                if (container != null && container.isResizeBridgeShowing) {
                    val animScale = readTransitionAnimationScale(hostCtx)
                    val settleMs = ResizeBridgePolicy.computeMinSettleDelayMs(
                        oldW = if (resizeCommitShrink) 1000 else 800,
                        oldH = if (resizeCommitShrink) 1000 else 800,
                        newW = if (resizeCommitShrink) 800 else 1000,
                        newH = if (resizeCommitShrink) 800 else 1000,
                        transitionAnimScale = animScale,
                    )
                    resizeBridgeSettleMs = settleMs
                    resizeBridgeGen += 1L
                    OpalLogStore.i("ResizeBridge", "armedCommit wid=${window.windowId} shrink=$resizeCommitShrink settleMs=$settleMs")
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(OpalPalette.WindowCanvasBg)
            .windowSurfaceHoverAndScrollInput(
                windowId = window.windowId,
                getCurrentWindow = { currentWin },
                getCurrentHeaderPx = { currentHeaderPx },
                getCurrentCropTopPx = { currentCropTopPx },
                lastHoverEventTime = lastHoverEventTime,
            ),
    ) {
        if (!window.isHibernated && !occludedByFullscreen) {
            val holderState = remember(window.windowId) { SurfaceBindHolder(window.windowId) }

            DisposableEffect(window.windowId) {
                onDispose {
                    surfaceContainer?.let { container ->
                        container.clearResizeBridgeImmediately()
                        LiveWindowPreviewRegistry.unregisterContainer(window.windowId, container)
                    }
                    surfaceContainer = null
                    holderState.releaseAll(notifyDaemon = true)
                }
            }

            // Keep SurfaceTexture buffer size synchronized with daemon VirtualDisplay resolution and
            // arm/re-arm the in-hierarchy freeze-frame bridge across VirtualDisplay.resize() settle.
            LaunchedEffect(window.contentWidth, window.contentHeight) {
                val cw = window.contentWidth.coerceAtLeast(240)
                val ch = window.contentHeight.coerceAtLeast(200)
                val (prevW, prevH) = lastCommittedContentSize
                lastCommittedContentSize = window.contentWidth to window.contentHeight
                if (hasRenderedLiveFrame && prevW > 0 && prevH > 0 &&
                    (prevW != window.contentWidth || prevH != window.contentHeight)
                ) {
                    val animScale = readTransitionAnimationScale(hostCtx)
                    val settleMs = ResizeBridgePolicy.computeMinSettleDelayMs(
                        oldW = prevW,
                        oldH = prevH,
                        newW = window.contentWidth,
                        newH = window.contentHeight,
                        transitionAnimScale = animScale,
                    )
                    if (settleMs > 0L) {
                        val armed = LiveWindowPreviewRegistry.armResizeBridgeSync(window.windowId)
                        if (armed) {
                            resizeBridgeSettleMs = settleMs
                            resizeBridgeGen += 1L
                            OpalLogStore.i(
                                "ResizeBridge",
                                "armedScene wid=${window.windowId} ${prevW}x${prevH}->${window.contentWidth}x${window.contentHeight} settleMs=$settleMs",
                            )
                        }
                    }
                }
                holderState.updateBufferSize(cw, ch)
            }

            // Hold the pre-resize freeze frame across BLASTSync + SurfaceFlinger setDisplaySize + ShellTransition,
            // then fade out cleanly on the first settled live frame (with a bounded timeout for static apps).
            LaunchedEffect(resizeBridgeGen) {
                if (resizeBridgeGen <= 0L) return@LaunchedEffect
                val container = surfaceContainer ?: return@LaunchedEffect
                if (!container.isResizeBridgeShowing) return@LaunchedEffect
                delay(resizeBridgeSettleMs)
                val cw = currentWin.contentWidth.coerceAtLeast(240)
                val ch = currentWin.contentHeight.coerceAtLeast(200)
                container.textureView.forceSyncBufferSize(cw, ch)
                holderState.updateBufferSize(cw, ch)
                applyTopCropTransform(container.textureView, currentCropTopPx, ch)
                val framesAtSettle = liveFrameCounter.get()
                val deadlineMs = SystemClock.uptimeMillis() + ResizeBridgePolicy.POST_SETTLE_FRAME_WAIT_MS
                while (liveFrameCounter.get() <= framesAtSettle && SystemClock.uptimeMillis() < deadlineMs) {
                    delay(16L)
                }
                OpalLogStore.i(
                    "ResizeBridge",
                    "release wid=${window.windowId} postFrames=${liveFrameCounter.get() - framesAtSettle}",
                )
                container.fadeOutResizeBridge(ResizeBridgePolicy.FADE_OUT_MS)
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WindowSurfaceContainerView(ctx).apply {
                        surfaceContainer = this
                        LiveWindowPreviewRegistry.registerContainer(window.windowId, this)
                        val tv = textureView
                        if (io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge.sessionFlow.value.isRemoteCastDisplay) {
                            tv.pointerIcon = android.view.PointerIcon.getSystemIcon(ctx, android.view.PointerIcon.TYPE_NULL)
                        }
                        // Opaque app surfaces skip per-pixel GPU alpha blending against the desktop backdrop.
                        tv.isOpaque = true
                        LiveWindowPreviewRegistry.register(window.windowId, tv)
                        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                LiveWindowPreviewRegistry.register(currentWin.windowId, tv)
                                val w = currentWin.contentWidth.coerceAtLeast(240)
                                val h = currentWin.contentHeight.coerceAtLeast(200)
                                val dpi = currentWin.contentDpi.coerceAtLeast(120)
                                val activeFps = resolveHostDisplayRefreshRate(tv)
                                tv.forceSyncBufferSize(w, h)
                                tv.post {
                                    tv.forceSyncBufferSize(w, h)
                                    applyTopCropTransform(tv, currentCropTopPx, h)
                                }
                                holderState.attachAsync(st, w, h, dpi, activeFps)
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                                // Keep buffer dimensions strictly synced with the daemon's committed VirtualDisplay
                                // resolution so transient GPU resize drags or TextureView relayouts never clobber
                                // SurfaceFlinger's BufferQueue back to view bounds (which clips/zooms content).
                                val cw = currentWin.contentWidth.coerceAtLeast(240)
                                val ch = currentWin.contentHeight.coerceAtLeast(200)
                                tv.forceSyncBufferSize(cw, ch)
                                tv.post {
                                    tv.forceSyncBufferSize(cw, ch)
                                    applyTopCropTransform(tv, currentCropTopPx, ch)
                                }
                                holderState.updateBufferSize(cw, ch)
                            }

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                LiveWindowPreviewRegistry.unregister(currentWin.windowId, tv)
                                OpalLogStore.i("Surface", "TextureView destroyed wid=${currentWin.windowId} hadFrame=$hasRenderedLiveFrame")
                                hasRenderedLiveFrame = false
                                holderState.onTextureDestroyed(st)
                                // Return false so OpalSurfBind releases Surface BEFORE SurfaceTexture,
                                // preventing native BufferQueue/writeToParcel use-after-free races.
                                return false
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                liveFrameCounter.incrementAndGet()
                                if (!hasRenderedLiveFrame) {
                                    val cw = currentWin.contentWidth.coerceAtLeast(240)
                                    val ch = currentWin.contentHeight.coerceAtLeast(200)
                                    tv.forceSyncBufferSize(cw, ch)
                                    hasRenderedLiveFrame = true
                                    OpalLogStore.i("Surface", "First live frame rendered wid=${currentWin.windowId} pkg=${currentWin.pkg}")
                                }
                            }
                        }

                        val sideNavTouchFilter = CompositorGeometry.SideNavigationTouchFilter()
                        tv.setOnTouchListener { v, ev ->
                            if (ev?.actionMasked == MotionEvent.ACTION_DOWN) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            if (ev == null || v.width <= 0 || v.height <= 0) return@setOnTouchListener false
                            val masked = ev.actionMasked
                            val isMouse = isMouseMotionEvent(ev)
                            if (masked == MotionEvent.ACTION_DOWN && isMouse &&
                                CompositorGeometry.isSideNavigationOnlyMouseButton(ev.buttonState)
                            ) {
                                val canvasX = currentWin.frame.left + ev.x
                                val canvasY = currentWin.frame.top + currentHeaderPx + ev.y
                                if (CompositorGeometry.isPointOccludedByHigherWindow(
                                        target = currentWin,
                                        canvasX = canvasX,
                                        canvasY = canvasY,
                                        visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
                                    )
                                ) {
                                    sideNavTouchFilter.reset()
                                    return@setOnTouchListener false
                                }
                            }
                            if (sideNavTouchFilter.shouldSuppress(masked, isMouse, ev.buttonState)) {
                                if (masked == MotionEvent.ACTION_DOWN && !currentWin.focused) {
                                    currentFocusCb()
                                }
                                return@setOnTouchListener true
                            }
                            forwardMotionEventToWindow(
                                v.width, v.height, ev, currentWin, currentHeaderPx, currentCropTopPx, currentFocusCb,
                            )
                        }
                        tv.setOnGenericMotionListener { v, ev ->
                            handleGenericMotionForWindow(
                                v.width, v.height, ev, currentWin, currentHeaderPx, currentCropTopPx, lastHoverEventTime, currentFocusCb,
                            )
                        }
                    }
                },
                update = { container ->
                    surfaceContainer = container
                    val cw = window.contentWidth.coerceAtLeast(240)
                    val ch = window.contentHeight.coerceAtLeast(200)
                    container.textureView.syncBufferSize(cw, ch)
                    applyTopCropTransform(container.textureView, cropTopDisplayPx, ch)
                    // Draw order and touch hit-testing across overlapping AndroidViews are driven
                    // strictly by Compose Modifier.zIndex (AndroidViewsHandler.dispatchDraw is a
                    // no-op and dispatchTouchEvent returns true) plus geometric occlusion guards.
                    // Keeping translationZ at 0f avoids HWUI RenderNode Z-barrier churn on release.
                    if (container.translationZ != 0f) container.translationZ = 0f
                    (container.parent as? View)?.let { holderView ->
                        if (holderView.translationZ != 0f) holderView.translationZ = 0f
                    }
                },
            )

            // Seamless wake bridge: keep showing frozen snapshot until the live VirtualDisplay draws frame 1
            if (!hasRenderedLiveFrame) {
                WindowWakeSnapshotOverlay(window = window, hibernateBitmap = hibernateBitmap)
            }
        } else if (occludedByFullscreen && !window.isHibernated) {
            // Completely covered by a fullscreen window: Surface is detached so SurfaceFlinger halts
            // background VirtualDisplay rendering while preserving the live task & display lease.
            Box(modifier = Modifier.fillMaxSize().background(OpalPalette.WindowCanvasBg))
        } else {
            // Hibernated window view: renders frozen TaskSnapshot with one-tap instant wake
            WindowHibernateCard(window = window, hibernateBitmap = hibernateBitmap)
        }
    }
}

/**
 * Native View container pairing [FixedBufferTextureView] (child 0) with a non-interactive sibling
 * [ImageView] freeze overlay (child 1) inside the same `AndroidView` host.
 *
 * `bridgeOverlayView` stays permanently at [View.INVISIBLE] (never [View.GONE], never `bringToFront()`)
 * so its laid-out `(0, 0, w, h)` bounds ALWAYS match `textureView` on Frame 0 of finger release
 * without triggering a `requestLayout()` pass on `AndroidViewHolder`.
 */
internal class WindowSurfaceContainerView(context: android.content.Context) : FrameLayout(context) {
    val textureView = FixedBufferTextureView(context)
    private val bridgeOverlayView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setBackgroundColor(0xFF0E1118.toInt())
        visibility = View.INVISIBLE
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
    }

    @Volatile
    var isResizeBridgeShowing: Boolean = false
        private set

    init {
        addView(
            textureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            bridgeOverlayView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = (right - left).coerceAtLeast(0)
        val h = (bottom - top).coerceAtLeast(0)
        textureView.layout(0, 0, w, h)
        bridgeOverlayView.layout(0, 0, w, h)
    }

    fun showResizeBridgeSync(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        bridgeOverlayView.animate().cancel()
        val w = width
        val h = height
        if (w > 0 && h > 0 && (bridgeOverlayView.width != w || bridgeOverlayView.height != h)) {
            bridgeOverlayView.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
            bridgeOverlayView.layout(0, 0, w, h)
        }
        bridgeOverlayView.setImageBitmap(bitmap)
        bridgeOverlayView.alpha = 1f
        bridgeOverlayView.visibility = View.VISIBLE
        isResizeBridgeShowing = true
        invalidate()
    }

    fun fadeOutResizeBridge(durationMs: Long) {
        if (!isResizeBridgeShowing) return
        isResizeBridgeShowing = false
        if (bridgeOverlayView.visibility != View.VISIBLE) {
            bridgeOverlayView.setImageDrawable(null)
            return
        }
        bridgeOverlayView.animate().cancel()
        bridgeOverlayView.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .withEndAction {
                if (!isResizeBridgeShowing) {
                    bridgeOverlayView.visibility = View.INVISIBLE
                    bridgeOverlayView.alpha = 1f
                    bridgeOverlayView.setImageDrawable(null)
                }
            }
            .start()
    }

    fun clearResizeBridgeImmediately() {
        isResizeBridgeShowing = false
        bridgeOverlayView.animate().cancel()
        bridgeOverlayView.visibility = View.INVISIBLE
        bridgeOverlayView.alpha = 1f
        bridgeOverlayView.setImageDrawable(null)
    }
}

/**
 * `TextureView` subclass that preserves the committed VirtualDisplay `SurfaceTexture` buffer size
 * across Android visibility/layout/resume transitions (preventing Android/OEM `TextureView`
 * internals from silently resetting `defaultBufferSize` back to view pixel dimensions and zooming
 * or clipping VirtualDisplay content).
 */
internal class FixedBufferTextureView(context: android.content.Context) : TextureView(context) {
    @Volatile
    private var targetBufferWidth: Int = 0

    @Volatile
    private var targetBufferHeight: Int = 0

    internal var hasCustomTopCropTransform: Boolean = false
    internal var lastAppliedCropPx: Int = 0
    internal var lastAppliedCropContentH: Int = 0
    internal var lastAppliedCropViewH: Int = 0

    fun syncBufferSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val changed = (targetBufferWidth != w || targetBufferHeight != h)
        targetBufferWidth = w
        targetBufferHeight = h
        if (changed) {
            surfaceTexture?.setDefaultBufferSize(w, h)
        }
    }

    fun forceSyncBufferSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        targetBufferWidth = w
        targetBufferHeight = h
        surfaceTexture?.setDefaultBufferSize(w, h)
    }

    private fun reapplyBufferSize() {
        val w = targetBufferWidth
        val h = targetBufferHeight
        if (w > 0 && h > 0) {
            surfaceTexture?.setDefaultBufferSize(w, h)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reapplyBufferSize()
        post { reapplyBufferSize() }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            reapplyBufferSize()
            post { reapplyBufferSize() }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            reapplyBufferSize()
            post { reapplyBufferSize() }
        }
    }
}

private fun resolveHostDisplayRefreshRate(view: TextureView): Float {
    val disp = view.display ?: return 60f
    val active = disp.mode?.refreshRate ?: disp.refreshRate
    return active.coerceIn(60f, 165f)
}

/**
 * Pure JVM-testable per-window Surface ownership & generation gate.
 * Prevents stale `onDispose` callbacks from detaching a newly mounted `TextureView` for the same
 * `windowId` and cancels queued attach tasks if the `SurfaceTexture` is destroyed first.
 */
internal class WindowSurfaceGenerationGate {
    private val lock = Any()
    private var nextOpGen = 0L
    private val activeHolderByWindow = HashMap<Int, Long>()
    private val latestOpGenByWindow = HashMap<Int, Long>()
    private val pendingDaemonDetachByWindow = HashMap<Int, Boolean>()

    fun onAttachRequested(windowId: Int, holderToken: Long): Long = synchronized(lock) {
        val gen = ++nextOpGen
        activeHolderByWindow[windowId] = holderToken
        latestOpGenByWindow[windowId] = gen
        pendingDaemonDetachByWindow[windowId] = true
        gen
    }

    fun isAttachStillValid(windowId: Int, holderToken: Long, opGen: Long): Boolean = synchronized(lock) {
        activeHolderByWindow[windowId] == holderToken && latestOpGenByWindow[windowId] == opGen
    }

    /**
     * Invalidates queued attaches for [holderToken] and returns `true` iff the daemon's
     * VirtualDisplay should detach its Surface immediately BEFORE `st.release()` abandons the
     * BufferQueue consumer.
     */
    fun onTextureDestroyed(windowId: Int, holderToken: Long): Boolean = synchronized(lock) {
        if (activeHolderByWindow[windowId] == holderToken) {
            latestOpGenByWindow[windowId] = ++nextOpGen
            pendingDaemonDetachByWindow.remove(windowId) == true
        } else {
            false
        }
    }

    /**
     * Returns `true` iff [holderToken] is still the active owner of [windowId] and has not yet
     * detached the daemon surface in [onTextureDestroyed].
     */
    fun onHolderDisposed(windowId: Int, holderToken: Long): Boolean = synchronized(lock) {
        if (activeHolderByWindow[windowId] == holderToken) {
            activeHolderByWindow.remove(windowId)
            latestOpGenByWindow[windowId] = ++nextOpGen
            pendingDaemonDetachByWindow.remove(windowId) == true
        } else {
            false
        }
    }
}

private object WindowSurfaceCoordinator {
    private val nextHolderToken = AtomicLong(1L)
    val gate = WindowSurfaceGenerationGate()
    private val bindExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalSurfBind").apply { isDaemon = true }
    }
    // Confined strictly to bindExec so Surface creation, Parcel marshaling, and release() never race.
    private val boundSurfaceByHolder = HashMap<Long, Surface>()

    fun allocateHolderToken(): Long = nextHolderToken.getAndIncrement()

    fun attachAsync(
        windowId: Int,
        holderToken: Long,
        st: SurfaceTexture,
        w: Int,
        h: Int,
        dpi: Int,
        fps: Float,
    ) {
        val opGen = gate.onAttachRequested(windowId, holderToken)
        bindExec.execute {
            if (!gate.isAttachStillValid(windowId, holderToken, opGen)) {
                return@execute
            }
            val surf = try {
                st.setDefaultBufferSize(w, h)
                Surface(st)
            } catch (_: Throwable) {
                return@execute
            }
            val prev = boundSurfaceByHolder.put(holderToken, surf)
            try {
                prev?.release()
            } catch (_: Throwable) {
            }
            if (!gate.isAttachStillValid(windowId, holderToken, opGen)) {
                boundSurfaceByHolder.remove(holderToken, surf)
                try {
                    surf.release()
                } catch (_: Throwable) {
                }
                return@execute
            }
            BridgeClient.attachWindowSurface(windowId, surf, w, h, dpi, fps)
        }
    }

    fun releaseDestroyedTexture(windowId: Int, holderToken: Long, st: SurfaceTexture) {
        val shouldDetachFirst = gate.onTextureDestroyed(windowId, holderToken)
        bindExec.execute {
            // Detach VirtualDisplay producer BEFORE releasing Surface & SurfaceTexture consumer
            // so high-FPS MediaCodec/GLES producers never hit an abandoned BufferQueue.
            if (shouldDetachFirst) {
                BridgeClient.detachWindowSurface(windowId, releaseDisplay = false)
            }
            val prev = boundSurfaceByHolder.remove(holderToken)
            try {
                prev?.release()
            } catch (_: Throwable) {
            }
            try {
                st.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun disposeHolder(windowId: Int, holderToken: Long, notifyDaemon: Boolean) {
        val shouldDetach = gate.onHolderDisposed(windowId, holderToken) && notifyDaemon
        bindExec.execute {
            if (shouldDetach) {
                BridgeClient.detachWindowSurface(windowId, releaseDisplay = false)
            }
            val prev = boundSurfaceByHolder.remove(holderToken)
            try {
                prev?.release()
            } catch (_: Throwable) {
            }
        }
    }
}

private class SurfaceBindHolder(private val windowId: Int) {
    private val holderToken = WindowSurfaceCoordinator.allocateHolderToken()

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    fun attachAsync(st: SurfaceTexture, w: Int, h: Int, dpi: Int, fps: Float) {
        surfaceTexture = st
        WindowSurfaceCoordinator.attachAsync(windowId, holderToken, st, w, h, dpi, fps)
    }

    fun updateBufferSize(w: Int, h: Int) {
        try {
            surfaceTexture?.setDefaultBufferSize(w, h)
        } catch (_: Throwable) {
        }
    }

    fun onTextureDestroyed(st: SurfaceTexture) {
        if (surfaceTexture === st) {
            surfaceTexture = null
        }
        WindowSurfaceCoordinator.releaseDestroyedTexture(windowId, holderToken, st)
    }

    fun releaseAll(notifyDaemon: Boolean) {
        surfaceTexture = null
        WindowSurfaceCoordinator.disposeHolder(windowId, holderToken, notifyDaemon)
    }
}
