/**
 * DOM view renderers for Standalone History Bar, In-Cast App Picker, and Lobby Remote App Picker.
 */

import { formatAppKey } from './protocol.js';
import {
  getAppAvatarColor,
  getAppAvatarInitial,
  MAX_STANDALONE_WINDOWS,
  sortAppsForLauncher,
} from './standalone_history.js';
import { formatPopoutPrefBadges } from './standalone_prefs.js';
import { createProfileBadgeEl, matchesAppSearch } from './standalone_pip_window.js';
import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);

export function renderLobbyHistoryBarView({
  historyEntries,
  prefsStore,
  getActiveWindow,
  onLaunchEntry,
  onToggleFavorite,
  onTogglePin,
  onRemoveEntry,
}) {
  const listEl = $('standaloneHistoryList');
  const clearBtn = $('clearStandaloneHistoryBtn');
  if (!listEl) return;

  const entries = [...historyEntries].sort((a, b) => {
    const fa = prefsStore.getPref(a.deviceKey, a.appKey || formatAppKey(a.pkg, a.userId || 0));
    const fb = prefsStore.getPref(b.deviceKey, b.appKey || formatAppKey(b.pkg, b.userId || 0));
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
    const liveWin = getActiveWindow(entry.pkg, entry.userId || 0);
    const isRunning = Boolean(liveWin);
    const pref = prefsStore.getPref(entry.deviceKey, entryKey);
    const isPinned = isRunning ? Boolean(liveWin.alwaysOnTop) : Boolean(pref.pinnedOnTop || entry.pinnedOnTop);
    const badges = formatPopoutPrefBadges(pref);

    const card = document.createElement('div');
    card.className = `standalone-hist-card${isRunning ? ' running' : ''}${pref.isFavorite ? ' is-favorite' : ''}`;
    card.title = isRunning
      ? t('standalone.histCardRunningTitle', { name: entry.label })
      : t('standalone.histCardOpenTitle', { name: entry.label });
    card.addEventListener('click', () => onLaunchEntry(entry));

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
    const profileBadge = createProfileBadgeEl(entry.userId, entry.profileType);
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
      onToggleFavorite(entry, entryKey);
    });

    const pinBtn = document.createElement('button');
    pinBtn.type = 'button';
    pinBtn.className = `hist-icon-btn${isPinned ? ' pinned' : ''}`;
    pinBtn.title = isPinned ? t('standalone.histPinSetTitle') : t('standalone.histPinUnsetTitle');
    pinBtn.textContent = '📌';
    pinBtn.addEventListener('click', (ev) => {
      ev.stopPropagation();
      onTogglePin(entry, entryKey, isPinned, isRunning);
    });

    const delBtn = document.createElement('button');
    delBtn.type = 'button';
    delBtn.className = 'hist-icon-btn remove';
    delBtn.title = t('standalone.histRemoveTitle');
    delBtn.textContent = '×';
    delBtn.addEventListener('click', (ev) => {
      ev.stopPropagation();
      onRemoveEntry(entry.id);
    });

    card.append(avatar, meta, favBtn, pinBtn, delBtn);
    listEl.appendChild(card);
  }
}

export function renderHistoryChipsStrip({
  wrapId,
  listId,
  historyEntries,
  getActiveWindow,
  includeRunningTitle = false,
  onSelectEntry,
}) {
  const wrap = $(wrapId);
  const list = $(listId);
  if (!wrap || !list) return;

  const entries = historyEntries.slice(0, 8);
  if (!entries.length) {
    wrap.hidden = true;
    return;
  }
  wrap.hidden = false;
  list.innerHTML = '';

  for (const entry of entries) {
    const entryKey = entry.appKey || formatAppKey(entry.pkg, entry.userId || 0);
    const isRunning = Boolean(getActiveWindow(entry.pkg, entry.userId || 0));
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = `pop-history-chip${isRunning ? ' running' : ''}`;
    if (includeRunningTitle) {
      chip.title = isRunning ? t('standalone.popChipRunningTitle') : t('standalone.popChipOpenTitle');
    }

    const dot = document.createElement('span');
    dot.className = 'app-avatar-badge sm';
    dot.style.background = getAppAvatarColor(entryKey);
    dot.textContent = getAppAvatarInitial(entry.label, entry.pkg);

    const txt = document.createElement('span');
    txt.textContent = entry.label;

    chip.append(dot, txt);
    const profileBadge = createProfileBadgeEl(entry.userId, entry.profileType);
    if (profileBadge) chip.appendChild(profileBadge);
    chip.addEventListener('click', () => onSelectEntry(entry));
    list.appendChild(chip);
  }
}

export function renderInCastAppListView({
  apps,
  historyEntries,
  sortMode,
  target,
  prefsStore,
  activeCount,
  getActiveWindow,
  onToggleFavorite,
  onOpenInDesktop,
  onLaunchStandalone,
}) {
  const q = ($('appSearchInput')?.value || '').trim().toLowerCase();
  const listEl = $('appsList');
  if (!listEl) return;
  listEl.innerHTML = '';

  const baseSorted = sortAppsForLauncher(apps, historyEntries, sortMode);
  const sorted = prefsStore.sortAppsWithFavorites(baseSorted, target.deviceKey);
  const filtered = sorted.filter((a) => matchesAppSearch(a, q));
  if (!filtered.length) {
    listEl.innerHTML = `<div class="hint-text" style="padding:10px;text-align:center">${t('standalone.noMatch')}</div>`;
    return;
  }

  for (const appItem of filtered.slice(0, 100)) {
    const appKey = appItem.appKey || formatAppKey(appItem.pkg, appItem.userId || 0);
    const pref = prefsStore.getPref(target.deviceKey, appKey);
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
      onToggleFavorite(target.deviceKey, appKey);
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

    const profileBadge = createProfileBadgeEl(appItem.userId, appItem.profileType);
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
    openBtn.addEventListener('click', () => onOpenInDesktop(appItem));
    btns.appendChild(openBtn);

    const isRunning = Boolean(getActiveWindow(appItem.pkg, appItem.userId || 0));
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
    popBtn.addEventListener('click', () => onLaunchStandalone(appItem, target));
    btns.appendChild(popBtn);

    row.append(info, btns);
    listEl.appendChild(row);
  }
}

export function renderLobbyAppPickerListView({
  apps,
  historyEntries,
  sortMode,
  target,
  prefsStore,
  activeCount,
  desktopEnabled,
  getActiveWindow,
  onToggleFavorite,
  onCreateShortcut,
  onLaunchStandalone,
}) {
  const listEl = $('lobbyAppsList');
  if (!listEl) return;
  listEl.innerHTML = '';

  const q = ($('lobbyAppSearchInput')?.value || '').trim().toLowerCase();
  const deviceKey = target?.deviceKey || (target?.host ? `${target.host}:${target.port || 19878}` : '');
  const baseSorted = sortAppsForLauncher(apps, historyEntries, sortMode);
  const sorted = prefsStore.sortAppsWithFavorites(baseSorted, deviceKey);
  const filtered = sorted.filter((a) => matchesAppSearch(a, q));
  if (!filtered.length) {
    listEl.innerHTML = `<div class="hint-text" style="padding:10px;text-align:center">${t('standalone.noMatch')}</div>`;
    return;
  }

  for (const appItem of filtered.slice(0, 120)) {
    const appKey = appItem.appKey || formatAppKey(appItem.pkg, appItem.userId || 0);
    const pref = prefsStore.getPref(deviceKey, appKey);
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
      onToggleFavorite(deviceKey, appKey);
    });

    const avatar = document.createElement('span');
    avatar.className = 'app-avatar-badge sm';
    avatar.style.background = getAppAvatarColor(appKey);
    avatar.textContent = getAppAvatarInitial(appItem.label, appItem.pkg);

    const lbl = document.createElement('span');
    lbl.className = 'app-label';
    lbl.textContent = appItem.label || appItem.pkg;
    info.append(favBtn, avatar, lbl);

    const profileBadge = createProfileBadgeEl(appItem.userId, appItem.profileType);
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

    if (desktopEnabled && target) {
      const lnkBtn = document.createElement('button');
      lnkBtn.type = 'button';
      lnkBtn.className = 'mini-btn';
      lnkBtn.title = t('standalone.desktopShortcutTitle');
      lnkBtn.textContent = t('standalone.desktopShortcutBtn');
      lnkBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        onCreateShortcut(appItem, target);
      });
      btns.appendChild(lnkBtn);
    }

    const isRunning = Boolean(getActiveWindow(appItem.pkg, appItem.userId || 0));
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
      onLaunchStandalone(appItem, target);
    });
    btns.appendChild(openBtn);

    row.append(info, btns);
    listEl.appendChild(row);
  }
}
