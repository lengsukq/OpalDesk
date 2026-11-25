package io.github.lengsukq.opaldesk.protocol

import android.os.IBinder
import android.os.Process
import android.util.Log

/**
 * ABI-aligned Kotlin mirror of {@code io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol}
 * and caller UID / Binder descriptor trust verification.
 */
object BridgeProtocol {
    const val PROTOCOL_VERSION = 2
    const val BINDER_DESC = "io.github.lengsukq.opaldesk.daemon.IBridgeControl"
    const val CTL_SOCKET_NAME = "io.github.lengsukq.opaldesk.daemon.ctl"
    const val ACTION_NEED_BINDER = "io.github.lengsukq.opaldesk.daemon.NEED_BINDER"

    const val PROVIDER_AUTHORITY = "io.github.lengsukq.opaldesk.provider"
    const val PROVIDER_METHOD_SET_BINDER = "setBinder"
    const val PROVIDER_METHOD_SYNC_SCENE = "syncScene"
    const val PROVIDER_METHOD_PAIRING_EVENT = "pairingEvent"
    const val PROVIDER_METHOD_IME_REQUEST = "imeRequest"
    const val PROVIDER_METHOD_REMOTE_STATE_CHANGED = "remoteStateChanged"

    const val EXTRA_CALLBACK_BINDER = "cb"
    const val EXTRA_PROC_TOKEN = "proc"
    const val EXTRA_SYNC_SCENE_JSON = "sceneJson"
    const val EXTRA_SYNC_STATE_SEQ = "stateSeq"
    const val EXTRA_PAIRING_JSON = "pairingJson"
    const val EXTRA_IME_WINDOW_ID = "windowId"
    const val EXTRA_IME_SHOW = "show"
    const val EXTRA_IME_INPUT_TYPE = "inputType"

    object Tx {
        const val GET_DAEMON_STATUS = 10
        const val GET_SCENE_SNAPSHOT = 11
        const val OPEN_WINDOW = 12
        const val ATTACH_WINDOW_SURFACE = 13
        const val DETACH_WINDOW_SURFACE = 14
        const val UPDATE_WINDOW_FRAME = 15
        const val RESIZE_WINDOW_DISPLAY = 16
        const val WINDOW_ACTION = 17
        const val INJECT_WINDOW_MOTION = 18
        const val INJECT_WINDOW_KEY = 19
        const val GET_WINDOW_SNAPSHOT = 20
        const val UPDATE_COMPOSITOR_CONFIG = 21
        const val CLOSE_ALL_WINDOWS = 22
        const val GET_REMOTE_STREAM_STATUS = 23
        const val SET_WIFI_STREAM_ENABLED = 24
        const val REGENERATE_WIFI_PIN = 25
        const val DISCONNECT_REMOTE_STREAM = 26
        const val SET_EXTERNAL_DISPLAY_MODE = 27
        const val UPDATE_DISPLAY_PREFS = 28
        const val SET_MOUSE_TARGET = 29
        const val SET_POINTER_SPEED = 30
        const val SET_HOST_SCREEN_POWER = 31
        const val SET_LOCAL_IME_FOCUS = 32
        const val GET_TRUSTED_PEERS = 33
        const val REVOKE_TRUSTED_PEER = 34
        const val GET_PENDING_PAIRING = 35
        const val RESPOND_PAIRING = 36
        const val INJECT_REMOTE_TRACKPAD = 37
        const val GET_DAEMON_LOGS = 38
        const val SET_ADVANCED_DPI_CONFIG = 39
        const val SET_APP_CUSTOM_DPI = 40
        const val SYNC_APP_WINDOW_BOUNDS = 41
        const val GET_ADVANCED_CONFIG_STATE = 42
        const val SET_REMOTE_MEDIA_CONFIG = 43
        const val SHUTDOWN = 99
    }

    object PairingDecision {
        const val REJECT = 0
        const val APPROVE_ONCE = 1
        const val APPROVE_PERMANENT = 2
    }

    object MouseTarget {
        const val LOCAL = 0
        const val EXTERNAL = 1
        const val PIN_LOCAL_DISPLAY0 = 2
        const val UNPIN_LOCAL_DISPLAY0 = 3
        const val RELEASE_EXTERNAL = 4
    }

    object Profile {
        const val AUTO = 0
        const val DESKTOP = 1
        const val PORTRAIT = 2
        const val TABLET = 3
    }

    object WinState {
        const val LIVE = "LIVE"
        const val HIBERNATED = "HIBERNATED"
        const val MINIMIZED = "MINIMIZED"
    }

    object Presentation {
        const val FLOATING = "FLOATING"
        const val MAXIMIZED = "MAXIMIZED"
        const val FULLSCREEN = "FULLSCREEN"
        const val SNAP_LEFT = "SNAP_LEFT"
        const val SNAP_RIGHT = "SNAP_RIGHT"
        const val SNAP_TL = "SNAP_TL"
        const val SNAP_TR = "SNAP_TR"
        const val SNAP_BL = "SNAP_BL"
        const val SNAP_BR = "SNAP_BR"
    }

    object WinAction {
        const val FOCUS = 1
        const val MINIMIZE = 2
        const val TOGGLE_MAXIMIZE = 3
        const val CLOSE = 4
        const val SEND_BACK = 5
        const val WAKE = 6
        const val SET_PROFILE_DESKTOP = 7
        const val SET_PROFILE_PORTRAIT = 8
        const val SET_PROFILE_TABLET = 9
        const val RELOAD_APP = 10
        const val TOGGLE_FULLSCREEN = 11
    }

    object InputBackend {
        const val AUTO = 0
        const val SYSTEM = 1
        const val UHID = 2

        const val STATUS_BLOCKED = -1
        const val STATUS_SYSTEM = 1
        const val STATUS_UHID = 2
    }
}

object IpcTrustVerifier {
    private const val TAG = "OpalIpcTrust"
    const val UID_ROOT = 0
    const val UID_SYSTEM = 1000
    const val UID_SHELL = 2000

    fun isTrustedDaemonUid(uid: Int): Boolean =
        uid == UID_ROOT || uid == UID_SYSTEM || uid == UID_SHELL

    fun isTrustedCallerUid(uid: Int, selfUid: Int = Process.myUid()): Boolean =
        isTrustedDaemonUid(uid) || (selfUid >= 0 && uid == selfUid)

    fun isValidDaemonBinder(binder: IBinder?): Boolean {
        if (binder == null || !binder.pingBinder()) return false
        return try {
            binder.interfaceDescriptor == BridgeProtocol.BINDER_DESC
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to verify daemon binder descriptor", t)
            false
        }
    }
}
