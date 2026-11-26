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
  evaluateCertificateTrust,
  formatShortFingerprint,
  isTauriDesktop,
  OpalDesktopBridge,
} from './desktop_bridge.js';
import { DEFAULT_MIC_CHANNELS, DEFAULT_MIC_SAMPLE_RATE, OpalMicCapturer } from './mic_capturer.js';
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
  FLAG_CLIPBOARD_IMAGE_PNG,
  SESSION_ROLE_DESKTOP,
  SESSION_ROLE_STANDALONE_APP,
  SYS_BACK,
  SYS_DISCONNECT,
  SYS_HOME,
  SYS_SET_AUDIO_ENABLED,
  SYS_TOGGLE_BLACKOUT,
  TEXT_MODE_COMMIT_PASTE,
  TYPE_APP_LIST,
  TYPE_AUDIO_CONFIG,
  TYPE_AUDIO_FRAME,
  TYPE_AUTH_RESULT,
  TYPE_CAMERA_CONFIG,
  TYPE_CAMERA_FRAME,
  TYPE_CAMERA_STATE,
  TYPE_CLIPBOARD_PUSH,
  TYPE_DEVICE_STATE,
  TYPE_FILE_TRANSFER_ACK,
  TYPE_FILE_TRANSFER_CHUNK,
  TYPE_HELLO,
  TYPE_IME_REQUEST,
  TYPE_INPUT_KEY,
  TYPE_INPUT_MOUSE,
  TYPE_INPUT_TEXT,
  TYPE_INPUT_TOUCH,
  TYPE_LIST_APPS,
  TYPE_MIC_CONFIG,
  TYPE_MIC_FRAME,
  TYPE_MIC_STATE,
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

const STORAGE_CLIENT_ID = 'opaldesk-web-client-id-v1';
const STORAGE_DEVICES = 'opaldesk-web-devices-v1';
const STORAGE_THEME = 'opaldesk-showcase-theme-v1';
const STORAGE_USB_TETHER_BETA = 'opaldesk-usb-tether-beta-v1';
const FILE_CHUNK_SIZE = 64 * 1024;

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
    this.explicitCertPin = '';
    this.activeVerifiedFingerprint = '';
    this.activeUsbLocalPort = 0;
    this.selectedAdbSerial = '';
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
    this.resizeDebounceTimer = null;
    this.textSeq = 1;
    this.transferSeq = 1;
    this.currentApps = [];
    this.resizeMode = 'auto';
    this.activeHost = '';
    this.activePort = 19878;
    this.lastWifiHost = '';
    this.blackoutActive = false;
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
      onError: (msg) => this.showToast(msg),
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
        this.showToast('已粘贴图片到手机端');
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
          this.appendActLog('✓ 访问码认证通过，等待手机端无线调试端口信息…');
        } else {
          this.setActError(res.message || '6 位访问码不正确');
          this.appendActLog(`✗ 认证失败: ${res.message || ''}`);
        }
      },
      onState: (state) => this.renderActivationState(state),
      onProgress: (msg) => this.appendActLog(`▸ ${msg}`),
      onResult: (res) => {
        this.appendActLog(`${res.ok ? '✓' : '✗'} ${res.message || ''}`);
        this.setActBusy(false);
        if (!res.ok) this.setActError(res.message || '激活未成功');
      },
      onReadyForCast: (info) => {
        this.appendActLog(`★ ${info.message || '已就绪'}`);
        if (info.ok && info.host) {
          this.showToast('引擎激活成功，正在切入 Web 投屏…');
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
        this.appendActLog(`连接关闭: ${reason}`);
        $('disconnectActBtn').hidden = true;
      },
    });

    this.init();
  }

  async init() {
    this.bindUiEvents();
    this.input.attach();
    if (this.desktop.enabled) {
      await this.initDesktopMode();
    }
    this.renderRecentDevices();
    this.installTrustPopupListener();
    this.installResizeObserver();

    const caps = await probeVideoCodecs();
    const badge = $('codecBadge');
    if (!caps.supported) {
      badge.textContent = 'WebCodecs 不可用';
      badge.classList.add('warn');
      this.showBrowserWarn(
        '当前浏览器不支持 WebCodecs 硬件视频解码（或处于非安全上下文）。请通过 GitHub Pages HTTPS 在最新版 Chrome / Edge / Firefox 中打开。'
      );
    } else {
      badge.textContent = caps.hevc ? 'H.264 + HEVC 硬解就绪' : 'H.264 硬解就绪';
      badge.classList.add('ok');
      if (!caps.hevc) {
        const hevcOpt = $('castCodecSelect').querySelector('option[value="hevc"]');
        if (hevcOpt) hevcOpt.textContent = 'HEVC / H.265 (当前浏览器未检出硬解)';
      }
    }

    if (isSafariBrowser()) {
      this.showBrowserWarn(
        '检测到 Safari 浏览器：Safari 对公网页面跨源连接局域网自签名 WSS 限制较严，若放行证书后仍无法握手，建议使用 Chrome 或 Edge 浏览器。'
      );
    }

    this.applyUrlParams();
  }

  async initDesktopMode() {
    const badge = $('brandModeBadge');
    if (badge) {
      badge.textContent = (navigator.userAgent || '').includes('Windows') ? 'Windows Client' : 'Desktop';
    }
    const heroTitle = $('heroTitle');
    if (heroTitle) heroTitle.textContent = 'OpalDesk 桌面无线显示器 & 激活中心';
    const heroSub = $('heroSubtitle');
    if (heroSub) {
      heroSub.textContent =
        '局域网 mDNS 自动发现 · TLS 1.3 TOFU 证书指纹核验直连 · WebCodecs 硬件直解 + USB 一键免命令投屏';
    }
    $('connectCastBtn').textContent = '开始投屏';

    try {
      const info = await this.desktop.initialize();
      if (info?.clientId) {
        this.clientId = info.clientId;
      }
      if (info?.adb?.customAdbPath) {
        $('customAdbPathInput').value = info.adb.customAdbPath;
      }
    } catch {
      // Keep graceful fallback if native state init encounters an error
    }

    $('desktopMdnsSection').hidden = this.transportMode === 'usb';
    this.usbTetherBetaEnabled = loadUsbTetherBetaPref();
    const betaCheck = $('usbTetherBetaCheck');
    if (betaCheck) betaCheck.checked = this.usbTetherBetaEnabled;
    this.syncDesktopUsbTetherUi();
    this.renderDesktopMdnsDevices(this.desktop.mdnsDevices);
    this.desktop.startDiscoveryPolling((devices) => this.renderDesktopMdnsDevices(devices));
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
    $('themeToggleBtn').addEventListener('click', () => {
      const cur = document.documentElement.getAttribute('data-theme');
      const next = cur === 'prism-pulse' ? 'pure-frost' : 'prism-pulse';
      document.documentElement.setAttribute('data-theme', next);
      try { localStorage.setItem(STORAGE_THEME, next); } catch {}
    });

    $('tabCastBtn').addEventListener('click', () => this.selectLobbyTab('cast'));
    $('tabActivateBtn').addEventListener('click', () => this.selectLobbyTab('activate'));
    $('modeWifiBtn').addEventListener('click', () => this.selectTransportMode('wifi'));
    $('modeUsbBtn').addEventListener('click', () => this.selectTransportMode('usb'));

    $('copyAdbForwardBtn').addEventListener('click', () => {
      const port = Number($('castPortInput').value) || 19878;
      this.copyText(`adb forward tcp:${port} localabstract:opaldesk_stream`, '已复制 USB 转发命令');
    });
    $('castPortInput').addEventListener('input', () => this.updateUsbForwardHint());
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
      this.showToast('已刷新局域网 mDNS 设备扫描');
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
    $('saveAdbPathBtn')?.addEventListener('click', async () => {
      if (!this.desktop.enabled) return;
      const custom = $('customAdbPathInput').value.trim();
      const st = await this.desktop.setCustomAdbPath(custom || null);
      this.showToast(st.available ? `ADB 已就绪: ${st.adbPath}` : '未在该路径找到可用的 adb 工具');
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
        this.setActError('请填写配对端口和 6 位无线调试配对码');
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
        this.setActError('请先填写无线调试主页显示的连接端口');
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
      this.copyText($('actFallbackCmd').textContent || '', '已复制无线 ADB 兜底命令');
    });

    // Stage toolbar actions
    $('cancelConnectBtn').addEventListener('click', () => this.disconnectCast('已取消连接'));
    $('tbDisconnectBtn').addEventListener('click', () => this.disconnectCast('已断开投屏'));
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
    $('tbBlackoutBtn')?.addEventListener('click', () => {
      this.toggleBlackout();
      this.bumpToolbarVisibility();
      this.input.focusIme();
    });
    $('tbInputModeBtn').addEventListener('click', () => {
      const nextTouch = !this.input.primaryLeftAsTouch;
      this.input.setPrimaryLeftAsTouch(nextTouch);
      const btn = $('tbInputModeBtn');
      btn.textContent = nextTouch ? '触控' : '鼠标';
      btn.classList.toggle('active', nextTouch);
      this.showToast(nextTouch ? '左键拖拽已切换为「触控滑动」模式（支持列表拖拽滚动）' : '左键拖拽已切换为「鼠标指针」模式（支持文本框选）');
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
      await this.audio.resumeFromGesture();
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
        $('qualityPopover').querySelectorAll('[data-preset]').forEach((b) => b.classList.toggle('active', b === btn));
      });
    });
    $('applyQualityBtn').addEventListener('click', () => {
      this.applyLiveResizeConfig();
      $('qualityPopover').hidden = true;
      this.input.focusIme();
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

    // Stage toolbar auto-hide + reveal via top grab handle or top-edge hover
    $('stageRevealHandle').addEventListener('pointerenter', () => this.bumpToolbarVisibility());
    $('stageRevealHandle').addEventListener('click', () => this.bumpToolbarVisibility());
    $('stageToolbar').addEventListener('pointerleave', () => this.bumpToolbarVisibility());
    $('stageView').addEventListener('pointermove', (e) => {
      if (e.clientY <= 18) this.bumpToolbarVisibility();
    });

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

    window.addEventListener('keydown', (e) => {
      if (!this.authenticated || !e.shiftKey || !(e.altKey || e.metaKey)) return;
      if (e.code === 'KeyB' && this.sessionRole !== SESSION_ROLE_STANDALONE_APP) {
        e.preventDefault();
        this.toggleBlackout();
      } else if (e.code === 'KeyM' && this.sessionRole !== SESSION_ROLE_STANDALONE_APP) {
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
      hintEl.innerHTML = `在电脑终端执行上方命令后，点击下方「开始 Web 投屏」即可通过 <code>ws://127.0.0.1:${port}/ws</code> 零警告直连。`;
    }
  }

  installTrustPopupListener() {
    window.addEventListener('message', (ev) => {
      const d = ev.data;
      if (!d || d.type !== 'opaldesk-tls-trusted') return;
      if (d.role === 'activate') {
        $('actTrustBox').hidden = true;
        this.showToast('激活服务证书已信任，正在自动连接…');
        this.startActivationConnection();
      } else if (d.role === 'cast') {
        $('castTrustBox').hidden = true;
        this.showToast('设备加密证书已信任，正在自动进入投屏…');
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
    const isCast = tab === 'cast';
    $('tabCastBtn').classList.toggle('active', isCast);
    $('tabActivateBtn').classList.toggle('active', !isCast);
    $('tabCastBtn').setAttribute('aria-selected', String(isCast));
    $('tabActivateBtn').setAttribute('aria-selected', String(!isCast));
    $('paneCast').hidden = !isCast;
    $('paneActivate').hidden = isCast;
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
      if (hostLabel) hostLabel.textContent = 'USB 本地转发地址';
      if (!$('castPortInput').value) $('castPortInput').value = '19878';
      $('castTrustBox').hidden = true;
      this.updateUsbForwardHint();
    } else {
      hostInput.readOnly = false;
      if (hostLabel) hostLabel.textContent = '手机局域网 IP';
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
      hint.textContent = '正在扫描局域网 _opaldesk._tcp 设备… 请确认手机端已开启「跨设备互联 → 共享本机」。';
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
      sub.textContent = `${dev.host}:${dev.port}${dev.shortFingerprint ? ` · 指纹 ${dev.shortFingerprint}` : ''}`;
      meta.append(title, sub);

      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'primary-btn compact';
      btn.textContent = '连接投屏';
      btn.addEventListener('click', () => {
        this.selectTransportMode('wifi');
        $('castHostInput').value = dev.host;
        $('castPortInput').value = String(dev.port || 19878);
        this.startCastConnection();
      });

      row.append(meta, btn);
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
      hint.textContent =
        '未识别到 USB 网络共享手机：请连接数据线并在手机开启系统「USB 网络共享」及 OpalDesk 共享服务后点「刷新识别」（Beta 测试功能，不保证所有设备均可正常使用）。';
      listEl.appendChild(hint);
      return;
    }
    for (const dev of devices) {
      const row = document.createElement('div');
      row.className = 'mdns-device-card';

      const meta = document.createElement('div');
      meta.className = 'mdns-device-meta';
      const title = document.createElement('strong');
      title.textContent = `${dev.displayName || dev.host} · USB 网络共享 (Beta)`;
      const nicLabel = dev.interfaceAlias || dev.interfaceDescription || 'USB';
      const sub = document.createElement('span');
      sub.className = 'mono';
      sub.textContent = `${dev.host}:${dev.port} · ${nicLabel}${dev.shortFingerprint ? ` · 指纹 ${dev.shortFingerprint}` : ''}`;
      meta.append(title, sub);

      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'primary-btn compact';
      btn.textContent = '连接投屏';
      btn.addEventListener('click', () => {
        this.explicitCertPin = '';
        this.selectTransportMode('wifi');
        $('castHostInput').value = dev.host;
        $('castPortInput').value = String(dev.port || 19878);
        this.startCastConnection();
      });

      row.append(meta, btn);
      listEl.appendChild(row);
    }
  }

  async refreshDesktopUsbTetherPanel(showToastOnDone = false) {
    if (!this.desktop.enabled || !this.usbTetherBetaEnabled) return;
    const listEl = $('desktopUsbTetherList');
    if (listEl) {
      listEl.hidden = false;
      listEl.innerHTML = '<span class="hint-text">正在探测 Windows USB 网络共享网卡网关…</span>';
    }
    try {
      const port = Number($('castPortInput')?.value) || 19878;
      const list = await this.desktop.scanUsbTetherDevices(port);
      this.renderDesktopUsbTetherDevices(list);
      if (showToastOnDone) {
        this.showToast(list.length ? `已识别 ${list.length} 台 USB 网络共享设备 (Beta)` : '暂未识别到 USB 网络共享设备');
      }
    } catch (err) {
      if (listEl) {
        listEl.innerHTML = '';
        const errSpan = document.createElement('span');
        errSpan.className = 'hint-text';
        errSpan.textContent = `USB 网络共享识别失败：${String(err?.message || err || '未知错误')}`;
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
      const status = await this.desktop.refreshAdbStatus();
      if (!status.available) {
        hintEl.textContent = '未检测到 adb.exe：请安装 Android Platform-Tools 或展开下方设置指定路径。';
        return;
      }
      hintEl.textContent = `已就绪 ADB: ${status.adbPath}`;
      const devices = await this.desktop.scanAdbDevices();
      if (!devices.length) {
        const empty = document.createElement('span');
        empty.className = 'hint-text';
        empty.textContent = '暂未发现 USB 设备：请连接数据线并在手机开启「开发者选项 → USB 调试」。';
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
            ? '状态：已授权就绪（点击右侧按钮将自动拉起守护进程并直连投屏）'
            : `状态：${dev.state}（请在手机弹窗勾选「始终允许调试」）`;
        meta.append(title, sub);

        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'primary-btn compact';
        btn.disabled = dev.state !== 'device';
        btn.textContent = '一键激活并投屏';
        btn.addEventListener('click', () => {
          this.selectedAdbSerial = dev.serial;
          this.startCastConnection();
        });
        row.append(meta, btn);
        listEl.appendChild(row);
      }
    } catch (err) {
      hintEl.textContent = String(err?.message || err || '扫描 ADB 设备失败');
    }
  }

  promptTofuConfirmation(host, port, evalRes) {
    return new Promise((resolve) => {
      this.tofuResolver = resolve;
      const isChanged = evalRes.status === 'changed';
      $('tofuModalTitle').textContent = isChanged
        ? '检测到设备加密证书已变更'
        : '首次连接：核验手机加密证书指纹';
      $('tofuModalDesc').textContent = isChanged
        ? `主机 ${host}:${port} 的证书与历史记录（${evalRes.expectedShort}）不一致。若手机重装过 OpalDesk，请核对新指纹后确认信任。`
        : `即将建立到 ${evalRes.mdnsDevice?.displayName || host} (${host}:${port}) 的 TLS 1.3 加密通道，请确认证书指纹：`;
      const mdnsBadge = $('tofuMdnsBadge');
      if (evalRes.mdnsVerified) {
        mdnsBadge.hidden = false;
        mdnsBadge.className = 'status-pill ok mono';
        mdnsBadge.textContent = '✓ 与局域网 mDNS 广播指纹一致';
      } else {
        mdnsBadge.hidden = false;
        mdnsBadge.className = 'status-pill warn mono';
        mdnsBadge.textContent = isChanged ? '⚠ 证书指纹已发生变更' : '手动 IP 直连 · 请核对手机端指纹';
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
      throw new Error('未能读取有效的设备 TLS 证书指纹');
    }
    if (evalRes.status === 'mismatch') {
      throw new Error(
        `TLS 证书指纹校验未通过（预期 ${evalRes.expectedShort}，实际 ${evalRes.actualShort}）`
      );
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
      this.setCastError('请填写手机局域网 IP 地址');
      return;
    }

    $('connectCastBtn').disabled = true;
    $('connectCastBtn').textContent = '正在探测设备…';
    const defaultBtnLabel = this.desktop.enabled ? '开始投屏' : '开始 Web 投屏';

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
          const status = await this.desktop.refreshAdbStatus();
          if (status.available) {
            const devices = await this.desktop.scanAdbDevices();
            const chosen =
              devices.find((d) => d.serial === this.selectedAdbSerial && d.state === 'device') ||
              devices.find((d) => d.state === 'device');
            if (!chosen) {
              throw new Error('未检测到已授权的 USB 调试手机，请连接数据线并在手机端允许 USB 调试');
            }
            $('connectCastBtn').textContent = '正在引导守护进程并建立 USB 通道…';
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
        this.setCastError(String(err?.message || err || '桌面端建立加密连接失败'));
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
          $('castTrustMsg').textContent = `无法直接验证 https://${host}:${port} 的本地加密证书。请先点击右侧按钮放行证书（或在 Chrome 本地网络弹窗点「允许」）。`;
        } else {
          this.setCastError(`无法连接到手机 ${host}:${port}（${probe.error}）。请确认手机已开启「跨设备互联 → 共享本机」且在同一局域网。`);
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
    this.teardownCastSocket();
    let ws;
    try {
      ws = new WebSocket(wsUrl);
    } catch (err) {
      $('connectCastBtn').disabled = false;
      $('connectCastBtn').textContent = this.desktop.enabled ? '开始投屏' : '开始 Web 投屏';
      const detail = err?.message || String(err || '');
      if (isUsb) {
        this.setCastError(
          isSafariBrowser()
            ? `Safari 禁止在 HTTPS 页面连接本地明文 ws://127.0.0.1:${port}/ws（${detail}）。请改用 Chrome / Edge 浏览器，或切换到局域网无线 (WSS) 模式。`
            : `无法发起本地 USB WebSocket 连接 (${wsUrl}): ${detail}`
        );
      } else {
        this.setCastError(`无法发起 WebSocket 连接 (${wsUrl}): ${detail}`);
      }
      return;
    }
    ws.binaryType = 'arraybuffer';
    this.ws = ws;

    const reqSpec = this.computeInitialStreamSpec();
    const savedToken = isUsb
      ? ''
      : ((this.desktop.enabled && this.desktop.getTrustedToken(host, port)) || getSavedToken(host, port));

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
        audioEnabled: this.sessionRole === SESSION_ROLE_DESKTOP && $('castAudioCheck').checked,
        authToken: savedToken,
        transportHint: isUsb ? 'usb' : 'wifi_tls',
        sessionRole: this.sessionRole,
        appPackage: this.standaloneAppPkg,
        appActivity: this.standaloneAppCls,
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
            ? `USB 通道握手失败（127.0.0.1:${port}）：请确认手机已授权 USB 调试、OpalDesk 守护进程已激活，且手机端已更新至最新版。`
            : `USB 本地转发握手失败（ws://127.0.0.1:${port}/ws）：请先在终端执行 adb forward tcp:${port} localabstract:opaldesk_stream，并确认手机端 OpalDesk 引擎已激活。`;
          this.disconnectCast(usbTip);
        } else {
          this.disconnectCast('WebSocket 握手失败：请检查证书是否已放行或手机端无线共享是否开启');
          if (!this.desktop.enabled) $('castTrustBox').hidden = false;
        }
      }
    };

    ws.onclose = (ev) => {
      const wasAuth = this.authenticated;
      if (!this.ws) return;
      const defaultFail = isUsb
        ? `未能建立 USB 投屏会话（127.0.0.1:${port}）：请检查 adb forward 与手机端守护进程是否运行`
        : '未能建立投屏会话';
      const reason = ev.reason || (wasAuth ? '投屏连接已断开' : defaultFail);
      this.disconnectCast(reason);
    };
  }

  handleDownlinkFrame(frame) {
    switch (frame.type) {
      case TYPE_AUTH_RESULT: {
        const auth = decodeAuthResult(frame.payload);
        if (!auth.ok) {
          this.disconnectCast(auth.message || '手机端拒绝了此次投屏请求');
          return;
        }
        this.authenticated = true;
        $('approvalOverlay').hidden = true;
        $('stagePeerName').textContent = auth.deviceName || this.activeHost;
        this.input.setEnabled(true);
        this.startHeartbeat();
        this.bumpToolbarVisibility();

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
        this.showToast(`已连接到 ${auth.deviceName || this.activeHost}`);
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
          if (typeof st.injectStatus === 'number') {
            this.injectStatus = st.injectStatus;
            this.updateTelemetry();
            if (st.injectStatus === -1 && !this.inputBlockedWarned) {
              this.inputBlockedWarned = true;
              this.showToast('手机端拦截了跨屏操控：小米/HyperOS 请在手机「开发者选项」开启「USB 调试（安全设置）」后重连');
            } else if (st.injectStatus > 0) {
              this.inputBlockedWarned = false;
            }
          }
        } catch {
          // Ignore malformed telemetry
        }
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
            this.showToast(`文件已送达手机: ${ack.pathOrError}`);
          } else {
            this.showToast(`文件传输失败: ${ack.pathOrError}`);
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
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const raw = encodeFrame(type, flags, undefined, payload);
    this.ws.send(raw);
  }

  computeInitialStreamSpec() {
    const isStandalone = this.sessionRole === SESSION_ROLE_STANDALONE_APP;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const vw = Math.max(640, Math.round(window.innerWidth * dpr));
    const vh = Math.max(480, Math.round(window.innerHeight * dpr));
    const width = isStandalone ? 1080 : Math.min(2560, vw & ~7);
    const height = isStandalone ? 2280 : Math.min(1600, vh & ~7);
    const dpi = isStandalone ? 380 : Math.max(160, Math.min(320, Math.round(160 * dpr)));
    const fps = Number($('castFpsSelect').value) || 60;
    const bitrateKbps = Number($('castBitrateSelect').value) || 14000;
    return { width, height, dpi, fps, bitrateKbps };
  }

  applyLiveResizeConfig() {
    if (!this.authenticated) return;
    let w;
    let h;
    if (this.resizeMode === 'auto') {
      const rect = $('canvasWrap').getBoundingClientRect();
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const isStandalone = this.sessionRole === SESSION_ROLE_STANDALONE_APP;
      const minW = isStandalone ? 480 : 640;
      const maxH = isStandalone ? 2560 : 1600;
      w = Math.min(2560, Math.max(minW, Math.round(rect.width * dpr)));
      h = Math.min(maxH, Math.max(480, Math.round(rect.height * dpr)));
    } else {
      const [pw, ph] = this.resizeMode.split('x').map(Number);
      w = pw || 1920;
      h = ph || 1080;
    }
    const fps = Number($('liveFpsSelect').value) || Number($('castFpsSelect').value) || 60;
    const bitrateKbps = Number($('liveBitrateSelect').value) || Number($('castBitrateSelect').value) || 14000;
    const dpi = Number($('liveDpiSelect')?.value) || 0;
    this.sendOpalFrame(TYPE_RESIZE_CONFIG, 0, encodeResizeConfig({ width: w, height: h, dpi, fps, bitrateKbps }));
  }

  async sendDroppedFiles(files) {
    for (const file of files) {
      const transferId = this.transferSeq++;
      this.showToast(`正在发送 ${file.name} (${(file.size / 1024 / 1024).toFixed(2)} MB)…`);
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
        () => this.showToast('已同步手机剪贴板内容'),
        () => this.showToast('收到手机剪贴板文本', '点此复制', () => this.copyText(text, '已复制手机剪贴板'))
      );
    } else {
      this.showToast('收到手机剪贴板文本', '点此复制', () => this.copyText(text, '已复制手机剪贴板'));
    }
  }

  handleRemoteClipboardImage(pngData) {
    const writeImg = () => {
      if (typeof ClipboardItem === 'undefined' || !navigator.clipboard?.write) {
        this.showToast('当前浏览器不支持自动写入图片剪贴板');
        return;
      }
      const blob = new Blob([pngData], { type: 'image/png' });
      navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]).then(
        () => this.showToast('已同步手机图片剪贴板'),
        () => this.showToast('收到手机剪贴板图片', '点此复制图片', () => writeImg())
      );
    };
    if (document.hasFocus() && typeof ClipboardItem !== 'undefined' && navigator.clipboard?.write) {
      writeImg();
    } else {
      this.showToast('收到手机剪贴板图片', '点此复制图片', () => writeImg());
    }
  }

  renderAppList() {
    const q = ($('appSearchInput').value || '').trim().toLowerCase();
    const listEl = $('appsList');
    listEl.innerHTML = '';
    const filtered = this.currentApps.filter(
      (a) => !q || (a.label || '').toLowerCase().includes(q) || (a.pkg || '').toLowerCase().includes(q)
    );
    if (!filtered.length) {
      listEl.innerHTML = '<div class="hint-text" style="padding:10px;text-align:center">未找到匹配应用</div>';
      return;
    }
    for (const app of filtered.slice(0, 80)) {
      const row = document.createElement('div');
      row.className = 'app-row';

      const info = document.createElement('div');
      info.className = 'app-info';
      if (app.icon) {
        const img = document.createElement('img');
        img.src = `data:image/png;base64,${app.icon}`;
        img.alt = '';
        info.appendChild(img);
      }
      const lbl = document.createElement('span');
      lbl.className = 'app-label';
      lbl.textContent = app.label || app.pkg;
      info.appendChild(lbl);

      const btns = document.createElement('div');
      btns.className = 'app-btns';

      const openBtn = document.createElement('button');
      openBtn.type = 'button';
      openBtn.className = 'mini-btn';
      openBtn.textContent = '打开';
      openBtn.addEventListener('click', () => {
        this.sendOpalFrame(TYPE_WINDOW_ACTION, 0, encodeWindowAction({ action: 'launch', pkg: app.pkg, cls: app.cls || '' }));
        $('appsPopover').hidden = true;
      });
      btns.appendChild(openBtn);

      const popBtn = document.createElement('button');
      popBtn.type = 'button';
      popBtn.className = 'mini-btn';
      popBtn.textContent = '独立小窗';
      popBtn.addEventListener('click', () => this.openStandaloneAppWindow(app));
      btns.appendChild(popBtn);

      row.append(info, btns);
      listEl.appendChild(row);
    }
  }

  openStandaloneAppWindow(app) {
    if (this.desktop.enabled) {
      this.desktop
        .openStandaloneWindow({
          pkg: app.pkg,
          cls: app.cls || '',
          label: app.label || app.pkg,
          host: this.activeHost,
          port: this.activePort,
          transport: this.transportMode,
          usbLocalPort: this.activeUsbLocalPort || this.activePort,
          certFingerprint: this.activeVerifiedFingerprint,
        })
        .catch((err) => this.showToast(`打开独立窗口失败: ${err?.message || err}`));
      $('appsPopover').hidden = true;
      return;
    }
    const u = new URL(window.location.href);
    u.search = '';
    u.hash = '';
    u.searchParams.set('host', this.activeHost);
    u.searchParams.set('port', String(this.activePort));
    if (this.transportMode === 'usb') u.searchParams.set('transport', 'usb');
    u.searchParams.set('role', SESSION_ROLE_STANDALONE_APP);
    u.searchParams.set('pkg', app.pkg);
    if (app.cls) u.searchParams.set('cls', app.cls);
    window.open(u.toString(), `opal_app_${app.pkg}`, 'width=440,height=880');
    $('appsPopover').hidden = true;
  }

  enterStageView(peerLabel, showApprovalWait) {
    const isStandalone = this.sessionRole === SESSION_ROLE_STANDALONE_APP;
    $('lobbyView').hidden = true;
    $('stageView').hidden = false;
    $('stagePeerName').textContent = peerLabel;
    $('approvalOverlay').hidden = !showApprovalWait;
    $('tbHomeBtn').hidden = isStandalone;
    if ($('tbBlackoutBtn')) $('tbBlackoutBtn').hidden = isStandalone;
    $('tbAppsBtn').hidden = isStandalone;
    $('tbAudioBtn').hidden = isStandalone;
    $('liveFpsSelect').value = $('castFpsSelect').value;
    $('liveBitrateSelect').value = $('castBitrateSelect').value;
    $('connectCastBtn').disabled = false;
    $('connectCastBtn').textContent = this.desktop.enabled ? '开始投屏' : '开始 Web 投屏';
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
    this.injectStatus = 0;
    this.inputBlockedWarned = false;
    this.syncBlackoutUi();
    this.teardownCastSocket();
    this.input.setEnabled(false);
    this.video.reset();
    this.audio.reset();
    $('stageView').hidden = true;
    $('lobbyView').hidden = false;
    $('approvalOverlay').hidden = true;
    $('qualityPopover').hidden = true;
    $('appsPopover').hidden = true;
    $('connectCastBtn').disabled = false;
    $('connectCastBtn').textContent = this.desktop.enabled ? '开始投屏' : '开始 Web 投屏';
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
    }, 3000);
  }

  updateTelemetry() {
    const parts = [`${this.remoteWidth}×${this.remoteHeight}`];
    if (this.remoteFps > 0) parts.push(`${this.remoteFps}fps`);
    if (this.lastRttMs > 0) parts.push(`${this.lastRttMs}ms`);
    parts.push(this.remoteCodecId === VIDEO_CODEC_HEVC ? 'HEVC' : 'H.264');
    if (this.injectStatus === 2) parts.push('UHID');
    else if (this.injectStatus === -1) parts.push('触控受限');
    $('stageTelemetry').textContent = parts.join(' · ');
  }

  toggleBlackout() {
    if (!this.authenticated || this.sessionRole === SESSION_ROLE_STANDALONE_APP) return;
    this.blackoutActive = !this.blackoutActive;
    this.syncBlackoutUi();
    this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_TOGGLE_BLACKOUT, this.blackoutActive ? 1 : 0));
    this.showToast(this.blackoutActive ? '已熄灭手机主屏' : '已点亮手机主屏');
  }

  syncBlackoutUi() {
    const btn = $('tbBlackoutBtn');
    if (!btn) return;
    btn.classList.toggle('active', Boolean(this.blackoutActive));
    btn.textContent = this.blackoutActive ? '亮屏' : '黑屏';
  }

  async toggleRemoteMic() {
    if (!this.authenticated) {
      this.showToast('请等待投屏连接完成后再开启麦克风');
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
    const tip = on ? '麦克风已开启 · 点击关闭' : '向手机回传本机麦克风声音';
    btn.title = tip;
    btn.setAttribute('aria-label', on ? '关闭麦克风' : '麦克风');
  }

  async toggleRemoteCamera() {
    if (!this.authenticated) {
      this.showToast('请等待投屏连接完成后再开启摄像头映射');
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
    const tip = on ? '摄像头映射已开启 · 点击关闭' : 'Root 后开启：向手机映射本机摄像头';
    btn.title = tip;
    btn.setAttribute('aria-label', on ? '关闭摄像头' : '摄像头');
  }

  syncKeyboardUi(open) {
    const btn = $('tbKeyboardBtn');
    if (!btn) return;
    const on = Boolean(open);
    btn.classList.toggle('active', on);
    const tip = on ? '收起软键盘' : '弹出 / 收起软键盘';
    btn.title = tip;
    btn.setAttribute('aria-label', on ? '收起键盘' : '软键盘');
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
        lbl.textContent = '点此出声';
        lbl.hidden = false;
      }
      btn.classList.add('active');
      btn.title = '点此解锁并播放声音';
      btn.setAttribute('aria-label', '点此出声');
      this.showToast('浏览器需要手势以解锁音频', '开启声音', () => this.audio.resumeFromGesture());
    } else if (st.muted) {
      if (onIcon) onIcon.hidden = true;
      if (offIcon) offIcon.hidden = false;
      if (lbl) lbl.hidden = true;
      btn.classList.remove('active');
      btn.title = '已静音 · 点击恢复声音';
      btn.setAttribute('aria-label', '恢复声音');
    } else {
      if (onIcon) onIcon.hidden = false;
      if (offIcon) offIcon.hidden = true;
      if (lbl) lbl.hidden = true;
      btn.classList.remove('active');
      btn.title = '静音 / 开启声音';
      btn.setAttribute('aria-label', '声音');
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
    btn.title = isFs ? '退出全屏' : '全屏显示';
    btn.setAttribute('aria-label', isFs ? '退出全屏' : '全屏显示');
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
      this.setActError('请填写手机局域网 IP');
      return;
    }
    if (!/^\d{6}$/.test(code)) {
      this.setActError('请输入手机屏幕上显示的 6 位会话访问码');
      return;
    }

    $('connectActBtn').disabled = true;
    $('connectActBtn').textContent = '正在探测激活服务…';
    if (this.desktop.enabled) {
      try {
        const tunnel = await this.ensureDesktopTlsTunnel(host, port, '/activate');
        $('connectActBtn').disabled = false;
        $('connectActBtn').textContent = '连接手机激活服务';
        if (!tunnel) return;
        $('actLogOutput').textContent = '';
        this.activation.connect(host, port, code, tunnel.localWsUrl);
      } catch (err) {
        $('connectActBtn').disabled = false;
        $('connectActBtn').textContent = '连接手机激活服务';
        this.setActError(
          `无法连接手机激活服务 ${host}:${port}（${err?.message || err}）。请确认手机端已开启「Web激活」服务且在同一局域网。`
        );
      }
      return;
    }
    const probe = await probeHttpsHealth(host, port);
    $('connectActBtn').disabled = false;
    $('connectActBtn').textContent = '连接手机激活服务';

    if (!probe.ok) {
      if (probe.needsTrust) {
        $('actTrustBox').hidden = false;
      } else {
        this.setActError(`无法连接手机激活服务 ${host}:${port}（${probe.error}）。请确认手机端已开启「Web激活」服务且在同一 Wi-Fi。`);
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
    $('actDiscoverySummary').textContent = `mDNS 配对端口: ${pPort || '等待手机打开配对弹窗'} · 连接端口: ${cPort || '未发现'}`;

    if (pPort > 0 && !$('adbPairPortInput').matches(':focus')) {
      $('adbPairPortInput').value = String(pPort);
    }
    if (cPort > 0 && !$('adbConnPortInput').matches(':focus')) {
      $('adbConnPortInput').value = String(cPort);
    }

    const badge = $('actDaemonBadge');
    badge.textContent = st.daemonConnected ? '引擎已激活' : '未激活';
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
        btn.title = shortFp ? `已固定证书指纹: ${shortFp}` : '';
        btn.textContent = `${dev.displayName || dev.host} (${dev.host}:${dev.port})`;
        btn.addEventListener('click', () => {
          this.selectTransportMode('wifi');
          $('castHostInput').value = dev.host;
          $('castPortInput').value = String(dev.port || 19878);
          this.startCastConnection();
        });

        const revokeBtn = document.createElement('button');
        revokeBtn.type = 'button';
        revokeBtn.className = 'recent-revoke-btn';
        revokeBtn.title = '移除此信任设备及证书指纹';
        revokeBtn.textContent = '×';
        revokeBtn.addEventListener('click', async () => {
          await this.desktop.revokeTrustedDevice(dev.id);
          this.renderRecentDevices();
          this.showToast(`已移除信任设备 ${dev.displayName || dev.host}`);
        });

        group.append(btn, revokeBtn);
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

  setCastError(msg) {
    const box = $('castErrorBox');
    box.hidden = !msg;
    box.textContent = msg || '';
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

  showToast(text, actionLabel = '', actionFn = null) {
    $('toastText').textContent = text;
    const btn = $('toastActionBtn');
    this.toastActionFn = actionFn;
    if (actionLabel && actionFn) {
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
      () => this.showToast('剪贴板写入受限，请手动选择复制')
    );
  }
}

window.addEventListener('DOMContentLoaded', () => {
  new OpalCastWebApp();
});
