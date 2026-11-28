/**
 * OpalDesk Desktop Native Bridge (Tauri v2 / Windows WebView2).
 * Activates exclusively when running inside the Tauri desktop shell,
 * leaving pure GitHub Pages / Web browser behavior unchanged when absent.
 */

import {
  buildStandaloneWindowQuery,
  resolveTauriInvoke,
  sanitizeCustomAdbPath,
} from './desktop_bridge_policy.js';

export * from './desktop_bridge_policy.js';

export class OpalDesktopBridge {
  constructor(win = globalThis.window) {
    this.invokeFn = resolveTauriInvoke(win);
    this.enabled = this.invokeFn !== null;
    this.runtimeInfo = null;
    this.clientPrefs = null;
    this.mdnsDevices = [];
    this.usbTetherDevices = [];
    this.trustedDevices = [];
    this.adbDevices = [];
    this.adbStatus = { available: false, adbPath: null, customAdbPath: null };
    this.pollTimer = null;
  }

  async invoke(cmd, args = {}) {
    if (!this.invokeFn) {
      throw new Error('当前不处于桌面客户端环境');
    }
    return this.invokeFn(cmd, args);
  }

  async initialize() {
    if (!this.enabled) return null;
    this.runtimeInfo = await this.invoke('desktop_runtime_info');
    this.adbStatus = this.runtimeInfo?.adb || this.adbStatus;
    this.clientPrefs = this.runtimeInfo?.prefs || null;
    await Promise.allSettled([this.refreshTrustedDevices(), this.pollMdnsDevices()]);
    return this.runtimeInfo;
  }

  async updateClientPrefs(patch) {
    if (!this.enabled) return null;
    try {
      this.clientPrefs = await this.invoke('update_client_prefs', { patch });
      return this.clientPrefs;
    } catch {
      return null;
    }
  }

  startDiscoveryPolling(onUpdate, intervalMs = 2500) {
    if (!this.enabled) return;
    this.stopDiscoveryPolling();
    const tick = async () => {
      try {
        const list = await this.pollMdnsDevices();
        if (typeof onUpdate === 'function') onUpdate(list);
      } catch {
        // Ignore transient mDNS poll failures
      }
    };
    tick();
    this.pollTimer = setInterval(tick, intervalMs);
  }

  stopDiscoveryPolling() {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  async pollMdnsDevices(forceRebrowse = false) {
    if (!this.enabled) return [];
    const cmd = forceRebrowse ? 'refresh_mdns_devices' : 'list_mdns_devices';
    const list = await this.invoke(cmd);
    this.mdnsDevices = Array.isArray(list) ? list : [];
    return this.mdnsDevices;
  }

  async refreshTrustedDevices() {
    if (!this.enabled) return [];
    const list = await this.invoke('list_trusted_devices');
    this.trustedDevices = Array.isArray(list) ? list : [];
    return this.trustedDevices;
  }

  getTrustedToken(host, port) {
    const cleanHost = String(host || '').trim();
    const numPort = Number(port) || 19878;
    const hit = this.trustedDevices.find(
      (d) => d.host === cleanHost && Number(d.port) === numPort && d.authToken
    );
    return hit?.authToken || '';
  }

  async upsertTrustedDevice(device) {
    const saved = await this.invoke('upsert_trusted_device', { device });
    await this.refreshTrustedDevices();
    return saved;
  }

  async revokeTrustedDevice(id) {
    await this.invoke('revoke_trusted_device', { id });
    await this.refreshTrustedDevices();
  }

  async probeCertificate(host, port, timeoutMs = 4500) {
    return this.invoke('probe_tls_certificate', {
      host: String(host || '').trim(),
      port: Number(port) || 19878,
      timeoutMs,
    });
  }

  async startTlsWsTunnel(host, port, path, expectedFingerprint) {
    return this.invoke('start_tls_ws_tunnel', {
      host: String(host || '').trim(),
      port: Number(port) || 19878,
      path,
      expectedFingerprint,
    });
  }

  async refreshAdbStatus() {
    this.adbStatus = await this.invoke('get_adb_status');
    if (this.clientPrefs && typeof this.clientPrefs === 'object' && this.adbStatus) {
      this.clientPrefs = {
        ...this.clientPrefs,
        customAdbPath: this.adbStatus.customAdbPath || null,
      };
    }
    return this.adbStatus;
  }

  async setCustomAdbPath(customPath) {
    const cleaned = sanitizeCustomAdbPath(customPath);
    this.adbStatus = await this.invoke('set_custom_adb_path', {
      path: cleaned || null,
    });
    if (this.clientPrefs && typeof this.clientPrefs === 'object') {
      this.clientPrefs = {
        ...this.clientPrefs,
        customAdbPath: this.adbStatus?.customAdbPath || null,
      };
    }
    return this.adbStatus;
  }

  async scanAdbDevices() {
    const list = await this.invoke('list_adb_devices');
    this.adbDevices = Array.isArray(list) ? list : [];
    return this.adbDevices;
  }

  async scanUsbTetherDevices(port = 19878) {
    if (!this.enabled) return [];
    const list = await this.invoke('scan_usb_tether_devices', {
      port: Number(port) || 19878,
    });
    this.usbTetherDevices = Array.isArray(list) ? list : [];
    return this.usbTetherDevices;
  }

  async bootstrapAndForwardUsb(serial, preferredPort = null) {
    return this.invoke('adb_bootstrap_and_forward', {
      serial: String(serial || '').trim(),
      preferredPort: preferredPort ? Number(preferredPort) : null,
    });
  }

  async openStandaloneWindow({
    pkg,
    cls = '',
    label = '',
    userId = 0,
    profileType = '',
    host,
    port,
    transport = 'wifi',
    usbLocalPort = 0,
    deviceKey = '',
    deviceName = '',
    certFingerprint = '',
    alwaysOnTop = false,
    forceReload = false,
    width = 440,
    height = 880,
    preset = '',
    dpi = 0,
    compat = false,
  }) {
    const normalizedUid = Math.max(0, Math.round(Number(userId) || 0));
    const query = buildStandaloneWindowQuery({
      host,
      port,
      transport,
      usbLocalPort,
      pkg,
      cls,
      label,
      userId: normalizedUid,
      profileType,
      deviceKey,
      deviceName,
      certFingerprint,
      alwaysOnTop,
      preset,
      dpi,
      compat,
    });
    return this.invoke('open_standalone_window', {
      pkg,
      userId: normalizedUid,
      title: label || pkg,
      query,
      width,
      height,
      alwaysOnTop: Boolean(alwaysOnTop),
      forceReload: Boolean(forceReload),
    });
  }

  async listStandaloneWindows() {
    if (!this.enabled) return [];
    const list = await this.invoke('list_standalone_windows');
    return Array.isArray(list) ? list : [];
  }

  async focusStandaloneWindow(pkgOrLabel) {
    if (!this.enabled) return false;
    return this.invoke('focus_standalone_window', { pkgOrLabel: String(pkgOrLabel || '') });
  }

  async closeStandaloneWindow(pkgOrLabel) {
    if (!this.enabled) return;
    return this.invoke('close_standalone_window', { pkgOrLabel: String(pkgOrLabel || '') });
  }

  async closeAllStandaloneWindows() {
    if (!this.enabled) return;
    return this.invoke('close_all_standalone_windows');
  }

  async setCurrentWindowAlwaysOnTop(alwaysOnTop) {
    if (!this.enabled) return false;
    return this.invoke('set_current_window_always_on_top', { alwaysOnTop: Boolean(alwaysOnTop) });
  }

  async getCurrentWindowAlwaysOnTop() {
    if (!this.enabled) return false;
    return this.invoke('get_current_window_always_on_top');
  }

  async setStandaloneWindowAlwaysOnTop(pkgOrLabel, alwaysOnTop) {
    if (!this.enabled) return false;
    return this.invoke('set_standalone_window_always_on_top', {
      pkgOrLabel: String(pkgOrLabel || ''),
      alwaysOnTop: Boolean(alwaysOnTop),
    });
  }

  async closeCurrentWindow() {
    if (!this.enabled) return;
    return this.invoke('close_current_window');
  }

  async appendClientLog(level, tag, message) {
    if (!this.enabled) return null;
    try {
      return await this.invoke('append_client_log', {
        level: String(level || 'I'),
        tag: String(tag || 'Web'),
        message: String(message || ''),
      });
    } catch {
      return null;
    }
  }

  async getClientLogs() {
    if (!this.enabled) return [];
    const list = await this.invoke('get_client_logs');
    return Array.isArray(list) ? list : [];
  }

  async clearClientLogs() {
    if (!this.enabled) return;
    return this.invoke('clear_client_logs');
  }

  async buildDiagnosticReport({ contextSummary = '', warningsOnly = false, query = '' } = {}) {
    return this.invoke('build_diagnostic_report', {
      contextSummary: String(contextSummary || ''),
      warningsOnly: Boolean(warningsOnly),
      query: String(query || ''),
    });
  }

  async exportDiagnosticReport(content) {
    return this.invoke('export_diagnostic_report', { content: String(content || '') });
  }

  async getFeedbackState() {
    return this.invoke('get_feedback_state');
  }

  async submitFeedbackReport({
    reporterName = '',
    problemDescription = '',
    kind = 'bug',
    includeLogs = true,
    contextSummary = '',
  }) {
    return this.invoke('submit_feedback_report', {
      reporterName: String(reporterName || ''),
      problemDescription: String(problemDescription || ''),
      kind: String(kind || 'bug'),
      includeLogs: Boolean(includeLogs),
      contextSummary: String(contextSummary || ''),
    });
  }

  async refreshFeedbackStatuses(force = false) {
    return this.invoke('refresh_feedback_statuses', { force: Boolean(force) });
  }

  async lookupFeedbackByCode(code) {
    return this.invoke('lookup_feedback_by_code', { code: String(code || '') });
  }

  async removeFeedbackRecord(eventId) {
    return this.invoke('remove_feedback_record', { eventId: String(eventId || '') });
  }

  async clearFeedbackHistory() {
    return this.invoke('clear_feedback_history');
  }

  // ---- v1.2 / v1.3 Windows Parity Native Commands ----

  async saveBinaryFile({ fileName, bytes, targetDir = 'desktop' }) {
    if (!this.enabled) return null;
    const data = bytes instanceof Uint8Array ? Array.from(bytes) : Array.from(new Uint8Array(bytes || 0));
    return this.invoke('save_binary_file', {
      fileName: String(fileName || 'opaldesk_file.bin'),
      data,
      targetDir: String(targetDir || 'desktop'),
    });
  }

  async beginFileDownload({ fileName, subdir = 'downloads' }) {
    return this.invoke('begin_file_download', {
      fileName: String(fileName || 'download.bin'),
      subdir: String(subdir || 'downloads'),
    });
  }

  async writeFileDownloadChunk({ sessionId, bytes }) {
    const data = bytes instanceof Uint8Array ? Array.from(bytes) : Array.from(new Uint8Array(bytes || 0));
    return this.invoke('write_file_download_chunk', {
      sessionId: String(sessionId || ''),
      data,
    });
  }

  async finishFileDownload({ sessionId }) {
    return this.invoke('finish_file_download', { sessionId: String(sessionId || '') });
  }

  async abortFileDownload({ sessionId }) {
    return this.invoke('abort_file_download', { sessionId: String(sessionId || '') });
  }

  async revealInExplorer(path) {
    if (!this.enabled || !path) return;
    return this.invoke('reveal_in_explorer', { path: String(path) });
  }

  async openFileInOs(path) {
    if (!this.enabled || !path) return;
    return this.invoke('open_file_in_os', { path: String(path) });
  }

  async setCurrentWindowSize(width, height) {
    if (!this.enabled) return false;
    return this.invoke('set_current_window_size', {
      width: Math.max(320, Math.round(Number(width) || 460)),
      height: Math.max(400, Math.round(Number(height) || 860)),
    });
  }

  async updateCallHudState(callState) {
    if (!this.enabled) return false;
    return this.invoke('update_call_hud_state', { callState });
  }

  async hideCallHud() {
    if (!this.enabled) return false;
    return this.invoke('hide_call_hud');
  }

  async dispatchCallHudAction(action) {
    if (!this.enabled) return false;
    return this.invoke('dispatch_call_hud_action', { action });
  }

  async sendNativeNotification({ title, body, appLabel = 'OpalDesk', tag = '' }) {
    if (!this.enabled) return false;
    try {
      return await this.invoke('send_native_notification', {
        title: String(title || ''),
        body: String(body || ''),
        appLabel: String(appLabel || 'OpalDesk'),
        tag: String(tag || ''),
      });
    } catch {
      return false;
    }
  }

  async updateTrayStatus({ connected = false, deviceName = '', unreadCount = 0, callActive = false } = {}) {
    if (!this.enabled) return false;
    try {
      return await this.invoke('update_tray_status', {
        connected: Boolean(connected),
        deviceName: String(deviceName || ''),
        unreadCount: Math.max(0, Math.round(Number(unreadCount || 0))),
        callActive: Boolean(callActive),
      });
    } catch {
      return false;
    }
  }

  async createAppShortcut(req) {
    return this.invoke('create_app_shortcut', { req });
  }

  async listAppShortcuts() {
    if (!this.enabled) return [];
    const list = await this.invoke('list_app_shortcuts');
    return Array.isArray(list) ? list : [];
  }

  async removeAppShortcut(pkg) {
    return this.invoke('remove_app_shortcut', { pkg: String(pkg || '') });
  }

  async registerUrlProtocol() {
    if (!this.enabled) return false;
    return this.invoke('register_url_protocol');
  }

  async scanBleNearby() {
    if (!this.enabled) return { supported: false, devices: [], note: '' };
    return this.invoke('scan_ble_nearby');
  }

  async triggerBleHotspot({ address, certFingerprintPrefix = '', clientId, authToken }) {
    return this.invoke('trigger_ble_hotspot', {
      req: {
        address: String(address || ''),
        certFingerprintPrefix: String(certFingerprintPrefix || ''),
        clientId: String(clientId || ''),
        authToken: String(authToken || ''),
      },
    });
  }

  listenEvent(eventName, handler) {
    const evApi = globalThis.window?.__TAURI__?.event;
    if (!evApi || typeof evApi.listen !== 'function') {
      return Promise.resolve(() => {});
    }
    return evApi.listen(eventName, (event) => handler(event?.payload, event));
  }
}
