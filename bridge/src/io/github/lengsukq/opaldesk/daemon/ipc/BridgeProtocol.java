package io.github.lengsukq.opaldesk.daemon.ipc;

/**
 * Single source of truth for the OpalDesk v2 Universal Compositor Binder IPC wire protocol,
 * LocalSocket control channel, Helper ContentProvider contracts, window profiles, and actions.
 *
 * <p>100% OEM-agnostic: zero freeform/multi-window mode hacks. Every window session runs
 * standard Android {@code WINDOWING_MODE_FULLSCREEN (1)} inside an isolated {@code VirtualDisplay}.
 */
public final class BridgeProtocol {
    public static final int PROTOCOL_VERSION = 2;

    // ---- Binder, LocalSocket & Helper IPC identifiers ----
    public static final String BINDER_DESC = "io.github.lengsukq.opaldesk.daemon.IBridgeControl";
    public static final String CTL_SOCKET_NAME = "io.github.lengsukq.opaldesk.daemon.ctl";
    public static final String ACTION_NEED_BINDER = "io.github.lengsukq.opaldesk.daemon.NEED_BINDER";

    public static final String HELPER_PROVIDER_AUTHORITY = "io.github.lengsukq.opaldesk.provider";
    public static final String HELPER_ACTION_BINDER_PUSH = "io.github.lengsukq.opaldesk.BINDER_PUSH";
    public static final String HELPER_SERVICE_CLASS = "io.github.lengsukq.opaldesk.BridgeService";
    public static final String HELPER_RECEIVER_CLASS = "io.github.lengsukq.opaldesk.AdbPairingReceiver";
    public static final String OPALDESK_BRIDGE_IME_SERVICE_SUFFIX = "/.ime.OpalDeskImeService";
    public static final String OPALDESK_BRIDGE_IME_ID =
            io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig.HELPER_PACKAGE + OPALDESK_BRIDGE_IME_SERVICE_SUFFIX;

    /** Optional standalone same-cert Yuyan/Rime companion IME plugin APK. */
    public static final String OPALDESK_IME_PLUGIN_PACKAGE = "io.github.lengsukq.opaldesk.ime";
    public static final String OPALDESK_IME_PLUGIN_SERVICE_SUFFIX = "/.OpalYuyanImeService";
    public static final String OPALDESK_IME_PLUGIN_ID =
            OPALDESK_IME_PLUGIN_PACKAGE + OPALDESK_IME_PLUGIN_SERVICE_SUFFIX;

    public static boolean isManagedOpalImeId(String imeId) {
        if (imeId == null || imeId.isEmpty()) return false;
        String clean = imeId.trim();
        return OPALDESK_BRIDGE_IME_ID.equals(clean) || OPALDESK_IME_PLUGIN_ID.equals(clean);
    }

    public static final String PROVIDER_METHOD_SET_BINDER = "setBinder";
    public static final String PROVIDER_METHOD_SYNC_SCENE = "syncScene";
    public static final String PROVIDER_METHOD_PAIRING_EVENT = "pairingEvent";
    public static final String PROVIDER_METHOD_IME_REQUEST = "imeRequest";
    public static final String PROVIDER_METHOD_IME_COMMIT_TEXT = "imeCommitText";
    public static final String PROVIDER_METHOD_REMOTE_STATE_CHANGED = "remoteStateChanged";
    public static final String PROVIDER_METHOD_PLUGIN_IME_SYNC = "pluginImeSync";

    public static final String EXTRA_SYNC_SCENE_JSON = "sceneJson";
    public static final String EXTRA_SYNC_STATE_SEQ = "stateSeq";
    public static final String EXTRA_PAIRING_JSON = "pairingJson";
    public static final String EXTRA_IME_WINDOW_ID = "windowId";
    public static final String EXTRA_IME_SHOW = "show";
    public static final String EXTRA_IME_INPUT_TYPE = "inputType";
    public static final String EXTRA_IME_COMMIT_TEXT = "text";
    public static final String EXTRA_IME_COMMITTED = "committed";

    public static final String CTL_CMD_SHUTDOWN = "SHUTDOWN";
    public static final String CTL_CMD_NEED_BINDER = "NEED_BINDER";
    public static final String CTL_REPLY_DONE = "DONE";
    public static final String CTL_REPLY_OK = "OK";

    // ---- Universal Compositor Binder Transaction Codes ----
    public static final int TX_GET_DAEMON_STATUS = 10;
    public static final int TX_GET_SCENE_SNAPSHOT = 11;
    public static final int TX_OPEN_WINDOW = 12;
    public static final int TX_ATTACH_WINDOW_SURFACE = 13;
    public static final int TX_DETACH_WINDOW_SURFACE = 14;
    public static final int TX_UPDATE_WINDOW_FRAME = 15;
    public static final int TX_RESIZE_WINDOW_DISPLAY = 16;
    public static final int TX_WINDOW_ACTION = 17;
    public static final int TX_INJECT_WINDOW_MOTION = 18;
    public static final int TX_INJECT_WINDOW_KEY = 19;
    public static final int TX_GET_WINDOW_SNAPSHOT = 20;
    public static final int TX_UPDATE_COMPOSITOR_CONFIG = 21;
    public static final int TX_CLOSE_ALL_WINDOWS = 22;
    public static final int TX_GET_REMOTE_STREAM_STATUS = 23;
    public static final int TX_SET_WIFI_STREAM_ENABLED = 24;
    public static final int TX_REGENERATE_WIFI_PIN = 25;
    public static final int TX_DISCONNECT_REMOTE_STREAM = 26;
    public static final int TX_SET_EXTERNAL_DISPLAY_MODE = 27;
    public static final int TX_UPDATE_DISPLAY_PREFS = 28;
    public static final int TX_SET_MOUSE_TARGET = 29;
    public static final int TX_SET_POINTER_SPEED = 30;
    public static final int TX_SET_HOST_SCREEN_POWER = 31;
    public static final int TX_SET_LOCAL_IME_FOCUS = 32;
    public static final int TX_GET_TRUSTED_PEERS = 33;
    public static final int TX_REVOKE_TRUSTED_PEER = 34;
    public static final int TX_GET_PENDING_PAIRING = 35;
    public static final int TX_RESPOND_PAIRING = 36;
    public static final int TX_INJECT_REMOTE_TRACKPAD = 37;
    public static final int TX_GET_DAEMON_LOGS = 38;
    public static final int TX_SET_ADVANCED_DPI_CONFIG = 39;
    public static final int TX_SET_APP_CUSTOM_DPI = 40;
    public static final int TX_SYNC_APP_WINDOW_BOUNDS = 41;
    public static final int TX_GET_ADVANCED_CONFIG_STATE = 42;
    public static final int TX_SET_REMOTE_MEDIA_CONFIG = 43;
    public static final int TX_SET_AUDIO_ROUTE_MODE = 44;
    public static final int TX_LAUNCH_EXTERNAL_DESKTOP = 45;
    public static final int TX_SET_DEX_IME_CONFIG = 46;
    public static final int TX_SET_DEX_IME_SESSION_ACTIVE = 47;
    public static final int TX_GET_SYSTEM_IME_STATE = 48;
    public static final int TX_NOTIFY_BRIDGE_IME_EVENT = 49;
    public static final int TX_SHUTDOWN = 99;

    // ---- Built-in / Companion Plugin IME Event Codes (TX_NOTIFY_BRIDGE_IME_EVENT) ----
    public static final int BRIDGE_IME_EVT_START_INPUT = 1;
    public static final int BRIDGE_IME_EVT_SHOW_REQUESTED = 2;
    public static final int BRIDGE_IME_EVT_HIDE_OR_FINISH = 3;
    public static final int BRIDGE_IME_EVT_YIELD_TO_DISPLAY0 = 4;
    public static final int BRIDGE_IME_EVT_PLUGIN_PKG_CHANGED = 5;

    // ---- Downlink Audio Routing Modes (OpalCast DeX) ----
    /** Legacy/default: capture all system playback audio (REMOTE_SUBMIX) to DeX receiver. */
    public static final int AUDIO_ROUTE_ALL_TO_DEX = 0;
    /** Per-app split: phone Display 0 apps play on phone; DeX apps route exclusively to DeX receiver. */
    public static final int AUDIO_ROUTE_SPLIT_BY_APP = 1;

    // ---- TLS Stream Listener Bind Scopes (TX_SET_WIFI_STREAM_ENABLED) ----
    /** Default: bind wildcard 0.0.0.0 across Wi-Fi LAN and any active USB tethering interfaces. */
    public static final int TLS_BIND_SCOPE_ALL = 0;
    /** Beta opt-in: bind strictly to the active USB tethering IPv4 interface so Wi-Fi ports stay closed. */
    public static final int TLS_BIND_SCOPE_USB_TETHER_ONLY = 1;

    // ---- Interactive Pairing Approval Decisions (TX_RESPOND_PAIRING) ----
    public static final int PAIRING_DECISION_REJECT = 0;
    public static final int PAIRING_DECISION_APPROVE_ONCE = 1;
    public static final int PAIRING_DECISION_APPROVE_PERMANENT = 2;

    // ---- Physical External Mouse Output Routing Targets ----
    public static final int MOUSE_TARGET_LOCAL = 0;
    public static final int MOUSE_TARGET_EXTERNAL = 1;
    /** Transient runtime pin: associates external physical mice/keyboards to Display 0 while Local DeX is active. */
    public static final int MOUSE_TARGET_PIN_LOCAL_DISPLAY0 = 2;
    /** Releases transient Display 0 pin when Local DeX pauses or closes. */
    public static final int MOUSE_TARGET_UNPIN_LOCAL_DISPLAY0 = 3;
    /** Releases active external/remote display routing back to Display 0 without overwriting saved user prefs. */
    public static final int MOUSE_TARGET_RELEASE_EXTERNAL = 4;

    // ---- Standard AOSP Windowing Mode (the ONLY mode used per isolated VirtualDisplay) ----
    public static final int WINDOWING_MODE_FULLSCREEN = 1;

    // ---- VirtualDisplay Flag Bitmasks (AOSP DisplayManager constants) ----
    /** VIRTUAL_DISPLAY_FLAG_PUBLIC (1<<0) | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY (1<<3) */
    public static final int VD_FLAGS_BASE_PUBLIC = (1 << 0) | (1 << 3);
    /**
     * Trusted VirtualDisplay flags granted to {@code com.android.shell} (uid=2000) that participate
     * in Android's global focus & InputMethodManagerService handoff:
     * PUBLIC (0x1) | OWN_CONTENT_ONLY (0x8) | SUPPORTS_TOUCH (0x40) | TRUSTED (0x400).
     * Intentionally omits OWN_FOCUS (0x4000) and STEAL_TOP_FOCUS_DISABLED (0x10000) so tapping any
     * EditText inside an isolated app window transfers IMMS client focus and opens the system IME.
     * Excludes PRESENTATION (0x2) so video apps never show "casting to external screen" placeholders.
     */
    public static final int VD_FLAGS_TRUSTED_IME = 0x00449;
    public static final int VD_FLAGS_TRUSTED_ISOLATED = 0x14449;
    public static final int VD_FLAGS_TRUSTED_FOCUS = 0x04449;
    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED (1<<10): permits local IME & system input policies. */
    public static final int VD_FLAG_TRUSTED = 1 << 10;
    public static final int VD_FLAG_SECURE = 0x0004;
    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL (1<<8): never dump tasks onto Display 0. */
    public static final int VD_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    /**
     * AOSP {@code WindowManager.REMOVE_CONTENT_MODE_DESTROY = 2}
     * ({@code UNDEFINED = 0}, {@code MOVE_TO_PRIMARY = 1}, {@code DESTROY = 2}):
     * destroys residual tasks on VirtualDisplay removal instead of migrating them onto Display 0.
     */
    public static final int REMOVE_CONTENT_MODE_DESTROY = 2;

    // ---- Window Profiles (Per-Window Independent Resolution & DPI) ----
    public static final int PROFILE_AUTO = 0;
    public static final int PROFILE_DESKTOP = 1;
    public static final int PROFILE_PORTRAIT = 2;
    public static final int PROFILE_TABLET = 3;

    // ---- Window Lifecycle States ----
    public static final String STATE_LIVE = "LIVE";
    public static final String STATE_HIBERNATED = "HIBERNATED";
    public static final String STATE_MINIMIZED = "MINIMIZED";

    // ---- Window Presentations / Snap States ----
    public static final String PRES_FLOATING = "FLOATING";
    public static final String PRES_MAXIMIZED = "MAXIMIZED";
    public static final String PRES_FULLSCREEN = "FULLSCREEN";
    public static final String PRES_SNAP_LEFT = "SNAP_LEFT";
    public static final String PRES_SNAP_RIGHT = "SNAP_RIGHT";
    public static final String PRES_SNAP_TL = "SNAP_TL";
    public static final String PRES_SNAP_TR = "SNAP_TR";
    public static final String PRES_SNAP_BL = "SNAP_BL";
    public static final String PRES_SNAP_BR = "SNAP_BR";

    // ---- Window Actions (TX_WINDOW_ACTION) ----
    public static final int WIN_ACTION_FOCUS = 1;
    public static final int WIN_ACTION_MINIMIZE = 2;
    public static final int WIN_ACTION_TOGGLE_MAXIMIZE = 3;
    public static final int WIN_ACTION_CLOSE = 4;
    public static final int WIN_ACTION_SEND_BACK = 5;
    public static final int WIN_ACTION_WAKE = 6;
    public static final int WIN_ACTION_SET_PROFILE_DESKTOP = 7;
    public static final int WIN_ACTION_SET_PROFILE_PORTRAIT = 8;
    public static final int WIN_ACTION_SET_PROFILE_TABLET = 9;
    public static final int WIN_ACTION_RELOAD_APP = 10;
    public static final int WIN_ACTION_TOGGLE_FULLSCREEN = 11;
    public static final int WIN_ACTION_TOGGLE_ORIENTATION = 12;

    // ---- Input Backends & Status ----
    public static final int INPUT_BACKEND_AUTO = 0;
    public static final int INPUT_BACKEND_SYSTEM = 1;
    public static final int INPUT_BACKEND_UHID = 2;

    public static final int INJECT_STATUS_SYSTEM = 1;
    public static final int INJECT_STATUS_UHID = 2;
    public static final int INJECT_STATUS_BLOCKED = -1;

    // ---- Display IME Policies (WindowManagerPolicyConstants) ----
    public static final int DISPLAY_IME_POLICY_LOCAL = 0;
    public static final int DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1;
    public static final int DISPLAY_IME_POLICY_HIDE = 2;

    // ---- User-Configurable DeX IME Display Modes ----
    /** Smart automatic policy: LOCAL when physical/Pogo keyboard connected, FALLBACK otherwise. */
    public static final int DEX_IME_DISPLAY_AUTO = 0;
    /** Always render IME inside DeX / external monitor displays (never pop on Display 0). */
    public static final int DEX_IME_DISPLAY_ALWAYS_LOCAL = 1;
    /** Always route IME back to host phone Display 0. */
    public static final int DEX_IME_DISPLAY_FALLBACK_HOST = 2;

    // ---- Optional Physical Keyboard Soft-IME Override (show_ime_with_hard_keyboard) ----
    public static final int HARD_KBD_SOFT_IME_SYSTEM = -1;
    public static final int HARD_KBD_SOFT_IME_HIDE = 0;
    public static final int HARD_KBD_SOFT_IME_SHOW = 1;

    private BridgeProtocol() {}
}
