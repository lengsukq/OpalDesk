package io.github.lengsukq.opaldesk.compositor

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.provider.Settings
import android.util.LruCache
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Lightweight registry of mounted live [TextureView]s so Mission Control / Exposé can capture
 * crisp real-time window previews in <5ms directly on the UI thread without daemon snapshot IPC.
 */
object LiveWindowPreviewRegistry {
    private const val MAX_PREVIEW_CACHE_KB = 12 * 1024 // 12 MiB cap for Android 17 MemoryLimiter safety
    const val RESIZE_BRIDGE_MAX_LONG_EDGE = 768
    private val liveViews = ConcurrentHashMap<Int, WeakReference<TextureView>>()
    private val liveContainers = ConcurrentHashMap<Int, WeakReference<WindowSurfaceContainerView>>()
    private val cachedPreviews = object : LruCache<Int, Bitmap>(MAX_PREVIEW_CACHE_KB) {
        override fun sizeOf(key: Int, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun register(windowId: Int, view: TextureView) {
        liveViews[windowId] = WeakReference(view)
    }

    fun unregister(windowId: Int, view: TextureView) {
        val current = liveViews[windowId]?.get()
        if (current == null || current === view) {
            liveViews.remove(windowId)
        }
    }

    internal fun registerContainer(windowId: Int, container: WindowSurfaceContainerView) {
        liveContainers[windowId] = WeakReference(container)
    }

    internal fun unregisterContainer(windowId: Int, container: WindowSurfaceContainerView) {
        val current = liveContainers[windowId]?.get()
        if (current == null || current === container) {
            liveContainers.remove(windowId)
        }
    }

    /**
     * Synchronously captures the currently settled TextureView frame and covers it with the
     * in-hierarchy sibling `bridgeOverlayView` (`ImageView` strictly above `TextureView` inside
     * the same `FrameLayout`) BEFORE `BridgeClient` dispatches `VirtualDisplay.resize()`.
     * If a bridge is already active (rapid back-to-back resizes), preserves the pristine bitmap
     * instead of snapshotting an intermediate SurfaceFlinger transition frame.
     */
    fun armResizeBridgeSync(windowId: Int, maxLongEdge: Int = RESIZE_BRIDGE_MAX_LONG_EDGE): Boolean {
        val container = liveContainers[windowId]?.get() ?: return false
        val bmp = if (container.isResizeBridgeShowing) {
            getCachedPreview(windowId)
        } else {
            captureLivePreview(windowId, maxLongEdge)
        }
        if (bmp != null && !bmp.isRecycled) {
            container.showResizeBridgeSync(bmp)
            OpalLogStore.i(
                "ResizeBridge",
                "showSync wid=$windowId view=${container.width}x${container.height} bmp=${bmp.width}x${bmp.height}",
            )
            return true
        }
        return false
    }

    /** Must be called on the Android main thread (uses [TextureView.getBitmap]). */
    fun captureLivePreview(windowId: Int, maxLongEdge: Int = 540): Bitmap? {
        val view = liveViews[windowId]?.get()
        if (view != null && view.isAvailable && view.width > 0 && view.height > 0) {
            val fresh = captureAndCache(windowId, view, maxLongEdge)
            if (fresh != null && !fresh.isRecycled) return fresh
        }
        return getCachedPreview(windowId)
    }

    fun getCachedPreview(windowId: Int): Bitmap? {
        val bmp = cachedPreviews.get(windowId)
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    fun evict(windowId: Int) {
        liveViews.remove(windowId)
        liveContainers.remove(windowId)
        cachedPreviews.remove(windowId)
    }

    fun trimMemory(clearAll: Boolean) {
        if (clearAll) {
            cachedPreviews.evictAll()
        } else {
            cachedPreviews.trimToSize(MAX_PREVIEW_CACHE_KB / 2)
        }
    }

    private fun captureAndCache(windowId: Int, view: TextureView, maxLongEdge: Int = 540): Bitmap? {
        val vw = view.width.coerceAtLeast(1)
        val vh = view.height.coerceAtLeast(1)
        val scale = (maxLongEdge.toFloat() / maxOf(vw, vh).toFloat()).coerceAtMost(1f)
        val bw = (vw * scale).toInt().coerceAtLeast(64)
        val bh = (vh * scale).toInt().coerceAtLeast(64)
        return try {
            val bmp = view.getBitmap(bw, bh)
            if (bmp != null && !bmp.isRecycled) {
                cachedPreviews.put(windowId, bmp)
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Pure JVM-testable timing policy for masking Android `VirtualDisplay.resize()` intermediate
 * frames (`BLASTSyncEngine` + `CompositionEngine.setProjection` old-bounds/new-content top-left
 * shrink + `ShellTransitions` `CHANGE` animation) behind the window's crisp pre-resize frame.
 */
internal object ResizeBridgePolicy {
    const val SHRINK_MIN_SETTLE_MS = 380L
    const val EXPAND_MIN_SETTLE_MS = 320L
    const val NO_ANIM_MIN_SETTLE_MS = 160L
    const val MAX_MIN_SETTLE_MS = 560L
    const val POST_SETTLE_FRAME_WAIT_MS = 160L
    const val FADE_OUT_MS = 85L

    fun computeMinSettleDelayMs(
        oldW: Int,
        oldH: Int,
        newW: Int,
        newH: Int,
        transitionAnimScale: Float,
    ): Long {
        if (oldW <= 0 || oldH <= 0 || newW <= 0 || newH <= 0) return 0L
        if (oldW == newW && oldH == newH) return 0L
        if (transitionAnimScale <= 0.01f) {
            return NO_ANIM_MIN_SETTLE_MS
        }
        val shrinking = newW < oldW || newH < oldH
        val base = if (shrinking) SHRINK_MIN_SETTLE_MS else EXPAND_MIN_SETTLE_MS
        val scaled = (base * transitionAnimScale.coerceIn(0.5f, 1.5f)).roundToInt().toLong()
        return scaled.coerceIn(NO_ANIM_MIN_SETTLE_MS, MAX_MIN_SETTLE_MS)
    }
}

private fun readTransitionAnimationScale(ctx: android.content.Context): Float = try {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
} catch (_: Throwable) {
    1f
}

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
            // Intercept Compose PointerEventType.Scroll AND unpressed Mouse Hover directly:
            // AndroidComposeView intercepts GenericMotionEvents (ACTION_SCROLL & ACTION_HOVER_MOVE)
            // into Compose PointerInput rather than AndroidView.onGenericMotionListener whenever
            // ancestor pointerInput modifiers exist. Forwarding hover here keeps the VirtualDisplay
            // cursor continuously synchronized before every USB mouse click!
            .pointerInput(window.windowId, "mouse_hover_and_scroll") {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val change = ev.changes.firstOrNull() ?: continue
                        if (ev.type == PointerEventType.Scroll) {
                            val delta = change.scrollDelta
                            // Android app windows inside OpalWin-* run at higher content DPI and apply
                            // native wheel-step scaling (RecyclerView / WebView), making raw trackpad
                            // ticks 2x-3x faster than the desktop App Drawer; damp slightly for parity.
                            val appScrollDamp = 0.55f
                            val vScroll = -delta.y * appScrollDamp
                            val hScroll = delta.x * appScrollDamp
                            if (vScroll != 0f || hScroll != 0f) {
                                val win = currentWin
                                val canvasX = win.frame.left + change.position.x
                                val canvasY = win.frame.top + currentHeaderPx + change.position.y
                                val occluded = CompositorGeometry.isPointOccludedByHigherWindow(
                                    target = win,
                                    canvasX = canvasX,
                                    canvasY = canvasY,
                                    visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
                                )
                                if (!occluded && win.isLive && win.displayId > 0) {
                                    change.consume()
                                    val mappedX = CompositorGeometry.mapViewToDisplayCoord(
                                        change.position.x,
                                        size.width,
                                        win.contentWidth.coerceAtLeast(2),
                                    )
                                    val mappedY = mapViewYToDisplayY(
                                        change.position.y,
                                        size.height,
                                        win.contentHeight.coerceAtLeast(2),
                                        currentCropTopPx,
                                    )
                                    val now = SystemClock.uptimeMillis()
                                    BridgeClient.injectWindowMotionOneway(
                                        windowId = win.windowId,
                                        action = MotionEvent.ACTION_SCROLL,
                                        downTime = now,
                                        eventTime = now,
                                        source = InputDevice.SOURCE_MOUSE,
                                        buttonState = 0,
                                        actionButton = 0,
                                        hScroll = hScroll,
                                        vScroll = vScroll,
                                        pointerIds = intArrayOf(0),
                                        toolTypes = intArrayOf(MotionEvent.TOOL_TYPE_MOUSE),
                                        xs = floatArrayOf(mappedX),
                                        ys = floatArrayOf(mappedY),
                                        pressures = floatArrayOf(0f),
                                    )
                                }
                            }
                        } else if (
                            change.type == PointerType.Mouse &&
                            !change.pressed &&
                            !change.previousPressed &&
                            (ev.type == PointerEventType.Move ||
                                ev.type == PointerEventType.Enter ||
                                ev.type == PointerEventType.Exit)
                        ) {
                            val win = currentWin
                            if (win.isLive && win.displayId > 0 && size.width > 0 && size.height > 0) {
                                val canvasX = win.frame.left + change.position.x
                                val canvasY = win.frame.top + currentHeaderPx + change.position.y
                                val occluded = ev.type != PointerEventType.Exit && CompositorGeometry.isPointOccludedByHigherWindow(
                                    target = win,
                                    canvasX = canvasX,
                                    canvasY = canvasY,
                                    visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
                                )
                                val evtTime = change.uptimeMillis.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
                                if (!occluded && lastHoverEventTime.getAndSet(evtTime) != evtTime) {
                                    val hoverAction = when (ev.type) {
                                        PointerEventType.Enter -> MotionEvent.ACTION_HOVER_ENTER
                                        PointerEventType.Exit -> MotionEvent.ACTION_HOVER_EXIT
                                        else -> MotionEvent.ACTION_HOVER_MOVE
                                    }
                                    val mappedX = CompositorGeometry.mapViewToDisplayCoord(
                                        change.position.x,
                                        size.width,
                                        win.contentWidth.coerceAtLeast(2),
                                    )
                                    val mappedY = mapViewYToDisplayY(
                                        change.position.y,
                                        size.height,
                                        win.contentHeight.coerceAtLeast(2),
                                        currentCropTopPx,
                                    )
                                    BridgeClient.injectWindowMotionOneway(
                                        windowId = win.windowId,
                                        action = hoverAction,
                                        downTime = evtTime,
                                        eventTime = evtTime,
                                        source = InputDevice.SOURCE_MOUSE,
                                        buttonState = 0,
                                        actionButton = 0,
                                        hScroll = 0f,
                                        vScroll = 0f,
                                        pointerIds = intArrayOf(0),
                                        toolTypes = intArrayOf(MotionEvent.TOOL_TYPE_MOUSE),
                                        xs = floatArrayOf(mappedX),
                                        ys = floatArrayOf(mappedY),
                                        pressures = floatArrayOf(0f),
                                    )
                                }
                            }
                        }
                    }
                }
            },
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
                val snap = hibernateBitmap ?: LiveWindowPreviewRegistry.getCachedPreview(window.windowId)
                if (snap != null && !snap.isRecycled) {
                    Image(
                        bitmap = snap.asImageBitmap(),
                        contentDescription = window.title,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else if (occludedByFullscreen && !window.isHibernated) {
            // Completely covered by a fullscreen window: Surface is detached so SurfaceFlinger halts
            // background VirtualDisplay rendering while preserving the live task & display lease.
            Box(modifier = Modifier.fillMaxSize().background(OpalPalette.WindowCanvasBg))
        } else {
            // Hibernated window view: renders frozen TaskSnapshot with one-tap instant wake
            val snap = hibernateBitmap
            if (snap != null && !snap.isRecycled) {
                Image(
                    bitmap = snap.asImageBitmap(),
                    contentDescription = window.title,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66080A10))
                    .clickable {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.WAKE)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.GlassSurface)
                            .border(1.dp, OpalPalette.BorderFocused, CircleShape)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        OpalText(
                            text = "后台快照休眠 · 点击立即唤醒",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.TextPrimary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    OpalText(
                        text = "自动回收后台虚拟屏配额，唤醒保留应用现场",
                        fontSize = 11.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
            }
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

private fun isMouseMotionEvent(ev: MotionEvent): Boolean =
    (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
        (ev.pointerCount > 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

private fun applyTopCropTransform(view: FixedBufferTextureView, cropTopDisplayPx: Int, contentH: Int) {
    val viewH = view.height
    if (cropTopDisplayPx <= 0 || contentH <= 2 || viewH <= 0 || cropTopDisplayPx >= contentH - 2) {
        if (view.hasCustomTopCropTransform) {
            view.hasCustomTopCropTransform = false
            view.lastAppliedCropPx = 0
            view.lastAppliedCropContentH = 0
            view.lastAppliedCropViewH = 0
            view.setTransform(null)
        }
        return
    }
    if (view.hasCustomTopCropTransform &&
        view.lastAppliedCropPx == cropTopDisplayPx &&
        view.lastAppliedCropContentH == contentH &&
        view.lastAppliedCropViewH == viewH
    ) {
        return
    }
    val cropRatio = cropTopDisplayPx.toFloat() / contentH.toFloat()
    val scaleY = 1f / (1f - cropRatio)
    val matrix = Matrix().apply {
        setScale(1f, scaleY)
        postTranslate(0f, -cropRatio * viewH * scaleY)
    }
    view.hasCustomTopCropTransform = true
    view.lastAppliedCropPx = cropTopDisplayPx
    view.lastAppliedCropContentH = contentH
    view.lastAppliedCropViewH = viewH
    view.setTransform(matrix)
}

private fun mapViewYToDisplayY(viewY: Float, viewH: Int, contentH: Int, cropTopDisplayPx: Int): Float {
    if (cropTopDisplayPx <= 0 || cropTopDisplayPx >= contentH - 2) {
        return CompositorGeometry.mapViewToDisplayCoord(viewY, viewH, contentH)
    }
    val visibleH = (contentH - cropTopDisplayPx).coerceAtLeast(2)
    val norm = (viewY / viewH.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    return (cropTopDisplayPx + norm * (visibleH - 1)).coerceIn(0f, (contentH - 1).toFloat())
}

private fun handleGenericMotionForWindow(
    viewW: Int,
    viewH: Int,
    ev: MotionEvent?,
    win: CompositorWindow,
    headerHeightPx: Int,
    cropTopDisplayPx: Int,
    lastHoverEventTime: AtomicLong,
    onFocusRequested: () -> Unit,
): Boolean {
    if (ev == null || viewW <= 0 || viewH <= 0) return false
    val masked = ev.actionMasked
    if (masked == MotionEvent.ACTION_BUTTON_PRESS || masked == MotionEvent.ACTION_BUTTON_RELEASE) {
        val canvasX = win.frame.left + ev.x
        val canvasY = win.frame.top + headerHeightPx + ev.y
        if (CompositorGeometry.isPointOccludedByHigherWindow(
                target = win,
                canvasX = canvasX,
                canvasY = canvasY,
                visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
            )
        ) {
            return false
        }
        if (masked == MotionEvent.ACTION_BUTTON_PRESS) {
            if (!win.focused) onFocusRequested()
            if (ev.actionButton == MotionEvent.BUTTON_BACK && win.isLive && win.displayId > 0) {
                if (io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher.tryClaimMouseSideBack()) {
                    BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.SEND_BACK)
                }
                return true
            }
        }
        // Consume mouse button generic events locally without injecting invalid ACTION_BUTTON_PRESS/RELEASE
        // into IInputManager (actual click press/release is delivered cleanly via onTouchListener ACTION_DOWN/UP).
        return isMouseMotionEvent(ev)
    }
    if (masked == MotionEvent.ACTION_HOVER_ENTER ||
        masked == MotionEvent.ACTION_HOVER_MOVE ||
        masked == MotionEvent.ACTION_HOVER_EXIT
    ) {
        if (lastHoverEventTime.getAndSet(ev.eventTime) == ev.eventTime) {
            return true
        }
        return forwardMotionEventToWindow(viewW, viewH, ev, win, headerHeightPx, cropTopDisplayPx, onFocusRequested)
    }
    if (masked == MotionEvent.ACTION_SCROLL) {
        return forwardMotionEventToWindow(viewW, viewH, ev, win, headerHeightPx, cropTopDisplayPx, onFocusRequested)
    }
    return false
}

private fun forwardMotionEventToWindow(
    viewW: Int,
    viewH: Int,
    ev: MotionEvent?,
    win: CompositorWindow,
    headerHeightPx: Int,
    cropTopDisplayPx: Int,
    onFocusRequested: () -> Unit,
): Boolean {
    if (ev == null || viewW <= 0 || viewH <= 0) return false
    val masked = ev.actionMasked
    val canvasX = win.frame.left + ev.x
    val canvasY = win.frame.top + (if (win.isFullscreen) 0 else headerHeightPx) + ev.y
    // Geometric Z-order occlusion guard: never accept clicks/hovers underneath a higher-Z floating window
    if (masked == MotionEvent.ACTION_DOWN ||
        masked == MotionEvent.ACTION_HOVER_ENTER ||
        masked == MotionEvent.ACTION_HOVER_MOVE ||
        masked == MotionEvent.ACTION_SCROLL
    ) {
        if (CompositorGeometry.isPointOccludedByHigherWindow(
                target = win,
                canvasX = canvasX,
                canvasY = canvasY,
                visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
            )
        ) {
            return false
        }
    }
    if (masked == MotionEvent.ACTION_DOWN) {
        val isMouseDown = isMouseMotionEvent(ev)
        io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.recordWindowPointerDown(
            windowId = win.windowId,
            displayId = win.displayId,
            canvasX = canvasX,
            canvasY = canvasY,
            modality = if (isMouseDown) {
                io.github.lengsukq.opaldesk.ime.InputModality.MOUSE
            } else {
                io.github.lengsukq.opaldesk.ime.InputModality.TOUCH
            },
            uptimeMs = ev.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis(),
        )
    }
    if (!win.isLive || win.displayId <= 0) {
        if (!win.focused && masked == MotionEvent.ACTION_DOWN) {
            onFocusRequested()
        }
        return true
    }

    val count = ev.pointerCount.coerceIn(1, 10)
    val ids = IntArray(count)
    val toolTypes = IntArray(count)
    val xs = FloatArray(count)
    val ys = FloatArray(count)
    val pressures = FloatArray(count)

    val cw = win.contentWidth.coerceAtLeast(2)
    val ch = win.contentHeight.coerceAtLeast(2)
    for (i in 0 until count) {
        ids[i] = ev.getPointerId(i)
        toolTypes[i] = ev.getToolType(i)
        xs[i] = CompositorGeometry.mapViewToDisplayCoord(ev.getX(i), viewW, cw)
        ys[i] = mapViewYToDisplayY(ev.getY(i), viewH, ch, cropTopDisplayPx)
        pressures[i] = ev.getPressure(i)
    }

    val isMouse = isMouseMotionEvent(ev)
    val effSource = when {
        isMouse -> InputDevice.SOURCE_MOUSE
        toolTypes[0] == MotionEvent.TOOL_TYPE_STYLUS -> InputDevice.SOURCE_STYLUS
        else -> InputDevice.SOURCE_TOUCHSCREEN
    }
    val normButtonState = if (
        isMouse &&
        (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_MOVE) &&
        ev.buttonState == 0
    ) {
        MotionEvent.BUTTON_PRIMARY
    } else {
        ev.buttonState
    }
    val hScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_HSCROLL) else 0f
    val vScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_VSCROLL) else 0f

    BridgeClient.injectWindowMotionOneway(
        windowId = win.windowId,
        action = ev.action,
        downTime = ev.downTime,
        eventTime = ev.eventTime,
        source = effSource,
        buttonState = normButtonState,
        actionButton = 0,
        hScroll = hScroll,
        vScroll = vScroll,
        pointerIds = ids,
        toolTypes = toolTypes,
        xs = xs,
        ys = ys,
        pressures = pressures,
    )
    return true
}
