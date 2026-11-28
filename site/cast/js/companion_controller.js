/**
 * OpalDesk Background Companion Hub, Call Control HUD Coordinator, T9 Dialer & BLE Nearby Controller.
 *
 * Transport arbitration:
 *  - When an active Desktop Cast session is authenticated, all Companion events/actions flow over
 *    the active Cast socket (zero duplicate connections).
 *  - When Desktop Cast is idle, CompanionController maintains a lightweight `SESSION_ROLE_COMPANION`
 *    control-only WebSocket (no video/audio) to keep notifications, media remote, Handoff,
 *    call HUD, file manager, and APK installer live in the background / system tray!
 */

import {
  TYPE_HELLO,
  TYPE_AUTH_RESULT,
  TYPE_DEVICE_STATE,
  TYPE_CLIPBOARD_PUSH,
  TYPE_NOTIFICATION_EVENT,
  TYPE_MEDIA_STATE,
  TYPE_CALL_STATE,
  TYPE_NOTIFICATION_ACTION,
  TYPE_MEDIA_ACTION,
  TYPE_CALL_ACTION,
  TYPE_PING,
  TYPE_PONG,
  SESSION_ROLE_COMPANION,
  encodeFrame,
  decodeFrame,
  encodeHello,
  decodeAuthResult,
  decodeDeviceState,
  decodeClipboardPush,
  decodeNotificationEvent,
  decodeMediaState,
  decodeCallState,
  encodeNotificationAction,
  encodeMediaAction,
  encodeCallAction,
  encodePingPong,
} from './protocol.js';
import {
  T9_DIALPAD_KEYS,
  createNotificationStore,
  formatCallDuration,
  sanitizeDialNumber,
  callSessionKey,
  shouldShowCallHud,
  resolveHandoffInfo,
} from './companion_core.js';
import { getSmsRejectTemplates, t } from './i18n.js';

const $ = (id) => document.getElementById(id);

function createEl(tag, className = '', text = '') {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text) el.textContent = text;
  return el;
}

export class OpalCompanionController {
  constructor(app) {
    this.app = app;
    this.desktop = app.desktop;

    this.notifications = createNotificationStore();
    this.mediaState = null;
    this.callState = null;
    this.handoff = null;
    this.peerDeviceState = null;
    this.dismissedCallKey = '';
    this.callTimer = null;

    // Standalone background companion socket (used only when desktop cast is idle)
    this.ws = null;
    this.connected = false;
    this.authenticated = false;
    this.target = null; // { host, port, transport, deviceName, certFingerprint, adbSerial }
    this.pingTimer = null;
    this.pingSeq = 1n;
    this.autoConnectAttempted = false;

    // BLE Nearby devices
    this.bleSupported = false;
    this.bleDevices = [];
    this.bleNote = '';
    this.bleBusyAddress = '';
  }

  get isCompanionOnline() {
    return Boolean(this.authenticated && this.ws && this.ws.readyState === WebSocket.OPEN);
  }

  get activePeerName() {
    if (this.app.authenticated) {
      return $('stagePeerName')?.textContent || this.app.activeHost || 'Android';
    }
    return this.target?.deviceName || this.target?.host || '';
  }

  bindEvents() {
    $('companionConnectBtn')?.addEventListener('click', () => void this.connectFromLobbyInput());
    $('companionDisconnectBtn')?.addEventListener('click', () => this.disconnect(t('companion.disconnectedToast')));
    $('companionFilesBtn')?.addEventListener('click', () => this.app.fileBrowser.open('files'));
    $('companionStorageBtn')?.addEventListener('click', () => this.app.fileBrowser.open('storage'));
    $('companionApkBtn')?.addEventListener('click', () => this.app.fileBrowser.open('apk'));
    $('companionDialerBtn')?.addEventListener('click', () => this.openDialerModal());
    $('companionClearNotifsBtn')?.addEventListener('click', () => this.clearAllNotifications());
    $('companionHandoffPopoutBtn')?.addEventListener('click', () => {
      if (this.handoff?.pkg) {
        this.app.launchPopoutByPackage(this.handoff.pkg, this.handoff.label || this.handoff.pkg);
      }
    });

    // Media controls
    $('companionMediaPrevBtn')?.addEventListener('click', () => this.sendMediaAction('previous'));
    $('companionMediaPlayBtn')?.addEventListener('click', () => this.sendMediaAction('toggle'));
    $('companionMediaNextBtn')?.addEventListener('click', () => this.sendMediaAction('next'));

    // OTP copy
    $('companionOtpCopyBtn')?.addEventListener('click', () => {
      const otp = this.notifications.getLatestOtp();
      if (otp?.code) this.app.copyText(otp.code, t('companion.otpCopiedToast', { code: otp.code }));
    });

    // T9 Quick Dialer modal
    $('closeDialerModalBtn')?.addEventListener('click', () => this.closeDialerModal());
    $('dialerModalOverlay')?.addEventListener('click', (e) => {
      if (e.target === $('dialerModalOverlay')) this.closeDialerModal();
    });
    $('dialerNumberInput')?.addEventListener('input', (e) => {
      e.target.value = sanitizeDialNumber(e.target.value);
    });
    $('dialerBackspaceBtn')?.addEventListener('click', () => {
      const inp = $('dialerNumberInput');
      if (!inp) return;
      inp.value = inp.value.slice(0, -1);
      inp.focus();
    });
    $('dialerCallBtn')?.addEventListener('click', () => this.submitDialerCall());
    $('dialerNumberInput')?.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        this.submitDialerCall();
      }
    });
    this._renderT9Keypad();

    // Web fallback floating Call HUD actions
    $('webCallHudAnswerBtn')?.addEventListener('click', () => this.handleCallHudAction({ op: 'answer' }));
    $('webCallHudRejectBtn')?.addEventListener('click', () => this.handleCallHudAction({ op: 'reject' }));
    $('webCallHudMuteBtn')?.addEventListener('click', () => this.handleCallHudAction({ op: 'toggle_mute' }));
    $('webCallHudSpeakerBtn')?.addEventListener('click', () => this.handleCallHudAction({ op: 'toggle_speaker' }));
    $('webCallHudHangupBtn')?.addEventListener('click', () => this.handleCallHudAction({ op: 'hangup' }));
    $('webCallHudDismissBtn')?.addEventListener('click', () => this.handleCallHudAction({ op: 'dismiss_hud' }));

    // BLE Nearby refresh
    $('refreshBleBtn')?.addEventListener('click', () => void this.refreshBleNearby(true));

    // Tauri desktop event listeners (Call HUD actions + deep links + tray commands)
    if (this.desktop.enabled) {
      this.desktop.listenEvent('opaldesk:call-hud-action', (payload) => {
        if (payload && typeof payload === 'object') {
          this.handleCallHudAction(payload);
        }
      });
      this.desktop.listenEvent('opaldesk:tray-action', (payload) => {
        const action = String(payload?.action || '');
        if (action === 'open_files') this.app.fileBrowser.open('files');
        else if (action === 'open_storage') this.app.fileBrowser.open('storage');
        else if (action === 'open_dialer') this.openDialerModal();
        else if (action === 'connect_companion') void this.tryAutoConnectCompanion(true);
      });
      this.desktop.listenEvent('opaldesk:deep-link', (payload) => {
        if (payload?.url) {
          this.handleDeepLinkUrl(String(payload.url));
        }
      });
    }
  }

  async initialize() {
    this.renderCompanionHub();
    if (this.desktop.enabled && this.app.sessionRole !== 'standalone_app') {
      void this.refreshBleNearby(false);
      // Register zero-admin HKCU opaldesk:// protocol silently on first start
      void this.desktop.registerUrlProtocol().catch(() => {});
      // Auto-connect companion in background if enabled and trusted device is reachable via mDNS
      const prefs = this.desktop.clientPrefs;
      if (prefs?.companionAutoConnect !== false) {
        setTimeout(() => void this.tryAutoConnectCompanion(false), 1800);
      }
    }
  }

  /**
   * Sends a control frame using the active Desktop Cast socket first, or falls back to
   * the lightweight Background Companion socket. Returns true if dispatched.
   */
  sendControlFrame(type, flags = 0, payload = null) {
    if (this.app.authenticated && this.app.ws && this.app.ws.readyState === WebSocket.OPEN) {
      return this.app.sendOpalFrame(type, flags, payload || new Uint8Array(0));
    }
    if (this.isCompanionOnline) {
      try {
        this.ws.send(encodeFrame(type, flags, undefined, payload || new Uint8Array(0)));
        return true;
      } catch {
        return false;
      }
    }
    return false;
  }

  hasControlChannel() {
    return Boolean(
      (this.app.authenticated && this.app.ws?.readyState === WebSocket.OPEN) ||
      this.isCompanionOnline
    );
  }

  // ==================== COMPANION BACKGROUND SESSION ====================

  async tryAutoConnectCompanion(manual = false) {
    if (this.app.authenticated || this.isCompanionOnline) return;
    if (!manual && this.autoConnectAttempted) return;
    this.autoConnectAttempted = true;

    const mdns = this.desktop.mdnsDevices || [];
    const trusted = this.desktop.trustedDevices || [];
    // Prefer an online mDNS device that is already permanently trusted (zero approval popup)
    for (const dev of mdns) {
      const match = trusted.find(
        (t) => t.authToken && ((t.host === dev.host && Number(t.port) === Number(dev.port || 19878)) ||
          (dev.fingerprintPrefix && t.certFingerprint?.startsWith(dev.fingerprintPrefix)))
      );
      if (match) {
        await this.connectTarget({
          host: dev.host,
          port: dev.port || 19878,
          transport: 'wifi',
          deviceName: dev.displayName || match.displayName || dev.host,
          certFingerprint: match.certFingerprint || '',
        }, { silent: !manual });
        return;
      }
    }
    if (manual && trusted[0]) {
      await this.connectTarget({
        host: trusted[0].host,
        port: trusted[0].port || 19878,
        transport: 'wifi',
        deviceName: trusted[0].displayName || trusted[0].host,
        certFingerprint: trusted[0].certFingerprint || '',
      }, { silent: false });
    }
  }

  async connectFromLobbyInput() {
    if (this.app.transportMode === 'usb') {
      const serial = this.app.selectedAdbSerial || this.desktop.adbDevices.find((d) => d.state === 'device')?.serial || '';
      if (!serial && this.desktop.enabled) {
        this.app.showToast(t('companion.errNoUsb'));
        return;
      }
      await this.connectTarget({
        host: '127.0.0.1',
        port: Number($('castPortInput')?.value) || 19878,
        transport: 'usb',
        adbSerial: serial,
        deviceName: serial || 'USB Android',
      });
      return;
    }
    const host = ($('castHostInput')?.value || '').trim();
    const port = Number($('castPortInput')?.value) || 19878;
    if (!host) {
      this.app.showToast(t('companion.errNeedHost'));
      return;
    }
    await this.connectTarget({ host, port, transport: 'wifi', deviceName: host });
  }

  async connectTarget(target, { silent = false } = {}) {
    if (this.app.authenticated) return;
    this.disconnect('', { quiet: true });
    this.target = { ...target };
    this.renderCompanionHub('connecting');

    try {
      let wsUrl = '';
      const isUsb = target.transport === 'usb';
      if (this.desktop.enabled) {
        if (isUsb) {
          const fwd = await this.desktop.bootstrapAndForwardUsb(target.adbSerial || '');
          this.target.port = fwd.localPort;
          wsUrl = fwd.localWsUrl;
        } else {
          const tunnel = await this.app.ensureDesktopTlsTunnel(
            target.host,
            target.port || 19878,
            '/ws',
            target.certFingerprint || ''
          );
          if (!tunnel) {
            this.disconnect('', { quiet: true });
            return;
          }
          this.target.certFingerprint = tunnel.verifiedFingerprint;
          wsUrl = tunnel.localWsUrl;
        }
      } else {
        wsUrl = isUsb
          ? `ws://127.0.0.1:${target.port || 19878}/ws`
          : `wss://${target.host}:${target.port || 19878}/ws`;
      }

      const authToken = isUsb
        ? ''
        : (this.desktop.enabled && this.desktop.getTrustedToken(target.host, target.port || 19878)) || '';

      const ws = new WebSocket(wsUrl);
      ws.binaryType = 'arraybuffer';
      this.ws = ws;

      ws.onopen = () => {
        this.connected = true;
        const hello = encodeHello({
          clientName: this.desktop.enabled ? 'OpalDesk Windows' : 'OpalDesk Web',
          clientId: this.app.clientId,
          width: 720,
          height: 1280,
          dpi: 240,
          fps: 30,
          bitrateKbps: 2500,
          audioEnabled: false,
          authToken,
          transportHint: isUsb ? 'usb' : 'wifi_tls',
          clientPlatform: this.desktop.enabled ? 'windows' : 'web',
          sessionRole: SESSION_ROLE_COMPANION,
          companionEvents: true,
        });
        ws.send(encodeFrame(TYPE_HELLO, 0, undefined, hello));
      };

      ws.onmessage = (ev) => {
        if (!(ev.data instanceof ArrayBuffer)) return;
        let frame;
        try {
          frame = decodeFrame(ev.data);
        } catch {
          return;
        }
        this.handleCompanionSocketFrame(frame);
      };

      ws.onerror = () => {
        if (!silent) this.app.showToast(t('companion.errConnectFailed'));
      };

      ws.onclose = () => {
        if (this.ws === ws) {
          this.disconnect('', { quiet: true });
        }
      };
    } catch (err) {
      this.disconnect('', { quiet: true });
      if (!silent) this.app.showToast(t('companion.errConnectDetail', { error: err?.message || err }));
    }
  }

  disconnect(toastMsg = '', { quiet = false } = {}) {
    clearInterval(this.pingTimer);
    this.pingTimer = null;
    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onerror = null;
      this.ws.onclose = null;
      try { this.ws.close(); } catch {}
      this.ws = null;
    }
    this.connected = false;
    this.authenticated = false;
    if (!this.app.authenticated) {
      this.app.fileBrowserClient?.reset(t('companion.resetDisconnected'));
      this.clearCompanionState();
    }
    this.renderCompanionHub();
    this._syncTrayStatus();
    if (toastMsg && !quiet) this.app.showToast(toastMsg);
  }

  handleCompanionSocketFrame(frame) {
    if (this.app.fileBrowserClient?.handleFrame(frame)) return;
    switch (frame.type) {
      case TYPE_AUTH_RESULT: {
        const auth = decodeAuthResult(frame.payload);
        if (!auth.ok) {
          this.disconnect(auth.message || t('companion.errRejected'));
          return;
        }
        this.authenticated = true;
        if (auth.deviceName && this.target) this.target.deviceName = auth.deviceName;
        if (this.target?.transport !== 'usb' && auth.authToken && this.desktop.enabled && this.target?.certFingerprint) {
          this.desktop.upsertTrustedDevice({
            host: this.target.host,
            port: this.target.port || 19878,
            displayName: auth.deviceName || this.target.host,
            model: '',
            certFingerprint: this.target.certFingerprint,
            authToken: auth.authToken,
            permanent: Boolean(auth.permanent),
            lastConnectedAtMs: Date.now(),
          }).catch(() => {});
        }
        this.sendControlFrame(TYPE_NOTIFICATION_ACTION, 0, encodeNotificationAction({ op: 'sync' }));
        clearInterval(this.pingTimer);
        this.pingTimer = setInterval(() => {
          if (this.isCompanionOnline) {
            this.sendControlFrame(TYPE_PING, 0, encodePingPong(this.pingSeq++));
          }
        }, 12_000);
        this.renderCompanionHub();
        this._syncTrayStatus();
        break;
      }
      case TYPE_DEVICE_STATE:
        this.handleDeviceState(decodeDeviceState(frame.payload));
        break;
      case TYPE_CLIPBOARD_PUSH: {
        const clip = decodeClipboardPush(frame.payload, frame.flags);
        if (!clip.isImage && clip.text) {
          navigator.clipboard?.writeText(clip.text).catch(() => {});
        }
        break;
      }
      case TYPE_NOTIFICATION_EVENT:
        this.handleNotificationFrame(frame.payload);
        break;
      case TYPE_MEDIA_STATE:
        this.handleMediaStateFrame(frame.payload);
        break;
      case TYPE_CALL_STATE:
        this.handleCallStateFrame(frame.payload);
        break;
      case TYPE_PONG:
        break;
      default:
        break;
    }
  }

  // ==================== SHARED DOWNLINK HANDLERS (DESKTOP CAST + COMPANION) ====================

  handleDeviceState(state) {
    this.peerDeviceState = state;
    this.handoff = resolveHandoffInfo(state);
    this.renderCompanionHub();
  }

  handleNotificationFrame(payload) {
    let evt;
    try {
      evt = decodeNotificationEvent(payload);
    } catch {
      return;
    }
    const { added } = this.notifications.applyEvent(evt);
    if (evt.action === 'posted' && added.length > 0 && this.desktop.enabled) {
      for (const item of added.slice(0, 3)) {
        void this.desktop.sendNativeNotification({
          title: item.title || item.appLabel || t('companion.nativeNotifTitle'),
          body: item.otpCode ? t('companion.nativeNotifOtpBody', { code: item.otpCode, text: item.text }) : item.text,
          appLabel: item.appLabel || item.pkg || 'OpalDesk',
          tag: item.key,
        });
      }
    }
    this.renderCompanionHub();
    this._syncTrayStatus();
  }

  handleMediaStateFrame(payload) {
    try {
      this.mediaState = decodeMediaState(payload);
    } catch {
      return;
    }
    this.renderCompanionHub();
  }

  handleCallStateFrame(payload) {
    let state;
    try {
      state = decodeCallState(payload);
    } catch {
      return;
    }
    this.callState = state;
    if (!state.isActive) {
      this.dismissedCallKey = '';
      clearInterval(this.callTimer);
      this.callTimer = null;
    } else if (state.state === 'offhook' && !this.callTimer) {
      this.callTimer = setInterval(() => this._refreshCallDurationTick(), 1000);
    }
    this.syncCallHudDisplay();
    this.renderCompanionHub();
    this._syncTrayStatus();
  }

  clearCompanionState() {
    this.notifications.clearAll();
    this.mediaState = null;
    this.callState = null;
    this.handoff = null;
    this.peerDeviceState = null;
    this.dismissedCallKey = '';
    clearInterval(this.callTimer);
    this.callTimer = null;
    this.syncCallHudDisplay();
  }

  // ==================== ACTIONS ====================

  sendMediaAction(op, positionMs = null) {
    this.sendControlFrame(TYPE_MEDIA_ACTION, 0, encodeMediaAction({ op, positionMs }));
  }

  dismissNotification(key) {
    this.notifications.dismissLocal(key);
    this.sendControlFrame(TYPE_NOTIFICATION_ACTION, 0, encodeNotificationAction({ op: 'dismiss', key }));
    this.renderCompanionHub();
    this._syncTrayStatus();
  }

  clearAllNotifications() {
    this.notifications.clearLocal();
    this.sendControlFrame(TYPE_NOTIFICATION_ACTION, 0, encodeNotificationAction({ op: 'clear_all' }));
    this.renderCompanionHub();
    this._syncTrayStatus();
  }

  invokeNotificationAction(key, actionIndex) {
    this.sendControlFrame(
      TYPE_NOTIFICATION_ACTION,
      0,
      encodeNotificationAction({ op: 'invoke_action', key, actionIndex })
    );
    this.app.showToast(t('companion.notifActionTriggered'));
  }

  replyToNotification(key, text) {
    const clean = String(text || '').trim();
    if (!clean) return;
    this.sendControlFrame(
      TYPE_NOTIFICATION_ACTION,
      0,
      encodeNotificationAction({ op: 'reply', key, replyText: clean })
    );
    this.app.showToast(t('companion.notifReplySent'));
  }

  handleCallHudAction(action) {
    const op = String(action?.op || '');
    if (op === 'dismiss_hud') {
      this.dismissedCallKey = callSessionKey(this.callState);
      this.syncCallHudDisplay();
      return;
    }
    if (!op) return;
    this.sendControlFrame(
      TYPE_CALL_ACTION,
      0,
      encodeCallAction({ op, number: action.number || null, smsText: action.smsText || null })
    );
  }

  // ==================== CALL HUD DISPLAY ====================

  syncCallHudDisplay() {
    const show = shouldShowCallHud(this.callState, this.dismissedCallKey);
    if (this.desktop.enabled) {
      if (show) {
        void this.desktop.updateCallHudState(this.callState).catch(() => {});
      } else {
        void this.desktop.hideCallHud().catch(() => {});
      }
      if ($('webCallHud')) $('webCallHud').hidden = true;
      return;
    }

    // Web browser fallback HUD
    const hud = $('webCallHud');
    if (!hud) return;
    hud.hidden = !show;
    if (!show || !this.callState) return;
    const st = this.callState;
    if ($('webCallHudCaller')) $('webCallHudCaller').textContent = st.displayCaller;
    if ($('webCallHudSub')) {
      $('webCallHudSub').textContent =
        st.state === 'ringing' ? `${t('call.ringing')} · ${st.number || ''}`
          : st.state === 'dialing' ? `${t('call.dialing')} · ${st.number || ''}`
            : `${t('call.offhook')} · ${formatCallDuration(st.startedAtMs)}`;
    }
    const isRinging = st.state === 'ringing';
    if ($('webCallHudAnswerBtn')) $('webCallHudAnswerBtn').hidden = !isRinging;
    if ($('webCallHudRejectBtn')) $('webCallHudRejectBtn').hidden = !isRinging;
    if ($('webCallHudMuteBtn')) {
      $('webCallHudMuteBtn').hidden = isRinging;
      $('webCallHudMuteBtn').classList.toggle('active', st.isMuted);
      $('webCallHudMuteBtn').textContent = st.isMuted ? t('call.mutedBtn') : t('call.muteBtn');
    }
    if ($('webCallHudSpeakerBtn')) {
      $('webCallHudSpeakerBtn').hidden = isRinging;
      $('webCallHudSpeakerBtn').classList.toggle('active', st.isSpeakerOn);
      $('webCallHudSpeakerBtn').textContent = st.isSpeakerOn ? t('call.speakerOnBtn') : t('call.speakerBtn');
    }
    if ($('webCallHudHangupBtn')) $('webCallHudHangupBtn').hidden = isRinging;
  }

  _refreshCallDurationTick() {
    if (!this.callState || this.callState.state !== 'offhook') return;
    const dur = formatCallDuration(this.callState.startedAtMs);
    if ($('webCallHudSub') && !$('webCallHud')?.hidden) {
      $('webCallHudSub').textContent = `${t('call.offhook')} · ${dur}`;
    }
    const bannerDur = $('companionCallTimer');
    if (bannerDur) bannerDur.textContent = dur;
  }

  // ==================== T9 QUICK DIALER ====================

  _renderT9Keypad() {
    const grid = $('dialerKeypadGrid');
    if (!grid) return;
    grid.textContent = '';
    for (const key of T9_DIALPAD_KEYS) {
      const btn = createEl('button', 'dialer-key-btn');
      btn.type = 'button';
      btn.append(createEl('span', 'dialer-key-digit', key.digit));
      if (key.letters) btn.append(createEl('span', 'dialer-key-letters', key.letters));
      btn.addEventListener('click', () => {
        const inp = $('dialerNumberInput');
        if (!inp) return;
        inp.value = sanitizeDialNumber(inp.value + key.digit);
        inp.focus();
      });
      grid.appendChild(btn);
    }
  }

  openDialerModal() {
    if (!this.hasControlChannel()) {
      this.app.showToast(t('companion.dialerNeedConnect'));
      return;
    }
    if ($('dialerModalOverlay')) {
      $('dialerModalOverlay').hidden = false;
      $('dialerNumberInput')?.focus();
    }
  }

  closeDialerModal() {
    if ($('dialerModalOverlay')) $('dialerModalOverlay').hidden = true;
  }

  submitDialerCall() {
    const num = sanitizeDialNumber($('dialerNumberInput')?.value || '');
    if (!num) {
      this.app.showToast(t('companion.dialerNeedNumber'));
      return;
    }
    this.sendControlFrame(TYPE_CALL_ACTION, 0, encodeCallAction({ op: 'dial', number: num }));
    this.app.showToast(t('companion.dialerSentToast', { number: num }));
    this.closeDialerModal();
  }

  // ==================== BLE NEARBY & INSTANT HOTSPOT ====================

  async refreshBleNearby(showToast = false) {
    const card = $('bleNearbyCard');
    if (!this.desktop.enabled || !card) return;
    try {
      const res = await this.desktop.scanBleNearby();
      this.bleSupported = Boolean(res?.supported);
      this.bleDevices = Array.isArray(res?.devices) ? res.devices : [];
      this.bleNote = String(res?.note || '');
      this.renderBleNearbyCard();
      if (showToast) {
        this.app.showToast(
          this.bleDevices.length
            ? t('companion.bleFoundToast', { count: this.bleDevices.length })
            : (this.bleNote || t('companion.bleNotFoundToast'))
        );
      }
    } catch {
      this.renderBleNearbyCard();
    }
  }

  renderBleNearbyCard() {
    const card = $('bleNearbyCard');
    const list = $('bleNearbyList');
    const note = $('bleNearbyNote');
    if (!card || !list) return;
    card.hidden = !this.desktop.enabled;
    if (note) note.textContent = this.bleNote || (this.bleSupported ? t('companion.bleReady') : t('companion.bleUnavailable'));
    list.textContent = '';

    if (!this.bleDevices.length) {
      list.appendChild(createEl('div', 'hint-text', t('companion.bleEmpty')));
      return;
    }

    for (const dev of this.bleDevices) {
      const trustedMatch = (this.desktop.trustedDevices || []).find(
        (t) => dev.certFingerprintPrefix && t.certFingerprint?.toLowerCase().startsWith(dev.certFingerprintPrefix.toLowerCase())
      );
      const item = createEl('div', 'device-card-row');
      const info = createEl('div', 'device-card-info');
      const title = createEl('div', 'device-card-title', trustedMatch?.displayName || dev.name || 'OpalDesk Android');
      const batt = dev.batteryPct >= 0 ? t('companion.bleBattery', { pct: dev.batteryPct }) : '';
      const hotspotTag = dev.hotspotActive ? t('companion.bleHotspotActive') : '';
      const sub = createEl('div', 'device-card-sub mono', `RSSI ${dev.rssi} dBm${batt}${hotspotTag}`);
      info.append(title, sub);

      const actions = createEl('div', 'device-card-actions');
      if (dev.canTriggerHotspot && trustedMatch?.authToken) {
        const hsBtn = createEl(
          'button',
          'btn-pill small primary',
          this.bleBusyAddress === dev.address ? t('companion.bleWaking') : t('companion.bleWakeBtn')
        );
        hsBtn.type = 'button';
        hsBtn.disabled = Boolean(this.bleBusyAddress);
        hsBtn.addEventListener('click', () => void this.triggerHotspotForBleDevice(dev, trustedMatch));
        actions.appendChild(hsBtn);
      } else if (!trustedMatch?.authToken) {
        actions.appendChild(createEl('span', 'status-pill', t('companion.bleNeedTrust')));
      }
      item.append(info, actions);
      list.appendChild(item);
    }
  }

  async triggerHotspotForBleDevice(bleDev, trustedDev) {
    this.bleBusyAddress = bleDev.address;
    this.renderBleNearbyCard();
    try {
      const res = await this.desktop.triggerBleHotspot({
        address: bleDev.address,
        certFingerprintPrefix: bleDev.certFingerprintPrefix,
        clientId: this.app.clientId,
        authToken: trustedDev.authToken,
      });
      this.app.showToast(res?.message || t('companion.bleWakeSentToast'));
    } catch (err) {
      this.app.showToast(t('companion.bleWakeFailToast', { error: err?.message || err }));
    } finally {
      this.bleBusyAddress = '';
      this.renderBleNearbyCard();
    }
  }

  // ==================== DEEP LINK HANDLER (opaldesk://) ====================

  handleDeepLinkUrl(rawUrl) {
    try {
      const u = new URL(rawUrl);
      if (u.protocol !== 'opaldesk:') return;
      const host = u.searchParams.get('host') || '';
      const port = Number(u.searchParams.get('port') || 19878);
      const pkg = u.searchParams.get('pkg') || '';
      const cls = u.searchParams.get('cls') || '';
      const label = u.searchParams.get('appLabel') || pkg;
      const userId = Math.max(0, Math.round(Number(u.searchParams.get('userId') || 0)));
      const profileType = u.searchParams.get('profileType') || '';
      const deviceKey = u.searchParams.get('deviceKey') || `${host}:${port}`;
      const deviceName = u.searchParams.get('deviceName') || host;
      const fp = u.searchParams.get('fp') || '';
      const transport = u.searchParams.get('transport') || 'wifi';
      if (pkg && (host || transport === 'usb')) {
        void this.app.standalone.requestLaunchStandaloneApp(
          { pkg, cls, label, userId, profileType },
          { host, port, transport, deviceKey, deviceName, certFingerprint: fp }
        );
      }
    } catch {
      // Ignore malformed deep links
    }
  }

  // ==================== LOBBY COMPANION HUB RENDERER ====================

  renderCompanionHub(overrideState = '') {
    const card = $('companionHubCard');
    if (!card) return;
    const online = this.hasControlChannel();
    const connecting = overrideState === 'connecting';

    const statusPill = $('companionStatusPill');
    if (statusPill) {
      if (this.app.authenticated) {
        statusPill.textContent = t('companion.statusCasting', { name: this.activePeerName });
        statusPill.className = 'status-pill ok';
      } else if (this.isCompanionOnline) {
        const batt = this.peerDeviceState?.batteryPct >= 0 ? ` · ${this.peerDeviceState.batteryPct}%` : '';
        statusPill.textContent = t('companion.statusOnline', { name: this.activePeerName, batt });
        statusPill.className = 'status-pill ok';
      } else if (connecting) {
        statusPill.textContent = t('companion.statusConnecting');
        statusPill.className = 'status-pill warn';
      } else {
        statusPill.textContent = t('companion.statusIdle');
        statusPill.className = 'status-pill';
      }
    }

    if ($('companionConnectBtn')) $('companionConnectBtn').hidden = online || connecting;
    if ($('companionDisconnectBtn')) $('companionDisconnectBtn').hidden = !this.isCompanionOnline;
    if ($('companionBody')) $('companionBody').hidden = !online;
    if (!online) return;

    // 1. Handoff card
    const handoffWrap = $('companionHandoffRow');
    if (handoffWrap) {
      handoffWrap.hidden = !this.handoff;
      if (this.handoff && $('companionHandoffApp')) {
        $('companionHandoffApp').textContent = this.handoff.label;
      }
    }

    // 2. OTP quick copy bar
    const otp = this.notifications.getLatestOtp();
    const otpBar = $('companionOtpBar');
    if (otpBar) {
      otpBar.hidden = !otp;
      if (otp) {
        if ($('companionOtpCode')) $('companionOtpCode').textContent = otp.code;
        if ($('companionOtpFrom')) $('companionOtpFrom').textContent = otp.appLabel;
      }
    }

    // 3. Live Call banner
    const callBanner = $('companionCallBanner');
    if (callBanner) {
      const activeCall = this.callState?.isActive;
      callBanner.hidden = !activeCall;
      if (activeCall) {
        this._renderCompanionCallBanner(callBanner, this.callState);
      }
    }

    // 4. Media remote
    const mediaBox = $('companionMediaBox');
    if (mediaBox) {
      const m = this.mediaState;
      mediaBox.hidden = !(m && m.active);
      if (m && m.active) {
        if ($('companionMediaTitle')) $('companionMediaTitle').textContent = m.title || t('companion.mediaNowPlaying');
        if ($('companionMediaArtist')) {
          $('companionMediaArtist').textContent = [m.artist, m.appLabel].filter(Boolean).join(' · ');
        }
        if ($('companionMediaPlayBtn')) {
          $('companionMediaPlayBtn').textContent = m.playing ? t('companion.mediaPause') : t('companion.mediaPlay');
        }
        const art = $('companionMediaArt');
        if (art) {
          if (m.artworkBase64) {
            art.src = m.artworkBase64.startsWith('data:') ? m.artworkBase64 : `data:image/jpeg;base64,${m.artworkBase64}`;
            art.hidden = false;
          } else {
            art.hidden = true;
          }
        }
      }
    }

    // 5. Notifications list
    this._renderNotificationsList();
  }

  _renderCompanionCallBanner(container, call) {
    container.textContent = '';
    const left = createEl('div', 'companion-call-info');
    const badge = createEl(
      'span',
      `status-pill ${call.state === 'ringing' ? 'warn' : 'ok'}`,
      call.state === 'ringing' ? t('call.ringing') : call.state === 'dialing' ? t('call.dialing') : t('call.offhook')
    );
    const caller = createEl('strong', '', call.displayCaller);
    const timer = createEl('span', 'mono muted', call.state === 'offhook' ? formatCallDuration(call.startedAtMs) : call.number);
    timer.id = 'companionCallTimer';
    left.append(badge, caller, timer);

    const btns = createEl('div', 'companion-call-btns');
    if (call.state === 'ringing') {
      const ans = createEl('button', 'btn-pill small emerald', t('call.answerBtn'));
      ans.type = 'button';
      ans.addEventListener('click', () => this.handleCallHudAction({ op: 'answer' }));
      const rej = createEl('button', 'btn-pill small danger', t('call.rejectBtn'));
      rej.type = 'button';
      rej.addEventListener('click', () => this.handleCallHudAction({ op: 'reject' }));
      const sms = createEl('button', 'btn-pill small', t('call.smsRejectBtn'));
      sms.type = 'button';
      sms.addEventListener('click', () => this.handleCallHudAction({ op: 'reject_sms', smsText: getSmsRejectTemplates()[0] }));
      btns.append(ans, rej, sms);
    } else {
      const mute = createEl('button', `btn-pill small${call.isMuted ? ' primary' : ''}`, call.isMuted ? t('call.mutedBtn') : t('call.muteBtn'));
      mute.type = 'button';
      mute.addEventListener('click', () => this.handleCallHudAction({ op: 'toggle_mute' }));
      const spk = createEl('button', `btn-pill small${call.isSpeakerOn ? ' primary' : ''}`, call.isSpeakerOn ? t('call.speakerOnBtn') : t('call.speakerBtn'));
      spk.type = 'button';
      spk.addEventListener('click', () => this.handleCallHudAction({ op: 'toggle_speaker' }));
      const hang = createEl('button', 'btn-pill small danger', t('call.hangupBtn'));
      hang.type = 'button';
      hang.addEventListener('click', () => this.handleCallHudAction({ op: 'hangup' }));
      btns.append(mute, spk, hang);
    }
    container.append(left, btns);
  }

  _renderNotificationsList() {
    const list = $('companionNotifList');
    const countEl = $('companionNotifCount');
    if (!list) return;
    const items = this.notifications.getItems();
    if (countEl) countEl.textContent = String(items.length);
    if ($('companionClearNotifsBtn')) $('companionClearNotifsBtn').hidden = items.length === 0;

    list.textContent = '';
    if (!items.length) {
      list.appendChild(createEl('div', 'hint-text', t('companion.notifEmpty')));
      return;
    }

    for (const item of items.slice(0, 20)) {
      const card = createEl('div', 'companion-notif-item');
      const head = createEl('div', 'companion-notif-head');
      head.append(
        createEl('span', 'companion-notif-app', item.appLabel || item.pkg),
        createEl('span', 'companion-notif-title', item.title)
      );
      if (item.clearable) {
        const closeBtn = createEl('button', 'hist-icon-btn remove', '×');
        closeBtn.type = 'button';
        closeBtn.title = t('companion.notifClearTitle');
        closeBtn.addEventListener('click', () => this.dismissNotification(item.key));
        head.appendChild(closeBtn);
      }

      const body = createEl('div', 'companion-notif-text', item.text);
      card.append(head, body);

      // Action row: OTP copy + generic Android notification actions + Open App + Inline Reply
      const hasFooter = Boolean(item.otpCode || item.actions.length || item.canReply || item.pkg);
      if (hasFooter) {
        const footer = createEl('div', 'companion-notif-actions');
        if (item.otpCode) {
          const otpBtn = createEl('button', 'btn-pill small primary', t('companion.notifCopyOtp', { code: item.otpCode }));
          otpBtn.type = 'button';
          otpBtn.addEventListener('click', () => this.app.copyText(item.otpCode, t('companion.otpCopiedToast', { code: item.otpCode })));
          footer.appendChild(otpBtn);
        }
        for (const act of item.actions.slice(0, 3)) {
          const actBtn = createEl('button', 'btn-pill small', act.label);
          actBtn.type = 'button';
          actBtn.addEventListener('click', () => this.invokeNotificationAction(item.key, act.index));
          footer.appendChild(actBtn);
        }
        if (item.pkg) {
          const popBtn = createEl('button', 'btn-pill small ghost', t('companion.notifPopout'));
          popBtn.type = 'button';
          popBtn.addEventListener('click', () => this.app.launchPopoutByPackage(item.pkg, item.appLabel || item.pkg));
          footer.appendChild(popBtn);
        }
        card.appendChild(footer);
      }

      if (item.canReply) {
        const replyRow = createEl('div', 'companion-reply-row');
        const inp = document.createElement('input');
        inp.type = 'text';
        inp.placeholder = t('companion.notifReplyPlaceholder', { label: item.replyLabel || t('companion.notifReplyDefault') });
        const sendBtn = createEl('button', 'btn-pill small primary', t('common.send'));
        sendBtn.type = 'button';
        const submit = () => {
          const val = inp.value.trim();
          if (!val) return;
          this.replyToNotification(item.key, val);
          inp.value = '';
        };
        sendBtn.addEventListener('click', submit);
        inp.addEventListener('keydown', (e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            submit();
          }
        });
        replyRow.append(inp, sendBtn);
        card.appendChild(replyRow);
      }

      list.appendChild(card);
    }
  }

  _syncTrayStatus() {
    if (!this.desktop.enabled) return;
    void this.desktop.updateTrayStatus({
      connected: this.hasControlChannel(),
      deviceName: this.activePeerName,
      unreadCount: this.notifications.getItems().length,
      callActive: Boolean(this.callState?.isActive),
    });
  }
}
