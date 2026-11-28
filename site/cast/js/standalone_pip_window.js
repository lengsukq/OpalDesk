/**
 * Standalone PiP/floating window badge, toolbar preference UI, reload modal & Web popup helpers.
 */

import {
  decodeAppList,
  decodeAuthResult,
  decodeFrame,
  encodeFrame,
  encodeHello,
  formatAppKey,
  getProfileSearchKeywords,
  normalizeProfileType,
  SESSION_ROLE_APP_CATALOG,
  SESSION_ROLE_STANDALONE_APP,
  TYPE_APP_LIST,
  TYPE_AUTH_RESULT,
  TYPE_HELLO,
  TYPE_LIST_APPS,
} from './protocol.js';
import { MAX_STANDALONE_WINDOWS, recordStandaloneLaunch } from './standalone_history.js';
import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);

export function getProfileBadgeText(userId = 0, profileType = '') {
  const uid = Math.max(0, Math.round(Number(userId) || 0));
  if (uid <= 0) return '';
  const norm = normalizeProfileType(profileType, uid);
  if (norm === 'clone') return t('profile.badgeClone');
  if (norm === 'work') return t('profile.badgeWork');
  if (norm === 'private') return t('profile.badgePrivate');
  return t('profile.badgeSecondary', { userId: uid });
}

export function createProfileBadgeEl(userId = 0, profileType = '') {
  const text = getProfileBadgeText(userId, profileType);
  if (!text) return null;
  const norm = normalizeProfileType(profileType, userId);
  const el = document.createElement('span');
  el.className = `app-profile-badge ${norm || 'clone'}`;
  el.textContent = text;
  return el;
}

export function matchesAppSearch(appItem, query) {
  if (!query) return true;
  const q = query.toLowerCase();
  if ((appItem?.label || '').toLowerCase().includes(q) || (appItem?.pkg || '').toLowerCase().includes(q)) {
    return true;
  }
  const uid = Math.max(0, Math.round(Number(appItem?.userId) || 0));
  if (uid <= 0) return false;
  const badge = getProfileBadgeText(uid, appItem?.profileType).toLowerCase();
  if (badge && badge.includes(q)) return true;
  return getProfileSearchKeywords(uid, appItem?.profileType).some((kw) => kw.toLowerCase().includes(q));
}

export function syncStandaloneToolbarPrefsUi({ isStandalone, pref }) {
  const bar = $('tbStandalonePrefsGroup');
  if (bar) bar.hidden = !isStandalone;
  if (!isStandalone || !pref) return;

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

export function syncQuotaBadges({ count, desktopEnabled }) {
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
    closeAllBtn.hidden = !desktopEnabled || count === 0;
  }
}

export function syncPinButtonUi({ desktopEnabled, pinned }) {
  const btn = $('tbPinBtn');
  if (!btn) return;
  if (!desktopEnabled) {
    btn.hidden = true;
    return;
  }
  btn.hidden = false;
  btn.classList.toggle('pinned-active', pinned);
  btn.textContent = pinned ? t('standalone.pinnedBtn') : t('standalone.pinBtn');
  btn.title = pinned ? t('standalone.pinOnTitle') : t('standalone.pinOffTitle');
}

export function showReloadConfirmModal(appName) {
  if ($('reloadModalTitle')) {
    $('reloadModalTitle').textContent = t('standalone.reloadTitleWithApp', { name: appName });
  }
  if ($('reloadModalDesc')) {
    $('reloadModalDesc').textContent = t('standalone.reloadDescWithApp', { name: appName });
  }
  if ($('reloadStandaloneModalOverlay')) {
    $('reloadStandaloneModalOverlay').hidden = false;
  }
}

export function buildWebStandalonePopupUrl({
  target,
  usbLocalPort,
  pkg,
  cls,
  label,
  userId,
  profileType,
}) {
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
  return u.toString();
}

export function formatStandaloneWindowTitle(pkg, label, userId, profileType) {
  const badgeText = getProfileBadgeText(userId, profileType);
  const cleanLabel = String(label || '').trim() || pkg;
  return badgeText && !cleanLabel.includes(badgeText) ? `${cleanLabel} (${badgeText})` : cleanLabel;
}

export function collectWebPopupWindows(webPopups) {
  const next = [];
  for (const [appKey, rec] of webPopups.entries()) {
    if (!rec.win || rec.win.closed) {
      webPopups.delete(appKey);
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
  return next;
}

export function resolveActiveWindowForKey(activeWindows, pkg, userId = 0) {
  const targetKey = formatAppKey(pkg, userId);
  return activeWindows.find((w) => {
    const winKey = w.appKey || formatAppKey(w.pkg, w.userId || 0);
    return winKey === targetKey;
  }) || null;
}

export async function prepareDesktopStandaloneTransport(ctrl, target) {
  let usbLocalPort = target.usbLocalPort || 0;
  let verifiedFp = target.certFingerprint || '';

  if (target.transport === 'usb') {
    if (!usbLocalPort) {
      const serial = target.adbSerial || ctrl.app.selectedAdbSerial;
      const devices = await ctrl.desktop.scanAdbDevices();
      const chosen =
        devices.find((d) => d.serial === serial && d.state === 'device') ||
        devices.find((d) => d.state === 'device');
      if (!chosen) {
        throw new Error(t('standalone.errNoUsb'));
      }
      const fwd = await ctrl.desktop.bootstrapAndForwardUsb(chosen.serial);
      usbLocalPort = fwd.localPort;
      target.adbSerial = chosen.serial;
      target.usbLocalPort = usbLocalPort;
    }
    return { cancelled: false, usbLocalPort, verifiedFp };
  }

  if (!target.host) throw new Error(t('standalone.errNoIp'));
  if (!verifiedFp) {
    const tunnel = await ctrl.app.ensureDesktopTlsTunnel(target.host, target.port || 19878, '/ws', '');
    if (!tunnel) return { cancelled: true, usbLocalPort, verifiedFp };
    verifiedFp = tunnel.verifiedFingerprint;
    target.certFingerprint = verifiedFp;
  }
  return { cancelled: false, usbLocalPort, verifiedFp };
}

export async function launchWebStandalonePopup(ctrl, {
  pkg, cls, label, userId, profileType, appKey, windowTitle, target, usbLocalPort, resolvedDeviceKey, resolvedPin, forceReload,
}) {
  await ctrl.refreshActiveWindows();
  const existingPopup = ctrl.webPopups.get(appKey);
  if (existingPopup && existingPopup.win && !existingPopup.win.closed && !forceReload) {
    ctrl.openReloadConfirmModal({
      appInfo: { pkg, cls, label: windowTitle, userId, profileType, appKey },
      target,
      alwaysOnTop: resolvedPin,
    });
    return;
  }
  if (forceReload && existingPopup && existingPopup.win && !existingPopup.win.closed) {
    try { existingPopup.win.close(); } catch {}
    ctrl.webPopups.delete(appKey);
  }
  if (ctrl.webPopups.size >= MAX_STANDALONE_WINDOWS) {
    ctrl.app.showToast(t('standalone.limitReachedToast', { max: MAX_STANDALONE_WINDOWS }));
    return;
  }

  const popupUrl = buildWebStandalonePopupUrl({
    target, usbLocalPort, pkg, cls, label, userId, profileType,
  });
  const popup = window.open(popupUrl, `opal_app_${appKey.replace(/[^a-zA-Z0-9_]/g, '_')}`, 'width=440,height=880');
  if (popup) {
    ctrl.webPopups.set(appKey, { win: popup, title: windowTitle, pkg, userId });
  }
  ctrl.historyEntries = recordStandaloneLaunch({
    pkg, cls, label, userId, profileType,
    deviceKey: resolvedDeviceKey,
    deviceName: target.deviceName || target.host || 'Android',
    transport: target.transport,
    host: target.host,
    port: target.port || 19878,
    pinnedOnTop: resolvedPin,
  });
  await ctrl.refreshActiveWindows();
}

export async function resolveAppCatalogWsUrl(ctrl, target) {
  const isUsb = target.transport === 'usb';
  if (ctrl.desktop.enabled) {
    if (isUsb) {
      const serial = target.adbSerial || ctrl.app.selectedAdbSerial;
      const fwd = await ctrl.desktop.bootstrapAndForwardUsb(serial);
      ctrl.lobbyCatalogTarget.usbLocalPort = fwd.localPort;
      ctrl.lobbyCatalogTarget.adbSerial = fwd.serial;
      return { wsUrl: fwd.localWsUrl, isUsb };
    }
    const tunnel = await ctrl.app.ensureDesktopTlsTunnel(
      target.host,
      target.port || 19878,
      '/ws',
      target.certFingerprint || ''
    );
    if (!tunnel) return null;
    ctrl.lobbyCatalogTarget.certFingerprint = tunnel.verifiedFingerprint;
    return { wsUrl: tunnel.localWsUrl, isUsb };
  }
  return {
    wsUrl: isUsb
      ? `ws://127.0.0.1:${target.port || 19878}/ws`
      : `wss://${target.host}:${target.port || 19878}/ws`,
    isUsb,
  };
}

export function fetchAppCatalogOverWs(ctrl, wsUrl, isUsb, authToken) {
  const ws = new WebSocket(wsUrl);
  ws.binaryType = 'arraybuffer';
  ctrl.lobbyCatalogWs = ws;

  ctrl.lobbyCatalogTimeout = setTimeout(() => {
    if (ctrl.lobbyCatalogWs === ws) {
      ctrl.closeLobbyCatalogSocket();
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
      clientId: ctrl.app.clientId,
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
        ctrl.closeLobbyCatalogSocket();
        return;
      }
      if (auth.deviceName && ctrl.lobbyCatalogTarget) {
        ctrl.lobbyCatalogTarget.deviceName = auth.deviceName;
      }
      if (!isUsb && auth.authToken && ctrl.desktop.enabled && ctrl.lobbyCatalogTarget?.certFingerprint) {
        ctrl.desktop
          .upsertTrustedDevice({
            host: ctrl.lobbyCatalogTarget.host,
            port: ctrl.lobbyCatalogTarget.port || 19878,
            displayName: auth.deviceName || ctrl.lobbyCatalogTarget.host,
            model: '',
            certFingerprint: ctrl.lobbyCatalogTarget.certFingerprint,
            authToken: auth.authToken,
            permanent: Boolean(auth.permanent),
            lastConnectedAtMs: Date.now(),
          })
          .catch(() => {});
      }
      ws.send(encodeFrame(TYPE_LIST_APPS));
    } else if (frame.type === TYPE_APP_LIST) {
      ctrl.lobbyCatalogApps = decodeAppList(frame.payload);
      ctrl.closeLobbyCatalogSocket();
      if ($('lobbyAppsStatusText')) {
        $('lobbyAppsStatusText').textContent = t('standalone.modalStatusLoaded', { count: ctrl.lobbyCatalogApps.length });
      }
      ctrl.renderLobbyAppPickerList();
    }
  };

  ws.onerror = () => {
    if (ctrl.lobbyCatalogWs === ws && $('lobbyAppsStatusText')) {
      $('lobbyAppsStatusText').textContent = t('standalone.modalStatusConnectFail');
    }
    ctrl.closeLobbyCatalogSocket();
  };
}
