/**
 * OpalDesk Web Cast Receiver & LAN Activation Entry Orchestrator.
 * Wires WebCodecs video/audio, input/IME, companion hub, standalone windows,
 * transport/discovery, downlink frame router, stage toolbar UI, and LAN activation.
 */

import { buildTrustPageUrl } from './activation_client.js';
import { OpalAudioPlayer } from './audio_player.js';
import { DEFAULT_CAMERA_FPS, DEFAULT_CAMERA_HEIGHT, DEFAULT_CAMERA_WIDTH, OpalCameraCapturer } from './camera_capturer.js';
import { OpalCompanionController } from './companion_controller.js';
import { OpalDesktopBridge } from './desktop_bridge.js';
import { OpalDiagnosticsController } from './diagnostics_controller.js';
import { OpalFileBrowserClient } from './file_browser_client.js';
import { OpalFileBrowserController } from './file_browser_controller.js';
import { OpalInputController } from './input_mapper.js';
import { OpalScreenRecorder } from './media_capture.js';
import { DEFAULT_MIC_SAMPLE_RATE, OpalMicCapturer } from './mic_capturer.js';
import { OpalStandaloneController } from './standalone_controller.js';
import { getProfileBadgeText } from './standalone_pip_window.js';
import { OpalVideoDecoder, probeVideoCodecs } from './video_decoder.js';
import {
  encodeFrame,
  encodeImageClipboardEvent,
  encodeKeyEvent,
  encodeMouseEvent,
  encodeResizeConfig,
  encodeTextEvent,
  encodeTouchEvent,
  FLAG_CLIPBOARD_IMAGE_PNG,
  resolveAutoCodecPreference,
  SESSION_ROLE_DESKTOP,
  SESSION_ROLE_STANDALONE_APP,
  TEXT_MODE_COMMIT_PASTE,
  TYPE_CAMERA_FRAME,
  TYPE_INPUT_KEY,
  TYPE_INPUT_MOUSE,
  TYPE_INPUT_TEXT,
  TYPE_INPUT_TOUCH,
  TYPE_MIC_FRAME,
  TYPE_REQUEST_IDR,
  TYPE_RESIZE_CONFIG,
} from './protocol.js';
import {
  applyDocumentI18n,
  getLanguagePref,
  onLocaleChange,
  setLanguagePref,
  STORAGE_LANGUAGE_PREF,
  t,
} from './i18n.js';
import {
  getOrCreateClientId,
  isSafariBrowser,
  loadCustomBitrateMbps,
  loadLocalCustomAdbPath,
  loadSavedDevices,
  loadUsbTetherBetaPref,
  resolveMergedCastPrefs,
  resolveSelectedBitrateConfig,
  restoreSavedCastPrefsUi,
  saveCodecPref,
  saveLocalCastPrefs,
  saveUsbTetherBetaPref,
  STORAGE_DEVICES,
  STORAGE_THEME,
  syncBitrateInputsUi,
} from './cast_storage.js';
import {
  applyLiveResizeConfig,
  disconnectCast,
  ensureDesktopTlsTunnel,
  refreshDesktopUsbPanel,
  refreshDesktopUsbTetherPanel,
  renderDesktopMdnsDevices,
  renderDesktopUsbTetherDevices,
  resolveTofuModal,
  restoreRememberedAdbPath,
  saveCustomAdbPath,
  sendDroppedFiles,
  startCastConnection,
  startHeartbeat,
  syncCustomAdbPathUi,
  syncDesktopUsbTetherUi,
} from './cast_session_transport.js';
import { handleDownlinkFrame } from './cast_frame_router.js';
import {
  bindStageAndGlobalEvents,
  bumpToolbarVisibility,
  disableRemoteCamera,
  disableRemoteMic,
  enterStageView,
  renderRecentDevices,
  syncAudioUi,
  syncBlackoutUi,
  syncCameraUi,
  syncFullscreenUi,
  syncKeyboardUi,
  syncMicUi,
  syncRemoteFullscreenUi,
  updateTelemetry,
} from './cast_toolbar_ui.js';
import {
  bindActivationEvents,
  createActivationController,
  startActivationConnection,
} from './lan_activation_controller.js';

const $ = (id) => document.getElementById(id);

class OpalCastWebApp {
  constructor() {
    this.desktop = new OpalDesktopBridge();
    this.clientId = getOrCreateClientId();
    this.transportMode = 'wifi';
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

    this.audio = new OpalAudioPlayer({ onStateChange: (st) => syncAudioUi(this, st) });

    this.micEnabled = false;
    this.mic = new OpalMicCapturer({
      sampleRate: DEFAULT_MIC_SAMPLE_RATE,
      onPcmFrame: (pcmBytes) => {
        if (this.authenticated && this.micEnabled) this.sendOpalFrame(TYPE_MIC_FRAME, 0, pcmBytes);
      },
      onError: (msg) => {
        disableRemoteMic(this, false);
        this.showToast(msg);
      },
    });

    this.cameraEnabled = false;
    this.camera = new OpalCameraCapturer({
      width: DEFAULT_CAMERA_WIDTH,
      height: DEFAULT_CAMERA_HEIGHT,
      fps: DEFAULT_CAMERA_FPS,
      onJpegFrame: (jpegBytes) => {
        if (this.authenticated && this.cameraEnabled) this.sendOpalFrame(TYPE_CAMERA_FRAME, 0, jpegBytes);
      },
      onError: (msg) => {
        disableRemoteCamera(this, false);
        this.showToast(msg);
      },
    });

    this.input = new OpalInputController($('castCanvas'), $('imeBridge'), {
      getRemoteSize: () => ({ width: this.remoteWidth, height: this.remoteHeight }),
      onMouse: (action, x, y, buttons, actBtn, hScroll, vScroll) => {
        this.sendOpalFrame(TYPE_INPUT_MOUSE, 0, encodeMouseEvent(action, x, y, buttons, actBtn, hScroll, vScroll));
      },
      onTouch: (action, idx, pointers) => this.sendOpalFrame(TYPE_INPUT_TOUCH, 0, encodeTouchEvent(action, idx, pointers)),
      onKey: (action, code, meta, repeat) => this.sendOpalFrame(TYPE_INPUT_KEY, 0, encodeKeyEvent(action, code, meta, repeat)),
      onText: (text, commit) => this.sendOpalFrame(TYPE_INPUT_TEXT, 0, encodeTextEvent(commit ? TEXT_MODE_COMMIT_PASTE : 1, this.textSeq++, text)),
      onImageClipboard: (pngBytes) => {
        this.sendOpalFrame(TYPE_INPUT_TEXT, FLAG_CLIPBOARD_IMAGE_PNG, encodeImageClipboardEvent(TEXT_MODE_COMMIT_PASTE, this.textSeq++, pngBytes));
        this.showToast(t('cast.clipboardImgSyncedToast'));
      },
      onFileDrop: (files) => sendDroppedFiles(this, files),
      onUserGesture: () => this.audio.resumeFromGesture(),
      onSoftKeyboardStateChange: (open) => syncKeyboardUi(open),
    });

    this.activation = createActivationController(this);
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
    this.fileBrowser = new OpalFileBrowserController({ app: this, client: this.fileBrowserClient });
    this.recorder = new OpalScreenRecorder({
      getCanvas: () => $('castCanvas'),
      desktop: this.desktop,
      getLabel: () => (this.sessionRole === SESSION_ROLE_STANDALONE_APP && this.standaloneAppLabel ? this.standaloneAppLabel : 'OpalDesk'),
      onStateChange: ({ recording, elapsedLabel }) => {
        if ($('tbRecordBtn')) $('tbRecordBtn').classList.toggle('recording', recording);
        if ($('tbRecordLabel')) $('tbRecordLabel').textContent = recording ? t('cast.recordStopLabel', { elapsed: elapsedLabel }) : t('cast.recordLabel');
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
      if (ev.key === STORAGE_LANGUAGE_PREF) setLanguagePref(ev.newValue || 'system', { persist: false });
    });
    this.bindUiEvents();
    this.input.attach();
    if (this.desktop.enabled) await this.initDesktopMode();
    restoreSavedCastPrefsUi(this);
    await Promise.all([this.diagnostics.initialize(), this.standalone.initialize(), this.companion.initialize()]);
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
        const merged = resolveMergedCastPrefs(this.desktop);
        codecSelect.value = resolveAutoCodecPreference({ hevcSupported: caps.hevc, savedCodec: merged.preferredCodec });
        if (!caps.hevc) {
          const hevcOpt = codecSelect.querySelector('option[value="hevc"]');
          if (hevcOpt) hevcOpt.textContent = t('cast.codecHevcNoHw');
        }
      }
    }
    if (isSafariBrowser()) this.showBrowserWarn(t('cast.safariWarn'));
    this.refreshLocalizedUi();
    this.applyUrlParams();
  }

  refreshLocalizedUi() {
    applyDocumentI18n();
    if ($('langSelect')) $('langSelect').value = getLanguagePref();
    if (this.desktop.enabled) {
      if ($('heroTitle')) $('heroTitle').textContent = t('hero.desktopTitle');
      if ($('heroSubtitle')) $('heroSubtitle').textContent = t('hero.desktopSubtitle');
      if ($('connectCastBtn') && !$('connectCastBtn').disabled) $('connectCastBtn').textContent = t('cast.connectDesktopBtn');
    } else if ($('connectCastBtn') && !$('connectCastBtn').disabled) {
      $('connectCastBtn').textContent = t('cast.connectWebBtn');
    }
    if (this.codecCaps && $('codecBadge')) {
      $('codecBadge').textContent = !this.codecCaps.supported
        ? t('nav.codecUnsupported')
        : this.codecCaps.hevc ? t('nav.codecHevcReady') : t('nav.codecH264Ready');
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
      syncCustomAdbPathUi(this, this.desktop.adbStatus);
      renderDesktopMdnsDevices(this, this.desktop.mdnsDevices);
      if (this.usbTetherBetaEnabled) renderDesktopUsbTetherDevices(this, this.desktop.usbTetherDevices);
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
    if ($('fmViewToggleBtn')) $('fmViewToggleBtn').textContent = this.fileBrowser?.viewMode === 'grid' ? t('fm.viewList') : t('fm.viewGrid');
    if ($('tbInputModeBtn')) $('tbInputModeBtn').textContent = this.input.primaryLeftAsTouch ? t('stage.touchBtn') : t('stage.mouseBtn');
    if ($('tbDisconnectBtn')) {
      const isStandaloneDesktop = this.sessionRole === SESSION_ROLE_STANDALONE_APP && this.desktop.enabled;
      $('tbDisconnectBtn').textContent = isStandaloneDesktop ? t('stage.closeWindowBtn') : t('stage.disconnectBtn');
      $('tbDisconnectBtn').title = isStandaloneDesktop ? t('stage.closeWindowTitle') : t('stage.disconnectTitle');
    }
    this.syncBlackoutUi();
    this.syncMicUi();
    this.syncCameraUi();
    syncFullscreenUi();
    this.syncRemoteFullscreenUi();
    if (this.authenticated) this.updateTelemetry();
  }

  async initDesktopMode() {
    document.body.classList.add('desktop-app');
    if ($('brandModeBadge')) $('brandModeBadge').textContent = (navigator.userAgent || '').includes('Windows') ? 'Windows Client' : 'Desktop';
    if ($('heroTitle')) $('heroTitle').textContent = t('hero.desktopTitle');
    if ($('heroSubtitle')) $('heroSubtitle').textContent = t('hero.desktopSubtitle');
    $('connectCastBtn').textContent = t('cast.connectDesktopBtn');

    try {
      const info = await this.desktop.initialize();
      if (info?.clientId) this.clientId = info.clientId;
      if (info?.prefs?.language) setLanguagePref(info.prefs.language, { persist: true, notify: false });
      if (info?.micaApplied) document.body.classList.add('desktop-mica-ready');
      if ($('hideToTrayRow')) $('hideToTrayRow').hidden = false;
      if ($('hideToTrayCheck')) $('hideToTrayCheck').checked = info?.prefs?.hideToTrayOnClose !== false;
      if (info?.initialDeepLink) setTimeout(() => this.companion.handleDeepLinkUrl(info.initialDeepLink), 450);
      await restoreRememberedAdbPath(this, info);
    } catch {
      const localAdbPath = loadLocalCustomAdbPath();
      if (localAdbPath) syncCustomAdbPathUi(this, { customAdbPath: localAdbPath }, { forceInput: true });
    }

    $('desktopMdnsSection').hidden = this.transportMode === 'usb';
    this.usbTetherBetaEnabled = loadUsbTetherBetaPref();
    if ($('usbTetherBetaCheck')) $('usbTetherBetaCheck').checked = this.usbTetherBetaEnabled;
    syncDesktopUsbTetherUi(this);
    renderDesktopMdnsDevices(this, this.desktop.mdnsDevices);
    this.desktop.startDiscoveryPolling((devices) => renderDesktopMdnsDevices(this, devices));
  }

  syncResizePresetUi() {
    const pop = $('qualityPopover');
    if (!pop) return;
    pop.querySelectorAll('[data-preset]').forEach((btn) => btn.classList.toggle('active', btn.dataset.preset === this.resizeMode));
  }

  syncBitrateInputsUi(scope = 'both') { syncBitrateInputsUi(this, scope); }
  resolveSelectedBitrateConfig(selId, inpId, opts) { return resolveSelectedBitrateConfig(this, selId, inpId, opts); }

  persistCastPrefs(patch) {
    saveLocalCastPrefs(patch);
    if (this.desktop.enabled) this.desktop.updateClientPrefs(patch);
  }

  syncLocalCursorUi() {
    const hide = Boolean(this.hideLocalCursor);
    if ($('canvasWrap')) $('canvasWrap').classList.toggle('hide-local-cursor', hide);
    if ($('hideLocalCursorCheck')) $('hideLocalCursorCheck').checked = hide;
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
    if (fp) this.explicitCertPin = fp;
    if (sessionCode) {
      try { url.hash = ''; window.history.replaceState(null, '', url.toString()); } catch {}
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
      const profileBadge = getProfileBadgeText(userId, profileType);
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
      if (host) { setTimeout(() => this.startCastConnection(), 200); return; }
    }
    if (mode === 'activate') {
      this.selectLobbyTab('activate');
      if (host && sessionCode) setTimeout(() => startActivationConnection(this), 250);
    }
  }

  bindUiEvents() {
    const langSel = $('langSelect');
    if (langSel) {
      langSel.value = getLanguagePref();
      langSel.addEventListener('change', (e) => {
        setLanguagePref(e.target.value, { persist: true });
        if (this.desktop.enabled) void this.desktop.updateClientPrefs({ language: getLanguagePref() });
      });
    }
    $('themeToggleBtn').addEventListener('click', () => {
      const next = document.documentElement.getAttribute('data-theme') === 'prism-pulse' ? 'pure-frost' : 'prism-pulse';
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
    $('hideToTrayCheck')?.addEventListener('change', (e) => this.persistCastPrefs({ hideToTrayOnClose: Boolean(e.target.checked) }));
    $('modeWifiBtn').addEventListener('click', () => this.selectTransportMode('wifi'));
    $('modeUsbBtn').addEventListener('click', () => this.selectTransportMode('usb'));
    $('copyAdbForwardBtn').addEventListener('click', () => {
      const port = Number($('castPortInput').value) || 19878;
      this.copyText(`adb forward tcp:${port} localabstract:opaldesk_stream`, t('cast.copyForwardToast'));
    });
    $('castPortInput').addEventListener('input', () => this.updateUsbForwardHint());
    const connectOnEnter = (e) => {
      if (e.key === 'Enter' && !$('connectCastBtn')?.disabled) { e.preventDefault(); this.startCastConnection(); }
    };
    $('castHostInput')?.addEventListener('keydown', connectOnEnter);
    $('castPortInput')?.addEventListener('keydown', connectOnEnter);
    $('castCodecSelect')?.addEventListener('change', (e) => { saveCodecPref(e.target.value); this.persistCastPrefs({ preferredCodec: e.target.value }); });
    $('castFpsSelect')?.addEventListener('change', (e) => this.persistCastPrefs({ targetFps: Number(e.target.value) || 60 }));
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
    $('liveBitrateSelect')?.addEventListener('change', () => this.syncBitrateInputsUi('live'));
    $('liveCustomBitrateInput')?.addEventListener('change', () => {
      this.resolveSelectedBitrateConfig('liveBitrateSelect', 'liveCustomBitrateInput', { notifyOnClamp: true });
      this.syncBitrateInputsUi('both');
    });
    $('castAudioCheck')?.addEventListener('change', (e) => this.persistCastPrefs({ audioEnabled: Boolean(e.target.checked) }));
    $('connectCastBtn').addEventListener('click', () => this.startCastConnection());
    $('openCastTrustBtn').addEventListener('click', () => this.openTrustPopup($('castHostInput').value.trim(), Number($('castPortInput').value) || 19878));
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
      renderDesktopMdnsDevices(this, await this.desktop.pollMdnsDevices(true));
      this.showToast(t('cast.mdnsRefreshedToast'));
    });
    $('scanAdbDevicesBtn')?.addEventListener('click', () => refreshDesktopUsbPanel(this));
    $('usbTetherBetaCheck')?.addEventListener('change', (e) => {
      if (!this.desktop.enabled) return;
      this.usbTetherBetaEnabled = Boolean(e.target.checked);
      saveUsbTetherBetaPref(this.usbTetherBetaEnabled);
      syncDesktopUsbTetherUi(this);
      if (this.usbTetherBetaEnabled) refreshDesktopUsbTetherPanel(this);
      else { this.desktop.usbTetherDevices = []; renderDesktopUsbTetherDevices(this, []); }
    });
    $('scanUsbTetherBtn')?.addEventListener('click', () => refreshDesktopUsbTetherPanel(this, true));
    $('customAdbPathInput')?.addEventListener('input', () => { this.customAdbPathDirty = true; });
    $('customAdbPathInput')?.addEventListener('keydown', async (e) => {
      if (e.key !== 'Enter' || !this.desktop.enabled) return;
      e.preventDefault();
      await saveCustomAdbPath(this, { customPath: $('customAdbPathInput').value });
      await refreshDesktopUsbPanel(this);
    });
    $('saveAdbPathBtn')?.addEventListener('click', async () => {
      if (!this.desktop.enabled) return;
      await saveCustomAdbPath(this, { customPath: $('customAdbPathInput').value });
      await refreshDesktopUsbPanel(this);
    });
    $('clearAdbPathBtn')?.addEventListener('click', async () => {
      if (!this.desktop.enabled) return;
      await saveCustomAdbPath(this, { customPath: '' });
      await refreshDesktopUsbPanel(this);
    });
    $('tofuApproveBtn')?.addEventListener('click', () => resolveTofuModal(this, true));
    $('tofuRejectBtn')?.addEventListener('click', () => resolveTofuModal(this, false));

    bindActivationEvents(this);
    bindStageAndGlobalEvents(this);
  }

  updateUsbForwardHint() {
    const port = Number($('castPortInput')?.value) || 19878;
    if ($('usbForwardCode')) $('usbForwardCode').textContent = `adb forward tcp:${port} localabstract:opaldesk_stream`;
    if ($('usbForwardHint')) $('usbForwardHint').textContent = t('cast.usbForwardHint', { port });
  }

  installTrustPopupListener() {
    window.addEventListener('message', (ev) => {
      const d = ev.data;
      if (!d || d.type !== 'opaldesk-tls-trusted') return;
      if (d.role === 'activate') {
        $('actTrustBox').hidden = true;
        this.showToast(t('cast.trustCertReadyAct'));
        startActivationConnection(this);
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
    if (!window.open(trustUrl, 'opaldesk_tls_trust', 'width=520,height=640')) window.location.href = trustUrl;
  }

  selectLobbyTab(tab) {
    const activeTab = tab === 'activate' ? 'activate' : tab === 'logs' ? 'logs' : 'cast';
    const tabs = [
      { key: 'cast', btn: $('tabCastBtn'), pane: $('paneCast') },
      { key: 'activate', btn: $('tabActivateBtn'), pane: $('paneActivate') },
      { key: 'logs', btn: $('tabLogsBtn'), pane: $('paneLogs') },
    ];
    for (const item of tabs) {
      const sel = item.key === activeTab;
      item.btn?.classList.toggle('active', sel);
      item.btn?.setAttribute('aria-selected', String(sel));
      if (item.pane) item.pane.hidden = !sel;
    }
    if (activeTab === 'logs') this.diagnostics?.onTabActivated();
    else if (activeTab === 'cast') this.standalone?.refreshActiveWindows();
  }

  selectTransportMode(mode) {
    this.transportMode = mode === 'usb' ? 'usb' : 'wifi';
    const isUsb = this.transportMode === 'usb';
    $('modeWifiBtn').classList.toggle('active', !isUsb);
    $('modeUsbBtn').classList.toggle('active', isUsb);
    const hostInput = $('castHostInput');
    const hostLabel = $('castHostLabel');
    if (isUsb && hostInput.value.trim() && hostInput.value.trim() !== '127.0.0.1') this.lastWifiHost = hostInput.value.trim();
    if (this.desktop.enabled) {
      $('usbHelperBox').hidden = true;
      $('desktopUsbSection').hidden = !isUsb;
      $('desktopMdnsSection').hidden = isUsb;
      if (isUsb) refreshDesktopUsbPanel(this);
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
      if (hostInput.value.trim() === '127.0.0.1') hostInput.value = this.lastWifiHost || '';
    }
  }

  // Delegated session, frame routing & toolbar methods
  ensureDesktopTlsTunnel(host, port, wsPath, pin = '') { return ensureDesktopTlsTunnel(this, host, port, wsPath, pin); }
  startCastConnection() { return startCastConnection(this); }
  disconnectCast(reason = '') { return disconnectCast(this, reason); }
  startHeartbeat() { return startHeartbeat(this); }
  applyLiveResizeConfig(opts) { return applyLiveResizeConfig(this, opts); }
  handleDownlinkFrame(frame) { return handleDownlinkFrame(this, frame); }
  enterStageView(peerLabel, showApprovalWait) { return enterStageView(this, peerLabel, showApprovalWait); }
  updateTelemetry() { return updateTelemetry(this); }
  syncBlackoutUi() { return syncBlackoutUi(this); }
  syncMicUi() { return syncMicUi(this); }
  syncCameraUi() { return syncCameraUi(this); }
  syncRemoteFullscreenUi() { return syncRemoteFullscreenUi(this); }
  bumpToolbarVisibility() { return bumpToolbarVisibility(this); }
  renderRecentDevices() { return renderRecentDevices(this); }
  renderAppList() { this.standalone.renderInCastAppList(); }

  sendOpalFrame(type, flags = 0, payload = undefined) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return false;
    this.ws.send(encodeFrame(type, flags, undefined, payload));
    return true;
  }

  hasControlChannel() { return this.companion.hasControlChannel(); }

  applyStandaloneStreamGeometry(geo) {
    if (!this.authenticated || !geo) return;
    const fps = Number($('liveFpsSelect')?.value) || Number($('castFpsSelect')?.value) || 60;
    const brCfg = this.resolveSelectedBitrateConfig('castBitrateSelect', 'castCustomBitrateInput');
    this.sendOpalFrame(TYPE_RESIZE_CONFIG, 0, encodeResizeConfig({
      width: geo.streamWidth, height: geo.streamHeight, dpi: geo.dpi || 0, fps, bitrateKbps: brCfg.bitrateKbps || 14000,
    }));
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
    if (!target) { this.showToast(t('cast.noDeviceForPopout')); return; }
    this.fileBrowser.close();
    void this.standalone.requestLaunchStandaloneApp({ pkg, cls: '', label: label || pkg }, target);
  }

  buildClientContextSummary() {
    const parts = [
      `Mode: ${this.desktop.enabled ? 'Tauri Desktop' : 'Web Browser'}`,
      `Transport: ${this.transportMode}`,
      `Role: ${this.sessionRole}`,
      `Connected: ${this.authenticated ? 'yes' : 'no'}`,
      `ActiveStandaloneWindows: ${this.standalone?.getActiveCount() || 0}/3`,
    ];
    if (this.activeHost) parts.push(`Peer: ${this.activeHost}:${this.activePort}`);
    return parts.join(' | ');
  }

  setCastError(msg) {
    const box = $('castErrorBox');
    box.hidden = !msg;
    box.textContent = msg || '';
    if (msg) this.diagnostics?.log('W', 'Cast', msg);
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
    if (actionLabel && resolvedFn) { btn.textContent = actionLabel; btn.hidden = false; }
    else { btn.hidden = true; }
    $('toastIsland').hidden = false;
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => { $('toastIsland').hidden = true; }, actionLabel ? 6500 : 3200);
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
