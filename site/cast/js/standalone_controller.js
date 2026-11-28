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
  encodeWindowAction,
  formatAppKey,
  normalizeProfileType,
  SESSION_ROLE_STANDALONE_APP,
  TYPE_WINDOW_ACTION,
} from './protocol.js';
import {
  clearStandaloneHistory,
  getStandalonePinPref,
  loadStandaloneHistory,
  MAX_STANDALONE_WINDOWS,
  recordStandaloneLaunch,
  removeStandaloneHistory,
  updateStandalonePinPref,
} from './standalone_history.js';
import {
  STORAGE_STANDALONE_PREFS,
  POPOUT_ASPECT_PRESETS,
  createStandalonePrefsStore,
  resolvePopoutLaunchGeometry,
} from './standalone_prefs.js';
import {
  collectWebPopupWindows,
  fetchAppCatalogOverWs,
  formatStandaloneWindowTitle,
  getProfileBadgeText,
  launchWebStandalonePopup,
  prepareDesktopStandaloneTransport,
  resolveActiveWindowForKey,
  resolveAppCatalogWsUrl,
  showReloadConfirmModal,
  syncPinButtonUi,
  syncQuotaBadges,
  syncStandaloneToolbarPrefsUi,
} from './standalone_pip_window.js';
import {
  renderHistoryChipsStrip,
  renderInCastAppListView,
  renderLobbyAppPickerListView,
  renderLobbyHistoryBarView,
} from './standalone_launcher_view.js';
import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);

export class OpalStandaloneController {
  constructor(app) {
    this.app = app;
    this.desktop = app.desktop;
    this.prefsStore = createStandalonePrefsStore();

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
    this.windowSizeSaveTimer = null;
  }

  bindEvents() {
    $('closeAllStandaloneBtn')?.addEventListener('click', () => this.closeAllWindows());
    $('clearStandaloneHistoryBtn')?.addEventListener('click', () => {
      this.historyEntries = clearStandaloneHistory();
      this.renderLobbyHistoryBar();
      this.renderInCastHistoryStrip();
      this.app.showToast(t('standalone.historyClearedToast'));
    });

    $('appsSortToggleBtn')?.addEventListener('click', () => {
      this.inCastSortMode = this.inCastSortMode === 'recent' ? 'name' : 'recent';
      if ($('appsSortToggleBtn')) {
        $('appsSortToggleBtn').textContent = this.inCastSortMode === 'recent' ? t('standalone.sortRecent') : t('standalone.sortName');
      }
      this.renderInCastAppList();
    });

    // Lobby remote app picker modal
    $('closeLobbyAppsModalBtn')?.addEventListener('click', () => this.closeLobbyAppPicker());
    $('lobbyAppsSortBtn')?.addEventListener('click', () => {
      this.lobbySortMode = this.lobbySortMode === 'recent' ? 'name' : 'recent';
      if ($('lobbyAppsSortBtn')) {
        $('lobbyAppsSortBtn').textContent = this.lobbySortMode === 'recent' ? t('standalone.sortRecent') : t('standalone.sortName');
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

    // Standalone popout preference controls (favorite, preset, compat mode, DPI)
    $('tbPopoutFavoriteBtn')?.addEventListener('click', () => {
      this.toggleCurrentStandaloneFavorite();
      this.app.bumpToolbarVisibility();
      this.app.input.focusIme();
    });
    $('tbPopoutPresetSelect')?.addEventListener('change', (e) => {
      void this.applyCurrentStandalonePreset(e.target.value);
      this.app.bumpToolbarVisibility();
      this.app.input.focusIme();
    });
    $('tbPopoutCompatBtn')?.addEventListener('click', () => {
      void this.toggleCurrentStandaloneCompat();
      this.app.bumpToolbarVisibility();
      this.app.input.focusIme();
    });
    $('tbPopoutDpiSelect')?.addEventListener('change', (e) => {
      void this.applyCurrentStandaloneDpi(Number(e.target.value) || 0);
      this.app.bumpToolbarVisibility();
      this.app.input.focusIme();
    });

    // Remember user-resized standalone window geometry automatically
    window.addEventListener('resize', () => {
      if (this.app.sessionRole !== SESSION_ROLE_STANDALONE_APP || !this.app.standaloneAppPkg) return;
      clearTimeout(this.windowSizeSaveTimer);
      this.windowSizeSaveTimer = setTimeout(() => {
        const w = Math.round(window.outerWidth || window.innerWidth || 0);
        const h = Math.round(window.outerHeight || window.innerHeight || 0);
        if (w >= 340 && h >= 420) {
          this.prefsStore.updatePref(this.app.standaloneDeviceKey, this.getCurrentStandaloneAppKey(), {
            customWidth: w,
            customHeight: h,
          });
        }
      }, 650);
    });

    // Cross-window localStorage sync (when standalone window updates pin/favorite/prefs or history)
    window.addEventListener('storage', (ev) => {
      if (ev.key === 'opaldesk-standalone-history-v1' || ev.key === STORAGE_STANDALONE_PREFS) {
        this.historyEntries = loadStandaloneHistory();
        this.renderLobbyHistoryBar();
        this.renderInCastHistoryStrip();
        if (!$('appsPopover')?.hidden) this.renderInCastAppList();
        if (!$('lobbyAppsModalOverlay')?.hidden) this.renderLobbyAppPickerList();
        this.syncStandaloneToolbarPrefsUi();
      }
    });
  }

  getCurrentStandaloneAppKey() {
    return formatAppKey(this.app.standaloneAppPkg, this.app.standaloneAppUserId || 0);
  }

  getCurrentStandalonePref() {
    if (this.app.sessionRole !== SESSION_ROLE_STANDALONE_APP || !this.app.standaloneAppPkg) return null;
    return this.prefsStore.getPref(this.app.standaloneDeviceKey, this.getCurrentStandaloneAppKey());
  }

  syncStandaloneToolbarPrefsUi() {
    const isStandalone = this.app.sessionRole === SESSION_ROLE_STANDALONE_APP && Boolean(this.app.standaloneAppPkg);
    syncStandaloneToolbarPrefsUi({
      isStandalone,
      pref: isStandalone ? this.getCurrentStandalonePref() : null,
    });
  }

  toggleCurrentStandaloneFavorite() {
    if (!this.app.standaloneAppPkg) return;
    const next = this.prefsStore.toggleFavorite(this.app.standaloneDeviceKey, this.getCurrentStandaloneAppKey());
    this.syncStandaloneToolbarPrefsUi();
    this.app.showToast(
      next.isFavorite
        ? t('standalone.favAddedToast', { name: this.app.standaloneAppLabel })
        : t('standalone.favRemovedToast', { name: this.app.standaloneAppLabel })
    );
  }

  async applyCurrentStandalonePreset(presetId) {
    if (!this.app.standaloneAppPkg || !POPOUT_ASPECT_PRESETS[presetId]) return;
    const next = this.prefsStore.updatePref(this.app.standaloneDeviceKey, this.getCurrentStandaloneAppKey(), {
      preferredPreset: presetId,
      compatibilityResolution: false,
    });
    const geo = resolvePopoutLaunchGeometry(next);
    if (this.desktop.enabled) {
      await this.desktop.setCurrentWindowSize(geo.winWidth, geo.winHeight).catch(() => {});
    }
    this.app.applyStandaloneStreamGeometry?.(geo);
    this.syncStandaloneToolbarPrefsUi();
    const presetKeyMap = { phone: 'standalone.presetPhone', fold: 'standalone.presetFold', tablet: 'standalone.presetTablet', custom: 'standalone.presetCustom' };
    this.app.showToast(t('standalone.presetSwitchedToast', {
      label: presetKeyMap[presetId] ? t(presetKeyMap[presetId]) : POPOUT_ASPECT_PRESETS[presetId].label,
      w: geo.streamWidth,
      h: geo.streamHeight,
    }));
  }

  async toggleCurrentStandaloneCompat() {
    if (!this.app.standaloneAppPkg) return;
    const cur = this.getCurrentStandalonePref();
    const next = this.prefsStore.updatePref(this.app.standaloneDeviceKey, this.getCurrentStandaloneAppKey(), {
      compatibilityResolution: !cur?.compatibilityResolution,
    });
    const geo = resolvePopoutLaunchGeometry(next);
    if (this.desktop.enabled && next.compatibilityResolution) {
      await this.desktop.setCurrentWindowSize(geo.winWidth, geo.winHeight).catch(() => {});
    }
    this.app.applyStandaloneStreamGeometry?.(geo);
    this.syncStandaloneToolbarPrefsUi();
    this.app.showToast(
      next.compatibilityResolution
        ? t('standalone.compatOnToast')
        : t('standalone.compatOffToast')
    );
  }

  async applyCurrentStandaloneDpi(dpi) {
    if (!this.app.standaloneAppPkg) return;
    const next = this.prefsStore.updatePref(this.app.standaloneDeviceKey, this.getCurrentStandaloneAppKey(), {
      customDpi: dpi,
    });
    const geo = resolvePopoutLaunchGeometry(next);
    this.app.applyStandaloneStreamGeometry?.(geo);
    this.syncStandaloneToolbarPrefsUi();
    this.app.showToast(dpi > 0 ? t('standalone.dpiSetToast', { dpi }) : t('standalone.dpiAutoToast'));
  }

  async createDesktopShortcutForApp(appItem, target) {
    if (!this.desktop.enabled || !target || !appItem?.pkg) return;
    const userId = Math.max(0, Math.round(Number(appItem.userId) || 0));
    const profileType = normalizeProfileType(appItem.profileType || '', userId);
    const appKey = formatAppKey(appItem.pkg, userId);
    const deviceKey = target.deviceKey || `${target.host}:${target.port || 19878}`;
    const pref = this.prefsStore.getPref(deviceKey, appKey);
    const badgeText = getProfileBadgeText(userId, profileType);
    const rawLabel = appItem.label || appItem.pkg;
    const shortcutLabel = badgeText && !rawLabel.includes(badgeText) ? `${rawLabel} (${badgeText})` : rawLabel;
    try {
      const res = await this.desktop.createAppShortcut({
        pkg: appItem.pkg,
        cls: appItem.cls || '',
        appLabel: shortcutLabel,
        userId,
        profileType,
        host: target.host || '',
        port: Number(target.port || 19878),
        transport: target.transport || 'wifi',
        deviceKey,
        deviceName: target.deviceName || target.host || 'Android',
        certFingerprint: target.certFingerprint || '',
        preset: pref.preferredPreset,
        dpi: pref.customDpi,
        compat: pref.compatibilityResolution,
        pinned: pref.pinnedOnTop,
      });
      this.app.showToast(t('standalone.shortcutCreatedToast', { name: shortcutLabel }), {
        actionText: t('cast.openLocationAction'),
        actionFn: () => {
          if (res?.shortcutPath) void this.desktop.revealInExplorer(res.shortcutPath);
        },
      });
    } catch (err) {
      this.app.showToast(t('standalone.shortcutFailToast', { error: err?.message || err }));
    }
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
      this.activeWindows = collectWebPopupWindows(this.webPopups);
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

  getActiveWindowForPkg(pkg, userId = 0) {
    return resolveActiveWindowForKey(this.activeWindows, pkg, userId);
  }

  getActiveCount() {
    return this.activeWindows.length;
  }

  syncQuotaBadges() {
    syncQuotaBadges({ count: this.getActiveCount(), desktopEnabled: this.desktop.enabled });
  }

  syncPinButtonUi() {
    syncPinButtonUi({ desktopEnabled: this.desktop.enabled, pinned: this.currentWindowPinned });
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
      this.app.showToast(t('standalone.pinDesktopOnlyToast'));
      return;
    }
    const next = !this.currentWindowPinned;
    try {
      this.currentWindowPinned = await this.desktop.setCurrentWindowAlwaysOnTop(next);
      this.syncPinButtonUi();
      if (this.app.sessionRole === SESSION_ROLE_STANDALONE_APP && this.app.standaloneAppPkg) {
        const curKey = this.getCurrentStandaloneAppKey();
        this.historyEntries = updateStandalonePinPref(
          curKey,
          this.currentWindowPinned,
          this.app.standaloneDeviceKey || ''
        );
        this.prefsStore.updatePref(this.app.standaloneDeviceKey || '', curKey, {
          pinnedOnTop: this.currentWindowPinned,
        });
      }
      this.app.diagnostics?.log('I', 'Standalone', `alwaysOnTop=${this.currentWindowPinned}`);
      this.app.showToast(this.currentWindowPinned ? t('standalone.pinOnToast') : t('standalone.pinOffToast'));
    } catch (err) {
      this.app.showToast(t('standalone.pinFailToast', { error: err?.message || err }));
    }
  }

  async closeAllWindows() {
    if (!this.desktop.enabled) return;
    try {
      await this.desktop.closeAllStandaloneWindows();
      this.app.diagnostics?.log('I', 'Standalone', 'Closed all standalone windows');
      await this.refreshActiveWindows();
      this.app.showToast(t('standalone.closeAllToast'));
    } catch (err) {
      this.app.showToast(t('standalone.closeAllFailToast', { error: err?.message || err }));
    }
  }

  // ==================== LOBBY HISTORY BAR ====================

  renderLobbyHistoryBar() {
    this.syncQuotaBadges();
    renderLobbyHistoryBarView({
      historyEntries: this.historyEntries,
      prefsStore: this.prefsStore,
      getActiveWindow: (pkg, uid) => this.getActiveWindowForPkg(pkg, uid),
      onLaunchEntry: (entry) => void this.launchFromHistoryEntry(entry),
      onToggleFavorite: (entry, entryKey) => {
        const next = this.prefsStore.toggleFavorite(entry.deviceKey, entryKey);
        this.renderLobbyHistoryBar();
        this.app.showToast(
          next.isFavorite
            ? t('standalone.favAddedToast', { name: entry.label })
            : t('standalone.favRemovedToast', { name: entry.label })
        );
      },
      onTogglePin: async (entry, entryKey, isPinned, isRunning) => {
        const nextPin = !isPinned;
        this.historyEntries = updateStandalonePinPref(entryKey, nextPin, entry.deviceKey);
        this.prefsStore.updatePref(entry.deviceKey, entryKey, { pinnedOnTop: nextPin });
        if (isRunning && this.desktop.enabled) {
          try {
            await this.desktop.setStandaloneWindowAlwaysOnTop(entryKey, nextPin);
            await this.refreshActiveWindows();
          } catch {
            // Ignore
          }
        }
        this.renderLobbyHistoryBar();
        this.app.showToast(
          nextPin
            ? t('standalone.histPinOnToast', { name: entry.label })
            : t('standalone.histPinOffToast', { name: entry.label })
        );
      },
      onRemoveEntry: (id) => {
        this.historyEntries = removeStandaloneHistory(id);
        this.renderLobbyHistoryBar();
        this.renderInCastHistoryStrip();
      },
    });
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
      {
        pkg: entry.pkg,
        cls: entry.cls || '',
        label: entry.label || entry.pkg,
        userId: entry.userId || 0,
        profileType: entry.profileType || '',
      },
      target,
      { alwaysOnTop: Boolean(entry.pinnedOnTop) }
    );
  }

  // ==================== LAUNCH + RELOAD CONFIRMATION ====================

  async requestLaunchStandaloneApp(appInfo, target, { alwaysOnTop, forceReload = false } = {}) {
    const pkg = String(appInfo?.pkg || '').trim();
    if (!pkg) return;
    const userId = Math.max(0, Math.round(Number(appInfo?.userId) || 0));
    const profileType = normalizeProfileType(appInfo?.profileType || '', userId);
    const appKey = formatAppKey(pkg, userId);
    const label = String(appInfo?.label || '').trim() || pkg;
    const cls = String(appInfo?.cls || '').trim();
    const windowTitle = formatStandaloneWindowTitle(pkg, label, userId, profileType);

    const resolvedDeviceKey =
      target.deviceKey || (target.transport === 'usb' ? `usb:${target.adbSerial || 'default'}` : `${target.host}:${target.port || 19878}`);
    const savedPref = this.prefsStore.getPref(resolvedDeviceKey, appKey);
    const resolvedGeo = resolvePopoutLaunchGeometry(savedPref);
    const resolvedPin =
      typeof alwaysOnTop === 'boolean'
        ? alwaysOnTop
        : Boolean(savedPref.pinnedOnTop || getStandalonePinPref(appKey, resolvedDeviceKey));

    try {
      // Ensure underlying transport (USB ADB forward or Wi-Fi TLS tunnel) is ready before opening window
      let usbLocalPort = target.usbLocalPort || 0;

      if (this.desktop.enabled) {
        const prep = await prepareDesktopStandaloneTransport(this, target);
        if (prep.cancelled) return;
        usbLocalPort = prep.usbLocalPort;
        const verifiedFp = prep.verifiedFp;

        const res = await this.desktop.openStandaloneWindow({
          pkg,
          cls,
          label: windowTitle,
          userId,
          profileType,
          host: target.host,
          port: target.port || 19878,
          transport: target.transport,
          usbLocalPort,
          deviceKey: resolvedDeviceKey,
          deviceName: target.deviceName || target.host || 'Android',
          certFingerprint: verifiedFp,
          alwaysOnTop: resolvedPin,
          forceReload,
          width: resolvedGeo.winWidth,
          height: resolvedGeo.winHeight,
          preset: savedPref.preferredPreset,
          dpi: savedPref.customDpi,
          compat: savedPref.compatibilityResolution,
        });

        if (res.status === 'already_open') {
          this.openReloadConfirmModal({
            appInfo: { pkg, cls, label: res.existingTitle || windowTitle, userId, profileType, appKey },
            target,
            alwaysOnTop: resolvedPin,
          });
          return;
        }
        if (res.status === 'limit_reached') {
          this.app.showToast(t('standalone.limitReachedToast', { max: res.maxCount || MAX_STANDALONE_WINDOWS }));
          await this.refreshActiveWindows();
          return;
        }

        this.historyEntries = recordStandaloneLaunch({
          pkg,
          cls,
          label,
          userId,
          profileType,
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
          `${forceReload ? 'Reload' : 'Open'} standalone ${windowTitle} (${appKey}) pinned=${resolvedPin} (${res.activeCount}/${res.maxCount})`
        );
        await this.refreshActiveWindows();
        this.app.showToast(
          forceReload
            ? t('standalone.reloadedToast', { name: windowTitle, count: res.activeCount, max: res.maxCount })
            : t('standalone.launchedToast', { name: windowTitle, count: res.activeCount, max: res.maxCount })
        );
        return;
      }

      await launchWebStandalonePopup(this, {
        pkg, cls, label, userId, profileType, appKey, windowTitle,
        target, usbLocalPort, resolvedDeviceKey, resolvedPin, forceReload,
      });
    } catch (err) {
      this.app.diagnostics?.log('W', 'Standalone', `Failed to open standalone ${appKey}: ${err?.message || err}`);
      this.app.showToast(t('standalone.launchFailToast', { error: err?.message || err }));
    }
  }

  openReloadConfirmModal(request) {
    this.pendingReloadRequest = request;
    showReloadConfirmModal(request.appInfo.label || request.appInfo.pkg);
  }

  async resolveReloadModal(action) {
    if ($('reloadStandaloneModalOverlay')) {
      $('reloadStandaloneModalOverlay').hidden = true;
    }
    const req = this.pendingReloadRequest;
    this.pendingReloadRequest = null;
    if (!req || action === 'cancel') return;

    if (action === 'focus') {
      const targetKey = req.appInfo.appKey || formatAppKey(req.appInfo.pkg, req.appInfo.userId || 0);
      if (this.desktop.enabled) {
        await this.desktop.focusStandaloneWindow(targetKey);
      } else {
        const rec = this.webPopups.get(targetKey);
        rec?.win?.focus();
      }
      this.app.showToast(t('standalone.focusedToast', { name: req.appInfo.label }));
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
    renderHistoryChipsStrip({
      wrapId: 'appsPopHistoryWrap',
      listId: 'appsPopHistoryList',
      historyEntries: this.historyEntries,
      getActiveWindow: (pkg, uid) => this.getActiveWindowForPkg(pkg, uid),
      includeRunningTitle: true,
      onSelectEntry: (entry) => {
        $('appsPopover').hidden = true;
        this.requestLaunchStandaloneApp(
          { pkg: entry.pkg, cls: entry.cls, label: entry.label, userId: entry.userId || 0, profileType: entry.profileType || '' },
          this.currentCastTarget(),
          { alwaysOnTop: entry.pinnedOnTop }
        );
      },
    });
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
    renderInCastAppListView({
      apps: this.app.currentApps,
      historyEntries: this.historyEntries,
      sortMode: this.inCastSortMode,
      target: this.currentCastTarget(),
      prefsStore: this.prefsStore,
      activeCount: this.getActiveCount(),
      getActiveWindow: (pkg, uid) => this.getActiveWindowForPkg(pkg, uid),
      onToggleFavorite: (deviceKey, appKey) => {
        this.prefsStore.toggleFavorite(deviceKey, appKey);
        this.renderInCastAppList();
      },
      onOpenInDesktop: (appItem) => {
        const payload = { action: 'launch', pkg: appItem.pkg, cls: appItem.cls || '' };
        if (Number(appItem.userId) > 0) payload.userId = Math.round(Number(appItem.userId));
        this.app.sendOpalFrame(TYPE_WINDOW_ACTION, 0, encodeWindowAction(payload));
        $('appsPopover').hidden = true;
      },
      onLaunchStandalone: (appItem, target) => {
        $('appsPopover').hidden = true;
        this.requestLaunchStandaloneApp(appItem, target);
      },
    });
  }

  // ==================== LOBBY REMOTE APP PICKER (app_catalog) ====================

  async openLobbyAppPicker(target) {
    this.closeLobbyCatalogSocket();
    this.lobbyCatalogTarget = { ...target };
    this.lobbyCatalogApps = [];

    if ($('lobbyAppsModalTitle')) {
      $('lobbyAppsModalTitle').textContent = t('standalone.modalTitleWithDevice', { device: target.deviceName || target.host || 'Android' });
    }
    if ($('lobbyAppSearchInput')) $('lobbyAppSearchInput').value = '';
    if ($('lobbyAppsList')) $('lobbyAppsList').innerHTML = '';
    if ($('lobbyAppsStatusText')) {
      $('lobbyAppsStatusText').textContent = t('standalone.modalStatusConnecting');
      $('lobbyAppsStatusText').hidden = false;
    }
    if ($('lobbyAppsModalOverlay')) $('lobbyAppsModalOverlay').hidden = false;

    await this.refreshActiveWindows();
    this.renderLobbyPickerHistoryStrip();

    try {
      const resolved = await resolveAppCatalogWsUrl(this, target);
      if (!resolved) {
        this.closeLobbyAppPicker();
        return;
      }
      const authToken = resolved.isUsb
        ? ''
        : (this.desktop.enabled && this.desktop.getTrustedToken(target.host, target.port || 19878)) || '';
      fetchAppCatalogOverWs(this, resolved.wsUrl, resolved.isUsb, authToken);
    } catch (err) {
      if ($('lobbyAppsStatusText')) {
        $('lobbyAppsStatusText').textContent = t('standalone.modalStatusFetchFail', { error: err?.message || err });
      }
      this.app.diagnostics?.log('W', 'AppCatalog', `Failed to fetch app catalog: ${err?.message || err}`);
    }
  }

  renderLobbyPickerHistoryStrip() {
    renderHistoryChipsStrip({
      wrapId: 'lobbyAppsHistoryWrap',
      listId: 'lobbyAppsHistoryList',
      historyEntries: this.historyEntries,
      getActiveWindow: (pkg, uid) => this.getActiveWindowForPkg(pkg, uid),
      onSelectEntry: (entry) => {
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
          { pkg: entry.pkg, cls: entry.cls, label: entry.label, userId: entry.userId || 0, profileType: entry.profileType || '' },
          target,
          { alwaysOnTop: entry.pinnedOnTop }
        );
      },
    });
  }

  renderLobbyAppPickerList() {
    this.syncQuotaBadges();
    this.renderLobbyPickerHistoryStrip();
    renderLobbyAppPickerListView({
      apps: this.lobbyCatalogApps,
      historyEntries: this.historyEntries,
      sortMode: this.lobbySortMode,
      target: this.lobbyCatalogTarget,
      prefsStore: this.prefsStore,
      activeCount: this.getActiveCount(),
      desktopEnabled: this.desktop.enabled,
      getActiveWindow: (pkg, uid) => this.getActiveWindowForPkg(pkg, uid),
      onToggleFavorite: (deviceKey, appKey) => {
        this.prefsStore.toggleFavorite(deviceKey, appKey);
        this.renderLobbyAppPickerList();
      },
      onCreateShortcut: (appItem, target) => void this.createDesktopShortcutForApp(appItem, target),
      onLaunchStandalone: (appItem, target) => {
        this.closeLobbyAppPicker();
        this.requestLaunchStandaloneApp(appItem, target);
      },
    });
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
