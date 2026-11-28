package io.github.lengsukq.opaldesk

import android.content.Context
import android.os.IBinder
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.prefs.WorkspaceProfileConfig
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.DexImeRuntimeState
import io.github.lengsukq.opaldesk.protocol.DexImeStateParser

internal fun BridgeClient.setExternalDisplayModeAsyncImpl(
    displayId: Int,
    width: Int,
    height: Int,
    fps: Float,
    scalingMode: Int,
    onDone: ((Boolean) -> Unit)?,
) {
    ioExec.execute {
        val svc = getService()
        val ok = if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.SET_EXTERNAL_DISPLAY_MODE,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(displayId)
                    d.writeInt(width)
                    d.writeInt(height)
                    d.writeInt((fps * 100f).toInt())
                    d.writeInt(scalingMode)
                },
            ) { it.readInt() != 0 } ?: false
        } else {
            false
        }
        if (ok) {
            refreshRemoteStatus()
            refreshStatus()
            BridgeService.requestExternalDisplayRescan(0L)
            BridgeService.requestExternalDisplayRescan(350L)
        }
        if (onDone != null) mainHandler.post { onDone(ok) }
    }
}

internal fun BridgeClient.setHostScreenPowerAsyncImpl(screenOn: Boolean, onDone: ((Boolean) -> Unit)?) {
    ioExec.execute {
        val svc = getService()
        val ok = if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.SET_HOST_SCREEN_POWER,
                oneway = false,
                writeArgs = { d -> d.writeInt(if (screenOn) 1 else 0) },
            ) { it.readInt() != 0 } ?: false
        } else {
            false
        }
        refreshStatus()
        if (onDone != null) mainHandler.post { onDone(ok) }
    }
}

internal fun BridgeClient.tryWakeHostScreenFromKeyEventImpl(event: android.view.KeyEvent): Boolean {
    if (!_statusFlow.value.hostScreenOff) return false
    val isWakeKey = when (event.keyCode) {
        android.view.KeyEvent.KEYCODE_VOLUME_UP,
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
        android.view.KeyEvent.KEYCODE_POWER,
        android.view.KeyEvent.KEYCODE_WAKEUP -> true
        else -> false
    }
    if (!isWakeKey) return false
    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
        setHostScreenPowerAsync(true)
    }
    return true
}

internal fun BridgeClient.updateDisplayPrefsAsyncImpl(
    targetFps: Int,
    windowDpiScalePct: Int,
    windowSupersampleMode: Int,
) {
    ProfileConfigStore.persistWorkingDisplayPrefs(
        ctx = appContext,
        targetFps = targetFps,
        windowDpiScalePct = windowDpiScalePct,
    )
    ioExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.UPDATE_DISPLAY_PREFS,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(targetFps)
                d.writeInt(windowDpiScalePct)
                if (windowSupersampleMode >= 0) {
                    d.writeInt(windowSupersampleMode)
                }
            },
        ) { it.readInt() }
        refreshStatus()
        refreshSceneSnapshot()
    }
}

internal fun BridgeClient.launchExternalDesktopOnDisplayAsyncImpl(
    displayId: Int,
    isWireless: Boolean,
    onDone: ((Boolean) -> Unit)?,
) {
    if (displayId <= 0) {
        if (onDone != null) mainHandler.post { onDone(false) }
        return
    }
    ioExec.execute {
        val svc = getService()
        val ok = if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.LAUNCH_EXTERNAL_DESKTOP,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(displayId)
                    d.writeInt(if (isWireless) 1 else 0)
                },
            ) { it.readInt() != 0 } ?: false
        } else {
            false
        }
        if (onDone != null) mainHandler.post { onDone(ok) }
    }
}

internal fun BridgeClient.setGlobalCustomDpiAsyncImpl(ctx: Context?, globalCustomDpi: Int) {
    bindAppContext(ctx)
    val updated = ProfileConfigStore.setWorkingGlobalCustomDpi(globalCustomDpi)
    val gen = globalDpiGen.incrementAndGet()
    dpiExec.execute {
        if (globalDpiGen.get() != gen) return@execute
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.SET_ADVANCED_DPI_CONFIG,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(updated.globalCustomDpi)
                d.writeInt(0)
                d.writeString("")
            },
        ) { it.readInt() }
        if (globalDpiGen.get() == gen) {
            refreshStatus()
            refreshSceneSnapshot()
        }
    }
}

internal fun BridgeClient.setAppCustomDpiAsyncImpl(ctx: Context?, pkg: String, customDpi: Int) {
    bindAppContext(ctx)
    val clamped = ProfileConfigStore.clampDpiOrAuto(customDpi)
    ProfileConfigStore.setWorkingAppCustomDpi(pkg, clamped)
    val gen = globalDpiGen.incrementAndGet()
    appDpiGenByPkg[pkg] = gen
    dpiExec.execute {
        if (appDpiGenByPkg[pkg] != gen) return@execute
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.SET_APP_CUSTOM_DPI,
            oneway = false,
            writeArgs = { d ->
                d.writeString(pkg)
                d.writeInt(clamped)
            },
        ) { it.readInt() }
        if (appDpiGenByPkg[pkg] == gen) {
            refreshSceneSnapshot()
        }
    }
}

internal fun BridgeClient.syncProfileToDaemonAsyncImpl(
    profile: WorkspaceProfileConfig,
    onDone: (() -> Unit)?,
) {
    ioExec.execute {
        val svc = getService()
        if (svc != null) {
            syncProfileToDaemonSync(svc, profile)
            refreshStatus()
            refreshSceneSnapshot()
        }
        if (onDone != null) mainHandler.post(onDone)
    }
}

internal fun BridgeClient.syncProfileToDaemonSync(
    svc: IBinder,
    profile: WorkspaceProfileConfig,
) {
    transact(
        svc,
        BridgeProtocol.Tx.UPDATE_COMPOSITOR_CONFIG,
        oneway = false,
        writeArgs = { d ->
            d.writeInt(profile.inputBackendMode)
            d.writeInt(profile.maxVdCount)
            d.writeInt(-1)
            d.writeInt(-1)
            d.writeInt(-1)
        },
    ) { it.readInt() }
    transact(
        svc,
        BridgeProtocol.Tx.UPDATE_DISPLAY_PREFS,
        oneway = false,
        writeArgs = { d ->
            d.writeInt(profile.targetFps)
            d.writeInt(profile.windowDpiScalePct)
        },
    ) { it.readInt() }
    transact(
        svc,
        BridgeProtocol.Tx.SYNC_APP_WINDOW_BOUNDS,
        oneway = false,
        writeArgs = { d -> d.writeString(profile.formatAppBoundsForDaemon()) },
    ) { it.readInt() }
    transact(
        svc,
        BridgeProtocol.Tx.SET_ADVANCED_DPI_CONFIG,
        oneway = false,
        writeArgs = { d ->
            d.writeInt(profile.globalCustomDpi)
            d.writeInt(1)
            d.writeString(profile.formatAppDpisForDaemon())
        },
    ) { it.readInt() }
}

internal fun BridgeClient.pullDaemonWindowBoundsSyncImpl() {
    val svc = getService() ?: return
    val json = transact(svc, BridgeProtocol.Tx.GET_ADVANCED_CONFIG_STATE, oneway = false, writeArgs = {}) {
        it.readString().orEmpty()
    }
    ProfileConfigStore.mergeDaemonBoundsIntoWorking(json)
}

internal fun BridgeClient.pullDaemonWindowBoundsAsyncImpl(onDone: (() -> Unit)?) {
    ioExec.execute {
        pullDaemonWindowBoundsSync()
        if (onDone != null) mainHandler.post(onDone)
    }
}

internal fun BridgeClient.setMouseTargetAsyncImpl(
    targetMode: Int,
    externalDisplayId: Int,
    onDone: ((Int) -> Unit)?,
) {
    ioExec.execute {
        val svc = getService()
        val bound = if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.SET_MOUSE_TARGET,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(targetMode)
                    d.writeInt(externalDisplayId)
                },
            ) { r ->
                r.readInt()
                if (r.dataAvail() >= 4) r.readInt() else 0
            } ?: 0
        } else {
            0
        }
        refreshStatus()
        if (onDone != null) mainHandler.post { onDone(bound) }
    }
}

internal fun BridgeClient.setLocalDesktopMousePinAsyncImpl(pinned: Boolean) {
    localDesktopMousePinned = pinned
    val mode = if (pinned) {
        BridgeProtocol.MouseTarget.PIN_LOCAL_DISPLAY0
    } else {
        BridgeProtocol.MouseTarget.UNPIN_LOCAL_DISPLAY0
    }
    ioExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.SET_MOUSE_TARGET,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(mode)
                d.writeInt(-1)
            },
        ) { it.readInt() }
    }
}

internal fun BridgeClient.releaseExternalMouseRoutingAsyncImpl() {
    ioExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.SET_MOUSE_TARGET,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(BridgeProtocol.MouseTarget.RELEASE_EXTERNAL)
                d.writeInt(-1)
            },
        ) { it.readInt() }
        refreshStatus()
    }
}

internal fun BridgeClient.onHostTaskRemovedImpl(hasPhysicalExternal: Boolean) {
    localDesktopMousePinned = false
    ioExec.execute {
        val svc = getService() ?: return@execute
        if (_statusFlow.value.hostScreenOff && !_remoteStatusFlow.value.connected && !hasPhysicalExternal) {
            transact(
                svc,
                BridgeProtocol.Tx.SET_HOST_SCREEN_POWER,
                oneway = false,
                writeArgs = { d -> d.writeInt(1) },
            ) { it.readInt() }
        }
        val releaseMode = if (!hasPhysicalExternal && !_remoteStatusFlow.value.connected) {
            BridgeProtocol.MouseTarget.RELEASE_EXTERNAL
        } else {
            BridgeProtocol.MouseTarget.UNPIN_LOCAL_DISPLAY0
        }
        transact(
            svc,
            BridgeProtocol.Tx.SET_MOUSE_TARGET,
            oneway = false,
            writeArgs = { d ->
                d.writeInt(releaseMode)
                d.writeInt(-1)
            },
        ) { it.readInt() }
        refreshStatus()
    }
}

internal fun BridgeClient.setPointerSpeedAsyncImpl(sensitivity: Float) {
    inputExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.SET_POINTER_SPEED,
            oneway = true,
            writeArgs = { d -> d.writeFloat(sensitivity) },
            readReply = { Unit },
        )
    }
}

internal fun BridgeClient.sendLocalImeFocus(
    focused: Boolean,
    hostDisplayId: Int,
    oneway: Boolean,
    onAck: (() -> Unit)?,
) {
    inputExec.execute {
        val svc = getService()
        if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.SET_LOCAL_IME_FOCUS,
                oneway = oneway,
                writeArgs = { d ->
                    d.writeInt(if (focused) 1 else 0)
                    d.writeInt(hostDisplayId.coerceAtLeast(0))
                },
                readReply = { r -> if (!oneway && r.dataAvail() >= 4) r.readInt() else 1 },
            )
        }
        if (onAck != null) {
            mainHandler.post(onAck)
        }
    }
}

internal fun BridgeClient.syncDexImeConfigSync(svc: IBinder, ctx: Context) {
    val displayMode = AppPrefs.getDexImeDisplayMode(ctx)
    val lockedIme = AppPrefs.getDexLockedImeId(ctx)
    val muteOnCast = AppPrefs.isMuteHostImeOnCastEnabled(ctx)
    val hardKbd = AppPrefs.getHardKbdSoftImeMode(ctx)
    val autoBridgeCast = AppPrefs.isAutoBridgeImeOnCastEnabled(ctx)
    transact(svc, BridgeProtocol.Tx.SET_DEX_IME_CONFIG, oneway = false, writeArgs = { d ->
        d.writeInt(displayMode)
        d.writeString(lockedIme)
        d.writeInt(if (muteOnCast) 1 else 0)
        d.writeInt(hardKbd)
        d.writeInt(if (autoBridgeCast) 1 else 0)
    }) { r ->
        r.readInt()
        if (r.dataAvail() > 0) r.readString()
    }
}

internal fun BridgeClient.setDexImeConfigAsyncImpl(
    ctx: Context?,
    displayMode: Int,
    lockedImeId: String,
    muteHostOnCast: Boolean,
    showWithHardKbd: Int,
    autoBridgeImeOnCast: Boolean,
    onDone: ((ok: Boolean, activeImeId: String) -> Unit)?,
) {
    if (ctx != null) {
        AppPrefs.setDexImeDisplayMode(ctx, displayMode)
        AppPrefs.setDexLockedImeId(ctx, lockedImeId)
        AppPrefs.setMuteHostImeOnCastEnabled(ctx, muteHostOnCast)
        AppPrefs.setHardKbdSoftImeMode(ctx, showWithHardKbd)
        AppPrefs.setAutoBridgeImeOnCastEnabled(ctx, autoBridgeImeOnCast)
    }
    ioExec.execute {
        val svc = getService()
        if (svc == null) {
            if (onDone != null) mainHandler.post { onDone(false, "") }
            return@execute
        }
        val result = transact(svc, BridgeProtocol.Tx.SET_DEX_IME_CONFIG, oneway = false, writeArgs = { d ->
            d.writeInt(displayMode)
            d.writeString(lockedImeId.trim())
            d.writeInt(if (muteHostOnCast) 1 else 0)
            d.writeInt(showWithHardKbd)
            d.writeInt(if (autoBridgeImeOnCast) 1 else 0)
        }) { r ->
            val ok = r.readInt() != 0
            val activeIme = if (r.dataAvail() > 0) r.readString().orEmpty() else ""
            ok to activeIme
        }
        refreshDexImeState()
        if (onDone != null) {
            mainHandler.post { onDone(result?.first == true, result?.second.orEmpty()) }
        }
    }
}

internal fun BridgeClient.setDexImeSessionActiveImpl(tag: String, active: Boolean) {
    val wasActive = activeDexSessionTags.isNotEmpty()
    if (active) {
        activeDexSessionTags.add(tag)
    } else {
        activeDexSessionTags.remove(tag)
    }
    val nowActive = activeDexSessionTags.isNotEmpty()
    if (wasActive == nowActive) return
    ioExec.execute {
        val svc = getService() ?: return@execute
        transact(svc, BridgeProtocol.Tx.SET_DEX_IME_SESSION_ACTIVE, oneway = false, writeArgs = { d ->
            d.writeInt(if (nowActive) 1 else 0)
        }) { it.readInt() }
        refreshDexImeState()
    }
}

internal fun BridgeClient.refreshDexImeStateImpl(): DexImeRuntimeState {
    val svc = getService()
    if (svc == null) {
        val empty = DexImeRuntimeState()
        _imeStateFlow.value = empty
        return empty
    }
    val json = transact(svc, BridgeProtocol.Tx.GET_SYSTEM_IME_STATE, oneway = false, writeArgs = {}) { r ->
        r.readString().orEmpty()
    }
    return DexImeStateParser.parse(json).also { _imeStateFlow.value = it }
}

internal fun BridgeClient.refreshDexImeStateAsyncImpl(onDone: ((DexImeRuntimeState) -> Unit)?) {
    ioExec.execute {
        val state = refreshDexImeState()
        if (onDone != null) mainHandler.post { onDone(state) }
    }
}

internal fun BridgeClient.notifyBridgeImeEventAsyncImpl(
    eventType: Int,
    imeDisplayId: Int,
    inputType: Int,
    pkg: String,
    onYieldResult: ((String) -> Unit)?,
) {
    inputExec.execute {
        val svc = getService()
        if (svc == null) {
            if (onYieldResult != null) mainHandler.post { onYieldResult("") }
            return@execute
        }
        val oneway = onYieldResult == null && eventType != BridgeProtocol.BridgeImeEvent.YIELD_TO_DISPLAY0
        val yieldedImeId = transact(
            svc,
            BridgeProtocol.Tx.NOTIFY_BRIDGE_IME_EVENT,
            oneway = oneway,
            writeArgs = { d ->
                d.writeInt(eventType)
                d.writeInt(imeDisplayId)
                d.writeInt(inputType)
                d.writeString(pkg)
            },
        ) { r ->
            if (!oneway) {
                r.readInt()
                if (r.dataAvail() > 0) r.readString().orEmpty() else ""
            } else {
                ""
            }
        }.orEmpty()
        if (eventType == BridgeProtocol.BridgeImeEvent.YIELD_TO_DISPLAY0) {
            ioExec.execute { refreshDexImeState() }
        }
        if (onYieldResult != null) {
            mainHandler.post { onYieldResult(yieldedImeId) }
        }
    }
}
