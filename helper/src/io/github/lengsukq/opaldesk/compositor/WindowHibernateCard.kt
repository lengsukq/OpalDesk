package io.github.lengsukq.opaldesk.compositor

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import android.util.LruCache
import android.view.TextureView
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
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

internal fun readTransitionAnimationScale(ctx: Context): Float = try {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
} catch (_: Throwable) {
    1f
}

@Composable
internal fun WindowWakeSnapshotOverlay(
    window: CompositorWindow,
    hibernateBitmap: Bitmap?,
) {
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

@Composable
internal fun WindowHibernateCard(
    window: CompositorWindow,
    hibernateBitmap: Bitmap?,
) {
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
