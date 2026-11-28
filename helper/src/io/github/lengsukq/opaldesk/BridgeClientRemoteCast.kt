package io.github.lengsukq.opaldesk

import android.content.Context
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.PairingJsonParser
import io.github.lengsukq.opaldesk.protocol.PendingPairingRequest
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.protocol.TrustedPeerEntry

internal fun BridgeClient.refreshRemoteStatusImpl(): RemoteStreamStatus {
    val svc = getService()
    if (svc == null) {
        val empty = RemoteStreamStatus()
        _remoteStatusFlow.value = empty
        lastAutoResAppliedRemoteDisplayId = -1
        return empty
    }
    return transact(svc, BridgeProtocol.Tx.GET_REMOTE_STREAM_STATUS, oneway = false, writeArgs = {}) { r ->
        RemoteStreamStatus(
            connected = r.readInt() != 0,
            sinkType = r.readInt(),
            peerName = r.readString().orEmpty(),
            width = r.readInt(),
            height = r.readInt(),
            fpsX100 = r.readInt(),
            bitrateKbps = r.readInt(),
            wifiEnabled = r.readInt() != 0,
            wifiPort = r.readInt(),
            certFingerprint = r.readString().orEmpty(),
            activeSessionPermanent = r.readInt() != 0,
            trustedCount = r.readInt(),
            lanIp = r.readString().orEmpty(),
            desktopDisplayId = if (r.dataAvail() >= 4) r.readInt() else -1,
            usbTetherIp = if (r.dataAvail() > 0) r.readString().orEmpty() else "",
            usbTetherInterface = if (r.dataAvail() > 0) r.readString().orEmpty() else "",
            tlsBindScope = if (r.dataAvail() >= 4) r.readInt() else BridgeProtocol.TlsBindScope.ALL,
        )
    }?.also { st ->
        _remoteStatusFlow.value = st
        maybeAutoApplyCustomResolutionToRemote(st)
        io.github.lengsukq.opaldesk.cast.BleNearbyService.onRemoteStatusUpdated(st)
    } ?: _remoteStatusFlow.value
}

private fun BridgeClient.maybeAutoApplyCustomResolutionToRemote(st: RemoteStreamStatus) {
    if (!st.hasActiveDesktopCast || st.desktopDisplayId <= 0) {
        lastAutoResAppliedRemoteDisplayId = -1
        return
    }
    if (st.desktopDisplayId == lastAutoResAppliedRemoteDisplayId) return
    lastAutoResAppliedRemoteDisplayId = st.desktopDisplayId
    val ctx = appContext ?: return
    if (!AppPrefs.isAutoCustomResolutionEnabled(ctx)) return
    val pref = AppPrefs.getDefaultCustomResolution(ctx)
    if (pref.width != st.width || pref.height != st.height) {
        setExternalDisplayModeAsync(-1, pref.width, pref.height, pref.fps.toFloat())
    }
}

internal fun BridgeClient.setWifiStreamEnabledAsyncImpl(
    enabled: Boolean,
    port: Int,
    bindScope: Int,
    onDone: (() -> Unit)?,
) {
    ioExec.execute {
        val svc = getService()
        if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.SET_WIFI_STREAM_ENABLED,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(if (enabled) 1 else 0)
                    d.writeInt(port)
                    d.writeInt(bindScope)
                },
            ) { r ->
                r.readInt()
                r.readInt()
                r.readString()
                if (r.dataAvail() >= 4) r.readInt()
            }
            refreshRemoteStatus()
            refreshPendingPairing()
        }
        if (onDone != null) mainHandler.post(onDone)
    }
}

internal fun BridgeClient.refreshTrustedPeersImpl(): List<TrustedPeerEntry> {
    val svc = getService()
    if (svc == null) {
        _trustedPeersFlow.value = emptyList()
        return emptyList()
    }
    val json = transact(svc, BridgeProtocol.Tx.GET_TRUSTED_PEERS, oneway = false, writeArgs = {}) { r ->
        r.readString().orEmpty()
    } ?: "[]"
    return PairingJsonParser.parseTrustedPeers(json).also { _trustedPeersFlow.value = it }
}

internal fun BridgeClient.refreshPendingPairingImpl(): PendingPairingRequest? {
    val svc = getService()
    if (svc == null) {
        _pendingPairingFlow.value = null
        return null
    }
    val json = transact(svc, BridgeProtocol.Tx.GET_PENDING_PAIRING, oneway = false, writeArgs = {}) { r ->
        r.readString().orEmpty()
    } ?: ""
    return PairingJsonParser.parsePending(json).also { _pendingPairingFlow.value = it }
}

internal fun BridgeClient.respondToPairingAsyncImpl(
    requestId: String,
    decision: Int,
    ctx: Context?,
    onDone: ((Boolean) -> Unit)?,
) {
    if (ctx != null) {
        BridgeService.updatePairingNotification(ctx, null)
    }
    ioExec.execute {
        val svc = getService()
        val ok = if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.RESPOND_PAIRING,
                oneway = false,
                writeArgs = { d ->
                    d.writeString(requestId)
                    d.writeInt(decision)
                },
            ) { it.readInt() != 0 } ?: false
        } else {
            false
        }
        val nextPending = refreshPendingPairing()
        if (ctx != null) {
            BridgeService.updatePairingNotification(ctx, nextPending)
        }
        refreshTrustedPeers()
        refreshRemoteStatus()
        if (onDone != null) mainHandler.post { onDone(ok) }
    }
}

internal fun BridgeClient.revokeTrustedPeerAsyncImpl(clientId: String, onDone: ((Boolean) -> Unit)?) {
    ioExec.execute {
        val svc = getService()
        val ok = if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.REVOKE_TRUSTED_PEER,
                oneway = false,
                writeArgs = { d -> d.writeString(clientId) },
            ) { it.readInt() != 0 } ?: false
        } else {
            false
        }
        refreshTrustedPeers()
        refreshRemoteStatus()
        if (onDone != null) mainHandler.post { onDone(ok) }
    }
}

internal fun BridgeClient.disconnectRemoteStreamAsyncImpl() {
    ioExec.execute {
        val svc = getService() ?: return@execute
        transact(svc, BridgeProtocol.Tx.DISCONNECT_REMOTE_STREAM, oneway = false, writeArgs = {}) { it.readInt() }
        refreshRemoteStatus()
    }
}

internal fun BridgeClient.setRemoteMediaConfigAsyncImpl(
    ctx: Context?,
    micEnabled: Boolean,
    cameraEnabled: Boolean,
    onDone: ((Boolean) -> Unit)?,
) {
    bindAppContext(ctx)
    val curStatus = _statusFlow.value
    val canUseRootCamera = curStatus.connected && curStatus.rootEnhanced
    if (cameraEnabled && !canUseRootCamera) {
        AppPrefs.setRemoteCameraEnabled(ctx, false)
        if (onDone != null) mainHandler.post { onDone(false) }
        return
    }
    val effectiveCamera = cameraEnabled && canUseRootCamera
    AppPrefs.setRemoteMicEnabled(ctx, micEnabled)
    AppPrefs.setRemoteCameraEnabled(ctx, effectiveCamera)
    ioExec.execute {
        val svc = getService()
        val ok = if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.SET_REMOTE_MEDIA_CONFIG,
                oneway = false,
                writeArgs = { d ->
                    d.writeInt(if (micEnabled) 1 else 0)
                    d.writeInt(if (effectiveCamera) 1 else 0)
                },
            ) { it.readInt() != 0 } ?: false
        } else {
            false
        }
        if (svc != null) {
            val updated = refreshStatus()
            if (!updated.rootEnhanced && effectiveCamera) {
                AppPrefs.setRemoteCameraEnabled(ctx, false)
            }
        }
        if (onDone != null) mainHandler.post { onDone(ok) }
    }
}

internal fun BridgeClient.setAudioRouteModeAsyncImpl(
    ctx: Context?,
    mode: Int,
    onDone: ((Boolean) -> Unit)?,
) {
    bindAppContext(ctx)
    val clamped = mode.coerceIn(
        BridgeProtocol.AudioRoute.ALL_TO_DEX,
        BridgeProtocol.AudioRoute.SPLIT_BY_APP,
    )
    AppPrefs.setAudioRouteMode(ctx, clamped)
    ioExec.execute {
        val svc = getService()
        val ok = if (svc != null) {
            transact(
                svc,
                BridgeProtocol.Tx.SET_AUDIO_ROUTE_MODE,
                oneway = false,
                writeArgs = { d -> d.writeInt(clamped) },
            ) { it.readInt() != 0 } ?: false
        } else {
            false
        }
        if (svc != null) {
            refreshStatus()
        }
        if (onDone != null) mainHandler.post { onDone(ok) }
    }
}

internal fun BridgeClient.injectRemoteTrackpadOnewayImpl(
    action: Int,
    x: Float,
    y: Float,
    hScroll: Float,
    vScroll: Float,
    buttonState: Int,
) {
    inputExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.INJECT_REMOTE_TRACKPAD,
            oneway = true,
            writeArgs = { d ->
                d.writeInt(action)
                d.writeFloat(x)
                d.writeFloat(y)
                d.writeFloat(hScroll)
                d.writeFloat(vScroll)
                d.writeInt(buttonState)
            },
            readReply = { Unit },
        )
    }
}

internal fun BridgeClient.syncNotificationsAsyncImpl(json: String) {
    if (json.isBlank()) return
    companionSyncExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.SYNC_NOTIFICATIONS,
            oneway = false,
            writeArgs = { d -> d.writeString(json) },
        ) { it.readInt() }
    }
}

internal fun BridgeClient.syncMediaStateAsyncImpl(json: String) {
    if (json.isBlank()) return
    companionSyncExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.SYNC_MEDIA_STATE,
            oneway = false,
            writeArgs = { d -> d.writeString(json) },
        ) { it.readInt() }
    }
}

internal fun BridgeClient.syncCallStateAsyncImpl(callStateJson: String) {
    if (callStateJson.isBlank()) return
    companionSyncExec.execute {
        val svc = getService() ?: return@execute
        transact(
            svc,
            BridgeProtocol.Tx.SYNC_CALL_STATE,
            oneway = false,
            writeArgs = { d -> d.writeString(callStateJson) },
        ) { it.readInt() }
    }
}

internal fun BridgeClient.triggerHotspotSyncImpl(clientId: String, nonceHex: String, hmacHex: String): String {
    val svc = getService()
        ?: return """{"status":"error","ssid":"","gatewayIp":"","port":0,"message":"OpalDesk 多窗引擎未连接"}"""
    return transact(
        svc,
        BridgeProtocol.Tx.TRIGGER_HOTSPOT,
        oneway = false,
        writeArgs = { d ->
            d.writeString(clientId)
            d.writeString(nonceHex)
            d.writeString(hmacHex)
        },
    ) { r ->
        r.readString().orEmpty()
    }?.takeIf { it.isNotBlank() }
        ?: """{"status":"error","ssid":"","gatewayIp":"","port":0,"message":"热点触发 IPC 通信失败"}"""
}
