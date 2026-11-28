/**
 * DOM view rendering helpers for OpalDesk Remote File Manager, Storage Analyzer & APK Installer.
 */

import {
  DIRECT_OPEN_MAX_MB_OPTIONS,
  QUICK_NAV_SHORTCUTS,
  buildPathBreadcrumbs,
  formatBytes,
  formatFileTimestamp,
  classifyEntryKind,
  isPreviewableImageEntry,
  shouldDirectOpenEntry,
  getStorageCategoryMeta,
} from './file_browser_client.js';
import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);

export const ENTRY_KIND_ICON = Object.freeze({
  dir: '📁',
  image: '🖼️',
  video: '🎬',
  audio: '🎵',
  apk: '📦',
  archive: '🗜️',
  doc: '📄',
  file: '📃',
});

export const APK_STAGE_KEYS = Object.freeze({
  uploading: 'fm.apkStageUploading',
  extracting: 'fm.apkStageExtracting',
  installing: 'fm.apkStageInstalling',
  succeeded: 'fm.apkStageSucceeded',
  failed: 'fm.apkStageFailed',
});

const QUICK_NAV_I18N_KEYS = Object.freeze({
  '/sdcard': 'fm.quickInternal',
  '/sdcard/Download': 'fm.quickDownloads',
  '/sdcard/DCIM': 'fm.quickDcim',
  '/sdcard/Pictures': 'fm.quickPictures',
  '/sdcard/Documents': 'fm.quickDocuments',
  '/sdcard/Android/data/com.tencent.mm/MicroMsg/Download': 'fm.quickWechat',
});

const STORAGE_CAT_I18N_KEYS = Object.freeze({
  photos: 'fm.catPhotos',
  images: 'fm.catPhotos',
  videos: 'fm.catVideos',
  audio: 'fm.catAudio',
  documents: 'fm.catDocuments',
  apks: 'fm.catApks',
  archives: 'fm.catArchives',
  downloads: 'fm.catDownloads',
  other: 'fm.catOther',
});

export function createEl(tag, className = '', text = '') {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text) el.textContent = text;
  return el;
}

export function triggerBrowserBlobDownload(bytes, fileName) {
  const blob = new Blob([bytes]);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  setTimeout(() => {
    URL.revokeObjectURL(url);
    a.remove();
  }, 1500);
}

export function renderDirectOpenSelect(sel, directOpenMaxMb) {
  if (!sel) return;
  sel.textContent = '';
  const options = DIRECT_OPEN_MAX_MB_OPTIONS.includes(directOpenMaxMb)
    ? DIRECT_OPEN_MAX_MB_OPTIONS
    : [...DIRECT_OPEN_MAX_MB_OPTIONS, directOpenMaxMb].sort((a, b) => a - b);
  for (const mb of options) {
    const opt = document.createElement('option');
    opt.value = String(mb);
    opt.textContent = mb === 0 ? t('fm.directOpenOff') : t('fm.directOpenOption', { mb });
    sel.appendChild(opt);
  }
  sel.value = String(directOpenMaxMb);
  sel.title = t('fm.directOpenTitle');
}

export function renderStatusBanner(el, text, tone = '') {
  if (!el) return;
  if (!text) {
    el.hidden = true;
    el.textContent = '';
    el.className = 'fm-status-banner';
    return;
  }
  el.hidden = false;
  el.textContent = text;
  el.className = `fm-status-banner${tone ? ` tone-${tone}` : ''}`;
}

export function renderQuickNavList(host, onSelectPath) {
  if (!host) return;
  host.textContent = '';
  for (const item of QUICK_NAV_SHORTCUTS) {
    const labelKey = QUICK_NAV_I18N_KEYS[item.path];
    const btn = createEl('button', 'fm-quick-nav-btn', labelKey ? t(labelKey) : item.label);
    btn.type = 'button';
    btn.dataset.path = item.path;
    btn.addEventListener('click', () => onSelectPath(item.path));
    host.appendChild(btn);
  }
}

export function renderBreadcrumbsBar(host, currentPath, onNavigate) {
  if (!host) return;
  host.textContent = '';
  const crumbs = buildPathBreadcrumbs(currentPath);
  crumbs.forEach((crumb, idx) => {
    if (idx > 0) host.appendChild(createEl('span', 'fm-crumb-sep', '/'));
    const label = crumb.path === '/sdcard' ? t('fm.crumbPhoneStorage') : crumb.label;
    const btn = createEl('button', 'fm-crumb-btn', label);
    btn.type = 'button';
    btn.disabled = idx === crumbs.length - 1;
    btn.addEventListener('click', () => onNavigate(crumb.path));
    host.appendChild(btn);
  });
}

export function renderEntriesList(host, {
  list,
  viewMode,
  loading,
  selectedPaths,
  desktopEnabled,
  directOpenMaxMb,
  onToggleSelect,
  onPopulateGridThumbnail,
  onNavigate,
  onInstallApk,
  onOpenDirectly,
  onPreviewImage,
  onDownload,
  onRename,
  onDelete,
}) {
  if (!host) return;
  host.classList.toggle('is-grid', viewMode === 'grid');
  host.textContent = '';

  if (list.length === 0) {
    host.appendChild(createEl('div', 'fm-empty', loading ? t('fm.loading') : t('fm.emptyDir')));
    return;
  }

  for (const entry of list) {
    const kind = classifyEntryKind(entry);
    const row = createEl('div', `fm-entry-item${selectedPaths.has(entry.path) ? ' selected' : ''}`);

    const chk = document.createElement('input');
    chk.type = 'checkbox';
    chk.className = 'fm-entry-check';
    chk.checked = selectedPaths.has(entry.path);
    chk.addEventListener('change', () => {
      onToggleSelect(entry.path, chk.checked);
      row.classList.toggle('selected', chk.checked);
    });

    const iconWrap = createEl('div', 'fm-entry-icon', ENTRY_KIND_ICON[kind] || '📃');
    if (kind === 'image' && viewMode === 'grid') {
      onPopulateGridThumbnail(entry, iconWrap);
    }

    const info = createEl('div', 'fm-entry-info');
    const nameEl = createEl('div', 'fm-entry-name', entry.name);
    const metaEl = createEl(
      'div',
      'fm-entry-meta',
      entry.isDir ? formatFileTimestamp(entry.modifiedMs) : `${formatBytes(entry.size)} · ${formatFileTimestamp(entry.modifiedMs)}`
    );
    info.append(nameEl, metaEl);
    info.addEventListener('click', () => {
      if (entry.isDir) {
        onNavigate(entry.path);
      } else if (kind === 'apk') {
        onInstallApk(entry.path);
      } else if (desktopEnabled && shouldDirectOpenEntry(entry, directOpenMaxMb)) {
        onOpenDirectly(entry);
      } else if (isPreviewableImageEntry(entry)) {
        onPreviewImage(entry);
      } else {
        onDownload(entry);
      }
    });

    const actions = createEl('div', 'fm-entry-actions');
    if (!entry.isDir) {
      if (kind === 'apk') {
        const instBtn = createEl('button', 'btn-pill small primary', t('fm.btnInstall'));
        instBtn.type = 'button';
        instBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          onInstallApk(entry.path);
        });
        actions.appendChild(instBtn);
      } else if (desktopEnabled) {
        const openBtn = createEl('button', 'btn-pill small primary', t('fm.btnOpen'));
        openBtn.type = 'button';
        openBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          onOpenDirectly(entry);
        });
        actions.appendChild(openBtn);
      } else if (isPreviewableImageEntry(entry)) {
        const prevBtn = createEl('button', 'btn-pill small', t('fm.btnPreview'));
        prevBtn.type = 'button';
        prevBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          onPreviewImage(entry);
        });
        actions.appendChild(prevBtn);
      }
      const dlBtn = createEl('button', 'btn-pill small', t('fm.btnDownload'));
      dlBtn.type = 'button';
      dlBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        onDownload(entry);
      });
      actions.appendChild(dlBtn);
    }

    const renBtn = createEl('button', 'btn-pill small ghost', t('fm.btnRename'));
    renBtn.type = 'button';
    renBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      onRename(entry);
    });
    const delBtn = createEl('button', 'btn-pill small danger-ghost', t('fm.btnDelete'));
    delBtn.type = 'button';
    delBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      onDelete([entry]);
    });
    actions.append(renBtn, delBtn);

    row.append(chk, iconWrap, info, actions);
    host.appendChild(row);
  }
}

export function renderStorageOverview(head, summary, storageLoading) {
  if (!head) return;
  head.textContent = '';
  if (!summary) {
    head.appendChild(createEl('div', 'fm-empty', storageLoading ? t('fm.storageScanning') : t('fm.storageRescanHint')));
    return;
  }
  const pct = summary.totalBytes > 0 ? Math.min(100, Math.round((summary.usedBytes / summary.totalBytes) * 100)) : 0;
  const stats = createEl('div', 'fm-storage-stats');
  stats.append(
    createEl('strong', '', t('fm.storageUsed', { used: formatBytes(summary.usedBytes), total: formatBytes(summary.totalBytes), pct })),
    createEl('span', 'muted', t('fm.storageFree', { free: formatBytes(summary.freeBytes) }))
  );
  const track = createEl('div', 'fm-storage-bar-track');
  const fill = createEl('div', 'fm-storage-bar-fill');
  fill.style.width = `${pct}%`;
  track.appendChild(fill);
  head.append(stats, track);
}

export function renderStorageCategories(host, summary, { onDownload, onDeleteLargeFile }) {
  const grid = createEl('div', 'fm-cat-grid');
  const total = Math.max(1, summary.usedBytes || summary.totalBytes);
  for (const cat of summary.categories || []) {
    const meta = getStorageCategoryMeta(cat.name);
    const catKey = STORAGE_CAT_I18N_KEYS[String(cat.name || '').toLowerCase()];
    const catLabel = catKey ? t(catKey) : meta.label;
    const card = createEl('div', 'fm-cat-card');
    const dot = createEl('span', 'fm-cat-dot');
    dot.style.background = meta.color;
    const top = createEl('div', 'fm-cat-top');
    top.append(dot, createEl('strong', '', catLabel));
    const val = createEl('div', 'fm-cat-val', formatBytes(cat.bytes));
    const sub = createEl('div', 'fm-cat-sub', t('fm.fileCount', { count: cat.fileCount, pct: ((cat.bytes / total) * 100).toFixed(1) }));
    card.append(top, val, sub);
    grid.appendChild(card);
  }
  host.appendChild(grid);

  if (summary.largeFiles?.length) {
    host.appendChild(createEl('h4', 'fm-sub-heading', t('fm.largeFilesHeading')));
    const list = createEl('div', 'fm-rank-list');
    for (const f of summary.largeFiles.slice(0, 20)) {
      const row = createEl('div', 'fm-rank-row');
      const info = createEl('div', 'fm-rank-info');
      info.append(createEl('div', 'fm-rank-title', f.name), createEl('div', 'fm-rank-path mono', f.path));
      const right = createEl('div', 'fm-rank-right');
      right.appendChild(createEl('span', 'fm-rank-size mono', formatBytes(f.size)));
      const dlBtn = createEl('button', 'btn-pill small', t('fm.btnDownload'));
      dlBtn.type = 'button';
      dlBtn.addEventListener('click', () => onDownload({ name: f.name, path: f.path, size: f.size, isDir: false }));
      const delBtn = createEl('button', 'btn-pill small danger-ghost', t('fm.btnDelete'));
      delBtn.type = 'button';
      delBtn.addEventListener('click', () => onDeleteLargeFile(f));
      right.append(dlBtn, delBtn);
      row.append(info, right);
      list.appendChild(row);
    }
    host.appendChild(list);
  }
}

export function renderStorageFolders(host, summary, onOpenFolder) {
  const folders = summary.folders || [];
  if (!folders.length) {
    host.appendChild(createEl('div', 'fm-empty', t('fm.noFolders')));
    return;
  }
  const maxBytes = Math.max(1, folders[0]?.bytes || 1);
  const list = createEl('div', 'fm-rank-list');
  for (const folder of folders.slice(0, 25)) {
    const row = createEl('div', 'fm-rank-row clickable');
    const info = createEl('div', 'fm-rank-info');
    info.append(
      createEl('div', 'fm-rank-title', `📁 ${folder.name || folder.path}`),
      createEl('div', 'fm-rank-path mono', t('fm.folderFiles', { path: folder.path, count: folder.fileCount }))
    );
    const bar = createEl('div', 'fm-mini-bar');
    const fill = createEl('div', 'fm-mini-bar-fill');
    fill.style.width = `${Math.max(4, Math.round((folder.bytes / maxBytes) * 100))}%`;
    bar.appendChild(fill);
    info.appendChild(bar);

    const right = createEl('div', 'fm-rank-right');
    right.appendChild(createEl('span', 'fm-rank-size mono', formatBytes(folder.bytes)));
    const openBtn = createEl('button', 'btn-pill small primary', t('fm.btnView'));
    openBtn.type = 'button';
    openBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      onOpenFolder(folder.path);
    });
    right.appendChild(openBtn);
    row.addEventListener('click', () => onOpenFolder(folder.path));
    row.append(info, right);
    list.appendChild(row);
  }
  host.appendChild(list);
}

export function renderStorageApps(host, summary, onPopoutApp) {
  const apps = summary.apps || [];
  if (!apps.length) {
    host.appendChild(createEl('div', 'fm-empty', t('fm.noApps')));
    return;
  }
  const maxBytes = Math.max(1, apps[0]?.totalBytes || 1);
  const list = createEl('div', 'fm-rank-list');
  for (const app of apps.slice(0, 40)) {
    const row = createEl('div', 'fm-rank-row');
    const info = createEl('div', 'fm-rank-info');
    info.append(
      createEl('div', 'fm-rank-title', app.appLabel || app.pkg),
      createEl(
        'div',
        'fm-rank-path mono',
        t('fm.appBreakdown', { apk: formatBytes(app.apkBytes), data: formatBytes(app.dataBytes), cache: formatBytes(app.cacheBytes) })
      )
    );
    const bar = createEl('div', 'fm-mini-bar');
    const fill = createEl('div', 'fm-mini-bar-fill accent');
    fill.style.width = `${Math.max(4, Math.round((app.totalBytes / maxBytes) * 100))}%`;
    bar.appendChild(fill);
    info.appendChild(bar);

    const right = createEl('div', 'fm-rank-right');
    right.appendChild(createEl('span', 'fm-rank-size mono', formatBytes(app.totalBytes)));
    if (app.pkg) {
      const popBtn = createEl('button', 'btn-pill small', t('fm.btnPopout'));
      popBtn.type = 'button';
      popBtn.addEventListener('click', () => onPopoutApp(app.pkg, app.appLabel || app.pkg));
      right.appendChild(popBtn);
    }
    row.append(info, right);
    list.appendChild(row);
  }
  host.appendChild(list);
}

export function renderApkProgressCard({ stageLabel, progress, detail = '', tone = '' }) {
  const card = $('fmApkProgressCard');
  if (!card) return;
  card.hidden = false;
  card.className = `fm-apk-progress-card${tone ? ` tone-${tone}` : ''}`;
  if ($('fmApkStageText')) $('fmApkStageText').textContent = stageLabel;
  if ($('fmApkDetailText')) $('fmApkDetailText').textContent = detail;
  if ($('fmApkProgressFill')) $('fmApkProgressFill').style.width = `${Math.max(0, Math.min(100, progress))}%`;
}
