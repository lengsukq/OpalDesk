package io.github.lengsukq.opaldesk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Surface
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorSceneParser

internal fun BridgeClient.refreshSceneSnapshotImpl(): CompositorScene {
    val svc = getService() ?: return _sceneFlow.value
    val json = transact(svc, BridgeProtocol.Tx.GET_SCENE_SNAPSHOT, oneway = false, writeArgs = {}) { reply ->
        reply.readString()
    }
    if (!json.isNullOrBlank()) {
        val parsed = CompositorSceneParser.parse(json)
        _sceneFlow.value = parsed
        return parsed
    }
    return _sceneFlow.value
}

internal fun BridgeClient.fetchSecondaryUserAppsJsonImpl(): String {
    val svc = getService() ?: return "[]"
    return transact(svc, BridgeProtocol.Tx.GET_SECONDARY_USER_APPS, oneway = false, writeArgs = {}) { reply ->
        reply.readString().orEmpty()
    }?.takeIf { it.isNotBlank() } ?: "[]"
}

internal fun BridgeClient.openWindowImpl(
    pkg: String,
    activityClass: String,
    profileCode: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    userId: Int,
): Int {
    val safeUserId = userId.coerceAtLeast(0)
    val svc = getService() ?: run {
        OpalLogStore.w("IPC", "openWindow($pkg, u=$safeUserId) aborted: daemon not connected")
        return -1
    }
    val winId = transact(
        svc,
        BridgeProtocol.Tx.OPEN_WINDOW,
        oneway = false,
        writeArgs = { d ->
            d.writeString(pkg)
            d.writeString(activityClass)
            d.writeInt(profileCode)
            d.writeInt(left)
            d.writeInt(top)
            d.writeInt(right)
            d.writeInt(bottom)
            if (safeUserId > 0) {
                d.writeInt(safeUserId)
            }
        },
    ) { it.readInt() } ?: -1
    if (winId > 0) {
        OpalLogStore.i("Window", "openWindow($pkg, u=$safeUserId) -> wid=$winId")
        scheduleWindowLifecycleLogSync(delaysMs = longArrayOf(400L), minIntervalMs = 2000L)
    } else {
        OpalLogStore.w("Window", "openWindow($pkg, u=$safeUserId) failed (wid=$winId)")
    }
    return winId
}

internal fun BridgeClient.attachWindowSurfaceImpl(
    windowId: Int,
    surface: Surface,
    contentW: Int,
    contentH: Int,
    dpi: Int,
    fps: Float,
): Int {
    val svc = getService() ?: run {
        OpalLogStore.w("Surface", "attachWindowSurface(wid=$windowId) aborted: daemon not connected")
        return -1
    }
    val displayId = transact(
        svc,
        BridgeProtocol.Tx.ATTACH_WINDOW_SURFACE,
        oneway = false,
        writeArgs = { d ->
            d.writeInt(windowId)
            d.writeInt(contentW)
            d.writeInt(contentH)
            d.writeInt(dpi)
            d.writeInt((fps * 100f).toInt())
            d.writeInt(1)
            surface.writeToParcel(d, 0)
        },
    ) { it.readInt() } ?: -1
    if (displayId > 0) {
        OpalLogStore.i("Surface", "attachWindowSurface wid=$windowId -> displayId=$displayId (${contentW}x${contentH}@${dpi}dpi)")
        scheduleWindowLifecycleLogSync(delaysMs = longArrayOf(280L, 1100L), minIntervalMs = 900L)
    } else {
        OpalLogStore.e("Surface", "attachWindowSurface FAILED wid=$windowId (${contentW}x${contentH}@${dpi}dpi)")
    }
    return displayId
}

internal fun BridgeClient.detachWindowSurfaceImpl(windowId: Int, releaseDisplay: Boolean): Boolean {
    val svc = getService() ?: return false
    return transact(
        svc,
        BridgeProtocol.Tx.DETACH_WINDOW_SURFACE,
        oneway = false,
        writeArgs = { d ->
            d.writeInt(windowId)
            d.writeInt(if (releaseDisplay) 1 else 0)
        },
    ) { it.readInt() != 0 } ?: false
}

internal fun BridgeClient.updateWindowFrameAsyncImpl(
    windowId: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    presentation: String,
) {
    ioExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.UPDATE_WINDOW_FRAME,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(windowId)
                d.writeInt(left)
                d.writeInt(top)
                d.writeInt(right)
                d.writeInt(bottom)
                d.writeString(presentation)
            },
        ) { it.readInt() }
    }
}

internal fun BridgeClient.resizeWindowDisplayOnewayImpl(windowId: Int, contentW: Int, contentH: Int, dpi: Int) {
    inputExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.RESIZE_WINDOW_DISPLAY,
            oneway = true,
            writeArgs = { d ->
                d.writeInt(windowId)
                d.writeInt(contentW)
                d.writeInt(contentH)
                d.writeInt(dpi)
            },
            readReply = { Unit },
        )
    }
}

internal fun BridgeClient.sendWindowActionAsyncImpl(windowId: Int, action: Int) {
    ioExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.WINDOW_ACTION,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(windowId)
                d.writeInt(action)
            },
        ) { it.readInt() }
    }
}

internal fun BridgeClient.injectWindowMotionOnewayImpl(
    windowId: Int,
    action: Int,
    downTime: Long,
    eventTime: Long,
    source: Int,
    buttonState: Int,
    actionButton: Int,
    hScroll: Float,
    vScroll: Float,
    pointerIds: IntArray,
    toolTypes: IntArray,
    xs: FloatArray,
    ys: FloatArray,
    pressures: FloatArray,
) {
    inputExec.execute {
        val svc = getService() ?: return@execute
        val count = pointerIds.size
        if (count <= 0) return@execute
        transact(
            svc,
            BridgeProtocol.Tx.INJECT_WINDOW_MOTION,
            oneway = true,
            writeArgs = { d ->
                d.writeInt(windowId)
                d.writeInt(action)
                d.writeLong(downTime)
                d.writeLong(eventTime)
                d.writeInt(source)
                d.writeInt(buttonState)
                d.writeInt(actionButton)
                d.writeFloat(hScroll)
                d.writeFloat(vScroll)
                d.writeInt(count)
                for (i in 0 until count) {
                    d.writeInt(pointerIds[i])
                    d.writeInt(toolTypes[i])
                    d.writeFloat(xs[i])
                    d.writeFloat(ys[i])
                    d.writeFloat(pressures[i])
                }
            },
            readReply = { Unit },
        )
    }
}

internal fun BridgeClient.injectWindowKeyAsyncImpl(
    windowId: Int,
    action: Int,
    keyCode: Int,
    metaState: Int,
    deviceId: Int,
    scanCode: Int,
    repeatCount: Int,
) {
    inputExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.INJECT_WINDOW_KEY,
            oneway = true,
            writeArgs = { d ->
                d.writeInt(windowId)
                d.writeInt(action)
                d.writeInt(keyCode)
                d.writeInt(metaState)
                d.writeInt(deviceId)
                d.writeInt(scanCode)
                d.writeInt(repeatCount)
            },
            readReply = { Unit },
        )
    }
}

internal fun BridgeClient.fetchWindowSnapshotBitmapImpl(windowId: Int, snapshotVer: Long): Bitmap? {
    val cacheKey = "$windowId:$snapshotVer"
    snapshotCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
    val svc = getService() ?: return null
    val bytes = transact(
        svc,
        BridgeProtocol.Tx.GET_WINDOW_SNAPSHOT,
        oneway = false,
        writeArgs = { d -> d.writeInt(windowId) },
    ) { it.createByteArray() }
    if (bytes == null || bytes.isEmpty()) return null
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { bmp ->
            snapshotCache.put(cacheKey, bmp)
        }
    } catch (_: Throwable) {
        null
    }
}

internal fun BridgeClient.updateCompositorConfigAsyncImpl(
    inputBackendMode: Int,
    maxVdCount: Int,
    canvasW: Int,
    canvasH: Int,
    canvasDpi: Int,
    dockInsetDp: Int = -1,
) {
    ioExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.UPDATE_COMPOSITOR_CONFIG,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(inputBackendMode)
                d.writeInt(maxVdCount)
                d.writeInt(canvasW)
                d.writeInt(canvasH)
                d.writeInt(canvasDpi)
                d.writeInt(dockInsetDp)
            },
        ) { it.readInt() }
        refreshStatus()
        refreshSceneSnapshot()
    }
}

internal fun BridgeClient.closeAllWindowsAsyncImpl(removeTasks: Boolean) {
    ioExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.CLOSE_ALL_WINDOWS,
            oneway = false,
            writeArgs = { d -> d.writeInt(if (removeTasks) 1 else 0) },
        ) { it.readInt() }
        refreshSceneSnapshot()
    }
}

internal fun BridgeClient.restoreSavedLayoutAsyncImpl(
    profile: io.github.lengsukq.opaldesk.prefs.WorkspaceProfileConfig,
    canvasW: Int,
    canvasH: Int,
    onDone: ((Int) -> Unit)?,
) {
    val layout = profile.savedLayout
    if (layout.isEmpty()) {
        if (onDone != null) mainHandler.post { onDone(0) }
        return
    }
    ioExec.execute {
        val w = canvasW.coerceAtLeast(360).toFloat()
        val h = canvasH.coerceAtLeast(360).toFloat()
        var restored = 0
        for (entry in layout.sortedBy { it.zOrder }) {
            val l = (entry.leftRatio * w).toInt()
            val t = (entry.topRatio * h).toInt()
            val r = (entry.rightRatio * w).toInt()
            val b = (entry.bottomRatio * h).toInt()
            val winId = openWindow(entry.pkg, entry.activityClass, entry.profileCode, l, t, r, b, entry.userId)
            if (winId > 0) {
                restored++
                if (entry.presentation != BridgeProtocol.Presentation.FLOATING) {
                    updateWindowFrameAsync(winId, l, t, r, b, entry.presentation)
                }
                if (entry.minimized) {
                    sendWindowActionAsync(winId, BridgeProtocol.WinAction.MINIMIZE)
                }
            }
        }
        refreshSceneSnapshot()
        if (onDone != null) mainHandler.post { onDone(restored) }
    }
}

internal fun BridgeClient.fetchDaemonLogsImpl(maxLines: Int): String {
    val svc = getService() ?: return ""
    val logs = transact(
        svc,
        BridgeProtocol.Tx.GET_DAEMON_LOGS,
        oneway = false,
        writeArgs = { d -> d.writeInt(maxLines.coerceIn(20, 500)) },
    ) { it.readString().orEmpty() }.orEmpty()
    if (logs.isNotBlank()) {
        OpalLogStore.updateDaemonLogs(logs)
        lastDaemonLogSyncMs = android.os.SystemClock.uptimeMillis()
    }
    return logs
}

internal fun BridgeClient.syncDaemonLogsThrottledLocked(minIntervalMs: Long, maxLines: Int = 350) {
    val now = android.os.SystemClock.uptimeMillis()
    synchronized(daemonLogSyncLock) {
        if (minIntervalMs > 0L && now - lastDaemonLogSyncMs < minIntervalMs) return
        lastDaemonLogSyncMs = now
    }
    fetchDaemonLogs(maxLines)
}

internal fun BridgeClient.scheduleWindowLifecycleLogSync(delaysMs: LongArray, minIntervalMs: Long) {
    val gen = surfaceLogSyncGen.incrementAndGet()
    delaysMs.forEach { delayMs ->
        mainHandler.postDelayed({
            if (surfaceLogSyncGen.get() != gen || !isBinderAliveFast()) return@postDelayed
            ioExec.execute {
                syncDaemonLogsThrottledLocked(minIntervalMs = minIntervalMs, maxLines = 350)
            }
        }, delayMs)
    }
}

internal fun BridgeClient.fetchDaemonLogsAsyncImpl(maxLines: Int, onDone: ((String) -> Unit)?) {
    ioExec.execute {
        val text = fetchDaemonLogs(maxLines)
        if (onDone != null) mainHandler.post { onDone(text) }
    }
}
