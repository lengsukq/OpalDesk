/**
 * DOM view renderers for Companion Notifications list and BLE Nearby Discovery card.
 */

import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);

function createEl(tag, className = '', text = '') {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text) el.textContent = text;
  return el;
}

export function renderBleNearbyCard({
  desktopEnabled,
  bleSupported,
  bleDevices,
  bleNote,
  bleBusyAddress,
  trustedDevices,
  onTriggerHotspot,
}) {
  const card = $('bleNearbyCard');
  const list = $('bleNearbyList');
  const note = $('bleNearbyNote');
  if (!card || !list) return;
  card.hidden = !desktopEnabled;
  if (note) note.textContent = bleNote || (bleSupported ? t('companion.bleReady') : t('companion.bleUnavailable'));
  list.textContent = '';

  if (!bleDevices.length) {
    list.appendChild(createEl('div', 'hint-text', t('companion.bleEmpty')));
    return;
  }

  for (const dev of bleDevices) {
    const trustedMatch = (trustedDevices || []).find(
      (td) => dev.certFingerprintPrefix && td.certFingerprint?.toLowerCase().startsWith(dev.certFingerprintPrefix.toLowerCase())
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
        bleBusyAddress === dev.address ? t('companion.bleWaking') : t('companion.bleWakeBtn')
      );
      hsBtn.type = 'button';
      hsBtn.disabled = Boolean(bleBusyAddress);
      hsBtn.addEventListener('click', () => onTriggerHotspot(dev, trustedMatch));
      actions.appendChild(hsBtn);
    } else if (!trustedMatch?.authToken) {
      actions.appendChild(createEl('span', 'status-pill', t('companion.bleNeedTrust')));
    }
    item.append(info, actions);
    list.appendChild(item);
  }
}

export function renderNotificationsList({
  items,
  onDismiss,
  onCopyOtp,
  onInvokeAction,
  onPopoutApp,
  onReply,
}) {
  const list = $('companionNotifList');
  const countEl = $('companionNotifCount');
  if (!list) return;
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
      closeBtn.addEventListener('click', () => onDismiss(item.key));
      head.appendChild(closeBtn);
    }

    const body = createEl('div', 'companion-notif-text', item.text);
    card.append(head, body);

    const hasFooter = Boolean(item.otpCode || item.actions.length || item.canReply || item.pkg);
    if (hasFooter) {
      const footer = createEl('div', 'companion-notif-actions');
      if (item.otpCode) {
        const otpBtn = createEl('button', 'btn-pill small primary', t('companion.notifCopyOtp', { code: item.otpCode }));
        otpBtn.type = 'button';
        otpBtn.addEventListener('click', () => onCopyOtp(item.otpCode));
        footer.appendChild(otpBtn);
      }
      for (const act of item.actions.slice(0, 3)) {
        const actBtn = createEl('button', 'btn-pill small', act.label);
        actBtn.type = 'button';
        actBtn.addEventListener('click', () => onInvokeAction(item.key, act.index));
        footer.appendChild(actBtn);
      }
      if (item.pkg) {
        const popBtn = createEl('button', 'btn-pill small ghost', t('companion.notifPopout'));
        popBtn.type = 'button';
        popBtn.addEventListener('click', () => onPopoutApp(item.pkg, item.appLabel || item.pkg));
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
        onReply(item.key, val);
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
