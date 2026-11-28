/**
 * OpalDesk Web Cast Receiver & LAN Activation Controller.
 * Orchestrates OpalCast v1.1 over WSS/WS, WebCodecs video/audio, input/IME, file transfer,
 * remote app launcher (+ Document PiP standalone windows), and Helper LAN activation handover.
 */

import { buildTrustPageUrl, LanActivationClient, probeHttpsHealth } from './activation_client.js';
import { OpalAudioPlayer } from './audio_player.js';
import { OpalInputController } from './input_mapper.js';
import { DEFAULT_CAMERA_FPS, DEFAULT_CAMERA_HEIGHT, DEFAULT_CAMERA_WIDTH, OpalCameraCapturer } from './camera_capturer.js';
import {
  clampCustomBitrateMbps,
  computeAdaptiveAutoBitrateStep,
  DEFAULT_CUSTOM_BITRATE_MBPS,
  evaluateCertificateTrust,
  formatShortFingerprint,
  isTauriDesktop,
  MAX_CUSTOM_BITRATE_MBPS,
  MIN_CUSTOM_BITRATE_MBPS,
  normalizeCastPrefs,
  OpalDesktopBridge,
  PRESET_BITRATE_KBPS,
  sanitizeCustomAdbPath,
  SUPPORTED_DPI_OPTIONS,
  SUPPORTED_FPS_OPTIONS,
  SUPPORTED_RESIZE_MODES,
} from './desktop_bridge.js';
import { OpalDiagnosticsController } from './diagnostics_controller.js';
import { DEFAULT_MIC_CHANNELS, DEFAULT_MIC_SAMPLE_RATE, OpalMicCapturer } from './mic_capturer.js';
import { OpalStandaloneController } from './standalone_controller.js';
import { OpalFileBrowserClient } from './file_browser_client.js';
import { OpalFileBrowserController } from './file_browser_controller.js';
import { OpalCompanionController } from './companion_controller.js';
import { captureCanvasScreenshot, OpalScreenRecorder } from './media_capture.js';
import { resolvePopoutLaunchGeometry } from './standalone_prefs.js';
import {
  decodeAppList,
  decodeAuthResult,
  decodeCameraState,
  decodeClipboardPush,
  decodeDeviceState,
  decodeFileTransferAck,
  decodeFrame,
  decodeImeRequest,
  decodeMicState,
  decodePingPong,
  decodeVideoConfig,
  decodeAudioConfig,
  DISPLAY_MODE_DEX,
  encodeCameraConfig,
  encodeFileTransferChunk,
  encodeFrame,
  encodeHello,
  encodeImageClipboardEvent,
  encodeKeyEvent,
  encodeMicConfig,
  encodeMouseEvent,
  encodeNotificationAction,
  encodePingPong,
  encodeResizeConfig,
  encodeSystemAction,
  encodeTextEvent,
  encodeTouchEvent,
  encodeWindowAction,
  FILE_ACK_OK,
  FILE_FLAG_DATA,
  FILE_FLAG_END,
  FILE_FLAG_START,
  fitDimensionsToMaxBounds,
  FLAG_CLIPBOARD_IMAGE_PNG,
  resolveAutoCodecPreference,
  SESSION_ROLE_DESKTOP,
  SESSION_ROLE_STANDALONE_APP,
  shouldRequestSessionAudio,
  SYS_BACK,
  SYS_DISCONNECT,
  SYS_HOME,
  SYS_SET_AUDIO_ENABLED,
  SYS_TOGGLE_BLACKOUT,
  TEXT_MODE_COMMIT_PASTE,
  TYPE_APK_INSTALL_STATUS,
  TYPE_APP_LIST,
  TYPE_AUDIO_CONFIG,
  TYPE_AUDIO_FRAME,
  TYPE_AUTH_RESULT,
  TYPE_CALL_STATE,
  TYPE_CAMERA_CONFIG,
  TYPE_CAMERA_FRAME,
  TYPE_CAMERA_STATE,
  TYPE_CLIPBOARD_PUSH,
  TYPE_DEVICE_STATE,
  TYPE_FILE_OP_RESPONSE,
  TYPE_FILE_PULL_CHUNK,
  TYPE_FILE_TRANSFER_ACK,
  TYPE_FILE_TRANSFER_CHUNK,
  TYPE_HELLO,
  TYPE_IME_REQUEST,
  TYPE_INPUT_KEY,
  TYPE_INPUT_MOUSE,
  TYPE_INPUT_TEXT,
  TYPE_INPUT_TOUCH,
  TYPE_LIST_APPS,
  TYPE_MEDIA_STATE,
  TYPE_MIC_CONFIG,
  TYPE_MIC_FRAME,
  TYPE_MIC_STATE,
  TYPE_NOTIFICATION_ACTION,
  TYPE_NOTIFICATION_EVENT,
  TYPE_PING,
  TYPE_PONG,
  TYPE_REQUEST_IDR,
  TYPE_RESIZE_CONFIG,
  TYPE_SYSTEM_ACTION,
  TYPE_VIDEO_CONFIG,
  TYPE_VIDEO_FRAME,
  TYPE_WINDOW_ACTION,
  VIDEO_CODEC_HEVC,
} from './protocol.js';
import { OpalVideoDecoder, probeVideoCodecs } from './video_decoder.js';
import {
  applyDocumentI18n,
  getLanguagePref,
  onLocaleChange,
  setLanguagePref,
  STORAGE_LANGUAGE_PREF,
  t,
} from './i18n.js';

const STORAGE_CLIENT_ID = 'opaldesk-web-client-id-v1';
const STORAGE_DEVICES = 'opaldesk-web-devices-v1';
const STORAGE_THEME = 'opaldesk-showcase-theme-v1';
const STORAGE_CODEC = 'opaldesk-web-codec-pref-v1';
const STORAGE_FPS = 'opaldesk-web-fps-pref-v1';
const STORAGE_BITRATE = 'opaldesk-web-bitrate-pref-v1';
const STORAGE_BITRATE_MODE = 'opaldesk-web-bitrate-mode-v1';
const STORAGE_CUSTOM_BITRATE_MBPS = 'opaldesk-web-custom-bitrate-mbps-v1';
const STORAGE_RESIZE_MODE = 'opaldesk-web-resize-mode-v1';
const STORAGE_LIVE_DPI = 'opaldesk-web-live-dpi-v1';
const STORAGE_AUDIO = 'opaldesk-web-audio-pref-v1';
const STORAGE_HIDE_CURSOR = 'opaldesk-web-hide-cursor-v1';
const STORAGE_USB_TETHER_BETA = 'opaldesk-usb-tether-beta-v1';
const STORAGE_CUSTOM_ADB_PATH = 'opaldesk-custom-adb-path-v1';
const FILE_CHUNK_SIZE = 64 * 1024;
const REVEAL_HANDLE_HOVER_DELAY_MS = 180;

function loadCodecPref() {
  try {
    return localStorage.getItem(STORAGE_CODEC) || '';
  } catch {
    return '';
  }
}

function saveCodecPref(codec) {
  try {
    if (codec === 'hevc' || codec === 'h264') {
      localStorage.setItem(STORAGE_CODEC, codec);
    }
  } catch {
    // Ignore storage restriction
  }
}

function loadCustomBitrateMbps() {
  try {
    const raw = localStorage.getItem(STORAGE_CUSTOM_BITRATE_MBPS);
    if (!raw) return DEFAULT_CUSTOM_BITRATE_MBPS;
    return clampCustomBitrateMbps(raw).mbps;
  } catch {
    return DEFAULT_CUSTOM_BITRATE_MBPS;
  }
}

function saveCustomBitrateMbps(mbps) {
  try {
    const res = clampCustomBitrateMbps(mbps);
    localStorage.setItem(STORAGE_CUSTOM_BITRATE_MBPS, String(res.mbps));
  } catch {
    // Ignore storage restriction
  }
}

function loadLocalCastPrefs() {
  try {
    const rawAudio = localStorage.getItem(STORAGE_AUDIO);
    const rawHideCursor = localStorage.getItem(STORAGE_HIDE_CURSOR);
    const rawMode = localStorage.getItem(STORAGE_BITRATE_MODE) || '';
    const rawResizeMode = localStorage.getItem(STORAGE_RESIZE_MODE) || '';
    const rawLiveDpi = Number(localStorage.getItem(STORAGE_LIVE_DPI) || 0);
    return normalizeCastPrefs({
      preferredCodec: loadCodecPref(),
      targetFps: Number(localStorage.getItem(STORAGE_FPS) || 0),
      bitrateKbps: Number(localStorage.getItem(STORAGE_BITRATE) || 0),
      bitrateMode: rawMode,
      resizeMode: rawResizeMode,
      liveDpi: rawLiveDpi,
      audioEnabled: rawAudio === null ? null : rawAudio === '1',
      hideLocalCursor: rawHideCursor === null ? null : rawHideCursor === '1',
    });
  } catch {
    return normalizeCastPrefs();
  }
}

function saveLocalCastPrefs(patch = {}) {
  try {
    if (patch.preferredCodec === 'hevc' || patch.preferredCodec === 'h264') {
      localStorage.setItem(STORAGE_CODEC, patch.preferredCodec);
    }
    if (typeof patch.targetFps === 'number' && SUPPORTED_FPS_OPTIONS.includes(patch.targetFps)) {
      localStorage.setItem(STORAGE_FPS, String(patch.targetFps));
    }
    if (patch.bitrateMode === 'auto' || patch.bitrateMode === 'preset' || patch.bitrateMode === 'custom') {
      localStorage.setItem(STORAGE_BITRATE_MODE, patch.bitrateMode);
    }
    if (typeof patch.bitrateKbps === 'number') {
      if (patch.bitrateKbps === 0) {
        localStorage.setItem(STORAGE_BITRATE, '0');
      } else if (patch.bitrateKbps >= 2500 && patch.bitrateKbps <= MAX_CUSTOM_BITRATE_MBPS * 1000) {
        localStorage.setItem(STORAGE_BITRATE, String(Math.round(patch.bitrateKbps)));
      }
    }
    if (typeof patch.resizeMode === 'string' && SUPPORTED_RESIZE_MODES.includes(patch.resizeMode)) {
      localStorage.setItem(STORAGE_RESIZE_MODE, patch.resizeMode);
    }
    if (typeof patch.liveDpi === 'number' && SUPPORTED_DPI_OPTIONS.includes(patch.liveDpi)) {
      localStorage.setItem(STORAGE_LIVE_DPI, String(patch.liveDpi));
    }
    if (typeof patch.audioEnabled === 'boolean') {
      localStorage.setItem(STORAGE_AUDIO, patch.audioEnabled ? '1' : '0');
    }
    if (typeof patch.hideLocalCursor === 'boolean') {
      localStorage.setItem(STORAGE_HIDE_CURSOR, patch.hideLocalCursor ? '1' : '0');
    }
  } catch {
    // Ignore storage restriction
  }
}

function loadUsbTetherBetaPref() {
  try {
    return localStorage.getItem(STORAGE_USB_TETHER_BETA) === '1';
  } catch {
    return false;
  }
}

function saveUsbTetherBetaPref(enabled) {
  try {
    localStorage.setItem(STORAGE_USB_TETHER_BETA, enabled ? '1' : '0');
  } catch {
    // Ignore storage restriction
  }
}

function loadLocalCustomAdbPath() {
  try {
    return sanitizeCustomAdbPath(localStorage.getItem(STORAGE_CUSTOM_ADB_PATH) || '');
  } catch {
    return '';
  }
}

function saveLocalCustomAdbPath(customPath) {
  try {
    const cleaned = sanitizeCustomAdbPath(customPath);
    if (cleaned) {
      localStorage.setItem(STORAGE_CUSTOM_ADB_PATH, cleaned);
    } else {
      localStorage.removeItem(STORAGE_CUSTOM_ADB_PATH);
    }
  } catch {
    // Ignore storage restriction
  }
}

const $ = (id) => document.getElementById(id);

function getOrCreateClientId() {
  try {
    let cid = localStorage.getItem(STORAGE_CLIENT_ID);
    if (cid && cid.length >= 12) return cid;
    const arr = new Uint8Array(12);
    crypto.getRandomValues(arr);
    cid = 'web-' + Array.from(arr, (b) => b.toString(16).padStart(2, '0')).join('');
    localStorage.setItem(STORAGE_CLIENT_ID, cid);
    return cid;
  } catch {
    return 'web-' + Math.random().toString(16).slice(2, 14);
  }
}

function loadSavedDevices() {
  try {
    const raw = localStorage.getItem(STORAGE_DEVICES);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveDeviceRecord(rec) {
  try {
    const list = loadSavedDevices().filter((d) => !(d.host === rec.host && d.port === rec.port));
    list.unshift({ ...rec, updatedAt: Date.now() });
    localStorage.setItem(STORAGE_DEVICES, JSON.stringify(list.slice(0, 8)));
  } catch {
    // Ignore storage quota errors
  }
}

function getSavedToken(host, port) {
  const found = loadSavedDevices().find((d) => d.host === host && Number(d.port) === Number(port));
  return found?.authToken || '';
}

function detectBrowserName() {
  if (isTauriDesktop()) {
    const ua = navigator.userAgent || '';
    if (ua.includes('Windows')) return 'OpalDesk Windows';
    if (ua.includes('Macintosh')) return 'OpalDesk Desktop (macOS)';
    return 'OpalDesk Desktop';
  }
  const ua = navigator.userAgent || '';
  if (ua.includes('Edg/')) return 'OpalDesk Web (Edge)';
  if (ua.includes('Chrome/')) return 'OpalDesk Web (Chrome)';
  if (ua.includes('Firefox/')) return 'OpalDesk Web (Firefox)';
  if (ua.includes('Safari/')) return 'OpalDesk Web (Safari)';
  return 'OpalDesk Web';
}

function isSafariBrowser() {
  const ua = navigator.userAgent || '';
  return ua.includes('Safari/') && !ua.includes('Chrome/') && !ua.includes('Chromium/') && !ua.includes('Edg/');
}

class OpalCastWebApp {
  constructor() {
    this.desktop = new OpalDesktopBridge();
    this.clientId = getOrCreateClientId();
    this.transportMode = 'wifi'; // 'wifi' | 'usb'
    this.sessionRole = SESSION_ROLE_DESKTOP;
    this.standaloneAppPkg = '';
    this.standaloneAppCls = '';
    this.standaloneAppLabel = '';
    this.standaloneAppUserId = 0;
    this.standaloneAppProfileType = '';
    this.standaloneDeviceKey = '';
    this.standaloneDeviceName = '';
    this.explicitCertPin = '';
    this.activeVerifiedFingerprint = '';
    this.activeUsbLocalPort = 0;
    this.selectedAdbSerial = '';
    this.customAdbPathDirty = false;
    this.tofuResolver = null;

    this.ws = null;
    this.connected = false;
    this.authenticated = false;
    this.remoteWidth = 1920;
    this.remoteHeight = 1080;
    this.remoteFps = 60;
    this.lastRttMs = 0;
    this.pingIdSeq = 1n;
    this.pingSentMap = new Map();
    this.pingTimer = null;
    this.toolbarFadeTimer = null;
    this.revealHoverTimer = null;
    this.resizeDebounceTimer = null;
    this.bitrateMode = 'preset';
    this.customBitrateMbps = loadCustomBitrateMbps();
    this.autoAdaptiveBitrateKbps = 0;
    this.autoHealthyStreak = 0;
    this.textSeq = 1;
    this.transferSeq = 1;
    this.currentApps = [];
    this.resizeMode = 'auto';
    this.activeHost = '';
    this.activePort = 19878;
    this.lastWifiHost = '';
    this.blackoutActive = false;
    this.remoteFullscreenTitle = '';
    this.remoteCodecId = 1;
    this.injectStatus = 0;
    this.inputBlockedWarned = false;

    this.toastTimer = null;
    this.toastActionFn = null;

    this.video = new OpalVideoDecoder($('castCanvas'), {
      onResize: (w, h, fps) => {
        this.remoteWidth = w;
        this.remoteHeight = h;
        if (fps > 0) this.remoteFps = Math.round(fps);
        this.updateTelemetry();
      },
      onRequestIdr: () => this.sendOpalFrame(TYPE_REQUEST_IDR),
      onStats: (st) => {
        this.remoteFps = st.fps;
        this.updateTelemetry();
      },
      onError: (msg) => {
        this.diagnostics?.log('W', 'Video', msg);
        this.showToast(msg);
      },
    });

    this.audio = new OpalAudioPlayer({
      onStateChange: (st) => this.syncAudioUi(st),
    });

    this.micEnabled = false;
    this.mic = new OpalMicCapturer({
      sampleRate: DEFAULT_MIC_SAMPLE_RATE,
      onPcmFrame: (pcmBytes) => {
        if (this.authenticated && this.micEnabled) {
          this.sendOpalFrame(TYPE_MIC_FRAME, 0, pcmBytes);
        }
      },
      onError: (msg) => {
        this.disableRemoteMic(false);
        this.showToast(msg);
      },
    });

    this.cameraEnabled = false;
    this.camera = new OpalCameraCapturer({
      width: DEFAULT_CAMERA_WIDTH,
      height: DEFAULT_CAMERA_HEIGHT,
      fps: DEFAULT_CAMERA_FPS,
      onJpegFrame: (jpegBytes) => {
        if (this.authenticated && this.cameraEnabled) {
          this.sendOpalFrame(TYPE_CAMERA_FRAME, 0, jpegBytes);
        }
      },
      onError: (msg) => {
        this.disableRemoteCamera(false);
        this.showToast(msg);
      },
    });

    this.input = new OpalInputController($('castCanvas'), $('imeBridge'), {
      getRemoteSize: () => ({ width: this.remoteWidth, height: this.remoteHeight }),
      onMouse: (action, x, y, buttons, actBtn, hScroll, vScroll) => {
        this.sendOpalFrame(TYPE_INPUT_MOUSE, 0, encodeMouseEvent(action, x, y, buttons, actBtn, hScroll, vScroll));
      },
      onTouch: (action, idx, pointers) => {
        this.sendOpalFrame(TYPE_INPUT_TOUCH, 0, encodeTouchEvent(action, idx, pointers));
      },
      onKey: (action, code, meta, repeat) => {
        this.sendOpalFrame(TYPE_INPUT_KEY, 0, encodeKeyEvent(action, code, meta, repeat));
      },
      onText: (text, commit) => {
        const mode = commit ? TEXT_MODE_COMMIT_PASTE : 1;
        this.sendOpalFrame(TYPE_INPUT_TEXT, 0, encodeTextEvent(mode, this.textSeq++, text));
      },
      onImageClipboard: (pngBytes) => {
        this.sendOpalFrame(
          TYPE_INPUT_TEXT,
          FLAG_CLIPBOARD_IMAGE_PNG,
          encodeImageClipboardEvent(TEXT_MODE_COMMIT_PASTE, this.textSeq++, pngBytes)
        );
        this.showToast(t('cast.clipboardImgSyncedToast'));
      },
      onFileDrop: (files) => this.sendDroppedFiles(files),
      onUserGesture: () => this.audio.resumeFromGesture(),
      onSoftKeyboardStateChange: (open) => this.syncKeyboardUi(open),
    });

    this.activation = new LanActivationClient({
      onStatus: (_st, msg) => this.appendActLog(msg),
      onAuthResult: (res) => {
        if (res.ok) {
          this.setActError('');
          $('actTrustBox').hidden = true;
          $('actWorkspace').hidden = false;
          $('disconnectActBtn').hidden = false;
          this.appendActLog(t('activate.authPassedLog'));
        } else {
          this.setActError(res.message || t('activate.errAuthFailed'));
          this.appendActLog(t('activate.authFailedLog', { msg: res.message || '' }));
        }
      },
      onState: (state) => this.renderActivationState(state),
      onProgress: (msg) => this.appendActLog(`▸ ${msg}`),
      onResult: (res) => {
        this.appendActLog(`${res.ok ? '✓' : '✗'} ${res.message || ''}`);
        this.setActBusy(false);
        if (!res.ok) this.setActError(res.message || t('activate.errNotSucceeded'));
      },
      onReadyForCast: (info) => {
        this.appendActLog(`★ ${info.message || 'Ready'}`);
        if (info.ok && info.host) {
          this.showToast(t('activate.readyForCastToast'));
          this.activation.disconnect();
          $('castHostInput').value = info.host;
          $('castPortInput').value = String(info.castPort || 19878);
          this.selectLobbyTab('cast');
          this.selectTransportMode('wifi');
          setTimeout(() => this.startCastConnection(), 450);
        }
      },
      onError: (msg) => {
        this.setActError(msg);
        this.appendActLog(`! ${msg}`);
      },
      onClose: (reason) => {
        this.appendActLog(t('activate.closeLog', { reason }));
        $('disconnectActBtn').hidden = true;
      },
    });

    this.diagnostics = new OpalDiagnosticsController({
      desktop: this.desktop,
      showToast: (text, label, fn) => this.showToast(text, label, fn),
      copyText: (text, msg) => this.copyText(text, msg),
      getContextSummary: () => this.buildClientContextSummary(),
    });
    this.standalone = new OpalStandaloneController(this);
    this.companion = new OpalCompanionController(this);
    this.fileBrowserClient = new OpalFileBrowserClient({
      sendFrame: (type, flags, payload) => this.companion.sendControlFrame(type, flags, payload),
    });
    this.fileBrowser = new OpalFileBrowserController({
      app: this,
      client: this.fileBrowserClient,
    });
    this.recorder = new OpalScreenRecorder({
      getCanvas: () => $('castCanvas'),
      desktop: this.desktop,
      getLabel: () => (this.sessionRole === SESSION_ROLE_STANDALONE_APP && this.standaloneAppLabel
        ? this.standaloneAppLabel
        : 'OpalDesk'),
      onStateChange: ({ recording, elapsedLabel }) => {
        const btn = $('tbRecordBtn');
        const lbl = $('tbRecordLabel');
        if (btn) btn.classList.toggle('recording', recording);
        if (lbl) lbl.textContent = recording ? t('cast.recordStopLabel', { elapsed: elapsedLabel }) : t('cast.recordLabel');
      },
      onSaved: ({ fileName, savedPath }) => {
        if (savedPath && this.desktop.enabled) {
          this.showToast(t('cast.recordSaved', { name: fileName }), {
            actionText: t('cast.openLocationAction'),
            actionFn: () => void this.desktop.revealInExplorer(savedPath),
          });
        } else {
          this.showToast(t('cast.recordSaved', { name: fileName }));
        }
      },
      onError: (msg) => this.showToast(msg),
    });

    this.init();
  }

  async init() {
    this.codecCaps = null;
    applyDocumentI18n();
    onLocaleChange(() => this.refreshLocalizedUi());
    window.addEventListener('storage', (ev) => {
      if (ev.key === STORAGE_LANGUAGE_PREF) {
        setLanguagePref(ev.newValue || 'system', { persist: false });
      }
    });
    this.bindUiEvents();
    this.input.attach();
    if (this.desktop.enabled) {
      await this.initDesktopMode();
    }
    this.restoreSavedCastPrefs();
    await Promise.all([
      this.diagnostics.initialize(),
      this.standalone.initialize(),
      this.companion.initialize(),
    ]);
    this.renderRecentDevices();
    this.installTrustPopupListener();
    this.installResizeObserver();

    const caps = await probeVideoCodecs();
    this.codecCaps = caps;
    const badge = $('codecBadge');
    if (!caps.supported) {
      badge.textContent = t('nav.codecUnsupported');
      badge.classList.add('warn');
      this.showBrowserWarn(t('cast.browserUnsupportedWarn'));
    } else {
      badge.textContent = caps.hevc ? t('nav.codecHevcReady') : t('nav.codecH264Ready');
      badge.classList.add('ok');
      const codecSelect = $('castCodecSelect');
      if (codecSelect) {
        const merged = this.resolveSavedCastPrefs();
        codecSelect.value = resolveAutoCodecPreference({
          hevcSupported: caps.hevc,
          savedCodec: merged.preferredCodec,
        });
        if (!caps.hevc) {
          const hevcOpt = codecSelect.querySelector('option[value="hevc"]');
          if (hevcOpt) hevcOpt.textContent = t('cast.codecHevcNoHw');
        }
      }
    }

    if (isSafariBrowser()) {
      this.showBrowserWarn(t('cast.safariWarn'));
    }

    this.refreshLocalizedUi();
    this.applyUrlParams();
  }

  refreshLocalizedUi() {
    applyDocumentI18n();
    const langSel = $('langSelect');
    if (langSel) {
      langSel.value = getLanguagePref();
    }
    if (this.desktop.enabled) {
      const heroTitle = $('heroTitle');
      if (heroTitle) heroTitle.textContent = t('hero.desktopTitle');
      const heroSub = $('heroSubtitle');
      if (heroSub) heroSub.textContent = t('hero.desktopSubtitle');
      if ($('connectCastBtn') && !$('connectCastBtn').disabled) {
        $('connectCastBtn').textContent = t('cast.connectDesktopBtn');
      }
    } else if ($('connectCastBtn') && !$('connectCastBtn').disabled) {
      $('connectCastBtn').textContent = t('cast.connectWebBtn');
    }
    if (this.codecCaps) {
      const badge = $('codecBadge');
      if (badge) {
        badge.textContent = !this.codecCaps.supported
          ? t('nav.codecUnsupported')
          : this.codecCaps.hevc
            ? t('nav.codecHevcReady')
            : t('nav.codecH264Ready');
      }
      if (!this.codecCaps.hevc) {
        const hevcOpt = $('castCodecSelect')?.querySelector('option[value="hevc"]');
        if (hevcOpt) hevcOpt.textContent = t('cast.codecHevcNoHw');
      }
    }
    if ($('castHostLabel')) {
      $('castHostLabel').textContent = this.transportMode === 'usb' ? t('cast.hostLabelUsb') : t('cast.hostLabelWifi');
    }
    this.updateUsbForwardHint();
    if (this.desktop.enabled) {
      this.syncCustomAdbPathUi(this.desktop.adbStatus);
      this.renderDesktopMdnsDevices(this.desktop.mdnsDevices);
      if (this.usbTetherBetaEnabled) {
        this.renderDesktopUsbTetherDevices(this.desktop.usbTetherDevices);
      }
    }
    this.renderRecentDevices();
    this.companion?.renderCompanionHub();
    this.companion?.renderBleNearbyCard();
    this.companion?.syncCallHudDisplay();
    this.standalone?.renderLobbyHistoryBar();
    this.standalone?.syncQuotaBadges();
    this.standalone?.syncPinButtonUi();
    this.standalone?.syncStandaloneToolbarPrefsUi();
    this.diagnostics?.renderMetrics();
    this.diagnostics?.renderLogConsole();
    this.diagnostics?.renderFeedbackRecords();
    this.fileBrowser?.renderQuickNav();
    this.fileBrowser?.renderBreadcrumbs();
    this.fileBrowser?.syncDirectOpenThresholdUi();
    if ($('fmViewToggleBtn')) {
      $('fmViewToggleBtn').textContent = this.fileBrowser?.viewMode === 'grid' ? t('fm.viewList') : t('fm.viewGrid');
    }
    if ($('tbInputModeBtn')) {
      $('tbInputModeBtn').textContent = this.input.primaryLeftAsTouch ? t('stage.touchBtn') : t('stage.mouseBtn');
    }
    if ($('tbDisconnectBtn')) {
      const isStandaloneDesktop = this.sessionRole === SESSION_ROLE_STANDALONE_APP && this.desktop.enabled;
      $('tbDisconnectBtn').textContent = isStandaloneDesktop ? t('stage.closeWindowBtn') : t('stage.disconnectBtn');
      $('tbDisconnectBtn').title = isStandaloneDesktop ? t('stage.closeWindowTitle') : t('stage.disconnectTitle');
    }
    this.syncBlackoutUi();
    this.syncMicUi();
    this.syncCameraUi();
    this.syncFullscreenUi();
    this.syncRemoteFullscreenUi();
    if (this.authenticated) {
      this.updateTelemetry();
    }
  }

  async initDesktopMode() {
    document.body.classList.add('desktop-app');
    const badge = $('brandModeBadge');
    if (badge) {
      badge.textContent = (navigator.userAgent || '').includes('Windows') ? 'Windows Client' : 'Desktop';
    }
    const heroTitle = $('heroTitle');
    if (heroTitle) heroTitle.textContent = t('hero.desktopTitle');
    const heroSub = $('heroSubtitle');
    if (heroSub) {
      heroSub.textContent = t('hero.desktopSubtitle');
    }
    $('connectCastBtn').textContent = t('cast.connectDesktopBtn');

    try {
      const info = await this.desktop.initialize();
      if (info?.clientId) {
        this.clientId = info.clientId;
      }
      if (info?.prefs?.language) {
        setLanguagePref(info.prefs.language, { persist: true, notify: false });
      }
      if (info?.micaApplied) {
        document.body.classList.add('desktop-mica-ready');
      }
      const trayRow = $('hideToTrayRow');
      const trayChk = $('hideToTrayCheck');
      if (trayRow) trayRow.hidden = false;
      if (trayChk) trayChk.checked = info?.prefs?.hideToTrayOnClose !== false;
      if (info?.initialDeepLink) {
        setTimeout(() => this.companion.handleDeepLinkUrl(info.initialDeepLink), 450);
      }
      await this.restoreRememberedAdbPath(info);
    } catch {
      const localAdbPath = loadLocalCustomAdbPath();
      if (localAdbPath) {
        this.syncCustomAdbPathUi({ customAdbPath: localAdbPath }, { forceInput: true });
      }
    }

    $('desktopMdnsSection').hidden = this.transportMode === 'usb';
    this.usbTetherBetaEnabled = loadUsbTetherBetaPref();
    const betaCheck = $('usbTetherBetaCheck');
    if (betaCheck) betaCheck.checked = this.usbTetherBetaEnabled;
    this.syncDesktopUsbTetherUi();
    this.renderDesktopMdnsDevices(this.desktop.mdnsDevices);
    this.desktop.startDiscoveryPolling((devices) => this.renderDesktopMdnsDevices(devices));
  }

  syncCustomAdbPathUi(status = this.desktop.adbStatus, { forceInput = false } = {}) {
    const savedPath = sanitizeCustomAdbPath(
      status?.customAdbPath || this.desktop.clientPrefs?.customAdbPath || ''
    );
    const inputEl = $('customAdbPathInput');
    if (inputEl && (forceInput || (!this.customAdbPathDirty && document.activeElement !== inputEl))) {
      inputEl.value = savedPath;
      this.customAdbPathDirty = false;
    }
    const summaryEl = $('adbPathSummary');
    if (summaryEl) {
      summaryEl.textContent = savedPath
        ? t('cast.adbSummaryRemembered')
        : t('cast.adbSummaryDefault');
    }
    const clearBtn = $('clearAdbPathBtn');
    if (clearBtn) {
      clearBtn.hidden = !savedPath;
    }
  }

  async restoreRememberedAdbPath(info) {
    const nativePath = sanitizeCustomAdbPath(
      info?.adb?.customAdbPath || info?.prefs?.customAdbPath || ''
    );
    if (nativePath) {
      saveLocalCustomAdbPath(nativePath);
      this.syncCustomAdbPathUi(info?.adb || { customAdbPath: nativePath }, { forceInput: true });
      return;
    }

    const localPath = loadLocalCustomAdbPath();
    if (!localPath) {
      this.syncCustomAdbPathUi(info?.adb || null, { forceInput: true });
      return;
    }

    try {
      const restored = await this.desktop.setCustomAdbPath(localPath);
      saveLocalCustomAdbPath(restored?.customAdbPath || localPath);
      this.syncCustomAdbPathUi(restored, { forceInput: true });
    } catch {
      this.syncCustomAdbPathUi({ customAdbPath: localPath }, { forceInput: true });
    }
  }

  async flushPendingCustomAdbPath() {
    if (!this.desktop.enabled || !this.customAdbPathDirty) return;
    const inputEl = $('customAdbPathInput');
    if (!inputEl) {
      this.customAdbPathDirty = false;
      return;
    }
    const pendingPath = sanitizeCustomAdbPath(inputEl.value);
    const currentSaved = sanitizeCustomAdbPath(this.desktop.adbStatus?.customAdbPath || '');
    if (pendingPath !== currentSaved) {
      await this.saveCustomAdbPath({ customPath: pendingPath, silent: true });
    } else {
      this.customAdbPathDirty = false;
    }
  }

  async saveCustomAdbPath({ customPath, silent = false } = {}) {
    if (!this.desktop.enabled) return null;
    const cleaned = sanitizeCustomAdbPath(customPath);
    const inputEl = $('customAdbPathInput');
    if (inputEl) inputEl.value = cleaned;
    this.customAdbPathDirty = false;
    saveLocalCustomAdbPath(cleaned);

    try {
      const st = await this.desktop.setCustomAdbPath(cleaned || null);
      saveLocalCustomAdbPath(st?.customAdbPath || '');
      this.syncCustomAdbPathUi(st, { forceInput: true });
      if (!silent) {
        if (!cleaned) {
          this.showToast(
            st.available ? t('cast.adbClearedAuto', { path: st.adbPath }) : t('cast.adbCleared')
          );
        } else if (st.available) {
          this.showToast(t('cast.adbSaved', { path: st.adbPath }));
        } else {
          this.showToast(t('cast.adbSavedNotFound'));
        }
      }
      return st;
    } catch (err) {
      const msg = String(err?.message || err || t('common.unknownError'));
      this.diagnostics?.log('W', 'ADB', `Failed to save custom ADB path: ${msg}`);
      if (!silent) {
        this.showToast(t('cast.adbSaveFailed', { error: msg }));
      }
      return null;
    }
  }

  resolveSavedCastPrefs() {
    const local = loadLocalCastPrefs();
    const native = this.desktop.enabled && this.desktop.clientPrefs
      ? normalizeCastPrefs(this.desktop.clientPrefs)
      : normalizeCastPrefs();
    return {
      preferredCodec: native.preferredCodec || local.preferredCodec || '',
      targetFps: native.targetFps || local.targetFps || 0,
      bitrateKbps: native.bitrateKbps || local.bitrateKbps || 0,
      bitrateMode: local.bitrateMode || native.bitrateMode || '',
      resizeMode: local.resizeMode || native.resizeMode || 'auto',
      liveDpi: local.liveDpi || native.liveDpi || 0,
      audioEnabled: native.audioEnabled !== null ? native.audioEnabled : local.audioEnabled,
      hideLocalCursor: native.hideLocalCursor !== null ? native.hideLocalCursor : Boolean(local.hideLocalCursor),
    };
  }

  syncResizePresetUi() {
    const pop = $('qualityPopover');
    if (!pop) return;
    pop.querySelectorAll('[data-preset]').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.preset === this.resizeMode);
    });
  }

  restoreSavedCastPrefs() {
    const saved = this.resolveSavedCastPrefs();
    const fpsEl = $('castFpsSelect');
    if (fpsEl && saved.targetFps && fpsEl.querySelector(`option[value="${saved.targetFps}"]`)) {
      fpsEl.value = String(saved.targetFps);
    }
    this.customBitrateMbps = loadCustomBitrateMbps();
    const brEl = $('castBitrateSelect');
    if (brEl) {
      if (saved.bitrateMode === 'auto') {
        this.bitrateMode = 'auto';
        brEl.value = 'auto';
      } else if (saved.bitrateMode === 'custom' || (saved.bitrateKbps > 0 && !PRESET_BITRATE_KBPS.includes(saved.bitrateKbps))) {
        this.bitrateMode = 'custom';
        brEl.value = 'custom';
        if (saved.bitrateKbps > 0) {
          this.customBitrateMbps = clampCustomBitrateMbps(saved.bitrateKbps / 1000).mbps;
        }
      } else if (saved.bitrateKbps && brEl.querySelector(`option[value="${saved.bitrateKbps}"]`)) {
        this.bitrateMode = 'preset';
        brEl.value = String(saved.bitrateKbps);
      } else {
        this.bitrateMode = 'preset';
      }
    }
    this.resizeMode = SUPPORTED_RESIZE_MODES.includes(saved.resizeMode) ? saved.resizeMode : 'auto';
    this.syncResizePresetUi();
    const dpiEl = $('liveDpiSelect');
    if (dpiEl && dpiEl.querySelector(`option[value="${saved.liveDpi}"]`)) {
      dpiEl.value = String(saved.liveDpi);
    }
    this.syncBitrateInputsUi('both');
    const audioEl = $('castAudioCheck');
    if (audioEl && typeof saved.audioEnabled === 'boolean') {
      audioEl.checked = saved.audioEnabled;
    }
    const codecEl = $('castCodecSelect');
    if (codecEl && saved.preferredCodec && codecEl.querySelector(`option[value="${saved.preferredCodec}"]`)) {
      codecEl.value = saved.preferredCodec;
    }
    this.hideLocalCursor = Boolean(saved.hideLocalCursor);
    this.syncLocalCursorUi?.();
  }

  syncBitrateInputsUi(scope = 'both') {
    const castSel = $('castBitrateSelect');
    const castWrap = $('castCustomBitrateWrap');
    const castInput = $('castCustomBitrateInput');
    const liveSel = $('liveBitrateSelect');
    const liveWrap = $('liveCustomBitrateWrap');
    const liveInput = $('liveCustomBitrateInput');
    if ((scope === 'both' || scope === 'cast') && castSel) {
      const isCustom = castSel.value === 'custom';
      if (castWrap) castWrap.hidden = !isCustom;
      if (castInput && document.activeElement !== castInput) {
        castInput.value = String(this.customBitrateMbps);
      }
    }
    if ((scope === 'both' || scope === 'live') && liveSel) {
      const isCustom = liveSel.value === 'custom';
      if (liveWrap) liveWrap.hidden = !isCustom;
      if (liveInput && document.activeElement !== liveInput) {
        liveInput.value = String(this.customBitrateMbps);
      }
    }
  }

  resolveSelectedBitrateConfig(selectId, inputId, { notifyOnClamp = false } = {}) {
    const sel = $(selectId);
    const val = sel ? sel.value : '14000';
    if (val === 'auto') {
      return { mode: 'auto', bitrateKbps: 0, mbps: 0 };
    }
    if (val === 'custom') {
      const inputEl = $(inputId);
      const raw = inputEl ? inputEl.value : this.customBitrateMbps;
      const res = clampCustomBitrateMbps(raw);
      this.customBitrateMbps = res.mbps;
      if (inputEl) inputEl.value = String(res.mbps);
      saveCustomBitrateMbps(res.mbps);
      if (notifyOnClamp && res.clamped) {
        this.showToast(t('cast.customBitrateClamped', {
          min: MIN_CUSTOM_BITRATE_MBPS,
          max: MAX_CUSTOM_BITRATE_MBPS,
          mbps: res.mbps,
        }));
      }
      return { mode: 'custom', bitrateKbps: res.kbps, mbps: res.mbps };
    }
    const presetKbps = Number(val) || 14000;
    return { mode: 'preset', bitrateKbps: presetKbps, mbps: Math.round(presetKbps / 1000) };
  }

  persistCastPrefs(patch) {
    saveLocalCastPrefs(patch);
    if (this.desktop.enabled) {
      this.desktop.updateClientPrefs(patch);
    }
  }

  syncLocalCursorUi() {
    const hide = Boolean(this.hideLocalCursor);
    const wrap = $('canvasWrap');
    if (wrap) wrap.classList.toggle('hide-local-cursor', hide);
    const chk = $('hideLocalCursorCheck');
    if (chk) chk.checked = hide;
  }

  applyUrlParams() {
    const url = new URL(window.location.href);
    const params = url.searchParams;
    const hashParams = new URLSearchParams(url.hash.replace(/^#/, ''));
    const mode = params.get('mode') || '';
    const host = params.get('host') || '';
    const port = params.get('port') || params.get('castPort') || '';
    const actPort = params.get('actPort') || '';
    const transport = params.get('transport') || '';
    const role = params.get('role') || '';
    const pkg = params.get('pkg') || '';
    const cls = params.get('cls') || '';
    const appLabel = params.get('appLabel') || '';
    const userId = Math.max(0, Math.round(Number(params.get('userId') || 0)));
    const profileType = params.get('profileType') || '';
    const deviceKey = params.get('deviceKey') || '';
    const deviceName = params.get('deviceName') || '';
    const pin = params.get('pin') || '';
    const fp = params.get('fp') || '';
    const sessionCode = hashParams.get('code') || '';
    if (fp) {
      this.explicitCertPin = fp;
    }

    // Erase sensitive one-time session code from URL bar immediately after reading
    if (sessionCode) {
      try {
        url.hash = '';
        window.history.replaceState(null, '', url.toString());
      } catch {
        // Ignore history restriction
      }
    }

    if (host) {
      $('castHostInput').value = host;
      $('actHostInput').value = host;
    } else {
      const recent = (this.desktop.enabled && this.desktop.trustedDevices[0]) || loadSavedDevices()[0];
      if (recent) {
        $('castHostInput').value = recent.host;
        $('castPortInput').value = String(recent.port || 19878);
        $('actHostInput').value = recent.host;
      }
    }
    if (port) $('castPortInput').value = port;
    if (actPort) $('actPortInput').value = actPort;
    if (sessionCode) $('actCodeInput').value = sessionCode;
    if (transport === 'usb') this.selectTransportMode('usb');

    if (role === SESSION_ROLE_STANDALONE_APP && pkg) {
      this.sessionRole = SESSION_ROLE_STANDALONE_APP;
      this.standaloneAppPkg = pkg;
      this.standaloneAppCls = cls;
      this.standaloneAppUserId = userId;
      this.standaloneAppProfileType = profileType;
      this.standaloneAppLabel = appLabel || pkg;
      this.standaloneDeviceKey = deviceKey;
      this.standaloneDeviceName = deviceName;
      const appKey = userId > 0 ? `${pkg}#u${userId}` : pkg;
      const profileBadge = this.standalone.getProfileBadgeText(userId, profileType);
      const titleDisplay = profileBadge && !this.standaloneAppLabel.includes(profileBadge)
        ? `${this.standaloneAppLabel} (${profileBadge})`
        : this.standaloneAppLabel;
      document.title = `OpalDesk · ${titleDisplay}`;
      const preset = params.get('preset') || '';
      const dpiParam = Number(params.get('dpi') || 0);
      const compatParam = params.get('compat') === '1';
      if (preset || dpiParam > 0 || compatParam) {
        const patch = {};
        if (preset) patch.preferredPreset = preset;
        if (dpiParam > 0) patch.customDpi = dpiParam;
        if (compatParam) patch.compatibilityResolution = true;
        this.standalone.prefsStore.updatePref(deviceKey, appKey, patch);
      }
      this.standalone.applyInitialStandalonePin(pin === '1');
      this.standalone.syncStandaloneToolbarPrefsUi();
      if (host) {
        setTimeout(() => this.startCastConnection(), 200);
        return;
      }
    }

    if (mode === 'activate') {
      this.selectLobbyTab('activate');
      if (host && sessionCode) {
        setTimeout(() => this.startActivationConnection(), 250);
      }
    }
  }

  bindUiEvents() {
    const langSel = $('langSelect');
    if (langSel) {
      langSel.value = getLanguagePref();
      langSel.addEventListener('change', (e) => {
        setLanguagePref(e.target.value, { persist: true });
        if (this.desktop.enabled) {
          void this.desktop.updateClientPrefs({ language: getLanguagePref() });
        }
      });
    }
    $('themeToggleBtn').addEventListener('click', () => {
      const cur = document.documentElement.getAttribute('data-theme');
      const next = cur === 'prism-pulse' ? 'pure-frost' : 'prism-pulse';
      document.documentElement.setAttribute('data-theme', next);
      try { localStorage.setItem(STORAGE_THEME, next); } catch {}
    });

    $('tabCastBtn').addEventListener('click', () => this.selectLobbyTab('cast'));
    $('tabActivateBtn').addEventListener('click', () => this.selectLobbyTab('activate'));
    $('tabLogsBtn')?.addEventListener('click', () => this.selectLobbyTab('logs'));
    this.diagnostics.bindEvents();
    this.standalone.bindEvents();
    this.fileBrowser.bindEvents();
    this.companion.bindEvents();
    $('hideToTrayCheck')?.addEventListener('change', (e) => {
      this.persistCastPrefs({ hideToTrayOnClose: Boolean(e.target.checked) });
    });
    $('modeWifiBtn').addEventListener('click', () => this.selectTransportMode('wifi'));
    $('modeUsbBtn').addEventListener('click', () => this.selectTransportMode('usb'));

    $('copyAdbForwardBtn').addEventListener('click', () => {
      const port = Number($('castPortInput').value) || 19878;
      this.copyText(`adb forward tcp:${port} localabstract:opaldesk_stream`, t('cast.copyForwardToast'));
    });
    $('castPortInput').addEventListener('input', () => this.updateUsbForwardHint());
    const connectOnEnter = (e) => {
      if (e.key === 'Enter' && !$('connectCastBtn')?.disabled) {
        e.preventDefault();
        this.startCastConnection();
      }
    };
    $('castHostInput')?.addEventListener('keydown', connectOnEnter);
    $('castPortInput')?.addEventListener('keydown', connectOnEnter);
    $('castCodecSelect')?.addEventListener('change', (e) => {
      const codec = e.target.value;
      saveCodecPref(codec);
      this.persistCastPrefs({ preferredCodec: codec });
    });
    $('castFpsSelect')?.addEventListener('change', (e) => {
      const fps = Number(e.target.value) || 60;
      this.persistCastPrefs({ targetFps: fps });
    });
    $('castBitrateSelect')?.addEventListener('change', () => {
      this.syncBitrateInputsUi('cast');
      const cfg = this.resolveSelectedBitrateConfig('castBitrateSelect', 'castCustomBitrateInput', { notifyOnClamp: true });
      this.bitrateMode = cfg.mode;
      this.persistCastPrefs({ bitrateMode: cfg.mode, bitrateKbps: cfg.bitrateKbps });
    });
    $('castCustomBitrateInput')?.addEventListener('change', () => {
      const cfg = this.resolveSelectedBitrateConfig('castBitrateSelect', 'castCustomBitrateInput', { notifyOnClamp: true });
      this.bitrateMode = cfg.mode;
      this.syncBitrateInputsUi('both');
      this.persistCastPrefs({ bitrateMode: cfg.mode, bitrateKbps: cfg.bitrateKbps });
    });
    $('liveBitrateSelect')?.addEventListener('change', () => {
      this.syncBitrateInputsUi('live');
    });
    $('liveCustomBitrateInput')?.addEventListener('change', () => {
      this.resolveSelectedBitrateConfig('liveBitrateSelect', 'liveCustomBitrateInput', { notifyOnClamp: true });
      this.syncBitrateInputsUi('both');
    });
    $('castAudioCheck')?.addEventListener('change', (e) => {
      this.persistCastPrefs({ audioEnabled: Boolean(e.target.checked) });
    });
    $('connectCastBtn').addEventListener('click', () => this.startCastConnection());
    $('openCastTrustBtn').addEventListener('click', () => {
      const host = $('castHostInput').value.trim();
      const port = Number($('castPortInput').value) || 19878;
      this.openTrustPopup(host, port);
    });
    $('clearRecentBtn').addEventListener('click', async () => {
      try { localStorage.removeItem(STORAGE_DEVICES); } catch {}
      if (this.desktop.enabled) {
        for (const dev of [...this.desktop.trustedDevices]) {
          try { await this.desktop.revokeTrustedDevice(dev.id); } catch {}
        }
      }
      this.renderRecentDevices();
    });

    $('refreshMdnsBtn')?.addEventListener('click', async () => {
      if (!this.desktop.enabled) return;
      const list = await this.desktop.pollMdnsDevices(true);
      this.renderDesktopMdnsDevices(list);
      this.showToast(t('cast.mdnsRefreshedToast'));
    });
    $('scanAdbDevicesBtn')?.addEventListener('click', () => this.refreshDesktopUsbPanel());
    $('usbTetherBetaCheck')?.addEventListener('change', (e) => {
      if (!this.desktop.enabled) return;
      this.usbTetherBetaEnabled = Boolean(e.target.checked);
      saveUsbTetherBetaPref(this.usbTetherBetaEnabled);
      this.syncDesktopUsbTetherUi();
      if (this.usbTetherBetaEnabled) {
        this.refreshDesktopUsbTetherPanel();
      } else {
        this.desktop.usbTetherDevices = [];
        this.renderDesktopUsbTetherDevices([]);
      }
    });
    $('scanUsbTetherBtn')?.addEventListener('click', () => this.refreshDesktopUsbTetherPanel(true));
    $('customAdbPathInput')?.addEventListener('input', () => {
      this.customAdbPathDirty = true;
    });
    $('customAdbPathInput')?.addEventListener('keydown', async (e) => {
      if (e.key !== 'Enter' || !this.desktop.enabled) return;
      e.preventDefault();
      await this.saveCustomAdbPath({ customPath: $('customAdbPathInput').value });
      await this.refreshDesktopUsbPanel();
    });
    $('saveAdbPathBtn')?.addEventListener('click', async () => {
      if (!this.desktop.enabled) return;
      await this.saveCustomAdbPath({ customPath: $('customAdbPathInput').value });
      await this.refreshDesktopUsbPanel();
    });
    $('clearAdbPathBtn')?.addEventListener('click', async () => {
      if (!this.desktop.enabled) return;
      await this.saveCustomAdbPath({ customPath: '' });
      await this.refreshDesktopUsbPanel();
    });
    $('tofuApproveBtn')?.addEventListener('click', () => this.resolveTofuModal(true));
    $('tofuRejectBtn')?.addEventListener('click', () => this.resolveTofuModal(false));

    // Activation events
    $('connectActBtn').addEventListener('click', () => this.startActivationConnection());
    $('disconnectActBtn').addEventListener('click', () => {
      this.activation.disconnect();
      $('actWorkspace').hidden = true;
    });
    $('openActTrustBtn').addEventListener('click', () => {
      const host = $('actHostInput').value.trim();
      const port = Number($('actPortInput').value) || 19879;
      this.openTrustPopup(host, port);
    });
    $('actPairStartBtn').addEventListener('click', () => {
      const pairPort = Number($('adbPairPortInput').value) || 0;
      const pairCode = $('adbPairCodeInput').value.trim();
      const connectPort = Number($('adbConnPortInput').value) || 0;
      if (!pairPort || pairCode.length !== 6) {
        this.setActError(t('activate.errPairRequired'));
        return;
      }
      this.setActError('');
      this.setActBusy(true);
      this.activation.submitPairAndStart({
        pairPort,
        pairCode,
        connectPort,
        autoEnableCast: $('actAutoCastCheck').checked,
      });
    });
    $('actDirectConnBtn').addEventListener('click', () => {
      const connectPort = Number($('adbConnPortInput').value) || 0;
      if (!connectPort) {
        this.setActError(t('activate.errConnPortRequired'));
        return;
      }
      this.setActError('');
      this.setActBusy(true);
      this.activation.submitDirectConnect({
        connectPort,
        autoEnableCast: $('actAutoCastCheck').checked,
      });
    });
    $('actShizukuBtn').addEventListener('click', () => {
      this.setActError('');
      this.setActBusy(true);
      this.activation.submitShizukuActivate({ autoEnableCast: $('actAutoCastCheck').checked });
    });
    $('actRefreshStateBtn').addEventListener('click', () => this.activation.requestStateRefresh());
    $('copyActFallbackBtn').addEventListener('click', () => {
      this.copyText($('actFallbackCmd').textContent || '', t('activate.copyFallbackToast'));
    });

    // Stage toolbar actions
    $('cancelConnectBtn').addEventListener('click', () => {
      if (this.sessionRole === SESSION_ROLE_STANDALONE_APP && this.desktop.enabled) {
        this.disconnectCast('');
        this.desktop.closeCurrentWindow().catch(() => {});
        return;
      }
      this.disconnectCast(t('cast.cancelledToast'));
    });
    $('tbDisconnectBtn').addEventListener('click', () => {
      if (this.sessionRole === SESSION_ROLE_STANDALONE_APP && this.desktop.enabled) {
        this.disconnectCast('');
        this.desktop.closeCurrentWindow().catch(() => {});
        return;
      }
      this.disconnectCast(t('cast.disconnectedToast'));
    });
    $('tbBackBtn').addEventListener('click', () => {
      this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_BACK));
      this.input.focusIme();
    });
    $('tbHomeBtn').addEventListener('click', () => {
      this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_HOME));
      if (this.input.virtualKeyboardDevice) {
        this.input.dismissSoftKeyboard();
      } else {
        this.input.focusIme();
      }
    });
    $('tbExitAppFsBtn')?.addEventListener('click', () => {
      this.remoteFullscreenTitle = '';
      this.syncRemoteFullscreenUi();
      this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_HOME));
      this.bumpToolbarVisibility();
      this.input.focusIme();
    });
    $('tbBlackoutBtn')?.addEventListener('click', () => {
      this.toggleBlackout();
      this.bumpToolbarVisibility();
      this.input.focusIme();
    });
    $('tbFilesBtn')?.addEventListener('click', () => {
      $('qualityPopover').hidden = true;
      $('appsPopover').hidden = true;
      this.fileBrowser.open('files');
    });
    $('tbDialerBtn')?.addEventListener('click', () => {
      $('qualityPopover').hidden = true;
      $('appsPopover').hidden = true;
      this.companion.openDialerModal();
    });
    $('tbScreenshotBtn')?.addEventListener('click', async () => {
      try {
        const label = this.sessionRole === SESSION_ROLE_STANDALONE_APP && this.standaloneAppLabel
          ? this.standaloneAppLabel
          : 'OpalDesk';
        const res = await captureCanvasScreenshot($('castCanvas'), { desktop: this.desktop, label });
        const clipSuffix = res.copiedToClipboard ? t('cast.screenshotClipboardSuffix') : '';
        if (res.savedPath && this.desktop.enabled) {
          this.showToast(t('cast.screenshotSavedDesktop', { suffix: clipSuffix }), {
            actionText: t('cast.openLocationAction'),
            actionFn: () => void this.desktop.revealInExplorer(res.savedPath),
          });
        } else {
          this.showToast(t('cast.screenshotSavedFile', { name: res.fileName, suffix: clipSuffix }));
        }
      } catch (err) {
        this.showToast(err?.message || t('cast.screenshotFailed'));
      }
      this.bumpToolbarVisibility();
      this.input.focusIme();
    });
    $('tbRecordBtn')?.addEventListener('click', () => {
      this.recorder.toggle();
      this.bumpToolbarVisibility();
      this.input.focusIme();
    });
    $('tbInputModeBtn').addEventListener('click', () => {
      const nextTouch = !this.input.primaryLeftAsTouch;
      this.input.setPrimaryLeftAsTouch(nextTouch);
      const btn = $('tbInputModeBtn');
      btn.textContent = nextTouch ? t('stage.touchBtn') : t('stage.mouseBtn');
      btn.classList.toggle('active', nextTouch);
      this.showToast(nextTouch ? t('cast.touchModeToast') : t('cast.mouseModeToast'));
      this.input.focusIme();
    });
    $('tbKeyboardBtn').addEventListener('click', () => {
      const open = this.input.toggleSoftKeyboard();
      this.syncKeyboardUi(open);
      this.bumpToolbarVisibility();
    });
    $('tbMicBtn').addEventListener('click', async () => {
      await this.toggleRemoteMic();
      this.bumpToolbarVisibility();
      this.input.focusIme();
    });
    $('tbCameraBtn').addEventListener('click', async () => {
      await this.toggleRemoteCamera();
      this.bumpToolbarVisibility();
      this.input.focusIme();
    });
    $('tbAudioBtn').addEventListener('click', async () => {
      const wasSuspended = Boolean(this.audio.audioCtx && this.audio.audioCtx.state === 'suspended');
      await this.audio.resumeFromGesture();
      if (wasSuspended && !this.audio.muted) {
        this.input.focusIme();
        return;
      }
      const nextMuted = !this.audio.muted;
      this.audio.setMuted(nextMuted);
      this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_SET_AUDIO_ENABLED, nextMuted ? 0 : 1));
      this.input.focusIme();
    });
    $('tbFullscreenBtn').addEventListener('click', () => {
      this.toggleFullscreen();
      this.input.focusIme();
    });
    document.addEventListener('fullscreenchange', () => this.syncFullscreenUi());

    // Quality popover
    $('tbQualityBtn').addEventListener('click', () => {
      $('appsPopover').hidden = true;
      $('qualityPopover').hidden = !$('qualityPopover').hidden;
      if ($('qualityPopover').hidden) this.input.focusIme();
    });
    $('closeQualityPopBtn').addEventListener('click', () => {
      $('qualityPopover').hidden = true;
      this.input.focusIme();
    });
    $('qualityPopover').querySelectorAll('[data-preset]').forEach((btn) => {
      btn.addEventListener('click', () => {
        this.resizeMode = btn.dataset.preset;
        this.syncResizePresetUi();
        this.persistCastPrefs({ resizeMode: this.resizeMode });
      });
    });
    $('applyQualityBtn').addEventListener('click', () => {
      this.applyLiveResizeConfig();
      $('qualityPopover').hidden = true;
      this.input.focusIme();
    });
    $('hideLocalCursorCheck')?.addEventListener('change', (e) => {
      this.hideLocalCursor = Boolean(e.target.checked);
      this.syncLocalCursorUi();
      this.persistCastPrefs({ hideLocalCursor: this.hideLocalCursor });
      this.showToast(this.hideLocalCursor ? t('cast.hideCursorOnToast') : t('cast.hideCursorOffToast'));
    });

    // App launcher popover
    $('tbAppsBtn').addEventListener('click', () => {
      $('qualityPopover').hidden = true;
      const opening = $('appsPopover').hidden;
      $('appsPopover').hidden = !opening;
      if (opening) {
        this.sendOpalFrame(TYPE_LIST_APPS);
        if (!this.input.virtualKeyboardDevice) {
          $('appSearchInput').focus();
        }
      } else {
        this.input.focusIme();
      }
    });
    $('closeAppsPopBtn').addEventListener('click', () => {
      $('appsPopover').hidden = true;
      this.input.focusIme();
    });
    $('appSearchInput').addEventListener('input', () => this.renderAppList());

    // Toast action button
    $('toastActionBtn').addEventListener('click', () => {
      if (this.toastActionFn) this.toastActionFn();
      $('toastIsland').hidden = true;
    });

    // Stage toolbar auto-hide + reveal strictly via centered top grab handle (with 180ms hover-intent
    // debounce so sweeping past top-right Android status buttons never blocks clicks behind it)
    $('stageRevealHandle').addEventListener('pointerenter', () => {
      clearTimeout(this.revealHoverTimer);
      this.revealHoverTimer = setTimeout(() => this.bumpToolbarVisibility(), REVEAL_HANDLE_HOVER_DELAY_MS);
    });
    $('stageRevealHandle').addEventListener('pointerleave', () => {
      clearTimeout(this.revealHoverTimer);
      this.revealHoverTimer = null;
    });
    $('stageRevealHandle').addEventListener('click', () => {
      clearTimeout(this.revealHoverTimer);
      this.revealHoverTimer = null;
      this.bumpToolbarVisibility();
    });
    $('stageToolbar').addEventListener('pointerleave', () => this.bumpToolbarVisibility());

    // Drag-drop visual cue
    let dragDepth = 0;
    $('canvasWrap').addEventListener('dragenter', (e) => {
      if (!this.authenticated) return;
      e.preventDefault();
      dragDepth++;
      $('dropOverlay').hidden = false;
    });
    $('canvasWrap').addEventListener('dragleave', () => {
      dragDepth = Math.max(0, dragDepth - 1);
      if (dragDepth === 0) $('dropOverlay').hidden = true;
    });
    $('canvasWrap').addEventListener('drop', () => {
      dragDepth = 0;
      $('dropOverlay').hidden = true;
    });

    // Consistent backdrop-click dismiss for secondary modals
    $('lobbyAppsModalOverlay')?.addEventListener('click', (e) => {
      if (e.target === $('lobbyAppsModalOverlay')) $('closeLobbyAppsModalBtn')?.click();
    });
    $('feedbackModalOverlay')?.addEventListener('click', (e) => {
      if (e.target === $('feedbackModalOverlay')) $('fbCancelBtn')?.click();
    });
    $('fileManagerOverlay')?.addEventListener('click', (e) => {
      if (e.target === $('fileManagerOverlay')) $('closeFileManagerBtn')?.click();
    });
    $('fmPreviewOverlay')?.addEventListener('click', (e) => {
      if (e.target === $('fmPreviewOverlay')) $('fmPreviewCloseBtn')?.click();
    });

    window.addEventListener('keydown', (e) => {
      const targetTag = (e.target && e.target.tagName) ? e.target.tagName.toUpperCase() : '';
      const isTyping = targetTag === 'INPUT' || targetTag === 'TEXTAREA' || targetTag === 'SELECT';

      // Escape: dismiss topmost open modal or stage popover cleanly
      if (e.key === 'Escape') {
        if ($('fmPreviewOverlay') && !$('fmPreviewOverlay').hidden) {
          e.preventDefault();
          $('fmPreviewCloseBtn')?.click();
          return;
        }
        if ($('fileManagerOverlay') && !$('fileManagerOverlay').hidden) {
          e.preventDefault();
          $('closeFileManagerBtn')?.click();
          return;
        }
        if ($('dialerModalOverlay') && !$('dialerModalOverlay').hidden) {
          e.preventDefault();
          $('closeDialerModalBtn')?.click();
          return;
        }
        if ($('lobbyAppsModalOverlay') && !$('lobbyAppsModalOverlay').hidden) {
          e.preventDefault();
          $('closeLobbyAppsModalBtn')?.click();
          return;
        }
        if ($('feedbackModalOverlay') && !$('feedbackModalOverlay').hidden) {
          e.preventDefault();
          $('fbCancelBtn')?.click();
          return;
        }
        if ($('qualityPopover') && !$('qualityPopover').hidden) {
          e.preventDefault();
          $('qualityPopover').hidden = true;
          this.input.focusIme();
          return;
        }
        if ($('appsPopover') && !$('appsPopover').hidden) {
          e.preventDefault();
          $('appsPopover').hidden = true;
          this.input.focusIme();
          return;
        }
      }

      // Lobby quick tab switching (Ctrl/Cmd + 1/2/3)
      if (!this.authenticated && !isTyping && (e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey) {
        if (e.key === '1') { e.preventDefault(); this.selectLobbyTab('cast'); return; }
        if (e.key === '2') { e.preventDefault(); this.selectLobbyTab('activate'); return; }
        if (e.key === '3') { e.preventDefault(); this.selectLobbyTab('logs'); return; }
      }

      // Global Ctrl/Cmd + Shift + F: open Phone Files & Storage when any channel is connected
      if (!isTyping && (e.ctrlKey || e.metaKey) && e.shiftKey && !e.altKey && e.code === 'KeyF') {
        if (this.companion?.hasControlChannel?.()) {
          e.preventDefault();
          this.fileBrowser.open('files');
          return;
        }
      }

      // Cast stage shortcuts (Ctrl/Cmd + Shift + S screenshot, Ctrl/Cmd + Shift + V record)
      if (this.authenticated && !isTyping && (e.ctrlKey || e.metaKey) && e.shiftKey && !e.altKey) {
        if (e.code === 'KeyS') {
          e.preventDefault();
          $('tbScreenshotBtn')?.click();
          return;
        }
        if (e.code === 'KeyV') {
          e.preventDefault();
          $('tbRecordBtn')?.click();
          return;
        }
      }

      if (!this.authenticated || !e.shiftKey || !(e.altKey || e.metaKey)) return;
      if (e.code === 'KeyT' && this.desktop.enabled) {
        e.preventDefault();
        this.standalone.toggleCurrentWindowAlwaysOnTop();
      } else if (e.code === 'KeyB' && this.sessionRole !== SESSION_ROLE_STANDALONE_APP) {
        e.preventDefault();
        this.toggleBlackout();
      } else if (e.code === 'KeyM') {
        e.preventDefault();
        $('tbAudioBtn')?.click();
      } else if (e.code === 'KeyH' && this.sessionRole !== SESSION_ROLE_STANDALONE_APP) {
        e.preventDefault();
        this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_HOME));
      }
    });
  }

  updateUsbForwardHint() {
    const port = Number($('castPortInput')?.value) || 19878;
    const codeEl = $('usbForwardCode');
    if (codeEl) codeEl.textContent = `adb forward tcp:${port} localabstract:opaldesk_stream`;
    const hintEl = $('usbForwardHint');
    if (hintEl) {
      hintEl.textContent = t('cast.usbForwardHint', { port });
    }
  }

  installTrustPopupListener() {
    window.addEventListener('message', (ev) => {
      const d = ev.data;
      if (!d || d.type !== 'opaldesk-tls-trusted') return;
      if (d.role === 'activate') {
        $('actTrustBox').hidden = true;
        this.showToast(t('cast.trustCertReadyAct'));
        this.startActivationConnection();
      } else if (d.role === 'cast') {
        $('castTrustBox').hidden = true;
        this.showToast(t('cast.trustCertReadyCast'));
        this.startCastConnection();
      }
    });
  }

  installResizeObserver() {
    const ro = new ResizeObserver(() => {
      if (!this.authenticated || this.resizeMode !== 'auto') return;
      clearTimeout(this.resizeDebounceTimer);
      this.resizeDebounceTimer = setTimeout(() => this.applyLiveResizeConfig(), 380);
    });
    ro.observe($('canvasWrap'));
  }

  openTrustPopup(host, port) {
    if (!host) return;
    const trustUrl = buildTrustPageUrl(host, port, window.location.href);
    const pop = window.open(trustUrl, 'opaldesk_tls_trust', 'width=520,height=640');
    if (!pop) {
      window.location.href = trustUrl;
    }
  }

  selectLobbyTab(tab) {
    const activeTab = tab === 'activate' ? 'activate' : tab === 'logs' ? 'logs' : 'cast';
    const tabs = [
      { key: 'cast', btn: $('tabCastBtn'), pane: $('paneCast') },
      { key: 'activate', btn: $('tabActivateBtn'), pane: $('paneActivate') },
      { key: 'logs', btn: $('tabLogsBtn'), pane: $('paneLogs') },
    ];
    for (const item of tabs) {
      const isSelected = item.key === activeTab;
      item.btn?.classList.toggle('active', isSelected);
      item.btn?.setAttribute('aria-selected', String(isSelected));
      if (item.pane) item.pane.hidden = !isSelected;
    }
    if (activeTab === 'logs') {
      this.diagnostics?.onTabActivated();
    } else if (activeTab === 'cast') {
      this.standalone?.refreshActiveWindows();
    }
  }

  selectTransportMode(mode) {
    this.transportMode = mode === 'usb' ? 'usb' : 'wifi';
    const isUsb = this.transportMode === 'usb';
    $('modeWifiBtn').classList.toggle('active', !isUsb);
    $('modeUsbBtn').classList.toggle('active', isUsb);
    const hostInput = $('castHostInput');
    const hostLabel = $('castHostLabel');
    if (isUsb && hostInput.value.trim() && hostInput.value.trim() !== '127.0.0.1') {
      this.lastWifiHost = hostInput.value.trim();
    }
    if (this.desktop.enabled) {
      $('usbHelperBox').hidden = true;
      $('desktopUsbSection').hidden = !isUsb;
      $('desktopMdnsSection').hidden = isUsb;
      if (isUsb) {
        this.refreshDesktopUsbPanel();
      }
    } else {
      $('usbHelperBox').hidden = !isUsb;
    }
    if (isUsb) {
      hostInput.value = '127.0.0.1';
      hostInput.readOnly = true;
      if (hostLabel) hostLabel.textContent = t('cast.hostLabelUsb');
      if (!$('castPortInput').value) $('castPortInput').value = '19878';
      $('castTrustBox').hidden = true;
      this.updateUsbForwardHint();
    } else {
      hostInput.readOnly = false;
      if (hostLabel) hostLabel.textContent = t('cast.hostLabelWifi');
      if (hostInput.value.trim() === '127.0.0.1') {
        hostInput.value = this.lastWifiHost || '';
      }
    }
  }

  renderDesktopMdnsDevices(devices = []) {
    const listEl = $('desktopMdnsList');
    if (!listEl || !this.desktop.enabled) return;
    listEl.innerHTML = '';
    if (!devices.length) {
      const hint = document.createElement('span');
      hint.className = 'hint-text';
      hint.textContent = t('cast.mdnsScanning');
      listEl.appendChild(hint);
      return;
    }
    for (const dev of devices) {
      const row = document.createElement('div');
      row.className = 'mdns-device-card';

      const meta = document.createElement('div');
      meta.className = 'mdns-device-meta';
      const title = document.createElement('strong');
      title.textContent = dev.model && !dev.displayName.includes(dev.model)
        ? `${dev.displayName} (${dev.model})`
        : dev.displayName;
      const sub = document.createElement('span');
      sub.className = 'mono';
      sub.textContent = `${dev.host}:${dev.port}${dev.shortFingerprint ? ` · ${t('cast.mdnsFingerprint', { fp: dev.shortFingerprint })}` : ''}`;
      meta.append(title, sub);

      const actions = document.createElement('div');
      actions.className = 'device-card-actions';

      const appBtn = document.createElement('button');
      appBtn.type = 'button';
      appBtn.className = 'mini-btn';
      appBtn.textContent = t('cast.mdnsStandalone');
      appBtn.addEventListener('click', () => {
        this.standalone.openLobbyAppPicker({
          transport: 'wifi',
          host: dev.host,
          port: dev.port || 19878,
          deviceKey: `${dev.host}:${dev.port || 19878}`,
          deviceName: dev.displayName || dev.host,
          certFingerprint: dev.fingerprint || '',
        });
      });

      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'primary-btn compact';
      btn.textContent = t('cast.mdnsConnect');
      btn.addEventListener('click', () => {
        this.selectTransportMode('wifi');
        $('castHostInput').value = dev.host;
        $('castPortInput').value = String(dev.port || 19878);
        this.startCastConnection();
      });

      actions.append(appBtn, btn);
      row.append(meta, actions);
      listEl.appendChild(row);
    }
  }

  syncDesktopUsbTetherUi() {
    const active = Boolean(this.desktop.enabled && this.usbTetherBetaEnabled);
    const scanBtn = $('scanUsbTetherBtn');
    const listEl = $('desktopUsbTetherList');
    if (scanBtn) scanBtn.hidden = !active;
    if (listEl) listEl.hidden = !active;
  }

  renderDesktopUsbTetherDevices(devices = []) {
    const listEl = $('desktopUsbTetherList');
    if (!listEl || !this.desktop.enabled || !this.usbTetherBetaEnabled) return;
    listEl.innerHTML = '';
    if (!devices.length) {
      const hint = document.createElement('span');
      hint.className = 'hint-text';
      hint.textContent = t('cast.usbTetherEmpty');
      listEl.appendChild(hint);
      return;
    }
    for (const dev of devices) {
      const row = document.createElement('div');
      row.className = 'mdns-device-card';

      const meta = document.createElement('div');
      meta.className = 'mdns-device-meta';
      const title = document.createElement('strong');
      title.textContent = t('cast.usbTetherTitle', { name: dev.displayName || dev.host });
      const nicLabel = dev.interfaceAlias || dev.interfaceDescription || 'USB';
      const sub = document.createElement('span');
      sub.className = 'mono';
      sub.textContent = `${dev.host}:${dev.port} · ${nicLabel}${dev.shortFingerprint ? ` · ${t('cast.mdnsFingerprint', { fp: dev.shortFingerprint })}` : ''}`;
      meta.append(title, sub);

      const actions = document.createElement('div');
      actions.className = 'device-card-actions';

      const appBtn = document.createElement('button');
      appBtn.type = 'button';
      appBtn.className = 'mini-btn';
      appBtn.textContent = t('cast.mdnsStandalone');
      appBtn.addEventListener('click', () => {
        this.standalone.openLobbyAppPicker({
          transport: 'wifi',
          host: dev.host,
          port: dev.port || 19878,
          deviceKey: `${dev.host}:${dev.port || 19878}`,
          deviceName: dev.displayName || dev.host,
          certFingerprint: dev.fingerprint || '',
        });
      });

      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'primary-btn compact';
      btn.textContent = t('cast.mdnsConnect');
      btn.addEventListener('click', () => {
        this.explicitCertPin = '';
        this.selectTransportMode('wifi');
        $('castHostInput').value = dev.host;
        $('castPortInput').value = String(dev.port || 19878);
        this.startCastConnection();
      });

      actions.append(appBtn, btn);
      row.append(meta, actions);
      listEl.appendChild(row);
    }
  }

  async refreshDesktopUsbTetherPanel(showToastOnDone = false) {
    if (!this.desktop.enabled || !this.usbTetherBetaEnabled) return;
    const listEl = $('desktopUsbTetherList');
    if (listEl) {
      listEl.hidden = false;
      listEl.innerHTML = `<span class="hint-text">${t('cast.usbTetherProbing')}</span>`;
    }
    try {
      const port = Number($('castPortInput')?.value) || 19878;
      const list = await this.desktop.scanUsbTetherDevices(port);
      this.renderDesktopUsbTetherDevices(list);
      if (showToastOnDone) {
        this.showToast(list.length ? t('cast.usbTetherToastFound', { count: list.length }) : t('cast.usbTetherToastNone'));
      }
    } catch (err) {
      if (listEl) {
        listEl.innerHTML = '';
        const errSpan = document.createElement('span');
        errSpan.className = 'hint-text';
        errSpan.textContent = t('cast.usbTetherFailed', { error: String(err?.message || err || t('common.unknownError')) });
        listEl.appendChild(errSpan);
      }
    }
  }

  async refreshDesktopUsbPanel() {
    if (!this.desktop.enabled) return;
    this.syncDesktopUsbTetherUi();
    if (this.usbTetherBetaEnabled) {
      this.refreshDesktopUsbTetherPanel();
    }
    const hintEl = $('adbStatusHint');
    const listEl = $('desktopAdbList');
    listEl.innerHTML = '';
    try {
      await this.flushPendingCustomAdbPath();
      const status = await this.desktop.refreshAdbStatus();
      this.syncCustomAdbPathUi(status);
      if (!status.available) {
        const detailsEl = $('adbPathDetails');
        if (detailsEl) detailsEl.open = true;
        hintEl.textContent = status.customAdbPath
          ? t('cast.adbNotFoundRemembered', { path: status.customAdbPath })
          : t('cast.adbNotFound');
        return;
      }
      hintEl.textContent = status.customAdbPath
        ? t('cast.adbReadyRemembered', { path: status.adbPath })
        : t('cast.adbReady', { path: status.adbPath });
      const devices = await this.desktop.scanAdbDevices();
      if (!devices.length) {
        const empty = document.createElement('span');
        empty.className = 'hint-text';
        empty.textContent = t('cast.adbNoDevice');
        listEl.appendChild(empty);
        return;
      }
      const firstOnline = devices.find((d) => d.state === 'device');
      if (firstOnline && !this.selectedAdbSerial) {
        this.selectedAdbSerial = firstOnline.serial;
      }
      for (const dev of devices) {
        const row = document.createElement('div');
        row.className = 'adb-device-row';
        const meta = document.createElement('div');
        meta.className = 'adb-device-meta';
        const title = document.createElement('strong');
        title.textContent = `${dev.displayName} (${dev.serial})`;
        const sub = document.createElement('span');
        sub.textContent =
          dev.state === 'device'
            ? t('cast.adbStateReady')
            : t('cast.adbStateUnauth', { state: dev.state });
        meta.append(title, sub);

        const actions = document.createElement('div');
        actions.className = 'device-card-actions';

        const appBtn = document.createElement('button');
        appBtn.type = 'button';
        appBtn.className = 'mini-btn';
        appBtn.disabled = dev.state !== 'device';
        appBtn.textContent = t('cast.mdnsStandalone');
        appBtn.addEventListener('click', () => {
          this.selectedAdbSerial = dev.serial;
          this.standalone.openLobbyAppPicker({
            transport: 'usb',
            host: '127.0.0.1',
            port: 19878,
            adbSerial: dev.serial,
            deviceKey: `usb:${dev.serial}`,
            deviceName: dev.displayName || dev.serial,
          });
        });

        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'primary-btn compact';
        btn.disabled = dev.state !== 'device';
        btn.textContent = t('cast.adbBtnConnect');
        btn.addEventListener('click', () => {
          this.selectedAdbSerial = dev.serial;
          this.startCastConnection();
        });
        actions.append(appBtn, btn);
        row.append(meta, actions);
        listEl.appendChild(row);
      }
    } catch (err) {
      hintEl.textContent = String(err?.message || err || t('cast.adbScanFailed'));
    }
  }

  promptTofuConfirmation(host, port, evalRes) {
    return new Promise((resolve) => {
      this.tofuResolver = resolve;
      const isChanged = evalRes.status === 'changed';
      $('tofuModalTitle').textContent = isChanged
        ? t('tofu.titleChanged')
        : t('tofu.titleFirst');
      $('tofuModalDesc').textContent = isChanged
        ? t('tofu.descChanged', { host, port, expected: evalRes.expectedShort })
        : t('tofu.descFirst', { name: evalRes.mdnsDevice?.displayName || host, host, port });
      const mdnsBadge = $('tofuMdnsBadge');
      if (evalRes.mdnsVerified) {
        mdnsBadge.hidden = false;
        mdnsBadge.className = 'status-pill ok mono';
        mdnsBadge.textContent = t('tofu.badgeMdnsOk');
      } else {
        mdnsBadge.hidden = false;
        mdnsBadge.className = 'status-pill warn mono';
        mdnsBadge.textContent = isChanged ? t('tofu.badgeChanged') : t('tofu.badgeManual');
      }
      $('tofuShortFp').textContent = evalRes.actualShort || '----';
      $('tofuFullFp').textContent = evalRes.actualFingerprint || '';
      $('tofuModalOverlay').hidden = false;
    });
  }

  resolveTofuModal(approved) {
    $('tofuModalOverlay').hidden = true;
    const fn = this.tofuResolver;
    this.tofuResolver = null;
    if (fn) fn(Boolean(approved));
  }

  async ensureDesktopTlsTunnel(host, port, wsPath, explicitPin = '') {
    await this.desktop.refreshTrustedDevices();
    const probed = await this.desktop.probeCertificate(host, port, 4500);
    const evalRes = evaluateCertificateTrust({
      host,
      port,
      probedFingerprint: probed.fingerprint,
      trustedDevices: this.desktop.trustedDevices,
      mdnsDevices: this.desktop.mdnsDevices,
      explicitPin,
    });
    if (evalRes.status === 'invalid') {
      throw new Error(t('cast.errInvalidCert'));
    }
    if (evalRes.status === 'mismatch') {
      throw new Error(t('cast.errCertMismatch', { expected: evalRes.expectedShort, actual: evalRes.actualShort }));
    }
    if (evalRes.status === 'tofu_required' || evalRes.status === 'changed') {
      const ok = await this.promptTofuConfirmation(host, port, evalRes);
      if (!ok) return null;
      await this.desktop.upsertTrustedDevice({
        host,
        port,
        displayName: evalRes.mdnsDevice?.displayName || host,
        model: evalRes.mdnsDevice?.model || '',
        certFingerprint: evalRes.actualFingerprint,
        authToken: this.desktop.getTrustedToken(host, port) || getSavedToken(host, port),
        permanent: false,
        lastConnectedAtMs: Date.now(),
      });
      this.renderRecentDevices();
    }
    return this.desktop.startTlsWsTunnel(host, port, wsPath, evalRes.actualFingerprint);
  }

  // ==================== CAST CONNECTION ====================

  async startCastConnection() {
    this.setCastError('');
    $('castTrustBox').hidden = true;

    const isUsb = this.transportMode === 'usb';
    const host = (isUsb ? '127.0.0.1' : $('castHostInput').value.trim());
    const port = Number($('castPortInput').value) || 19878;
    if (!host) {
      this.setCastError(t('cast.errMissingHost'));
      return;
    }

    $('connectCastBtn').disabled = true;
    $('connectCastBtn').textContent = t('cast.probingBtn');
    const defaultBtnLabel = this.desktop.enabled ? t('cast.connectDesktopBtn') : t('cast.connectWebBtn');

    if (this.desktop.enabled) {
      try {
        if (isUsb) {
          if (this.sessionRole === SESSION_ROLE_STANDALONE_APP && port !== 19878) {
            this.activeHost = '127.0.0.1';
            this.activePort = port;
            this.activeUsbLocalPort = port;
            this.openCastWebSocket(`ws://127.0.0.1:${port}/ws`, '127.0.0.1', port, true);
            return;
          }
          await this.flushPendingCustomAdbPath();
          const status = await this.desktop.refreshAdbStatus();
          if (status.available) {
            const devices = await this.desktop.scanAdbDevices();
            const chosen =
              devices.find((d) => d.serial === this.selectedAdbSerial && d.state === 'device') ||
              devices.find((d) => d.state === 'device');
            if (!chosen) {
              throw new Error(t('cast.errNoUsbDevice'));
            }
            $('connectCastBtn').textContent = t('cast.usbBootstrappingBtn');
            const fwd = await this.desktop.bootstrapAndForwardUsb(chosen.serial);
            this.selectedAdbSerial = chosen.serial;
            this.activeHost = '127.0.0.1';
            this.activePort = fwd.localPort;
            this.activeUsbLocalPort = fwd.localPort;
            this.openCastWebSocket(fwd.localWsUrl, '127.0.0.1', fwd.localPort, true);
            return;
          }
          this.activeHost = '127.0.0.1';
          this.activePort = port;
          this.openCastWebSocket(`ws://127.0.0.1:${port}/ws`, '127.0.0.1', port, true);
          return;
        }

        const tunnel = await this.ensureDesktopTlsTunnel(host, port, '/ws', this.explicitCertPin);
        if (!tunnel) {
          $('connectCastBtn').disabled = false;
          $('connectCastBtn').textContent = defaultBtnLabel;
          return;
        }
        this.activeHost = host;
        this.activePort = port;
        this.activeVerifiedFingerprint = tunnel.verifiedFingerprint;
        this.openCastWebSocket(tunnel.localWsUrl, host, port, false);
      } catch (err) {
        $('connectCastBtn').disabled = false;
        $('connectCastBtn').textContent = defaultBtnLabel;
        this.setCastError(String(err?.message || err || t('cast.errDesktopConnectFailed')));
      }
      return;
    }

    if (!isUsb) {
      const probe = await probeHttpsHealth(host, port);
      if (!probe.ok) {
        $('connectCastBtn').disabled = false;
        $('connectCastBtn').textContent = defaultBtnLabel;
        if (probe.needsTrust) {
          $('castTrustBox').hidden = false;
          $('castTrustMsg').textContent = t('cast.trustProbeMsg', { host, port });
        } else {
          this.setCastError(t('cast.errProbeFailed', { host, port, error: probe.error }));
        }
        return;
      }
    }

    this.activeHost = host;
    this.activePort = port;
    const wsUrl = isUsb ? `ws://${host}:${port}/ws` : `wss://${host}:${port}/ws`;
    this.openCastWebSocket(wsUrl, host, port, isUsb);
  }

  openCastWebSocket(wsUrl, host, port, isUsb) {
    // Release standalone companion socket when full desktop cast takes over
    if (this.sessionRole === SESSION_ROLE_DESKTOP) {
      this.companion.disconnect('', { quiet: true });
    }
    this.teardownCastSocket();
    let ws;
    try {
      ws = new WebSocket(wsUrl);
    } catch (err) {
      $('connectCastBtn').disabled = false;
      $('connectCastBtn').textContent = this.desktop.enabled ? t('cast.connectDesktopBtn') : t('cast.connectWebBtn');
      const detail = err?.message || String(err || '');
      if (isUsb) {
        this.setCastError(
          isSafariBrowser()
            ? t('cast.errSafariUsb', { port, detail })
            : t('cast.errWsInitUsb', { url: wsUrl, detail })
        );
      } else {
        this.setCastError(t('cast.errWsInit', { url: wsUrl, detail }));
      }
      return;
    }
    ws.binaryType = 'arraybuffer';
    this.ws = ws;

    const reqSpec = this.computeInitialStreamSpec();
    const savedToken = isUsb
      ? ''
      : ((this.desktop.enabled && this.desktop.getTrustedToken(host, port)) || getSavedToken(host, port));

    const wantAudio = shouldRequestSessionAudio(this.sessionRole, Boolean($('castAudioCheck')?.checked));
    this.audio.setMuted(!wantAudio);

    this.enterStageView(host, !savedToken && !isUsb);

    ws.onopen = () => {
      this.connected = true;
      const helloPayload = encodeHello({
        clientName: detectBrowserName(),
        clientId: this.clientId,
        preferredCodec: $('castCodecSelect').value || 'h264',
        width: reqSpec.width,
        height: reqSpec.height,
        dpi: reqSpec.dpi,
        fps: reqSpec.fps,
        bitrateKbps: reqSpec.bitrateKbps,
        displayMode: DISPLAY_MODE_DEX,
        audioEnabled: wantAudio,
        authToken: savedToken,
        transportHint: isUsb ? 'usb' : 'wifi_tls',
        clientPlatform: this.desktop.enabled ? 'windows' : 'web',
        sessionRole: this.sessionRole,
        appPackage: this.standaloneAppPkg,
        appActivity: this.standaloneAppCls,
        appUserId: this.standaloneAppUserId,
      });
      this.sendOpalFrame(TYPE_HELLO, 0, helloPayload);
    };

    ws.onmessage = (ev) => {
      if (!(ev.data instanceof ArrayBuffer)) return;
      let frame;
      try {
        frame = decodeFrame(ev.data);
      } catch {
        return;
      }
      this.handleDownlinkFrame(frame);
    };

    ws.onerror = () => {
      if (!this.authenticated) {
        if (isUsb) {
          const usbTip = this.desktop.enabled
            ? t('cast.errUsbHandshakeDesktop', { port })
            : t('cast.errUsbHandshakeWeb', { port });
          this.disconnectCast(usbTip);
        } else {
          this.disconnectCast(t('cast.errWsHandshake'));
          if (!this.desktop.enabled) $('castTrustBox').hidden = false;
        }
      }
    };

    ws.onclose = (ev) => {
      const wasAuth = this.authenticated;
      if (!this.ws) return;
      const defaultFail = isUsb
        ? t('cast.errUsbSessionFail', { port })
        : t('cast.errSessionFail');
      const reason = ev.reason || (wasAuth ? t('cast.errDisconnected') : defaultFail);
      this.disconnectCast(reason);
    };
  }

  handleDownlinkFrame(frame) {
    if (this.fileBrowserClient?.handleFrame(frame)) return;
    switch (frame.type) {
      case TYPE_AUTH_RESULT: {
        const auth = decodeAuthResult(frame.payload);
        if (!auth.ok) {
          this.disconnectCast(auth.message || t('cast.errAuthRejected'));
          return;
        }
        this.authenticated = true;
        $('approvalOverlay').hidden = true;
        $('stagePeerName').textContent =
          this.sessionRole === SESSION_ROLE_STANDALONE_APP && this.standaloneAppLabel
            ? this.standaloneAppLabel
            : (auth.deviceName || this.activeHost);
        this.input.setEnabled(true);
        this.startHeartbeat();
        this.bumpToolbarVisibility();
        this.standalone.syncStandaloneToolbarPrefsUi();
        if (this.sessionRole === SESSION_ROLE_DESKTOP) {
          this.sendOpalFrame(TYPE_NOTIFICATION_ACTION, 0, encodeNotificationAction({ op: 'sync' }));
          this.companion.renderCompanionHub();
        }
        this.diagnostics?.log(
          'I',
          'Session',
          `连接认证通过 role=${this.sessionRole} peer=${auth.deviceName || this.activeHost} transport=${this.transportMode}`
        );

        if (this.transportMode !== 'usb') {
          const prevToken =
            (this.desktop.enabled && this.desktop.getTrustedToken(this.activeHost, this.activePort)) ||
            getSavedToken(this.activeHost, this.activePort);
          saveDeviceRecord({
            host: this.activeHost,
            port: this.activePort,
            deviceName: auth.deviceName || this.activeHost,
            authToken: auth.authToken || prevToken,
            permanent: auth.permanent || Boolean(prevToken),
          });
          if (this.desktop.enabled && this.activeVerifiedFingerprint) {
            this.desktop
              .upsertTrustedDevice({
                host: this.activeHost,
                port: this.activePort,
                displayName: auth.deviceName || this.activeHost,
                model: '',
                certFingerprint: this.activeVerifiedFingerprint,
                authToken: auth.authToken || prevToken,
                permanent: auth.permanent || Boolean(prevToken),
                lastConnectedAtMs: Date.now(),
              })
              .then(() => this.renderRecentDevices())
              .catch(() => {});
          }
          this.renderRecentDevices();
        }
        this.showToast(t('cast.connectedToast', { name: auth.deviceName || this.activeHost }));
        break;
      }

      case TYPE_VIDEO_CONFIG: {
        const cfg = decodeVideoConfig(frame.payload);
        this.remoteCodecId = cfg.codec;
        this.video.configure(cfg);
        break;
      }

      case TYPE_VIDEO_FRAME: {
        this.video.decodeFrame(frame.payload, frame.flags, frame.timestampUs);
        break;
      }

      case TYPE_AUDIO_CONFIG: {
        const acfg = decodeAudioConfig(frame.payload);
        this.audio.configure(acfg);
        break;
      }

      case TYPE_AUDIO_FRAME: {
        this.audio.decodeFrame(frame.payload, frame.timestampUs);
        break;
      }

      case TYPE_DEVICE_STATE: {
        try {
          const st = decodeDeviceState(frame.payload);
          if (st.deviceName) $('stagePeerName').textContent = st.deviceName;
          if (typeof st.blackout === 'boolean') {
            this.blackoutActive = st.blackout;
            this.syncBlackoutUi();
          }
          if (typeof st.fullscreenWindowTitle === 'string') {
            const nextFsTitle = st.fullscreenWindowTitle.trim();
            const enteredFs = !this.remoteFullscreenTitle && Boolean(nextFsTitle);
            this.remoteFullscreenTitle = nextFsTitle;
            this.syncRemoteFullscreenUi();
            if (enteredFs) this.bumpToolbarVisibility();
          }
          if (typeof st.injectStatus === 'number') {
            this.injectStatus = st.injectStatus;
            this.updateTelemetry();
            if (st.injectStatus === -1 && !this.inputBlockedWarned) {
              this.inputBlockedWarned = true;
              this.showToast(t('cast.inputBlockedToast'));
            } else if (st.injectStatus > 0) {
              this.inputBlockedWarned = false;
            }
          }
          this.companion.handleDeviceState(st);
        } catch {
          // Ignore malformed telemetry
        }
        break;
      }

      case TYPE_NOTIFICATION_EVENT: {
        this.companion.handleNotificationFrame(frame.payload);
        break;
      }

      case TYPE_MEDIA_STATE: {
        this.companion.handleMediaStateFrame(frame.payload);
        break;
      }

      case TYPE_CALL_STATE: {
        this.companion.handleCallStateFrame(frame.payload);
        break;
      }

      case TYPE_IME_REQUEST: {
        try {
          const req = decodeImeRequest(frame.payload);
          this.input.handleRemoteImeRequest(req.show, req.inputType);
        } catch {
          // Ignore
        }
        break;
      }

      case TYPE_MIC_STATE: {
        try {
          const st = decodeMicState(frame.payload);
          if (!st.active) {
            this.mic.stop();
            this.micEnabled = false;
          } else {
            this.micEnabled = true;
          }
          this.syncMicUi();
          if (st.message) this.showToast(st.message);
        } catch {
          // Ignore
        }
        break;
      }

      case TYPE_CAMERA_STATE: {
        try {
          const st = decodeCameraState(frame.payload);
          if (!st.active) {
            this.camera.stop();
            this.cameraEnabled = false;
          } else {
            this.cameraEnabled = true;
            if ((st.facing === 'front' || st.facing === 'back') && this.camera.facing !== st.facing) {
              this.camera.start(st.facing).catch(() => {});
            }
          }
          this.syncCameraUi();
          if (st.message) this.showToast(st.message);
        } catch {
          // Ignore
        }
        break;
      }

      case TYPE_CLIPBOARD_PUSH: {
        try {
          const clip = decodeClipboardPush(frame.payload, frame.flags);
          if (clip.isImage && clip.pngData && clip.pngData.byteLength > 0) {
            this.handleRemoteClipboardImage(clip.pngData);
          } else if (!clip.isImage && clip.text) {
            this.handleRemoteClipboardText(clip.text);
          }
        } catch {
          // Ignore
        }
        break;
      }

      case TYPE_APP_LIST: {
        try {
          this.currentApps = decodeAppList(frame.payload);
          this.renderAppList();
        } catch {
          // Ignore
        }
        break;
      }

      case TYPE_FILE_TRANSFER_ACK: {
        try {
          const ack = decodeFileTransferAck(frame.payload);
          if (ack.status === FILE_ACK_OK) {
            this.showToast(t('cast.fileSentToast', { path: ack.pathOrError }));
          } else {
            this.showToast(t('cast.fileFailToast', { error: ack.pathOrError }));
          }
        } catch {
          // Ignore
        }
        break;
      }

      case TYPE_PONG: {
        try {
          const id = decodePingPong(frame.payload);
          const sentAt = this.pingSentMap.get(id);
          if (sentAt !== undefined) {
            this.pingSentMap.delete(id);
            this.lastRttMs = Math.max(1, Math.round(performance.now() - sentAt));
            this.updateTelemetry();
          }
        } catch {
          // Ignore
        }
        break;
      }
      default:
        break;
    }
  }

  sendOpalFrame(type, flags = 0, payload = undefined) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return false;
    const raw = encodeFrame(type, flags, undefined, payload);
    this.ws.send(raw);
    return true;
  }

  hasControlChannel() {
    return this.companion.hasControlChannel();
  }

  applyStandaloneStreamGeometry(geo) {
    if (!this.authenticated || !geo) return;
    const fps = Number($('liveFpsSelect')?.value) || Number($('castFpsSelect')?.value) || 60;
    const brCfg = this.resolveSelectedBitrateConfig('castBitrateSelect', 'castCustomBitrateInput');
    this.sendOpalFrame(
      TYPE_RESIZE_CONFIG,
      0,
      encodeResizeConfig({
        width: geo.streamWidth,
        height: geo.streamHeight,
        dpi: geo.dpi || 0,
        fps,
        bitrateKbps: brCfg.bitrateKbps || 14000,
      })
    );
  }

  launchPopoutByPackage(pkg, label = '') {
    if (!pkg) return;
    const target = this.authenticated
      ? this.standalone.currentCastTarget()
      : this.companion.target
        ? {
            transport: this.companion.target.transport || 'wifi',
            host: this.companion.target.host,
            port: this.companion.target.port || 19878,
            adbSerial: this.companion.target.adbSerial || '',
            deviceKey: `${this.companion.target.host}:${this.companion.target.port || 19878}`,
            deviceName: this.companion.target.deviceName || this.companion.target.host || 'Android',
            certFingerprint: this.companion.target.certFingerprint || '',
          }
        : null;
    if (!target) {
      this.showToast(t('cast.noDeviceForPopout'));
      return;
    }
    this.fileBrowser.close();
    void this.standalone.requestLaunchStandaloneApp({ pkg, cls: '', label: label || pkg }, target);
  }

  computeInitialStreamSpec() {
    const isStandalone = this.sessionRole === SESSION_ROLE_STANDALONE_APP;
    const isWired = this.transportMode === 'usb';
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const vw = Math.max(640, Math.round(window.innerWidth * dpr));
    const vh = Math.max(480, Math.round(window.innerHeight * dpr));
    let width;
    let height;
    let standaloneGeo = null;
    if (isStandalone) {
      standaloneGeo = resolvePopoutLaunchGeometry(this.standalone.getCurrentStandalonePref());
      width = standaloneGeo.streamWidth;
      height = standaloneGeo.streamHeight;
    } else if (this.resizeMode && this.resizeMode !== 'auto') {
      const [pw, ph] = this.resizeMode.split('x').map(Number);
      width = pw || 1920;
      height = ph || 1080;
    } else {
      const maxW = isWired ? 3840 : 2560;
      const maxH = isWired ? 2160 : 1600;
      const fitted = fitDimensionsToMaxBounds(vw, vh, maxW, maxH, 8);
      width = fitted.width;
      height = fitted.height;
    }
    const savedDpi = Number($('liveDpiSelect')?.value) || 0;
    const dpi = isStandalone
      ? (standaloneGeo?.dpi || 360)
      : (savedDpi > 0 ? savedDpi : Math.max(160, Math.min(320, Math.round(160 * dpr))));
    const fps = Number($('castFpsSelect').value) || 60;
    const brCfg = this.resolveSelectedBitrateConfig('castBitrateSelect', 'castCustomBitrateInput');
    this.bitrateMode = brCfg.mode;
    this.autoAdaptiveBitrateKbps = 0;
    this.autoHealthyStreak = 0;
    return { width, height, dpi, fps, bitrateKbps: brCfg.bitrateKbps };
  }

  applyLiveResizeConfig({ bitrateOverrideKbps = null, silent = false } = {}) {
    if (!this.authenticated) return;
    let w;
    let h;
    if (this.resizeMode === 'auto') {
      const rect = $('canvasWrap').getBoundingClientRect();
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const isStandalone = this.sessionRole === SESSION_ROLE_STANDALONE_APP;
      const isWired = this.transportMode === 'usb';
      const minW = isStandalone ? 480 : 640;
      const rawW = Math.max(minW, Math.round(rect.width * dpr));
      const rawH = Math.max(480, Math.round(rect.height * dpr));
      const maxW = isStandalone ? 2560 : (isWired ? 3840 : 2560);
      const maxH = isStandalone ? 2560 : (isWired ? 2160 : 1600);
      const fitted = fitDimensionsToMaxBounds(rawW, rawH, maxW, maxH, 8);
      w = fitted.width;
      h = fitted.height;
    } else {
      const [pw, ph] = this.resizeMode.split('x').map(Number);
      w = pw || 1920;
      h = ph || 1080;
    }
    const fps = Number($('liveFpsSelect').value) || Number($('castFpsSelect').value) || 60;
    const dpi = Number($('liveDpiSelect')?.value) || 0;
    let bitrateKbps;
    if (typeof bitrateOverrideKbps === 'number' && bitrateOverrideKbps > 0) {
      bitrateKbps = bitrateOverrideKbps;
    } else {
      const brCfg = this.resolveSelectedBitrateConfig('liveBitrateSelect', 'liveCustomBitrateInput', {
        notifyOnClamp: !silent,
      });
      this.bitrateMode = brCfg.mode;
      bitrateKbps = brCfg.bitrateKbps;
      if (brCfg.mode === 'auto') {
        this.autoAdaptiveBitrateKbps = 0;
        this.autoHealthyStreak = 0;
      }
      if ($('castFpsSelect')?.querySelector(`option[value="${fps}"]`)) {
        $('castFpsSelect').value = String(fps);
      }
      if ($('castBitrateSelect')) {
        $('castBitrateSelect').value = brCfg.mode === 'auto'
          ? 'auto'
          : (brCfg.mode === 'custom' ? 'custom' : String(bitrateKbps));
      }
      this.syncBitrateInputsUi('both');
      this.persistCastPrefs({
        targetFps: fps,
        bitrateMode: brCfg.mode,
        bitrateKbps,
        resizeMode: this.resizeMode,
        liveDpi: dpi,
      });
    }
    this.sendOpalFrame(TYPE_RESIZE_CONFIG, 0, encodeResizeConfig({ width: w, height: h, dpi, fps, bitrateKbps }));
  }

  async sendDroppedFiles(files) {
    for (const file of files) {
      const transferId = this.transferSeq++;
      this.showToast(t('cast.fileSendingToast', { name: file.name, mb: (file.size / 1024 / 1024).toFixed(2) }));
      const buf = new Uint8Array(await file.arrayBuffer());
      let offset = 0;
      let isFirst = true;
      while (offset < buf.byteLength || isFirst) {
        const end = Math.min(buf.byteLength, offset + FILE_CHUNK_SIZE);
        const slice = buf.subarray(offset, end);
        let flags = FILE_FLAG_DATA;
        if (isFirst) flags |= FILE_FLAG_START;
        if (end >= buf.byteLength) flags |= FILE_FLAG_END;
        this.sendOpalFrame(
          TYPE_FILE_TRANSFER_CHUNK,
          0,
          encodeFileTransferChunk(transferId, flags, {
            fileName: file.name,
            totalSize: buf.byteLength,
            data: slice,
          })
        );
        isFirst = false;
        offset = end;
        if (this.ws && this.ws.bufferedAmount > 2 * 1024 * 1024) {
          await new Promise((r) => setTimeout(r, 30));
        }
      }
    }
  }

  handleRemoteClipboardText(text) {
    if (document.hasFocus() && navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        () => this.showToast(t('cast.clipboardSyncedToast')),
        () => this.showToast(t('cast.clipboardReceivedToast'), t('cast.clipboardCopyAction'), () => this.copyText(text, t('cast.clipboardCopiedToast')))
      );
    } else {
      this.showToast(t('cast.clipboardReceivedToast'), t('cast.clipboardCopyAction'), () => this.copyText(text, t('cast.clipboardCopiedToast')));
    }
  }

  handleRemoteClipboardImage(pngData) {
    const writeImg = () => {
      if (typeof ClipboardItem === 'undefined' || !navigator.clipboard?.write) {
        this.showToast(t('cast.clipboardImgUnsupportedToast'));
        return;
      }
      const blob = new Blob([pngData], { type: 'image/png' });
      navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]).then(
        () => this.showToast(t('cast.clipboardImgSyncedToast')),
        () => this.showToast(t('cast.clipboardImgReceivedToast'), t('cast.clipboardImgCopyAction'), () => writeImg())
      );
    };
    if (document.hasFocus() && typeof ClipboardItem !== 'undefined' && navigator.clipboard?.write) {
      writeImg();
    } else {
      this.showToast(t('cast.clipboardImgReceivedToast'), t('cast.clipboardImgCopyAction'), () => writeImg());
    }
  }

  renderAppList() {
    this.standalone.renderInCastAppList();
  }

  openStandaloneAppWindow(app) {
    this.standalone.requestLaunchStandaloneApp(app, this.standalone.currentCastTarget());
    $('appsPopover').hidden = true;
  }

  enterStageView(peerLabel, showApprovalWait) {
    const isStandalone = this.sessionRole === SESSION_ROLE_STANDALONE_APP;
    $('lobbyView').hidden = true;
    $('stageView').hidden = false;
    $('stagePeerName').textContent = isStandalone && this.standaloneAppLabel ? this.standaloneAppLabel : peerLabel;
    $('approvalOverlay').hidden = !showApprovalWait;
    $('tbHomeBtn').hidden = isStandalone;
    if ($('tbBlackoutBtn')) $('tbBlackoutBtn').hidden = isStandalone;
    $('tbAppsBtn').hidden = isStandalone;
    $('tbAudioBtn').hidden = false;
    if ($('tbDisconnectBtn')) {
      $('tbDisconnectBtn').textContent = isStandalone && this.desktop.enabled ? t('stage.closeWindowBtn') : t('stage.disconnectBtn');
      $('tbDisconnectBtn').title = isStandalone && this.desktop.enabled ? t('stage.closeWindowTitle') : t('stage.disconnectTitle');
    }
    this.standalone.syncPinButtonUi();
    $('liveFpsSelect').value = $('castFpsSelect').value;
    $('liveBitrateSelect').value = $('castBitrateSelect').value;
    this.syncBitrateInputsUi('live');
    $('connectCastBtn').disabled = false;
    $('connectCastBtn').textContent = this.desktop.enabled ? t('cast.connectDesktopBtn') : t('cast.connectWebBtn');
  }

  disconnectCast(reasonMessage = '') {
    if (this.ws && this.ws.readyState === WebSocket.OPEN && this.authenticated) {
      try {
        this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_DISCONNECT));
      } catch {
        // Ignore send race
      }
    }
    this.mic.stop();
    this.micEnabled = false;
    this.syncMicUi();
    this.camera.stop();
    this.cameraEnabled = false;
    this.syncCameraUi();
    this.blackoutActive = false;
    this.remoteFullscreenTitle = '';
    this.injectStatus = 0;
    this.inputBlockedWarned = false;
    this.autoAdaptiveBitrateKbps = 0;
    this.autoHealthyStreak = 0;
    if (this.recorder?.isRecording) {
      this.recorder.stop();
    }
    this.fileBrowserClient?.reset(t('cast.errDisconnected'));
    this.syncBlackoutUi();
    this.syncRemoteFullscreenUi();
    this.teardownCastSocket();
    this.companion?.renderCompanionHub();
    this.input.setEnabled(false);
    this.video.reset();
    this.audio.reset();
    $('stageView').hidden = true;
    $('lobbyView').hidden = false;
    $('approvalOverlay').hidden = true;
    $('qualityPopover').hidden = true;
    $('appsPopover').hidden = true;
    $('connectCastBtn').disabled = false;
    $('connectCastBtn').textContent = this.desktop.enabled ? t('cast.connectDesktopBtn') : t('cast.connectWebBtn');
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
    }
    if (reasonMessage) {
      this.setCastError(reasonMessage);
    }
  }

  teardownCastSocket() {
    clearInterval(this.pingTimer);
    this.pingTimer = null;
    clearTimeout(this.toolbarFadeTimer);
    clearTimeout(this.revealHoverTimer);
    this.revealHoverTimer = null;
    clearTimeout(this.resizeDebounceTimer);
    this.pingSentMap.clear();
    this.connected = false;
    this.authenticated = false;
    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onerror = null;
      this.ws.onclose = null;
      try { this.ws.close(1000, 'bye'); } catch {}
      this.ws = null;
    }
  }

  startHeartbeat() {
    clearInterval(this.pingTimer);
    this.pingTimer = setInterval(() => {
      if (!this.authenticated) return;
      const id = this.pingIdSeq++;
      this.pingSentMap.set(id, performance.now());
      if (this.pingSentMap.size > 16) {
        const firstKey = this.pingSentMap.keys().next().value;
        this.pingSentMap.delete(firstKey);
      }
      this.sendOpalFrame(TYPE_PING, 0, encodePingPong(id));
      this.evaluateAutoAdaptiveBitrate();
    }, 3000);
  }

  evaluateAutoAdaptiveBitrate() {
    if (!this.authenticated || this.bitrateMode !== 'auto') return;
    const targetFps = Number($('liveFpsSelect')?.value) || Number($('castFpsSelect')?.value) || 60;
    const step = computeAdaptiveAutoBitrateStep({
      currentKbps: this.autoAdaptiveBitrateKbps,
      targetFps,
      actualFps: this.remoteFps,
      rttMs: this.lastRttMs,
      isWired: this.transportMode === 'usb',
      width: this.remoteWidth,
      height: this.remoteHeight,
      preferHevc: this.remoteCodecId === VIDEO_CODEC_HEVC,
      healthyStreak: this.autoHealthyStreak,
    });
    this.autoHealthyStreak = step.healthyStreak;
    if (this.autoAdaptiveBitrateKbps <= 0) {
      this.autoAdaptiveBitrateKbps = step.nextKbps;
    }
    if (step.changed) {
      this.autoAdaptiveBitrateKbps = step.nextKbps;
      this.applyLiveResizeConfig({ bitrateOverrideKbps: step.nextKbps, silent: true });
      this.updateTelemetry();
    }
  }

  updateTelemetry() {
    const parts = [`${this.remoteWidth}×${this.remoteHeight}`];
    if (this.remoteFps > 0) parts.push(`${this.remoteFps}fps`);
    if (this.lastRttMs > 0) parts.push(`${this.lastRttMs}ms`);
    parts.push(this.remoteCodecId === VIDEO_CODEC_HEVC ? 'HEVC' : 'H.264');
    if (this.bitrateMode === 'auto') {
      parts.push(
        this.autoAdaptiveBitrateKbps > 0
          ? t('stage.telemetryAutoBitrate', { mbps: Math.round(this.autoAdaptiveBitrateKbps / 1000) })
          : t('stage.telemetryAutoBitrateShort')
      );
    }
    if (this.injectStatus === 2) parts.push('UHID');
    else if (this.injectStatus === -1) parts.push(t('stage.telemetryTouchLimited'));
    $('stageTelemetry').textContent = parts.join(' · ');
  }

  toggleBlackout() {
    if (!this.authenticated || this.sessionRole === SESSION_ROLE_STANDALONE_APP) return;
    this.blackoutActive = !this.blackoutActive;
    this.syncBlackoutUi();
    this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_TOGGLE_BLACKOUT, this.blackoutActive ? 1 : 0));
    this.showToast(this.blackoutActive ? t('cast.blackoutOnToast') : t('cast.blackoutOffToast'));
  }

  syncBlackoutUi() {
    const btn = $('tbBlackoutBtn');
    if (!btn) return;
    btn.classList.toggle('active', Boolean(this.blackoutActive));
    btn.textContent = this.blackoutActive ? t('stage.blackoutOnBtn') : t('stage.blackoutBtn');
  }

  async toggleRemoteMic() {
    if (!this.authenticated) {
      this.showToast(t('cast.micNeedConnectToast'));
      return;
    }
    if (this.micEnabled) {
      this.disableRemoteMic(true);
      return;
    }
    const ok = await this.mic.start();
    if (!ok) return;
    this.micEnabled = true;
    this.syncMicUi();
    this.sendOpalFrame(TYPE_MIC_CONFIG, 0, encodeMicConfig({
      enabled: true,
      codec: 'pcm16',
      sampleRate: DEFAULT_MIC_SAMPLE_RATE,
      channels: DEFAULT_MIC_CHANNELS,
    }));
  }

  disableRemoteMic(notifyServer = true) {
    this.mic.stop();
    this.micEnabled = false;
    this.syncMicUi();
    if (notifyServer && this.authenticated) {
      this.sendOpalFrame(TYPE_MIC_CONFIG, 0, encodeMicConfig({
        enabled: false,
        codec: 'pcm16',
        sampleRate: DEFAULT_MIC_SAMPLE_RATE,
        channels: DEFAULT_MIC_CHANNELS,
      }));
    }
  }

  syncMicUi() {
    const btn = $('tbMicBtn');
    if (!btn) return;
    const on = Boolean(this.micEnabled);
    btn.classList.toggle('active-emerald', on);
    btn.classList.remove('active');
    const tip = on ? t('stage.micOnTitle') : t('stage.micTitle');
    btn.title = tip;
    btn.setAttribute('aria-label', on ? t('stage.micOffAria') : t('stage.micAria'));
  }

  async toggleRemoteCamera() {
    if (!this.authenticated) {
      this.showToast(t('cast.camNeedConnectToast'));
      return;
    }
    if (this.cameraEnabled) {
      this.disableRemoteCamera(true);
      return;
    }
    const ok = await this.camera.start();
    if (!ok) return;
    this.cameraEnabled = true;
    this.syncCameraUi();
    this.sendOpalFrame(TYPE_CAMERA_CONFIG, 0, encodeCameraConfig({
      enabled: true,
      codec: 'mjpeg',
      width: DEFAULT_CAMERA_WIDTH,
      height: DEFAULT_CAMERA_HEIGHT,
      fps: DEFAULT_CAMERA_FPS,
      facing: 'front',
    }));
  }

  disableRemoteCamera(notifyServer = true) {
    this.camera.stop();
    this.cameraEnabled = false;
    this.syncCameraUi();
    if (notifyServer && this.authenticated) {
      this.sendOpalFrame(TYPE_CAMERA_CONFIG, 0, encodeCameraConfig({
        enabled: false,
        codec: 'mjpeg',
        width: DEFAULT_CAMERA_WIDTH,
        height: DEFAULT_CAMERA_HEIGHT,
        fps: DEFAULT_CAMERA_FPS,
        facing: 'front',
      }));
    }
  }

  syncCameraUi() {
    const btn = $('tbCameraBtn');
    if (!btn) return;
    const on = Boolean(this.cameraEnabled);
    btn.classList.toggle('active-emerald', on);
    btn.classList.remove('active');
    const tip = on ? t('stage.camOnTitle') : t('stage.camTitle');
    btn.title = tip;
    btn.setAttribute('aria-label', on ? t('stage.camOffAria') : t('stage.camAria'));
  }

  syncKeyboardUi(open) {
    const btn = $('tbKeyboardBtn');
    if (!btn) return;
    const on = Boolean(open);
    btn.classList.toggle('active', on);
    const tip = on ? t('stage.keyboardHideTitle') : t('stage.keyboardTitle');
    btn.title = tip;
    btn.setAttribute('aria-label', on ? t('stage.keyboardHideAria') : t('stage.keyboardAria'));
  }

  syncAudioUi(st) {
    const btn = $('tbAudioBtn');
    if (!btn) return;
    const onIcon = btn.querySelector('.tb-icon-sound-on');
    const offIcon = btn.querySelector('.tb-icon-sound-off');
    const lbl = btn.querySelector('.tb-inline-label');
    if (st.needsGesture) {
      if (onIcon) onIcon.hidden = false;
      if (offIcon) offIcon.hidden = true;
      if (lbl) {
        lbl.textContent = t('stage.audioUnlockLabel');
        lbl.hidden = false;
      }
      btn.classList.add('active');
      btn.title = t('stage.audioUnlockTitle');
      btn.setAttribute('aria-label', t('stage.audioUnlockLabel'));
      this.showToast(t('cast.audioGestureToast'), t('cast.audioUnlockAction'), () => this.audio.resumeFromGesture());
    } else if (st.muted) {
      if (onIcon) onIcon.hidden = true;
      if (offIcon) offIcon.hidden = false;
      if (lbl) lbl.hidden = true;
      btn.classList.remove('active');
      btn.title = t('stage.audioMutedTitle');
      btn.setAttribute('aria-label', t('stage.audioRestoreAria'));
    } else {
      if (onIcon) onIcon.hidden = false;
      if (offIcon) offIcon.hidden = true;
      if (lbl) lbl.hidden = true;
      btn.classList.remove('active');
      btn.title = t('stage.audioTitle');
      btn.setAttribute('aria-label', t('stage.audioAria'));
    }
  }

  syncFullscreenUi() {
    const btn = $('tbFullscreenBtn');
    if (!btn) return;
    const isFs = Boolean(document.fullscreenElement);
    const enterIcon = btn.querySelector('.tb-icon-fs-enter');
    const exitIcon = btn.querySelector('.tb-icon-fs-exit');
    if (enterIcon) enterIcon.hidden = isFs;
    if (exitIcon) exitIcon.hidden = !isFs;
    btn.classList.toggle('active', isFs);
    btn.title = isFs ? t('stage.fsExitTitle') : t('stage.fsEnterTitle');
    btn.setAttribute('aria-label', isFs ? t('stage.fsExitTitle') : t('stage.fsEnterTitle'));
  }

  syncRemoteFullscreenUi() {
    const btn = $('tbExitAppFsBtn');
    if (!btn) return;
    const active = Boolean(this.remoteFullscreenTitle) && this.sessionRole !== SESSION_ROLE_STANDALONE_APP;
    btn.hidden = !active;
    if (active) {
      btn.title = t('stage.exitAppFsTitleWithApp', { title: this.remoteFullscreenTitle });
    }
  }

  bumpToolbarVisibility() {
    const tb = $('stageToolbar');
    tb.classList.remove('dimmed');
    clearTimeout(this.toolbarFadeTimer);
    this.toolbarFadeTimer = setTimeout(() => {
      if ($('qualityPopover').hidden && $('appsPopover').hidden && !tb.matches(':hover')) {
        tb.classList.add('dimmed');
      }
    }, 2600);
  }

  toggleFullscreen() {
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
    } else {
      $('stageView').requestFullscreen().catch(() => {});
    }
  }

  // ==================== LAN ACTIVATION ====================

  async startActivationConnection() {
    this.setActError('');
    $('actTrustBox').hidden = true;

    const host = $('actHostInput').value.trim();
    const port = Number($('actPortInput').value) || 19879;
    const code = $('actCodeInput').value.trim();

    if (!host) {
      this.setActError(t('activate.errMissingHost'));
      return;
    }
    if (!/^\d{6}$/.test(code)) {
      this.setActError(t('activate.errInvalidCode'));
      return;
    }

    $('connectActBtn').disabled = true;
    $('connectActBtn').textContent = t('activate.probingBtn');
    if (this.desktop.enabled) {
      try {
        const tunnel = await this.ensureDesktopTlsTunnel(host, port, '/activate');
        $('connectActBtn').disabled = false;
        $('connectActBtn').textContent = t('activate.connectBtn');
        if (!tunnel) return;
        $('actLogOutput').textContent = '';
        this.activation.connect(host, port, code, tunnel.localWsUrl);
      } catch (err) {
        $('connectActBtn').disabled = false;
        $('connectActBtn').textContent = t('activate.connectBtn');
        this.setActError(t('activate.errConnectFailed', { host, port, error: err?.message || err }));
      }
      return;
    }
    const probe = await probeHttpsHealth(host, port);
    $('connectActBtn').disabled = false;
    $('connectActBtn').textContent = t('activate.connectBtn');

    if (!probe.ok) {
      if (probe.needsTrust) {
        $('actTrustBox').hidden = false;
      } else {
        this.setActError(t('activate.errConnectFailed', { host, port, error: probe.error }));
      }
      return;
    }

    $('actLogOutput').textContent = '';
    this.activation.connect(host, port, code);
  }

  renderActivationState(st) {
    $('actDeviceTitle').textContent = `${st.deviceName || 'Android'} (${st.lanIp || ''})`;
    const pPort = st.discovery?.pairingPort || 0;
    const cPort = st.discovery?.connectPort || 0;
    $('actDiscoverySummary').textContent = t('activate.discoverySummary', {
      pairPort: pPort || t('activate.waitingPairPopup'),
      connPort: cPort || t('activate.notFound'),
    });

    if (pPort > 0 && !$('adbPairPortInput').matches(':focus')) {
      $('adbPairPortInput').value = String(pPort);
    }
    if (cPort > 0 && !$('adbConnPortInput').matches(':focus')) {
      $('adbConnPortInput').value = String(cPort);
    }

    const badge = $('actDaemonBadge');
    badge.textContent = st.daemonConnected ? t('activate.daemonActive') : t('activate.daemonInactive');
    badge.className = `status-pill ${st.daemonConnected ? 'ok' : 'warn'}`;
    $('actShizukuBtn').hidden = !st.shizukuGranted;
    this.setActBusy(Boolean(st.busy));

    const ip = st.lanIp || $('actHostInput').value.trim();
    const fallback = cPort > 0
      ? `adb connect ${ip}:${cPort} && ${st.pcAdbCmd || ''}`
      : (st.pcAdbCmd || '');
    $('actFallbackCmd').textContent = fallback;
  }

  setActBusy(busy) {
    $('actPairStartBtn').disabled = busy;
    $('actDirectConnBtn').disabled = busy;
    $('actShizukuBtn').disabled = busy;
  }

  appendActLog(line) {
    if (!line) return;
    const el = $('actLogOutput');
    const ts = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    el.textContent += `[${ts}] ${line}\n`;
    el.scrollTop = el.scrollHeight;
  }

  // ==================== HELPERS ====================

  renderRecentDevices() {
    if (this.desktop.enabled) {
      const list = this.desktop.trustedDevices;
      const wrap = $('recentDevicesWrap');
      const container = $('recentDevicesList');
      if (!list.length) {
        wrap.hidden = true;
        return;
      }
      wrap.hidden = false;
      container.innerHTML = '';
      for (const dev of list) {
        const group = document.createElement('div');
        group.className = 'recent-chip-group';

        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'recent-chip';
        const shortFp = formatShortFingerprint(dev.certFingerprint);
        btn.title = shortFp ? t('cast.recentFpTitle', { fp: shortFp }) : '';
        btn.textContent = `${dev.displayName || dev.host} (${dev.host}:${dev.port})`;
        btn.addEventListener('click', () => {
          this.selectTransportMode('wifi');
          $('castHostInput').value = dev.host;
          $('castPortInput').value = String(dev.port || 19878);
          this.startCastConnection();
        });

        const appBtn = document.createElement('button');
        appBtn.type = 'button';
        appBtn.className = 'recent-revoke-btn';
        appBtn.title = t('cast.recentStandaloneTitle');
        appBtn.textContent = t('cast.recentStandaloneBtn');
        appBtn.addEventListener('click', () => {
          this.standalone.openLobbyAppPicker({
            transport: 'wifi',
            host: dev.host,
            port: dev.port || 19878,
            deviceKey: `${dev.host}:${dev.port || 19878}`,
            deviceName: dev.displayName || dev.host,
            certFingerprint: dev.certFingerprint || '',
          });
        });

        const revokeBtn = document.createElement('button');
        revokeBtn.type = 'button';
        revokeBtn.className = 'recent-revoke-btn';
        revokeBtn.title = t('cast.recentRevokeTitle');
        revokeBtn.textContent = '×';
        revokeBtn.addEventListener('click', async () => {
          await this.desktop.revokeTrustedDevice(dev.id);
          this.renderRecentDevices();
          this.showToast(t('cast.recentRemovedToast', { name: dev.displayName || dev.host }));
        });

        group.append(btn, appBtn, revokeBtn);
        container.appendChild(group);
      }
      return;
    }
    const list = loadSavedDevices();
    const wrap = $('recentDevicesWrap');
    const container = $('recentDevicesList');
    if (!list.length) {
      wrap.hidden = true;
      return;
    }
    wrap.hidden = false;
    container.innerHTML = '';
    for (const dev of list) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'recent-chip';
      btn.textContent = `${dev.deviceName || dev.host} (${dev.host}:${dev.port})`;
      btn.addEventListener('click', () => {
        this.selectTransportMode('wifi');
        $('castHostInput').value = dev.host;
        $('castPortInput').value = String(dev.port || 19878);
        this.startCastConnection();
      });
      container.appendChild(btn);
    }
  }

  buildClientContextSummary() {
    const parts = [
      `Mode: ${this.desktop.enabled ? 'Tauri Desktop' : 'Web Browser'}`,
      `Transport: ${this.transportMode}`,
      `Role: ${this.sessionRole}`,
      `Connected: ${this.authenticated ? 'yes' : 'no'}`,
      `ActiveStandaloneWindows: ${this.standalone?.getActiveCount() || 0}/3`,
    ];
    if (this.activeHost) {
      parts.push(`Peer: ${this.activeHost}:${this.activePort}`);
    }
    return parts.join(' | ');
  }

  setCastError(msg) {
    const box = $('castErrorBox');
    box.hidden = !msg;
    box.textContent = msg || '';
    if (msg) this.diagnostics?.log('W', 'Cast', msg);
  }

  setActError(msg) {
    const box = $('actErrorBox');
    box.hidden = !msg;
    box.textContent = msg || '';
  }

  showBrowserWarn(msg) {
    const el = $('browserWarnBanner');
    el.hidden = false;
    el.textContent = msg;
  }

  showToast(text, actionLabelOrOpts = '', actionFn = null) {
    let actionLabel = '';
    let resolvedFn = actionFn;
    if (actionLabelOrOpts && typeof actionLabelOrOpts === 'object') {
      actionLabel = String(actionLabelOrOpts.actionText || actionLabelOrOpts.actionLabel || '');
      resolvedFn = typeof actionLabelOrOpts.actionFn === 'function' ? actionLabelOrOpts.actionFn : null;
    } else {
      actionLabel = String(actionLabelOrOpts || '');
    }
    $('toastText').textContent = text;
    const btn = $('toastActionBtn');
    this.toastActionFn = resolvedFn;
    if (actionLabel && resolvedFn) {
      btn.textContent = actionLabel;
      btn.hidden = false;
    } else {
      btn.hidden = true;
    }
    $('toastIsland').hidden = false;
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => {
      $('toastIsland').hidden = true;
    }, actionLabel ? 6500 : 3200);
  }

  copyText(text, successMsg) {
    if (!text) return;
    navigator.clipboard?.writeText(text).then(
      () => this.showToast(successMsg),
      () => this.showToast(t('cast.clipboardWriteRestricted'))
    );
  }
}

window.addEventListener('DOMContentLoaded', () => {
  new OpalCastWebApp();
});
