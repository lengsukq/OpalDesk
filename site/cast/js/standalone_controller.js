/**
 * Standalone Floating App Window Manager for Windows Desktop Client & Web Cast.
 * Handles:
 *  - Max 3 concurrent standalone floating windows
 *  - Always-on-top pinning (per-window & remembered per app)
 *  - Recent/History App Mini-Window bar (Lobby + In-Cast App Picker)
 *  - Lightweight Lobby App Catalog picker (`app_catalog` session role, no full desktop needed)
 *  - Secondary reload confirmation dialog when target app window is already open
 */

import {
  decodeAppList,
  decodeAuthResult,
  decodeFrame,
  encodeFrame,
  encodeHello,
  encodeWindowAction,
  SESSION_ROLE_APP_CATALOG,
  SESSION_ROLE_STANDALONE_APP,
  TYPE_APP_LIST,
  TYPE_AUTH_RESULT,
  TYPE_HELLO,
  TYPE_LIST_APPS,
  TYPE_WINDOW_ACTION,
} from './protocol.js';
import {
  clearStandaloneHistory,
  getAppAvatarColor,
  getAppAvatarInitial,
  getStandalonePinPref,
  loadStandaloneHistory,
  MAX_STANDALONE_WINDOWS,
  recordStandaloneLaunch,
  removeStandaloneHistory,
  sortAppsForLauncher,
  updateStandalonePinPref,
} from './standalone_history.js';

const $ = (id) => document.getElementById(id);

export class OpalStandaloneController {
  constructor(app) {
    this.app = app;
    this.desktop = app.desktop;

    this.historyEntries = loadStandaloneHistory();
    this.activeWindows = []; // Tauri: StandaloneWindowInfo[]
    this.webPopups = new Map(); // Web fallback: pkg -> { win, title }
    this.pollTimer = null;

    this.inCastSortMode = 'recent'; // 'recent' | 'name'
    this.lobbySortMode = 'recent';

    this.lobbyCatalogTarget = null;
    this.lobbyCatalogApps = [];
    this.lobbyCatalogWs = null;
    this.lobbyCatalogTimeout = null;

    this.pendingReloadRequest = null;
    this.currentWindowPinned = false;
  }

  bindEvents() {
    $('closeAllStandaloneBtn')?.addEventListener('click', () => this.closeAllWindows());
    $('clearStandaloneHistoryBtn')?.addEventListener('click', () => {
      this.historyEntries = clearStandaloneHistory();
      this.renderLobbyHistoryBar();
      this.renderInCastHistoryStrip();
      this.app.showToast('已清空历史小窗记录');
    });

    $('appsSortToggleBtn')?.addEventListener('click', () => {
      this.inCastSortMode = this.inCastSortMode === 'recent' ? 'name' : 'recent';
      if ($('appsSortToggleBtn')) {
        $('appsSortToggleBtn').textContent = this.inCastSortMode === 'recent' ? '常用排序' : '名称排序';
      }
      this.renderInCastAppList();
    });

    // Lobby remote app picker modal
    $('closeLobbyAppsModalBtn')?.addEventListener('click', () => this.closeLobbyAppPicker());
    $('lobbyAppsSortBtn')?.addEventListener('click', () => {
      this.lobbySortMode = this.lobbySortMode === 'recent' ? 'name' : 'recent';
      if ($('lobbyAppsSortBtn')) {
        $('lobbyAppsSortBtn').textContent = this.lobbySortMode === 'recent' ? '常用排序' : '名称排序';
      }
      this.renderLobbyAppPickerList();
    });
    $('lobbyAppSearchInput')?.addEventListener('input', () => this.renderLobbyAppPickerList());
    $('lobbyAppsModalOverlay')?.addEventListener('click', (e) => {
      if (e.target === $('lobbyAppsModalOverlay')) this.closeLobbyAppPicker();
    });

    // Secondary reload confirmation modal
    $('reloadConfirmBtn')?.addEventListener('click', () => this.resolveReloadModal('reload'));
    $('reloadFocusBtn')?.addEventListener('click', () => this.resolveReloadModal('focus'));
    $('reloadCancelBtn')?.addEventListener('click', () => this.resolveReloadModal('cancel'));
    $('reloadStandaloneModalOverlay')?.addEventListener('click', (e) => {
      if (e.target === $('reloadStandaloneModalOverlay')) this.resolveReloadModal('cancel');
    });

    // Current window always-on-top toggle button
    $('tbPinBtn')?.addEventListener('click', () => {
      this.toggleCurrentWindowAlwaysOnTop();
      this.app.bumpToolbarVisibility();
      this.app.input.focusIme();
    });

    // Cross-window localStorage sync (when standalone window updates pin preference or history)
    window.addEventListener('storage', (ev) => {
      if (ev.key === 'opaldesk-standalone-history-v1') {
        this.historyEntries = loadStandaloneHistory();
        this.renderLobbyHistoryBar();
        this.renderInCastHistoryStrip();
      }
    });
  }

  async initialize() {
    this.historyEntries = loadStandaloneHistory();
    this.renderLobbyHistoryBar();

    if (this.desktop.enabled) {
      await this.refreshActiveWindows();
      this.startActiveWindowPolling();
      try {
        this.currentWindowPinned = await this.desktop.getCurrentWindowAlwaysOnTop();
        this.syncPinButtonUi();
      } catch {
        // Ignore
      }
    }
  }

  startActiveWindowPolling() {
    if (!this.desktop.enabled) return;
    clearInterval(this.pollTimer);
    this.pollTimer = setInterval(() => {
      if (!document.hidden) {
        this.refreshActiveWindows();
      }
    }, 2500);
  }

  async refreshActiveWindows() {
    if (this.desktop.enabled) {
      try {
        this.activeWindows = await this.desktop.listStandaloneWindows();
      } catch {
        // Ignore transient failure
      }
    } else {
      const next = [];
      for (const [pkg, rec] of this.webPopups.entries()) {
        if (!rec.win || rec.win.closed) {
          this.webPopups.delete(pkg);
        } else {
          next.push({ label: `standalone-${pkg}`, pkg, title: rec.title, alwaysOnTop: false });
        }
      }
      this.activeWindows = next;
    }
    this.syncQuotaBadges();
    this.renderLobbyHistoryBar();
    if (!$('appsPopover')?.hidden) {
      this.renderInCastHistoryStrip();
      this.renderInCastAppList();
    }
    if (!$('lobbyAppsModalOverlay')?.hidden) {
      this.renderLobbyAppPickerList();
    }
  }

  getActiveWindowForPkg(pkg) {
    return this.activeWindows.find((w) => w.pkg === pkg) || null;
  }

  getActiveCount() {
    return this.activeWindows.length;
  }

  syncQuotaBadges() {
    const count = this.getActiveCount();
    const text = `已开 ${count}/${MAX_STANDALONE_WINDOWS}`;
    const full = count >= MAX_STANDALONE_WINDOWS;

    for (const id of ['standaloneQuotaPill', 'lobbyAppsQuotaBadge']) {
      const el = $(id);
      if (!el) continue;
      el.textContent = text;
      el.className = `status-pill mono ${full ? 'warn' : count > 0 ? 'ok' : ''}`;
    }
    const popBadge = $('appsPopQuotaBadge');
    if (popBadge) {
      popBadge.textContent = `小窗 ${count}/${MAX_STANDALONE_WINDOWS}`;
      popBadge.className = `status-pill mono ${full ? 'warn' : count > 0 ? 'ok' : ''}`;
    }
    const closeAllBtn = $('closeAllStandaloneBtn');
    if (closeAllBtn) {
      closeAllBtn.hidden = !this.desktop.enabled || count === 0;
    }
  }

  syncPinButtonUi() {
    const btn = $('tbPinBtn');
    if (!btn) return;
    if (!this.desktop.enabled) {
      btn.hidden = true;
      return;
    }
    btn.hidden = false;
    btn.classList.toggle('pinned-active', this.currentWindowPinned);
    btn.textContent = this.currentWindowPinned ? '已置顶' : '置顶';
    btn.title = this.currentWindowPinned
      ? '窗口已置顶 · 点击取消置顶 (Alt+Shift+T)'
      : '将当前悬浮窗置顶显示 (Alt+Shift+T)';
  }

  async applyInitialStandalonePin(pinFlag) {
    if (!this.desktop.enabled) return;
    if (pinFlag) {
      try {
        this.currentWindowPinned = await this.desktop.setCurrentWindowAlwaysOnTop(true);
      } catch {
        this.currentWindowPinned = false;
      }
    } else {
      try {
        this.currentWindowPinned = await this.desktop.getCurrentWindowAlwaysOnTop();
      } catch {
        this.currentWindowPinned = false;
      }
    }
    this.syncPinButtonUi();
  }

  async toggleCurrentWindowAlwaysOnTop() {
    if (!this.desktop.enabled) {
      this.app.showToast('窗口置顶功能需在 OpalDesk Windows / macOS 桌面客户端中使用');
      return;
    }
    const next = !this.currentWindowPinned;
    try {
      this.currentWindowPinned = await this.desktop.setCurrentWindowAlwaysOnTop(next);
      this.syncPinButtonUi();
      if (this.app.sessionRole === SESSION_ROLE_STANDALONE_APP && this.app.standaloneAppPkg) {
        this.historyEntries = updateStandalonePinPref(
          this.app.standaloneAppPkg,
          this.currentWindowPinned,
          this.app.standaloneDeviceKey || ''
        );
      }
      this.app.diagnostics?.log('I', 'Standalone', `窗口置顶已${this.currentWindowPinned ? '开启' : '关闭'}`);
      this.app.showToast(this.currentWindowPinned ? '已开启悬浮窗置顶' : '已取消悬浮窗置顶');
    } catch (err) {
      this.app.showToast(`切换置顶失败：${err?.message || err}`);
    }
  }

  async closeAllWindows() {
    if (!this.desktop.enabled) return;
    try {
      await this.desktop.closeAllStandaloneWindows();
      this.app.diagnostics?.log('I', 'Standalone', '关闭了全部单应用悬浮小窗');
      await this.refreshActiveWindows();
      this.app.showToast('已关闭全部应用悬浮小窗');
    } catch (err) {
      this.app.showToast(`关闭小窗失败：${err?.message || err}`);
    }
  }

  // ==================== LOBBY HISTORY BAR ====================

  renderLobbyHistoryBar() {
    const listEl = $('standaloneHistoryList');
    const clearBtn = $('clearStandaloneHistoryBtn');
    if (!listEl) return;

    this.syncQuotaBadges();
    const entries = this.historyEntries;
    if (clearBtn) clearBtn.hidden = entries.length === 0;

    listEl.innerHTML = '';
    if (!entries.length) {
      const empty = document.createElement('span');
      empty.className = 'hint-text';
      empty.textContent = '暂无历史小窗记录：点击下方设备卡片的「应用小窗」或在投屏控制栏打开独立小窗后将自动保存。';
      listEl.appendChild(empty);
      return;
    }

    for (const entry of entries) {
      const liveWin = this.getActiveWindowForPkg(entry.pkg);
      const isRunning = Boolean(liveWin);
      const isPinned = isRunning ? Boolean(liveWin.alwaysOnTop) : Boolean(entry.pinnedOnTop);

      const card = document.createElement('div');
      card.className = `standalone-hist-card${isRunning ? ' running' : ''}`;
      card.title = isRunning
        ? `${entry.label}（运行中 · 点击可选择重载小窗+应用或聚焦）`
        : `一键打开 ${entry.label} 悬浮小窗`;
      card.addEventListener('click', () => this.launchFromHistoryEntry(entry));

      const avatar = document.createElement('div');
      avatar.className = 'app-avatar-badge';
      avatar.style.background = getAppAvatarColor(entry.pkg);
      avatar.textContent = getAppAvatarInitial(entry.label, entry.pkg);
      if (isRunning) {
        const dot = document.createElement('span');
        dot.className = 'running-indicator-dot';
        avatar.appendChild(dot);
      }

      const meta = document.createElement('div');
      meta.className = 'standalone-hist-meta';
      const name = document.createElement('div');
      name.className = 'standalone-hist-name';
      name.textContent = entry.label;
      const sub = document.createElement('div');
      sub.className = 'standalone-hist-sub';
      const transportTag = entry.transport === 'usb' ? 'USB' : 'Wi-Fi';
      sub.textContent = `${isRunning ? '运行中 · ' : ''}${entry.deviceName || entry.host || 'Android'} (${transportTag})`;
      meta.append(name, sub);

      const pinBtn = document.createElement('button');
      pinBtn.type = 'button';
      pinBtn.className = `hist-icon-btn${isPinned ? ' pinned' : ''}`;
      pinBtn.title = isPinned ? '已设为置顶 · 点击取消' : '点击预设或切换置顶';
      pinBtn.textContent = '📌';
      pinBtn.addEventListener('click', async (ev) => {
        ev.stopPropagation();
        const nextPin = !isPinned;
        this.historyEntries = updateStandalonePinPref(entry.pkg, nextPin, entry.deviceKey);
        if (isRunning && this.desktop.enabled) {
          try {
            await this.desktop.setStandaloneWindowAlwaysOnTop(entry.pkg, nextPin);
            await this.refreshActiveWindows();
          } catch {
            // Ignore
          }
        }
        this.renderLobbyHistoryBar();
        this.app.showToast(`${entry.label} ${nextPin ? '已开启置顶' : '已取消置顶'}`);
      });

      const delBtn = document.createElement('button');
      delBtn.type = 'button';
      delBtn.className = 'hist-icon-btn remove';
      delBtn.title = '移除此历史记录';
      delBtn.textContent = '×';
      delBtn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        this.historyEntries = removeStandaloneHistory(entry.id);
        this.renderLobbyHistoryBar();
        this.renderInCastHistoryStrip();
      });

      card.append(avatar, meta, pinBtn, delBtn);
      listEl.appendChild(card);
    }
  }

  async launchFromHistoryEntry(entry) {
    const target = {
      transport: entry.transport,
      host: entry.host,
      port: entry.port || 19878,
      adbSerial: entry.adbSerial || '',
      deviceKey: entry.deviceKey,
      deviceName: entry.deviceName || entry.host || 'Android',
      certFingerprint: entry.certFingerprint || '',
    };
    await this.requestLaunchStandaloneApp(
      { pkg: entry.pkg, cls: entry.cls || '', label: entry.label || entry.pkg },
      target,
      { alwaysOnTop: Boolean(entry.pinnedOnTop) }
    );
  }

  // ==================== LAUNCH + RELOAD CONFIRMATION ====================

  async requestLaunchStandaloneApp(appInfo, target, { alwaysOnTop, forceReload = false } = {}) {
    const pkg = String(appInfo?.pkg || '').trim();
    if (!pkg) return;
    const label = String(appInfo?.label || '').trim() || pkg;
    const cls = String(appInfo?.cls || '').trim();

    const resolvedDeviceKey =
      target.deviceKey || (target.transport === 'usb' ? `usb:${target.adbSerial || 'default'}` : `${target.host}:${target.port || 19878}`);
    const resolvedPin =
      typeof alwaysOnTop === 'boolean' ? alwaysOnTop : getStandalonePinPref(pkg, resolvedDeviceKey);

    try {
      // Ensure underlying transport (USB ADB forward or Wi-Fi TLS tunnel) is ready before opening window
      let usbLocalPort = target.usbLocalPort || 0;
      let verifiedFp = target.certFingerprint || '';

      if (this.desktop.enabled) {
        if (target.transport === 'usb') {
          if (!usbLocalPort) {
            const serial = target.adbSerial || this.app.selectedAdbSerial;
            const devices = await this.desktop.scanAdbDevices();
            const chosen =
              devices.find((d) => d.serial === serial && d.state === 'device') ||
              devices.find((d) => d.state === 'device');
            if (!chosen) {
              throw new Error('未检测到已授权的 USB 调试手机，请检查数据线连接');
            }
            const fwd = await this.desktop.bootstrapAndForwardUsb(chosen.serial);
            usbLocalPort = fwd.localPort;
            target.adbSerial = chosen.serial;
            target.usbLocalPort = usbLocalPort;
          }
        } else {
          if (!target.host) throw new Error('缺少手机局域网 IP 地址');
          if (!verifiedFp) {
            const tunnel = await this.app.ensureDesktopTlsTunnel(target.host, target.port || 19878, '/ws', '');
            if (!tunnel) return; // User cancelled TOFU
            verifiedFp = tunnel.verifiedFingerprint;
            target.certFingerprint = verifiedFp;
          }
        }

        const res = await this.desktop.openStandaloneWindow({
          pkg,
          cls,
          label,
          host: target.host,
          port: target.port || 19878,
          transport: target.transport,
          usbLocalPort,
          deviceKey: resolvedDeviceKey,
          deviceName: target.deviceName || target.host || 'Android',
          certFingerprint: verifiedFp,
          alwaysOnTop: resolvedPin,
          forceReload,
        });

        if (res.status === 'already_open') {
          this.openReloadConfirmModal({
            appInfo: { pkg, cls, label: res.existingTitle || label },
            target,
            alwaysOnTop: resolvedPin,
          });
          return;
        }
        if (res.status === 'limit_reached') {
          this.app.showToast(`最多同时打开 ${res.maxCount || MAX_STANDALONE_WINDOWS} 个单应用悬浮窗，请先关闭其中一个`);
          await this.refreshActiveWindows();
          return;
        }

        this.historyEntries = recordStandaloneLaunch({
          pkg,
          cls,
          label,
          deviceKey: resolvedDeviceKey,
          deviceName: target.deviceName || target.host || 'Android',
          transport: target.transport,
          host: target.host,
          port: target.port || 19878,
          adbSerial: target.adbSerial || '',
          certFingerprint: verifiedFp,
          pinnedOnTop: resolvedPin,
        });
        this.app.diagnostics?.log(
          'I',
          'Standalone',
          `${forceReload ? '重载' : '打开'}悬浮小窗 ${label} (${pkg}) pinned=${resolvedPin} (${res.activeCount}/${res.maxCount})`
        );
        await this.refreshActiveWindows();
        this.app.showToast(
          `${forceReload ? '已重载' : '已打开'}「${label}」悬浮小窗 (${res.activeCount}/${res.maxCount})`
        );
        return;
      }

      // Pure Web browser fallback (with max 3 + reload confirmation)
      await this.refreshActiveWindows();
      const existingPopup = this.webPopups.get(pkg);
      if (existingPopup && existingPopup.win && !existingPopup.win.closed && !forceReload) {
        this.openReloadConfirmModal({
          appInfo: { pkg, cls, label },
          target,
          alwaysOnTop: resolvedPin,
        });
        return;
      }
      if (forceReload && existingPopup && existingPopup.win && !existingPopup.win.closed) {
        try { existingPopup.win.close(); } catch {}
        this.webPopups.delete(pkg);
      }
      if (this.webPopups.size >= MAX_STANDALONE_WINDOWS) {
        this.app.showToast(`最多同时打开 ${MAX_STANDALONE_WINDOWS} 个单应用悬浮窗，请先关闭其中一个`);
        return;
      }

      const u = new URL(window.location.href);
      u.search = '';
      u.hash = '';
      u.searchParams.set('host', target.transport === 'usb' ? '127.0.0.1' : target.host);
      u.searchParams.set('port', String(target.transport === 'usb' ? (usbLocalPort || target.port || 19878) : (target.port || 19878)));
      if (target.transport === 'usb') u.searchParams.set('transport', 'usb');
      u.searchParams.set('role', SESSION_ROLE_STANDALONE_APP);
      u.searchParams.set('pkg', pkg);
      if (cls) u.searchParams.set('cls', cls);
      u.searchParams.set('appLabel', label);

      const popup = window.open(u.toString(), `opal_app_${pkg}`, 'width=440,height=880');
      if (popup) {
        this.webPopups.set(pkg, { win: popup, title: label });
      }
      this.historyEntries = recordStandaloneLaunch({
        pkg,
        cls,
        label,
        deviceKey: resolvedDeviceKey,
        deviceName: target.deviceName || target.host || 'Android',
        transport: target.transport,
        host: target.host,
        port: target.port || 19878,
        pinnedOnTop: resolvedPin,
      });
      await this.refreshActiveWindows();
    } catch (err) {
      this.app.diagnostics?.log('W', 'Standalone', `打开小窗失败 ${pkg}: ${err?.message || err}`);
      this.app.showToast(`打开独立小窗失败：${err?.message || err}`);
    }
  }

  openReloadConfirmModal(request) {
    this.pendingReloadRequest = request;
    const name = request.appInfo.label || request.appInfo.pkg;
    if ($('reloadModalTitle')) {
      $('reloadModalTitle').textContent = `应用「${name}」的小窗已打开`;
    }
    if ($('reloadModalDesc')) {
      $('reloadModalDesc').textContent = `「${name}」的单应用悬浮小窗正在运行。是否关闭现有窗口并重载小窗 + 应用？也可以选择仅聚焦到现有窗口。`;
    }
    if ($('reloadStandaloneModalOverlay')) {
      $('reloadStandaloneModalOverlay').hidden = false;
    }
  }

  async resolveReloadModal(action) {
    if ($('reloadStandaloneModalOverlay')) {
      $('reloadStandaloneModalOverlay').hidden = true;
    }
    const req = this.pendingReloadRequest;
    this.pendingReloadRequest = null;
    if (!req || action === 'cancel') return;

    if (action === 'focus') {
      if (this.desktop.enabled) {
        await this.desktop.focusStandaloneWindow(req.appInfo.pkg);
      } else {
        const rec = this.webPopups.get(req.appInfo.pkg);
        rec?.win?.focus();
      }
      this.app.showToast(`已聚焦「${req.appInfo.label}」悬浮小窗`);
      return;
    }

    if (action === 'reload') {
      await this.requestLaunchStandaloneApp(req.appInfo, req.target, {
        alwaysOnTop: req.alwaysOnTop,
        forceReload: true,
      });
    }
  }

  // ==================== IN-CAST APP PICKER ====================

  renderInCastHistoryStrip() {
    const wrap = $('appsPopHistoryWrap');
    const list = $('appsPopHistoryList');
    if (!wrap || !list) return;

    const entries = this.historyEntries.slice(0, 8);
    if (!entries.length) {
      wrap.hidden = true;
      return;
    }
    wrap.hidden = false;
    list.innerHTML = '';

    for (const entry of entries) {
      const isRunning = Boolean(this.getActiveWindowForPkg(entry.pkg));
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = `pop-history-chip${isRunning ? ' running' : ''}`;
      chip.title = isRunning ? '运行中 · 点击可重载或聚焦' : '一键以悬浮小窗打开';

      const dot = document.createElement('span');
      dot.className = 'app-avatar-badge sm';
      dot.style.background = getAppAvatarColor(entry.pkg);
      dot.textContent = getAppAvatarInitial(entry.label, entry.pkg);

      const txt = document.createElement('span');
      txt.textContent = entry.label;

      chip.append(dot, txt);
      chip.addEventListener('click', () => {
        $('appsPopover').hidden = true;
        this.requestLaunchStandaloneApp(
          { pkg: entry.pkg, cls: entry.cls, label: entry.label },
          this.currentCastTarget(),
          { alwaysOnTop: entry.pinnedOnTop }
        );
      });
      list.appendChild(chip);
    }
  }

  currentCastTarget() {
    const isUsb = this.app.transportMode === 'usb';
    return {
      transport: this.app.transportMode,
      host: this.app.activeHost,
      port: this.app.activePort,
      usbLocalPort: this.app.activeUsbLocalPort || this.app.activePort,
      adbSerial: this.app.selectedAdbSerial || '',
      deviceKey: isUsb ? `usb:${this.app.selectedAdbSerial || 'default'}` : `${this.app.activeHost}:${this.app.activePort}`,
      deviceName: $('stagePeerName')?.textContent || this.app.activeHost || 'Android',
      certFingerprint: this.app.activeVerifiedFingerprint || '',
    };
  }

  renderInCastAppList() {
    this.syncQuotaBadges();
    this.renderInCastHistoryStrip();
    const q = ($('appSearchInput')?.value || '').trim().toLowerCase();
    const listEl = $('appsList');
    if (!listEl) return;
    listEl.innerHTML = '';

    const sorted = sortAppsForLauncher(this.app.currentApps, this.historyEntries, this.inCastSortMode);
    const filtered = sorted.filter(
      (a) => !q || (a.label || '').toLowerCase().includes(q) || (a.pkg || '').toLowerCase().includes(q)
    );
    if (!filtered.length) {
      listEl.innerHTML = '<div class="hint-text" style="padding:10px;text-align:center">未找到匹配应用</div>';
      return;
    }

    const activeCount = this.getActiveCount();
    const target = this.currentCastTarget();

    for (const appItem of filtered.slice(0, 100)) {
      const row = document.createElement('div');
      row.className = 'app-row';

      const info = document.createElement('div');
      info.className = 'app-info';
      if (appItem.icon) {
        const img = document.createElement('img');
        img.src = `data:image/png;base64,${appItem.icon}`;
        img.alt = '';
        info.appendChild(img);
      } else {
        const avatar = document.createElement('span');
        avatar.className = 'app-avatar-badge sm';
        avatar.style.background = getAppAvatarColor(appItem.pkg);
        avatar.textContent = getAppAvatarInitial(appItem.label, appItem.pkg);
        info.appendChild(avatar);
      }
      const lbl = document.createElement('span');
      lbl.className = 'app-label';
      lbl.textContent = appItem.label || appItem.pkg;
      info.appendChild(lbl);

      const btns = document.createElement('div');
      btns.className = 'app-btns';

      const openBtn = document.createElement('button');
      openBtn.type = 'button';
      openBtn.className = 'mini-btn';
      openBtn.textContent = '打开';
      openBtn.addEventListener('click', () => {
        this.app.sendOpalFrame(
          TYPE_WINDOW_ACTION,
          0,
          encodeWindowAction({ action: 'launch', pkg: appItem.pkg, cls: appItem.cls || '' })
        );
        $('appsPopover').hidden = true;
      });
      btns.appendChild(openBtn);

      const isRunning = Boolean(this.getActiveWindowForPkg(appItem.pkg));
      const popBtn = document.createElement('button');
      popBtn.type = 'button';
      popBtn.className = 'mini-btn';
      if (isRunning) {
        popBtn.textContent = '小窗已开';
      } else if (activeCount >= MAX_STANDALONE_WINDOWS) {
        popBtn.textContent = `已满 ${MAX_STANDALONE_WINDOWS}/${MAX_STANDALONE_WINDOWS}`;
        popBtn.disabled = true;
      } else {
        popBtn.textContent = '独立小窗';
      }
      popBtn.addEventListener('click', () => {
        $('appsPopover').hidden = true;
        this.requestLaunchStandaloneApp(appItem, target);
      });
      btns.appendChild(popBtn);

      row.append(info, btns);
      listEl.appendChild(row);
    }
  }

  // ==================== LOBBY REMOTE APP PICKER (app_catalog) ====================

  async openLobbyAppPicker(target) {
    this.closeLobbyCatalogSocket();
    this.lobbyCatalogTarget = { ...target };
    this.lobbyCatalogApps = [];

    if ($('lobbyAppsModalTitle')) {
      $('lobbyAppsModalTitle').textContent = `${target.deviceName || target.host || 'Android'} · 应用小窗`;
    }
    if ($('lobbyAppSearchInput')) $('lobbyAppSearchInput').value = '';
    if ($('lobbyAppsList')) $('lobbyAppsList').innerHTML = '';
    if ($('lobbyAppsStatusText')) {
      $('lobbyAppsStatusText').textContent = '正在建立轻量通道获取手机已安装应用…';
      $('lobbyAppsStatusText').hidden = false;
    }
    if ($('lobbyAppsModalOverlay')) $('lobbyAppsModalOverlay').hidden = false;

    await this.refreshActiveWindows();
    this.renderLobbyPickerHistoryStrip();

    try {
      let wsUrl = '';
      let isUsb = target.transport === 'usb';
      if (this.desktop.enabled) {
        if (isUsb) {
          const serial = target.adbSerial || this.app.selectedAdbSerial;
          const fwd = await this.desktop.bootstrapAndForwardUsb(serial);
          this.lobbyCatalogTarget.usbLocalPort = fwd.localPort;
          this.lobbyCatalogTarget.adbSerial = fwd.serial;
          wsUrl = fwd.localWsUrl;
        } else {
          const tunnel = await this.app.ensureDesktopTlsTunnel(
            target.host,
            target.port || 19878,
            '/ws',
            target.certFingerprint || ''
          );
          if (!tunnel) {
            this.closeLobbyAppPicker();
            return;
          }
          this.lobbyCatalogTarget.certFingerprint = tunnel.verifiedFingerprint;
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

      this.fetchAppCatalogOverWs(wsUrl, isUsb, authToken);
    } catch (err) {
      if ($('lobbyAppsStatusText')) {
        $('lobbyAppsStatusText').textContent = `获取应用列表失败：${err?.message || err}`;
      }
      this.app.diagnostics?.log('W', 'AppCatalog', `大厅拉取应用目录失败: ${err?.message || err}`);
    }
  }

  fetchAppCatalogOverWs(wsUrl, isUsb, authToken) {
    const ws = new WebSocket(wsUrl);
    ws.binaryType = 'arraybuffer';
    this.lobbyCatalogWs = ws;

    this.lobbyCatalogTimeout = setTimeout(() => {
      if (this.lobbyCatalogWs === ws) {
        this.closeLobbyCatalogSocket();
        if ($('lobbyAppsStatusText')) {
          $('lobbyAppsStatusText').textContent = '获取应用列表超时，请确认手机端已允许连接并开启共享服务。';
        }
      }
    }, 20000);

    ws.onopen = () => {
      if ($('lobbyAppsStatusText')) {
        $('lobbyAppsStatusText').textContent = !isUsb && !authToken
          ? '请在手机弹窗点击「允许」以获取应用列表…'
          : '已连接，正在读取应用目录…';
      }
      const hello = encodeHello({
        clientName: 'OpalDesk Desktop',
        clientId: this.app.clientId,
        width: 720,
        height: 1280,
        dpi: 240,
        fps: 60,
        bitrateKbps: 4000,
        audioEnabled: false,
        authToken,
        transportHint: isUsb ? 'usb' : 'wifi_tls',
        sessionRole: SESSION_ROLE_APP_CATALOG,
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
      if (frame.type === TYPE_AUTH_RESULT) {
        const auth = decodeAuthResult(frame.payload);
        if (!auth.ok) {
          if ($('lobbyAppsStatusText')) {
            $('lobbyAppsStatusText').textContent = auth.message || '手机端拒绝了应用列表请求';
          }
          this.closeLobbyCatalogSocket();
          return;
        }
        if (auth.deviceName && this.lobbyCatalogTarget) {
          this.lobbyCatalogTarget.deviceName = auth.deviceName;
        }
        if (!isUsb && auth.authToken && this.desktop.enabled && this.lobbyCatalogTarget?.certFingerprint) {
          this.desktop
            .upsertTrustedDevice({
              host: this.lobbyCatalogTarget.host,
              port: this.lobbyCatalogTarget.port || 19878,
              displayName: auth.deviceName || this.lobbyCatalogTarget.host,
              model: '',
              certFingerprint: this.lobbyCatalogTarget.certFingerprint,
              authToken: auth.authToken,
              permanent: Boolean(auth.permanent),
              lastConnectedAtMs: Date.now(),
            })
            .catch(() => {});
        }
        ws.send(encodeFrame(TYPE_LIST_APPS));
      } else if (frame.type === TYPE_APP_LIST) {
        this.lobbyCatalogApps = decodeAppList(frame.payload);
        this.closeLobbyCatalogSocket();
        if ($('lobbyAppsStatusText')) {
          $('lobbyAppsStatusText').textContent = `已加载 ${this.lobbyCatalogApps.length} 个应用，点击任意应用即可以悬浮小窗打开。`;
        }
        this.renderLobbyAppPickerList();
      }
    };

    ws.onerror = () => {
      if (this.lobbyCatalogWs === ws && $('lobbyAppsStatusText')) {
        $('lobbyAppsStatusText').textContent = '连接手机应用目录失败，请确认设备在线。';
      }
      this.closeLobbyCatalogSocket();
    };
  }

  renderLobbyPickerHistoryStrip() {
    const wrap = $('lobbyAppsHistoryWrap');
    const list = $('lobbyAppsHistoryList');
    if (!wrap || !list) return;

    const entries = this.historyEntries.slice(0, 8);
    if (!entries.length) {
      wrap.hidden = true;
      return;
    }
    wrap.hidden = false;
    list.innerHTML = '';

    for (const entry of entries) {
      const isRunning = Boolean(this.getActiveWindowForPkg(entry.pkg));
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = `pop-history-chip${isRunning ? ' running' : ''}`;

      const dot = document.createElement('span');
      dot.className = 'app-avatar-badge sm';
      dot.style.background = getAppAvatarColor(entry.pkg);
      dot.textContent = getAppAvatarInitial(entry.label, entry.pkg);

      const txt = document.createElement('span');
      txt.textContent = entry.label;

      chip.append(dot, txt);
      chip.addEventListener('click', () => {
        const target = this.lobbyCatalogTarget || {
          transport: entry.transport,
          host: entry.host,
          port: entry.port,
          adbSerial: entry.adbSerial,
          deviceKey: entry.deviceKey,
          deviceName: entry.deviceName,
          certFingerprint: entry.certFingerprint,
        };
        this.closeLobbyAppPicker();
        this.requestLaunchStandaloneApp(
          { pkg: entry.pkg, cls: entry.cls, label: entry.label },
          target,
          { alwaysOnTop: entry.pinnedOnTop }
        );
      });
      list.appendChild(chip);
    }
  }

  renderLobbyAppPickerList() {
    this.syncQuotaBadges();
    this.renderLobbyPickerHistoryStrip();
    const listEl = $('lobbyAppsList');
    if (!listEl) return;
    listEl.innerHTML = '';

    const q = ($('lobbyAppSearchInput')?.value || '').trim().toLowerCase();
    const sorted = sortAppsForLauncher(this.lobbyCatalogApps, this.historyEntries, this.lobbySortMode);
    const filtered = sorted.filter(
      (a) => !q || (a.label || '').toLowerCase().includes(q) || (a.pkg || '').toLowerCase().includes(q)
    );
    if (!filtered.length) {
      listEl.innerHTML = '<div class="hint-text" style="padding:10px;text-align:center">暂无匹配应用</div>';
      return;
    }

    const activeCount = this.getActiveCount();
    const target = this.lobbyCatalogTarget;

    for (const appItem of filtered.slice(0, 120)) {
      const row = document.createElement('div');
      row.className = 'app-row';

      const info = document.createElement('div');
      info.className = 'app-info';
      const avatar = document.createElement('span');
      avatar.className = 'app-avatar-badge sm';
      avatar.style.background = getAppAvatarColor(appItem.pkg);
      avatar.textContent = getAppAvatarInitial(appItem.label, appItem.pkg);

      const lbl = document.createElement('span');
      lbl.className = 'app-label';
      lbl.textContent = appItem.label || appItem.pkg;
      info.append(avatar, lbl);

      const isRunning = Boolean(this.getActiveWindowForPkg(appItem.pkg));
      const openBtn = document.createElement('button');
      openBtn.type = 'button';
      openBtn.className = isRunning ? 'mini-btn' : 'primary-btn compact';
      if (isRunning) {
        openBtn.textContent = '小窗已开 · 重载/聚焦';
      } else if (activeCount >= MAX_STANDALONE_WINDOWS) {
        openBtn.textContent = `已满 ${MAX_STANDALONE_WINDOWS}/${MAX_STANDALONE_WINDOWS}`;
        openBtn.disabled = true;
      } else {
        openBtn.textContent = '打开悬浮小窗';
      }
      openBtn.addEventListener('click', () => {
        if (!target) return;
        this.closeLobbyAppPicker();
        this.requestLaunchStandaloneApp(appItem, target);
      });

      row.append(info, openBtn);
      listEl.appendChild(row);
    }
  }

  closeLobbyCatalogSocket() {
    clearTimeout(this.lobbyCatalogTimeout);
    this.lobbyCatalogTimeout = null;
    if (this.lobbyCatalogWs) {
      this.lobbyCatalogWs.onopen = null;
      this.lobbyCatalogWs.onmessage = null;
      this.lobbyCatalogWs.onerror = null;
      this.lobbyCatalogWs.onclose = null;
      try { this.lobbyCatalogWs.close(); } catch {}
      this.lobbyCatalogWs = null;
    }
  }

  closeLobbyAppPicker() {
    this.closeLobbyCatalogSocket();
    if ($('lobbyAppsModalOverlay')) $('lobbyAppsModalOverlay').hidden = true;
  }
}
