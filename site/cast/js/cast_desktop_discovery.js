/**
 * Desktop mDNS, ADB, custom ADB path, and USB Tethering discovery UI helpers
 * for OpalDesk Windows/Desktop Client.
 */

import { sanitizeCustomAdbPath } from './desktop_bridge.js';
import { loadLocalCustomAdbPath, saveLocalCustomAdbPath } from './cast_storage.js';
import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);

export function syncCustomAdbPathUi(app, status = app.desktop.adbStatus, { forceInput = false } = {}) {
  const savedPath = sanitizeCustomAdbPath(
    status?.customAdbPath || app.desktop.clientPrefs?.customAdbPath || ''
  );
  const inputEl = $('customAdbPathInput');
  if (inputEl && (forceInput || (!app.customAdbPathDirty && document.activeElement !== inputEl))) {
    inputEl.value = savedPath;
    app.customAdbPathDirty = false;
  }
  const summaryEl = $('adbPathSummary');
  if (summaryEl) {
    summaryEl.textContent = savedPath ? t('cast.adbSummaryRemembered') : t('cast.adbSummaryDefault');
  }
  const clearBtn = $('clearAdbPathBtn');
  if (clearBtn) clearBtn.hidden = !savedPath;
}

export async function restoreRememberedAdbPath(app, info) {
  const nativePath = sanitizeCustomAdbPath(info?.adb?.customAdbPath || info?.prefs?.customAdbPath || '');
  if (nativePath) {
    saveLocalCustomAdbPath(nativePath);
    syncCustomAdbPathUi(app, info?.adb || { customAdbPath: nativePath }, { forceInput: true });
    return;
  }
  const localPath = loadLocalCustomAdbPath();
  if (!localPath) {
    syncCustomAdbPathUi(app, info?.adb || null, { forceInput: true });
    return;
  }
  try {
    const restored = await app.desktop.setCustomAdbPath(localPath);
    saveLocalCustomAdbPath(restored?.customAdbPath || localPath);
    syncCustomAdbPathUi(app, restored, { forceInput: true });
  } catch {
    syncCustomAdbPathUi(app, { customAdbPath: localPath }, { forceInput: true });
  }
}

export async function flushPendingCustomAdbPath(app) {
  if (!app.desktop.enabled || !app.customAdbPathDirty) return;
  const inputEl = $('customAdbPathInput');
  if (!inputEl) {
    app.customAdbPathDirty = false;
    return;
  }
  const pendingPath = sanitizeCustomAdbPath(inputEl.value);
  const currentSaved = sanitizeCustomAdbPath(app.desktop.adbStatus?.customAdbPath || '');
  if (pendingPath !== currentSaved) {
    await saveCustomAdbPath(app, { customPath: pendingPath, silent: true });
  } else {
    app.customAdbPathDirty = false;
  }
}

export async function saveCustomAdbPath(app, { customPath, silent = false } = {}) {
  if (!app.desktop.enabled) return null;
  const cleaned = sanitizeCustomAdbPath(customPath);
  const inputEl = $('customAdbPathInput');
  if (inputEl) inputEl.value = cleaned;
  app.customAdbPathDirty = false;
  saveLocalCustomAdbPath(cleaned);

  try {
    const st = await app.desktop.setCustomAdbPath(cleaned || null);
    saveLocalCustomAdbPath(st?.customAdbPath || '');
    syncCustomAdbPathUi(app, st, { forceInput: true });
    if (!silent) {
      if (!cleaned) {
        app.showToast(st.available ? t('cast.adbClearedAuto', { path: st.adbPath }) : t('cast.adbCleared'));
      } else if (st.available) {
        app.showToast(t('cast.adbSaved', { path: st.adbPath }));
      } else {
        app.showToast(t('cast.adbSavedNotFound'));
      }
    }
    return st;
  } catch (err) {
    const msg = String(err?.message || err || t('common.unknownError'));
    app.diagnostics?.log('W', 'ADB', `Failed to save custom ADB path: ${msg}`);
    if (!silent) app.showToast(t('cast.adbSaveFailed', { error: msg }));
    return null;
  }
}

export function renderDesktopMdnsDevices(app, devices = []) {
  const listEl = $('desktopMdnsList');
  if (!listEl || !app.desktop.enabled) return;
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
      app.standalone.openLobbyAppPicker({
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
      app.selectTransportMode('wifi');
      $('castHostInput').value = dev.host;
      $('castPortInput').value = String(dev.port || 19878);
      app.startCastConnection();
    });

    actions.append(appBtn, btn);
    row.append(meta, actions);
    listEl.appendChild(row);
  }
}

export function syncDesktopUsbTetherUi(app) {
  const active = Boolean(app.desktop.enabled && app.usbTetherBetaEnabled);
  if ($('scanUsbTetherBtn')) $('scanUsbTetherBtn').hidden = !active;
  if ($('desktopUsbTetherList')) $('desktopUsbTetherList').hidden = !active;
}

export function renderDesktopUsbTetherDevices(app, devices = []) {
  const listEl = $('desktopUsbTetherList');
  if (!listEl || !app.desktop.enabled || !app.usbTetherBetaEnabled) return;
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
      app.standalone.openLobbyAppPicker({
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
      app.explicitCertPin = '';
      app.selectTransportMode('wifi');
      $('castHostInput').value = dev.host;
      $('castPortInput').value = String(dev.port || 19878);
      app.startCastConnection();
    });

    actions.append(appBtn, btn);
    row.append(meta, actions);
    listEl.appendChild(row);
  }
}

export async function refreshDesktopUsbTetherPanel(app, showToastOnDone = false) {
  if (!app.desktop.enabled || !app.usbTetherBetaEnabled) return;
  const listEl = $('desktopUsbTetherList');
  if (listEl) {
    listEl.hidden = false;
    listEl.innerHTML = `<span class="hint-text">${t('cast.usbTetherProbing')}</span>`;
  }
  try {
    const port = Number($('castPortInput')?.value) || 19878;
    const list = await app.desktop.scanUsbTetherDevices(port);
    renderDesktopUsbTetherDevices(app, list);
    if (showToastOnDone) {
      app.showToast(list.length ? t('cast.usbTetherToastFound', { count: list.length }) : t('cast.usbTetherToastNone'));
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

export async function refreshDesktopUsbPanel(app) {
  if (!app.desktop.enabled) return;
  syncDesktopUsbTetherUi(app);
  if (app.usbTetherBetaEnabled) {
    refreshDesktopUsbTetherPanel(app);
  }
  const hintEl = $('adbStatusHint');
  const listEl = $('desktopAdbList');
  listEl.innerHTML = '';
  try {
    await flushPendingCustomAdbPath(app);
    const status = await app.desktop.refreshAdbStatus();
    syncCustomAdbPathUi(app, status);
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
    const devices = await app.desktop.scanAdbDevices();
    if (!devices.length) {
      const empty = document.createElement('span');
      empty.className = 'hint-text';
      empty.textContent = t('cast.adbNoDevice');
      listEl.appendChild(empty);
      return;
    }
    const firstOnline = devices.find((d) => d.state === 'device');
    if (firstOnline && !app.selectedAdbSerial) {
      app.selectedAdbSerial = firstOnline.serial;
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
        app.selectedAdbSerial = dev.serial;
        app.standalone.openLobbyAppPicker({
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
        app.selectedAdbSerial = dev.serial;
        app.startCastConnection();
      });
      actions.append(appBtn, btn);
      row.append(meta, actions);
      listEl.appendChild(row);
    }
  } catch (err) {
    hintEl.textContent = String(err?.message || err || t('cast.adbScanFailed'));
  }
}
