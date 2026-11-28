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
  formatAppKey,
  getProfileSearchKeywords,
  normalizeProfileType,
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
import {
  STORAGE_STANDALONE_PREFS,
  POPOUT_ASPECT_PRESETS,
  createStandalonePrefsStore,
  resolvePopoutLaunchGeometry,
  formatPopoutPrefBadges,
} from './standalone_prefs.js';
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

  getProfileBadgeText(userId = 0, profileType = '') {
    const uid = Math.max(0, Math.round(Number(userId) || 0));
    if (uid <= 0) return '';
    const norm = normalizeProfileType(profileType, uid);
    if (norm === 'clone') return t('profile.badgeClone');
    if (norm === 'work') return t('profile.badgeWork');
    if (norm === 'private') return t('profile.badgePrivate');
    return t('profile.badgeSecondary', { userId: uid });
  }

  createProfileBadgeEl(userId = 0, profileType = '') {
    const text = this.getProfileBadgeText(userId, profileType);
    if (!text) return null;
    const norm = normalizeProfileType(profileType, userId);
    const el = document.createElement('span');
    el.className = `app-profile-badge ${norm || 'clone'}`;
    el.textContent = text;
    return el;
  }

  matchesAppSearch(appItem, query) {
    if (!query) return true;
    const q = query.toLowerCase();
    if ((appItem?.label || '').toLowerCase().includes(q) || (appItem?.pkg || '').toLowerCase().includes(q)) {
      return true;
    }
    const uid = Math.max(0, Math.round(Number(appItem?.userId) || 0));
    if (uid <= 0) return false;
    const badge = this.getProfileBadgeText(uid, appItem?.profileType).toLowerCase();
    if (badge && badge.includes(q)) return true;
    return getProfileSearchKeywords(uid, appItem?.profileType).some((kw) => kw.toLowerCase().includes(q));
  }

  getCurrentStandalonePref() {
    if (this.app.sessionRole !== SESSION_ROLE_STANDALONE_APP || !this.app.standaloneAppPkg) return null;
    return this.prefsStore.getPref(this.app.standaloneDeviceKey, this.getCurrentStandaloneAppKey());
  }

  syncStandaloneToolbarPrefsUi() {
    const isStandalone = this.app.sessionRole === SESSION_ROLE_STANDALONE_APP && Boolean(this.app.standaloneAppPkg);
    const bar = $('tbStandalonePrefsGroup');
    if (bar) bar.hidden = !isStandalone;
    if (!isStandalone) return;

    const pref = this.getCurrentStandalonePref();
    if (!pref) return;

    const favBtn = $('tbPopoutFavoriteBtn');
    if (favBtn) {
      favBtn.textContent = pref.isFavorite ? '★' : '☆';
      favBtn.classList.toggle('active', pref.isFavorite);
      favBtn.title = pref.isFavorite ? t('standalone.favRemoveTitle') : t('standalone.favAddTitle');
    }
    const presetSel = $('tbPopoutPresetSelect');
    if (presetSel && presetSel.querySelector(`option[value="${pref.preferredPreset}"]`)) {
      presetSel.value = pref.preferredPreset;
    }
    const compatBtn = $('tbPopoutCompatBtn');
    if (compatBtn) {
      compatBtn.classList.toggle('active', pref.compatibilityResolution);
      compatBtn.title = pref.compatibilityResolution
        ? t('standalone.compatOnTitle')
        : t('standalone.compatOffTitle');
    }
    const dpiSel = $('tbPopoutDpiSelect');
    if (dpiSel && dpiSel.querySelector(`option[value="${pref.customDpi}"]`)) {
      dpiSel.value = String(pref.customDpi);
    }
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
    const badgeText = this.getProfileBadgeText(userId, profileType);
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
      const next = [];
      for (const [appKey, rec] of this.webPopups.entries()) {
        if (!rec.win || rec.win.closed) {
          this.webPopups.delete(appKey);
        } else {
          next.push({
            label: `standalone-${appKey}`,
            pkg: rec.pkg || appKey,
            appKey,
            userId: rec.userId || 0,
            title: rec.title,
            alwaysOnTop: false,
          });
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

  getActiveWindowForPkg(pkg, userId = 0) {
    const targetKey = formatAppKey(pkg, userId);
    return this.activeWindows.find((w) => {
      const winKey = w.appKey || formatAppKey(w.pkg, w.userId || 0);
      return winKey === targetKey;
    }) || null;
  }

  getActiveCount() {
    return this.activeWindows.length;
  }

  syncQuotaBadges() {
    const count = this.getActiveCount();
    const text = t('standalone.quotaOpened', { count, max: MAX_STANDALONE_WINDOWS });
    const full = count >= MAX_STANDALONE_WINDOWS;

    for (const id of ['standaloneQuotaPill', 'lobbyAppsQuotaBadge']) {
      const el = $(id);
      if (!el) continue;
      el.textContent = text;
      el.className = `status-pill mono ${full ? 'warn' : count > 0 ? 'ok' : ''}`;
    }
    const popBadge = $('appsPopQuotaBadge');
    if (popBadge) {
      popBadge.textContent = t('standalone.quotaPop', { count, max: MAX_STANDALONE_WINDOWS });
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
    btn.textContent = this.currentWindowPinned ? t('standalone.pinnedBtn') : t('standalone.pinBtn');
    btn.title = this.currentWindowPinned
      ? t('standalone.pinOnTitle')
      : t('standalone.pinOffTitle');
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
    const listEl = $('standaloneHistoryList');
    const clearBtn = $('clearStandaloneHistoryBtn');
    if (!listEl) return;

    this.syncQuotaBadges();
    const entries = [...this.historyEntries].sort((a, b) => {
      const fa = this.prefsStore.getPref(a.deviceKey, a.appKey || formatAppKey(a.pkg, a.userId || 0));
      const fb = this.prefsStore.getPref(b.deviceKey, b.appKey || formatAppKey(b.pkg, b.userId || 0));
      if (fa.isFavorite !== fb.isFavorite) return fa.isFavorite ? -1 : 1;
      if (fa.isFavorite && fb.isFavorite && fa.favoriteOrder !== fb.favoriteOrder) {
        return fa.favoriteOrder - fb.favoriteOrder;
      }
      return 0;
    });
    if (clearBtn) clearBtn.hidden = entries.length === 0;

    listEl.innerHTML = '';
    if (!entries.length) {
      const empty = document.createElement('span');
      empty.className = 'hint-text';
      empty.textContent = t('standalone.historyEmpty');
      listEl.appendChild(empty);
      return;
    }

    for (const entry of entries) {
      const entryKey = entry.appKey || formatAppKey(entry.pkg, entry.userId || 0);
      const liveWin = this.getActiveWindowForPkg(entry.pkg, entry.userId || 0);
      const isRunning = Boolean(liveWin);
      const pref = this.prefsStore.getPref(entry.deviceKey, entryKey);
      const isPinned = isRunning ? Boolean(liveWin.alwaysOnTop) : Boolean(pref.pinnedOnTop || entry.pinnedOnTop);
      const badges = formatPopoutPrefBadges(pref);

      const card = document.createElement('div');
      card.className = `standalone-hist-card${isRunning ? ' running' : ''}${pref.isFavorite ? ' is-favorite' : ''}`;
      card.title = isRunning
        ? t('standalone.histCardRunningTitle', { name: entry.label })
        : t('standalone.histCardOpenTitle', { name: entry.label });
      card.addEventListener('click', () => this.launchFromHistoryEntry(entry));

      const avatar = document.createElement('div');
      avatar.className = 'app-avatar-badge';
      avatar.style.background = getAppAvatarColor(entryKey);
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
      const profileBadge = this.createProfileBadgeEl(entry.userId, entry.profileType);
      if (profileBadge) {
        name.appendChild(document.createTextNode(' '));
        name.appendChild(profileBadge);
      }
      const sub = document.createElement('div');
      sub.className = 'standalone-hist-sub';
      const transportTag = entry.transport === 'usb' ? 'USB' : 'Wi-Fi';
      const badgeSuffix = badges.length ? ` · ${badges.join(' / ')}` : '';
      sub.textContent = `${isRunning ? t('standalone.histRunningPrefix') : ''}${entry.deviceName || entry.host || 'Android'} (${transportTag})${badgeSuffix}`;
      meta.append(name, sub);

      const favBtn = document.createElement('button');
      favBtn.type = 'button';
      favBtn.className = `hist-icon-btn fav${pref.isFavorite ? ' active' : ''}`;
      favBtn.title = pref.isFavorite ? t('standalone.favRemoveTitle') : t('standalone.favAddTitle');
      favBtn.textContent = pref.isFavorite ? '★' : '☆';
      favBtn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        const next = this.prefsStore.toggleFavorite(entry.deviceKey, entryKey);
        this.renderLobbyHistoryBar();
        this.app.showToast(
          next.isFavorite
            ? t('standalone.favAddedToast', { name: entry.label })
            : t('standalone.favRemovedToast', { name: entry.label })
        );
      });

      const pinBtn = document.createElement('button');
      pinBtn.type = 'button';
      pinBtn.className = `hist-icon-btn${isPinned ? ' pinned' : ''}`;
      pinBtn.title = isPinned ? t('standalone.histPinSetTitle') : t('standalone.histPinUnsetTitle');
      pinBtn.textContent = '📌';
      pinBtn.addEventListener('click', async (ev) => {
        ev.stopPropagation();
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
      });

      const delBtn = document.createElement('button');
      delBtn.type = 'button';
      delBtn.className = 'hist-icon-btn remove';
      delBtn.title = t('standalone.histRemoveTitle');
      delBtn.textContent = '×';
      delBtn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        this.historyEntries = removeStandaloneHistory(entry.id);
        this.renderLobbyHistoryBar();
        this.renderInCastHistoryStrip();
      });

      card.append(avatar, meta, favBtn, pinBtn, delBtn);
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
    const badgeText = this.getProfileBadgeText(userId, profileType);
    const windowTitle = badgeText && !label.includes(badgeText) ? `${label} (${badgeText})` : label;

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
              throw new Error(t('standalone.errNoUsb'));
            }
            const fwd = await this.desktop.bootstrapAndForwardUsb(chosen.serial);
            usbLocalPort = fwd.localPort;
            target.adbSerial = chosen.serial;
            target.usbLocalPort = usbLocalPort;
          }
        } else {
          if (!target.host) throw new Error(t('standalone.errNoIp'));
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

      // Pure Web browser fallback (with max 3 + reload confirmation)
      await this.refreshActiveWindows();
      const existingPopup = this.webPopups.get(appKey);
      if (existingPopup && existingPopup.win && !existingPopup.win.closed && !forceReload) {
        this.openReloadConfirmModal({
          appInfo: { pkg, cls, label: windowTitle, userId, profileType, appKey },
          target,
          alwaysOnTop: resolvedPin,
        });
        return;
      }
      if (forceReload && existingPopup && existingPopup.win && !existingPopup.win.closed) {
        try { existingPopup.win.close(); } catch {}
        this.webPopups.delete(appKey);
      }
      if (this.webPopups.size >= MAX_STANDALONE_WINDOWS) {
        this.app.showToast(t('standalone.limitReachedToast', { max: MAX_STANDALONE_WINDOWS }));
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
      if (userId > 0) {
        u.searchParams.set('userId', String(userId));
        if (profileType) u.searchParams.set('profileType', profileType);
      }

      const popup = window.open(u.toString(), `opal_app_${appKey.replace(/[^a-zA-Z0-9_]/g, '_')}`, 'width=440,height=880');
      if (popup) {
        this.webPopups.set(appKey, { win: popup, title: windowTitle, pkg, userId });
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
        pinnedOnTop: resolvedPin,
      });
      await this.refreshActiveWindows();
    } catch (err) {
      this.app.diagnostics?.log('W', 'Standalone', `Failed to open standalone ${appKey}: ${err?.message || err}`);
      this.app.showToast(t('standalone.launchFailToast', { error: err?.message || err }));
    }
  }

  openReloadConfirmModal(request) {
    this.pendingReloadRequest = request;
    const name = request.appInfo.label || request.appInfo.pkg;
    if ($('reloadModalTitle')) {
      $('reloadModalTitle').textContent = t('standalone.reloadTitleWithApp', { name });
    }
    if ($('reloadModalDesc')) {
      $('reloadModalDesc').textContent = t('standalone.reloadDescWithApp', { name });
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
      const entryKey = entry.appKey || formatAppKey(entry.pkg, entry.userId || 0);
      const isRunning = Boolean(this.getActiveWindowForPkg(entry.pkg, entry.userId || 0));
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = `pop-history-chip${isRunning ? ' running' : ''}`;
      chip.title = isRunning ? t('standalone.popChipRunningTitle') : t('standalone.popChipOpenTitle');

      const dot = document.createElement('span');
      dot.className = 'app-avatar-badge sm';
      dot.style.background = getAppAvatarColor(entryKey);
      dot.textContent = getAppAvatarInitial(entry.label, entry.pkg);

      const txt = document.createElement('span');
      txt.textContent = entry.label;

      chip.append(dot, txt);
      const profileBadge = this.createProfileBadgeEl(entry.userId, entry.profileType);
      if (profileBadge) chip.appendChild(profileBadge);
      chip.addEventListener('click', () => {
        $('appsPopover').hidden = true;
        this.requestLaunchStandaloneApp(
          { pkg: entry.pkg, cls: entry.cls, label: entry.label, userId: entry.userId || 0, profileType: entry.profileType || '' },
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

    const target = this.currentCastTarget();
    const baseSorted = sortAppsForLauncher(this.app.currentApps, this.historyEntries, this.inCastSortMode);
    const sorted = this.prefsStore.sortAppsWithFavorites(baseSorted, target.deviceKey);
    const filtered = sorted.filter((a) => this.matchesAppSearch(a, q));
    if (!filtered.length) {
      listEl.innerHTML = `<div class="hint-text" style="padding:10px;text-align:center">${t('standalone.noMatch')}</div>`;
      return;
    }

    const activeCount = this.getActiveCount();

    for (const appItem of filtered.slice(0, 100)) {
      const appKey = appItem.appKey || formatAppKey(appItem.pkg, appItem.userId || 0);
      const pref = this.prefsStore.getPref(target.deviceKey, appKey);
      const row = document.createElement('div');
      row.className = `app-row${pref.isFavorite ? ' is-favorite' : ''}`;

      const info = document.createElement('div');
      info.className = 'app-info';
      const favBtn = document.createElement('button');
      favBtn.type = 'button';
      favBtn.className = `app-fav-star${pref.isFavorite ? ' active' : ''}`;
      favBtn.textContent = pref.isFavorite ? '★' : '☆';
      favBtn.title = pref.isFavorite ? t('standalone.favRemoveTitle') : t('standalone.favAddTitle');
      favBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        this.prefsStore.toggleFavorite(target.deviceKey, appKey);
        this.renderInCastAppList();
      });
      info.appendChild(favBtn);

      if (appItem.icon) {
        const img = document.createElement('img');
        img.src = `data:image/png;base64,${appItem.icon}`;
        img.alt = '';
        info.appendChild(img);
      } else {
        const avatar = document.createElement('span');
        avatar.className = 'app-avatar-badge sm';
        avatar.style.background = getAppAvatarColor(appKey);
        avatar.textContent = getAppAvatarInitial(appItem.label, appItem.pkg);
        info.appendChild(avatar);
      }
      const lbl = document.createElement('span');
      lbl.className = 'app-label';
      lbl.textContent = appItem.label || appItem.pkg;
      info.appendChild(lbl);

      const profileBadge = this.createProfileBadgeEl(appItem.userId, appItem.profileType);
      if (profileBadge) info.appendChild(profileBadge);

      const badges = formatPopoutPrefBadges(pref);
      if (badges.length) {
        const b = document.createElement('span');
        b.className = 'app-pref-badge';
        b.textContent = badges[0];
        info.appendChild(b);
      }

      const btns = document.createElement('div');
      btns.className = 'app-btns';

      const openBtn = document.createElement('button');
      openBtn.type = 'button';
      openBtn.className = 'mini-btn';
      openBtn.textContent = t('standalone.openBtn');
      openBtn.addEventListener('click', () => {
        const payload = { action: 'launch', pkg: appItem.pkg, cls: appItem.cls || '' };
        if (Number(appItem.userId) > 0) payload.userId = Math.round(Number(appItem.userId));
        this.app.sendOpalFrame(TYPE_WINDOW_ACTION, 0, encodeWindowAction(payload));
        $('appsPopover').hidden = true;
      });
      btns.appendChild(openBtn);

      const isRunning = Boolean(this.getActiveWindowForPkg(appItem.pkg, appItem.userId || 0));
      const popBtn = document.createElement('button');
      popBtn.type = 'button';
      popBtn.className = 'mini-btn';
      if (isRunning) {
        popBtn.textContent = t('standalone.windowOpenBtn');
      } else if (activeCount >= MAX_STANDALONE_WINDOWS) {
        popBtn.textContent = t('standalone.fullBtn', { max: MAX_STANDALONE_WINDOWS });
        popBtn.disabled = true;
      } else {
        popBtn.textContent = t('standalone.popoutBtn');
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
        $('lobbyAppsStatusText').textContent = t('standalone.modalStatusFetchFail', { error: err?.message || err });
      }
      this.app.diagnostics?.log('W', 'AppCatalog', `Failed to fetch app catalog: ${err?.message || err}`);
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
          $('lobbyAppsStatusText').textContent = t('standalone.modalStatusTimeout');
        }
      }
    }, 20000);

    ws.onopen = () => {
      if ($('lobbyAppsStatusText')) {
        $('lobbyAppsStatusText').textContent = !isUsb && !authToken
          ? t('standalone.modalStatusAllowPhone')
          : t('standalone.modalStatusReading');
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
            $('lobbyAppsStatusText').textContent = auth.message || t('standalone.modalStatusRejected');
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
          $('lobbyAppsStatusText').textContent = t('standalone.modalStatusLoaded', { count: this.lobbyCatalogApps.length });
        }
        this.renderLobbyAppPickerList();
      }
    };

    ws.onerror = () => {
      if (this.lobbyCatalogWs === ws && $('lobbyAppsStatusText')) {
        $('lobbyAppsStatusText').textContent = t('standalone.modalStatusConnectFail');
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
      const entryKey = entry.appKey || formatAppKey(entry.pkg, entry.userId || 0);
      const isRunning = Boolean(this.getActiveWindowForPkg(entry.pkg, entry.userId || 0));
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = `pop-history-chip${isRunning ? ' running' : ''}`;

      const dot = document.createElement('span');
      dot.className = 'app-avatar-badge sm';
      dot.style.background = getAppAvatarColor(entryKey);
      dot.textContent = getAppAvatarInitial(entry.label, entry.pkg);

      const txt = document.createElement('span');
      txt.textContent = entry.label;

      chip.append(dot, txt);
      const profileBadge = this.createProfileBadgeEl(entry.userId, entry.profileType);
      if (profileBadge) chip.appendChild(profileBadge);
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
          { pkg: entry.pkg, cls: entry.cls, label: entry.label, userId: entry.userId || 0, profileType: entry.profileType || '' },
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
    const target = this.lobbyCatalogTarget;
    const deviceKey = target?.deviceKey || (target?.host ? `${target.host}:${target.port || 19878}` : '');
    const baseSorted = sortAppsForLauncher(this.lobbyCatalogApps, this.historyEntries, this.lobbySortMode);
    const sorted = this.prefsStore.sortAppsWithFavorites(baseSorted, deviceKey);
    const filtered = sorted.filter((a) => this.matchesAppSearch(a, q));
    if (!filtered.length) {
      listEl.innerHTML = `<div class="hint-text" style="padding:10px;text-align:center">${t('standalone.noMatch')}</div>`;
      return;
    }

    const activeCount = this.getActiveCount();

    for (const appItem of filtered.slice(0, 120)) {
      const appKey = appItem.appKey || formatAppKey(appItem.pkg, appItem.userId || 0);
      const pref = this.prefsStore.getPref(deviceKey, appKey);
      const row = document.createElement('div');
      row.className = `app-row${pref.isFavorite ? ' is-favorite' : ''}`;

      const info = document.createElement('div');
      info.className = 'app-info';
      const favBtn = document.createElement('button');
      favBtn.type = 'button';
      favBtn.className = `app-fav-star${pref.isFavorite ? ' active' : ''}`;
      favBtn.textContent = pref.isFavorite ? '★' : '☆';
      favBtn.title = pref.isFavorite ? t('standalone.favRemoveTitle') : t('standalone.favAddTitle');
      favBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        this.prefsStore.toggleFavorite(deviceKey, appKey);
        this.renderLobbyAppPickerList();
      });

      const avatar = document.createElement('span');
      avatar.className = 'app-avatar-badge sm';
      avatar.style.background = getAppAvatarColor(appKey);
      avatar.textContent = getAppAvatarInitial(appItem.label, appItem.pkg);

      const lbl = document.createElement('span');
      lbl.className = 'app-label';
      lbl.textContent = appItem.label || appItem.pkg;
      info.append(favBtn, avatar, lbl);

      const profileBadge = this.createProfileBadgeEl(appItem.userId, appItem.profileType);
      if (profileBadge) info.appendChild(profileBadge);

      const badges = formatPopoutPrefBadges(pref);
      if (badges.length) {
        const b = document.createElement('span');
        b.className = 'app-pref-badge';
        b.textContent = badges[0];
        info.appendChild(b);
      }

      const btns = document.createElement('div');
      btns.className = 'app-btns';

      if (this.desktop.enabled && target) {
        const lnkBtn = document.createElement('button');
        lnkBtn.type = 'button';
        lnkBtn.className = 'mini-btn';
        lnkBtn.title = t('standalone.desktopShortcutTitle');
        lnkBtn.textContent = t('standalone.desktopShortcutBtn');
        lnkBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          void this.createDesktopShortcutForApp(appItem, target);
        });
        btns.appendChild(lnkBtn);
      }

      const isRunning = Boolean(this.getActiveWindowForPkg(appItem.pkg, appItem.userId || 0));
      const openBtn = document.createElement('button');
      openBtn.type = 'button';
      openBtn.className = isRunning ? 'mini-btn' : 'primary-btn compact';
      if (isRunning) {
        openBtn.textContent = t('standalone.windowOpenActionBtn');
      } else if (activeCount >= MAX_STANDALONE_WINDOWS) {
        openBtn.textContent = t('standalone.fullBtn', { max: MAX_STANDALONE_WINDOWS });
        openBtn.disabled = true;
      } else {
        openBtn.textContent = t('standalone.openWindowBtn');
      }
      openBtn.addEventListener('click', () => {
        if (!target) return;
        this.closeLobbyAppPicker();
        this.requestLaunchStandaloneApp(appItem, target);
      });
      btns.appendChild(openBtn);

      row.append(info, btns);
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
