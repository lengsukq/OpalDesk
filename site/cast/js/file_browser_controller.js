/**
 * OpalDesk Remote File Manager, 3-Tab Storage Analyzer & Wireless APK Installer UI Controller.
 * Shared across Windows Tauri Desktop (streaming disk writes + Explorer reveal + local quick preview)
 * and Web browsers (Blob download + inline image preview).
 */

import {
  DEFAULT_REMOTE_ROOT,
  DEFAULT_DIRECT_OPEN_MAX_MB,
  DIRECT_OPEN_MAX_MB_OPTIONS,
  QUICK_NAV_SHORTCUTS,
  normalizeRemotePath,
  joinRemotePath,
  getParentRemotePath,
  buildPathBreadcrumbs,
  formatBytes,
  formatFileTimestamp,
  classifyEntryKind,
  isPreviewableImageEntry,
  sanitizeDirectOpenMaxMb,
  shouldDirectOpenEntry,
  sortRemoteEntries,
  getStorageCategoryMeta,
} from './file_browser_client.js';
import { inferApkFormat } from './protocol.js';
import { t } from './i18n.js';

const STORAGE_DIRECT_OPEN_MAX_MB = 'opaldesk-direct-open-max-mb-v1';

const $ = (id) => document.getElementById(id);

const ENTRY_KIND_ICON = Object.freeze({
  dir: '📁',
  image: '🖼️',
  video: '🎬',
  audio: '🎵',
  apk: '📦',
  archive: '🗜️',
  doc: '📄',
  file: '📃',
});

const APK_STAGE_KEYS = Object.freeze({
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

function createEl(tag, className = '', text = '') {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text) el.textContent = text;
  return el;
}

function triggerBrowserBlobDownload(bytes, fileName) {
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

export class OpalFileBrowserController {
  constructor({ app, client }) {
    this.app = app;
    this.client = client;

    this.activeTab = 'files'; // 'files' | 'storage' | 'apk'
    this.storageSubTab = 'categories'; // 'categories' | 'folders' | 'apps'
    this.currentPath = DEFAULT_REMOTE_ROOT;
    this.entries = [];
    this.selectedPaths = new Set();
    this.sortBy = 'name';
    this.sortDir = 'asc';
    this.viewMode = 'list'; // 'list' | 'grid'
    this.searchQuery = '';
    this.loading = false;

    this.storageSummary = null;
    this.storageLoading = false;
    this.thumbnailCache = new Map();
    this.tempOpenCache = new Map(); // cacheKey -> local temp path (desktop)
    this.directOpenMaxMb = this._loadLocalDirectOpenMaxMb();
    this.activeTransferNote = '';
    this.apkInstallBusy = false;
  }

  _loadLocalDirectOpenMaxMb() {
    try {
      const raw = window.localStorage.getItem(STORAGE_DIRECT_OPEN_MAX_MB);
      return sanitizeDirectOpenMaxMb(raw, DEFAULT_DIRECT_OPEN_MAX_MB);
    } catch {
      return DEFAULT_DIRECT_OPEN_MAX_MB;
    }
  }

  _saveLocalDirectOpenMaxMb(mb) {
    try {
      window.localStorage.setItem(STORAGE_DIRECT_OPEN_MAX_MB, String(mb));
    } catch {
      // Ignore storage restriction
    }
  }

  syncDirectOpenThresholdUi() {
    const desktopPref = this.app.desktop?.clientPrefs?.directOpenMaxMb;
    if (desktopPref !== undefined && desktopPref !== null) {
      this.directOpenMaxMb = sanitizeDirectOpenMaxMb(desktopPref, DEFAULT_DIRECT_OPEN_MAX_MB);
      this._saveLocalDirectOpenMaxMb(this.directOpenMaxMb);
    }
    const sel = $('fmDirectOpenSelect');
    if (!sel) return;
    sel.textContent = '';
    const options = DIRECT_OPEN_MAX_MB_OPTIONS.includes(this.directOpenMaxMb)
      ? DIRECT_OPEN_MAX_MB_OPTIONS
      : [...DIRECT_OPEN_MAX_MB_OPTIONS, this.directOpenMaxMb].sort((a, b) => a - b);
    for (const mb of options) {
      const opt = document.createElement('option');
      opt.value = String(mb);
      opt.textContent = mb === 0 ? t('fm.directOpenOff') : t('fm.directOpenOption', { mb });
      sel.appendChild(opt);
    }
    sel.value = String(this.directOpenMaxMb);
    sel.title = t('fm.directOpenTitle');
  }

  bindEvents() {
    $('closeFileManagerBtn')?.addEventListener('click', () => this.close());
    $('fileManagerOverlay')?.addEventListener('click', (e) => {
      if (e.target === $('fileManagerOverlay')) this.close();
    });

    // Main tabs
    $('fmTabFilesBtn')?.addEventListener('click', () => this.selectTab('files'));
    $('fmTabStorageBtn')?.addEventListener('click', () => this.selectTab('storage'));
    $('fmTabApkBtn')?.addEventListener('click', () => this.selectTab('apk'));

    // File explorer controls
    $('fmUpBtn')?.addEventListener('click', () => this.navigate(getParentRemotePath(this.currentPath)));
    $('fmRefreshBtn')?.addEventListener('click', () => this.refreshCurrentDir());
    $('fmMkdirBtn')?.addEventListener('click', () => this.promptMkdir());
    $('fmUploadBtn')?.addEventListener('click', () => $('fmUploadInput')?.click());
    $('fmUploadInput')?.addEventListener('change', (e) => {
      const files = Array.from(e.target.files || []);
      e.target.value = '';
      if (files.length) void this.uploadFiles(files);
    });
    $('fmSearchInput')?.addEventListener('input', (e) => {
      this.searchQuery = String(e.target.value || '').trim().toLowerCase();
      this.renderEntries();
    });
    $('fmSortSelect')?.addEventListener('change', (e) => {
      const [by, dir] = String(e.target.value || 'name:asc').split(':');
      this.sortBy = by || 'name';
      this.sortDir = dir || 'asc';
      this.renderEntries();
    });
    $('fmDirectOpenSelect')?.addEventListener('change', (e) => {
      const nextMb = sanitizeDirectOpenMaxMb(e.target.value, DEFAULT_DIRECT_OPEN_MAX_MB);
      this.directOpenMaxMb = nextMb;
      this._saveLocalDirectOpenMaxMb(nextMb);
      if (this.app.desktop?.enabled) {
        void this.app.desktop.updateClientPrefs({ directOpenMaxMb: nextMb });
      }
    });
    this.syncDirectOpenThresholdUi();
    $('fmViewToggleBtn')?.addEventListener('click', () => {
      this.viewMode = this.viewMode === 'list' ? 'grid' : 'list';
      $('fmViewToggleBtn').textContent = this.viewMode === 'list' ? t('fm.viewGrid') : t('fm.viewList');
      this.renderEntries();
    });
    $('fmBatchDownloadBtn')?.addEventListener('click', () => void this.downloadSelectedEntries());
    $('fmBatchDeleteBtn')?.addEventListener('click', () => void this.deleteSelectedEntries());

    // Drag & drop upload onto file explorer
    const dropHost = $('fmEntriesWrap');
    if (dropHost) {
      dropHost.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropHost.classList.add('drag-over');
      });
      dropHost.addEventListener('dragleave', () => dropHost.classList.remove('drag-over'));
      dropHost.addEventListener('drop', (e) => {
        e.preventDefault();
        dropHost.classList.remove('drag-over');
        const files = Array.from(e.dataTransfer?.files || []);
        if (files.length) void this.uploadFiles(files);
      });
    }

    // Quick sidebar shortcuts
    this.renderQuickNav();

    // Storage sub-tabs
    $('fmSubCategoriesBtn')?.addEventListener('click', () => this.selectStorageSubTab('categories'));
    $('fmSubFoldersBtn')?.addEventListener('click', () => this.selectStorageSubTab('folders'));
    $('fmSubAppsBtn')?.addEventListener('click', () => this.selectStorageSubTab('apps'));
    $('fmRescanStorageBtn')?.addEventListener('click', () => void this.scanStorage(true));

    // Wireless APK installer
    $('fmPickApkBtn')?.addEventListener('click', () => $('fmApkFileInput')?.click());
    $('fmApkFileInput')?.addEventListener('change', (e) => {
      const file = e.target.files?.[0];
      e.target.value = '';
      if (file) void this.uploadAndInstallApk(file);
    });
    const apkZone = $('fmApkDropzone');
    if (apkZone) {
      apkZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        apkZone.classList.add('drag-over');
      });
      apkZone.addEventListener('dragleave', () => apkZone.classList.remove('drag-over'));
      apkZone.addEventListener('drop', (e) => {
        e.preventDefault();
        apkZone.classList.remove('drag-over');
        const file = e.dataTransfer?.files?.[0];
        if (file) void this.uploadAndInstallApk(file);
      });
    }

    // Image preview lightbox close
    $('fmPreviewCloseBtn')?.addEventListener('click', () => {
      $('fmPreviewOverlay').hidden = true;
    });
  }

  open(initialTab = 'files', initialPath = '') {
    if (!this.app.hasControlChannel()) {
      this.app.showToast(t('fm.needConnectToast'));
      return;
    }
    const overlay = $('fileManagerOverlay');
    if (!overlay) return;
    overlay.hidden = false;
    if (initialPath) {
      this.currentPath = normalizeRemotePath(initialPath);
    }
    this.syncDirectOpenThresholdUi();
    this.selectTab(initialTab);
  }

  close() {
    const overlay = $('fileManagerOverlay');
    if (overlay) overlay.hidden = true;
  }

  selectTab(tab) {
    this.activeTab = tab;
    $('fmTabFilesBtn')?.classList.toggle('active', tab === 'files');
    $('fmTabStorageBtn')?.classList.toggle('active', tab === 'storage');
    $('fmTabApkBtn')?.classList.toggle('active', tab === 'apk');

    if ($('fmFilesPane')) $('fmFilesPane').hidden = tab !== 'files';
    if ($('fmStoragePane')) $('fmStoragePane').hidden = tab !== 'storage';
    if ($('fmApkPane')) $('fmApkPane').hidden = tab !== 'apk';

    if (tab === 'files') {
      void this.navigate(this.currentPath);
    } else if (tab === 'storage') {
      if (!this.storageSummary) {
        void this.scanStorage(false);
      } else {
        this.renderStorage();
      }
    }
  }

  selectStorageSubTab(sub) {
    this.storageSubTab = sub;
    $('fmSubCategoriesBtn')?.classList.toggle('active', sub === 'categories');
    $('fmSubFoldersBtn')?.classList.toggle('active', sub === 'folders');
    $('fmSubAppsBtn')?.classList.toggle('active', sub === 'apps');
    this.renderStorageBody();
  }

  setStatusBanner(text, tone = '') {
    const el = $('fmStatusBanner');
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

  renderQuickNav() {
    const host = $('fmQuickNavList');
    if (!host) return;
    host.textContent = '';
    for (const item of QUICK_NAV_SHORTCUTS) {
      const labelKey = QUICK_NAV_I18N_KEYS[item.path];
      const btn = createEl('button', 'fm-quick-nav-btn', labelKey ? t(labelKey) : item.label);
      btn.type = 'button';
      btn.dataset.path = item.path;
      btn.addEventListener('click', () => {
        if (this.activeTab !== 'files') this.selectTab('files');
        void this.navigate(item.path);
      });
      host.appendChild(btn);
    }
  }

  async navigate(path) {
    const target = normalizeRemotePath(path);
    this.currentPath = target;
    this.selectedPaths.clear();
    this.renderBreadcrumbs();
    this.syncQuickNavActive();
    await this.refreshCurrentDir();
  }

  syncQuickNavActive() {
    const host = $('fmQuickNavList');
    if (!host) return;
    host.querySelectorAll('.fm-quick-nav-btn').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.path === this.currentPath);
    });
  }

  renderBreadcrumbs() {
    const host = $('fmBreadcrumbs');
    if (!host) return;
    host.textContent = '';
    const crumbs = buildPathBreadcrumbs(this.currentPath);
    crumbs.forEach((crumb, idx) => {
      if (idx > 0) host.appendChild(createEl('span', 'fm-crumb-sep', '/'));
      const label = crumb.path === '/sdcard' ? t('fm.crumbPhoneStorage') : crumb.label;
      const btn = createEl('button', 'fm-crumb-btn', label);
      btn.type = 'button';
      btn.disabled = idx === crumbs.length - 1;
      btn.addEventListener('click', () => void this.navigate(crumb.path));
      host.appendChild(btn);
    });
  }

  async refreshCurrentDir() {
    if (!this.app.hasControlChannel()) {
      this.setStatusBanner(t('fm.notConnectedBanner'), 'warn');
      return;
    }
    this.loading = true;
    this.setStatusBanner(t('fm.readingDir', { path: this.currentPath }));
    try {
      const resp = await this.client.list(this.currentPath);
      this.entries = resp.entries || [];
      this.setStatusBanner('');
    } catch (err) {
      this.entries = [];
      this.setStatusBanner(err?.message || t('fm.readDirFail'), 'error');
    } finally {
      this.loading = false;
      this.renderEntries();
    }
  }

  syncBatchBar() {
    const bar = $('fmBatchBar');
    if (!bar) return;
    const count = this.selectedPaths.size;
    bar.hidden = count === 0;
    const label = $('fmSelectedCount');
    if (label) label.textContent = t('fm.selectedCount', { count });
  }

  renderEntries() {
    const host = $('fmEntriesWrap');
    if (!host) return;
    host.classList.toggle('is-grid', this.viewMode === 'grid');
    host.textContent = '';
    this.syncBatchBar();

    let list = sortRemoteEntries(this.entries, this.sortBy, this.sortDir);
    if (this.searchQuery) {
      list = list.filter((e) => e.name.toLowerCase().includes(this.searchQuery));
    }

    if (list.length === 0) {
      host.appendChild(createEl('div', 'fm-empty', this.loading ? t('fm.loading') : t('fm.emptyDir')));
      return;
    }

    for (const entry of list) {
      const kind = classifyEntryKind(entry);
      const row = createEl('div', `fm-entry-item${this.selectedPaths.has(entry.path) ? ' selected' : ''}`);

      const chk = document.createElement('input');
      chk.type = 'checkbox';
      chk.className = 'fm-entry-check';
      chk.checked = this.selectedPaths.has(entry.path);
      chk.addEventListener('change', () => {
        if (chk.checked) this.selectedPaths.add(entry.path);
        else this.selectedPaths.delete(entry.path);
        row.classList.toggle('selected', chk.checked);
        this.syncBatchBar();
      });

      const iconWrap = createEl('div', 'fm-entry-icon', ENTRY_KIND_ICON[kind] || '📃');
      if (kind === 'image' && this.viewMode === 'grid') {
        void this._populateGridThumbnail(entry, iconWrap);
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
          void this.navigate(entry.path);
        } else if (kind === 'apk') {
          void this.installRemoteApk(entry.path);
        } else if (this.app.desktop?.enabled && shouldDirectOpenEntry(entry, this.directOpenMaxMb)) {
          void this.openEntryDirectly(entry);
        } else if (isPreviewableImageEntry(entry)) {
          void this.previewImage(entry);
        } else {
          void this.downloadEntry(entry);
        }
      });

      const actions = createEl('div', 'fm-entry-actions');
      if (!entry.isDir) {
        if (kind === 'apk') {
          const instBtn = createEl('button', 'btn-pill small primary', t('fm.btnInstall'));
          instBtn.type = 'button';
          instBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            void this.installRemoteApk(entry.path);
          });
          actions.appendChild(instBtn);
        } else {
          if (this.app.desktop?.enabled) {
            const openBtn = createEl('button', 'btn-pill small primary', t('fm.btnOpen'));
            openBtn.type = 'button';
            openBtn.addEventListener('click', (e) => {
              e.stopPropagation();
              void this.openEntryDirectly(entry);
            });
            actions.appendChild(openBtn);
          } else if (isPreviewableImageEntry(entry)) {
            const prevBtn = createEl('button', 'btn-pill small', t('fm.btnPreview'));
            prevBtn.type = 'button';
            prevBtn.addEventListener('click', (e) => {
              e.stopPropagation();
              void this.previewImage(entry);
            });
            actions.appendChild(prevBtn);
          }
        }
        const dlBtn = createEl('button', 'btn-pill small', t('fm.btnDownload'));
        dlBtn.type = 'button';
        dlBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          void this.downloadEntry(entry);
        });
        actions.appendChild(dlBtn);
      }

      const renBtn = createEl('button', 'btn-pill small ghost', t('fm.btnRename'));
      renBtn.type = 'button';
      renBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        void this.promptRename(entry);
      });
      const delBtn = createEl('button', 'btn-pill small danger-ghost', t('fm.btnDelete'));
      delBtn.type = 'button';
      delBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        void this.confirmDelete([entry]);
      });
      actions.append(renBtn, delBtn);

      row.append(chk, iconWrap, info, actions);
      host.appendChild(row);
    }
  }

  async _populateGridThumbnail(entry, container) {
    try {
      let b64 = this.thumbnailCache.get(entry.path);
      if (!b64) {
        b64 = await this.client.getThumbnail(entry.path);
        if (b64) this.thumbnailCache.set(entry.path, b64);
      }
      if (!b64 || !container.isConnected) return;
      const img = document.createElement('img');
      img.className = 'fm-thumb-img';
      img.alt = entry.name;
      img.src = b64.startsWith('data:') ? b64 : `data:image/jpeg;base64,${b64}`;
      container.textContent = '';
      container.appendChild(img);
    } catch {
      // Keep emoji icon fallback
    }
  }

  async previewImage(entry) {
    this.setStatusBanner(t('fm.loadingPreview', { name: entry.name }));
    try {
      const b64 = this.thumbnailCache.get(entry.path) || (await this.client.getThumbnail(entry.path));
      if (!b64) throw new Error(t('fm.thumbFail'));
      this.thumbnailCache.set(entry.path, b64);
      const img = $('fmPreviewImg');
      const title = $('fmPreviewTitle');
      if (img) img.src = b64.startsWith('data:') ? b64 : `data:image/jpeg;base64,${b64}`;
      if (title) title.textContent = `${entry.name} (${formatBytes(entry.size)})`;
      if ($('fmPreviewOverlay')) $('fmPreviewOverlay').hidden = false;
      this.setStatusBanner('');
    } catch (err) {
      this.setStatusBanner(err?.message || t('fm.previewFail'), 'error');
    }
  }

  async openEntryDirectly(entry) {
    if (!entry || entry.isDir) return;
    const desktop = this.app.desktop;
    const useNativeTemp = desktop?.enabled
      && typeof desktop.beginFileDownload === 'function'
      && typeof desktop.openFileInOs === 'function';

    if (!useNativeTemp) {
      if (isPreviewableImageEntry(entry)) {
        await this.previewImage(entry);
      } else {
        await this.downloadEntry(entry);
      }
      return;
    }

    const cacheKey = `${entry.path}|${entry.size || 0}|${entry.modifiedMs || 0}`;
    const cachedPath = this.tempOpenCache.get(cacheKey);
    if (cachedPath) {
      try {
        await desktop.openFileInOs(cachedPath);
        this.setStatusBanner(t('fm.openedToast', { name: entry.name }), 'ok');
        this.app.showToast(t('fm.openedToast', { name: entry.name }));
        return;
      } catch {
        this.tempOpenCache.delete(cacheKey);
      }
    }

    let sessionId = '';
    this.setStatusBanner(t('fm.opening', { name: entry.name }));
    try {
      const begun = await desktop.beginFileDownload({ fileName: entry.name, subdir: 'temp' });
      sessionId = begun.sessionId;
      await this.client.pullFile(entry.path, {
        onChunk: async (chunkBytes) => {
          await desktop.writeFileDownloadChunk({ sessionId, bytes: chunkBytes });
        },
        onProgress: (received, total) => {
          const pct = total > 0 ? Math.min(100, Math.round((received / total) * 100)) : 0;
          this.setStatusBanner(t('fm.openingProgress', { name: entry.name, pct }));
        },
      });
      const done = await desktop.finishFileDownload({ sessionId });
      sessionId = '';
      this.tempOpenCache.set(cacheKey, done.savedPath);
      if (this.tempOpenCache.size > 48) {
        const oldestKey = this.tempOpenCache.keys().next().value;
        if (oldestKey) this.tempOpenCache.delete(oldestKey);
      }
      await desktop.openFileInOs(done.savedPath);
      this.setStatusBanner(t('fm.openedToast', { name: entry.name }), 'ok');
      this.app.showToast(t('fm.openedToast', { name: entry.name }), {
        actionText: t('cast.openLocationAction'),
        actionFn: () => void desktop.revealInExplorer(done.savedPath),
      });
    } catch (err) {
      if (sessionId) {
        await desktop.abortFileDownload({ sessionId }).catch(() => {});
      }
      this.setStatusBanner(err?.message || t('fm.openFail', { name: entry.name }), 'error');
    }
  }

  async downloadEntry(entry) {
    if (!entry || entry.isDir) return;
    const desktop = this.app.desktop;
    const useNativeStream = desktop?.enabled && typeof desktop.beginFileDownload === 'function';
    let sessionId = '';
    this.setStatusBanner(t('fm.downloading', { name: entry.name }));

    try {
      if (useNativeStream) {
        const begun = await desktop.beginFileDownload({ fileName: entry.name, subdir: 'downloads' });
        sessionId = begun.sessionId;
      }

      const result = await this.client.pullFile(entry.path, {
        onChunk: useNativeStream
          ? async (chunkBytes) => {
              await desktop.writeFileDownloadChunk({ sessionId, bytes: chunkBytes });
            }
          : null,
        onProgress: (received, total) => {
          const pct = total > 0 ? Math.min(100, Math.round((received / total) * 100)) : 0;
          this.setStatusBanner(t('fm.downloadingProgress', { name: entry.name, pct, received: formatBytes(received) }));
        },
      });

      if (useNativeStream) {
        const done = await desktop.finishFileDownload({ sessionId });
        sessionId = '';
        this.setStatusBanner(t('fm.downloadedTo', { path: done.savedPath }), 'ok');
        this.app.showToast(t('fm.downloadedToast', { name: entry.name }), {
          actionText: t('cast.openLocationAction'),
          actionFn: () => void desktop.revealInExplorer(done.savedPath),
        });
      } else if (result.bytes) {
        triggerBrowserBlobDownload(result.bytes, result.fileName || entry.name);
        this.setStatusBanner(`${t('fm.downloadedToast', { name: entry.name })} (${formatBytes(result.receivedBytes)})`, 'ok');
      }
    } catch (err) {
      if (useNativeStream && sessionId) {
        await desktop.abortFileDownload({ sessionId }).catch(() => {});
      }
      this.setStatusBanner(err?.message || t('fm.downloadFail', { name: entry.name }), 'error');
    }
  }

  async downloadSelectedEntries() {
    const selected = this.entries.filter((e) => this.selectedPaths.has(e.path) && !e.isDir);
    if (!selected.length) {
      this.app.showToast(t('fm.selectFilesToast'));
      return;
    }
    for (const item of selected) {
      await this.downloadEntry(item);
    }
    this.selectedPaths.clear();
    this.renderEntries();
  }

  async deleteSelectedEntries() {
    const selected = this.entries.filter((e) => this.selectedPaths.has(e.path));
    if (!selected.length) return;
    await this.confirmDelete(selected);
  }

  async uploadFiles(files) {
    if (!files?.length) return;
    for (const file of files) {
      this.setStatusBanner(t('fm.uploading', { name: file.name }));
      try {
        await this.client.uploadFile(file, {
          onProgress: (sent, total) => {
            const pct = total > 0 ? Math.round((sent / total) * 100) : 100;
            this.setStatusBanner(t('fm.uploadingProgress', { name: file.name, pct }));
          },
        });
        this.app.showToast(t('fm.uploadedToast', { name: file.name }));
      } catch (err) {
        this.setStatusBanner(err?.message || t('fm.uploadFail', { name: file.name }), 'error');
        return;
      }
    }
    this.setStatusBanner(t('fm.uploadDone'), 'ok');
    if (this.currentPath.startsWith('/sdcard/Download')) {
      await this.refreshCurrentDir();
    }
  }

  async promptMkdir() {
    const name = window.prompt(t('fm.mkdirPrompt'), '');
    if (!name || !name.trim()) return;
    try {
      await this.client.mkdir(joinRemotePath(this.currentPath, name.trim()));
      await this.refreshCurrentDir();
      this.app.showToast(t('fm.mkdirToast', { name: name.trim() }));
    } catch (err) {
      this.setStatusBanner(err?.message || t('fm.mkdirFail'), 'error');
    }
  }

  async promptRename(entry) {
    const nextName = window.prompt(t('fm.renamePrompt'), entry.name);
    if (!nextName || !nextName.trim() || nextName.trim() === entry.name) return;
    const parent = getParentRemotePath(entry.path);
    const nextPath = joinRemotePath(parent, nextName.trim());
    try {
      await this.client.rename(entry.path, nextPath);
      await this.refreshCurrentDir();
      this.app.showToast(t('fm.renameToast', { name: nextName.trim() }));
    } catch (err) {
      this.setStatusBanner(err?.message || t('fm.renameFail'), 'error');
    }
  }

  async confirmDelete(list) {
    const msg = list.length === 1
      ? t('fm.deleteConfirmOne', { name: list[0].name })
      : t('fm.deleteConfirmMany', { count: list.length });
    if (!window.confirm(msg)) return;
    for (const item of list) {
      try {
        await this.client.deletePath(item.path);
        this.selectedPaths.delete(item.path);
      } catch (err) {
        this.setStatusBanner(err?.message || t('fm.deleteFail', { name: item.name }), 'error');
        break;
      }
    }
    await this.refreshCurrentDir();
  }

  // ==================== 3-TAB STORAGE SPACE ANALYZER ====================

  async scanStorage(force = false) {
    if (this.storageLoading) return;
    if (!force && this.storageSummary) {
      this.renderStorage();
      return;
    }
    this.storageLoading = true;
    this.setStatusBanner(t('fm.scanningStorage'));
    try {
      this.storageSummary = await this.client.scanStorage(DEFAULT_REMOTE_ROOT, 4);
      this.setStatusBanner('');
    } catch (err) {
      this.setStatusBanner(err?.message || t('fm.scanFail'), 'error');
    } finally {
      this.storageLoading = false;
      this.renderStorage();
    }
  }

  renderStorage() {
    const head = $('fmStorageOverview');
    const summary = this.storageSummary;
    if (head) {
      head.textContent = '';
      if (!summary) {
        head.appendChild(createEl('div', 'fm-empty', this.storageLoading ? t('fm.storageScanning') : t('fm.storageRescanHint')));
      } else {
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
    }
    this.renderStorageBody();
  }

  renderStorageBody() {
    const host = $('fmStorageBody');
    if (!host) return;
    host.textContent = '';
    const summary = this.storageSummary;
    if (!summary) return;

    if (this.storageSubTab === 'categories') {
      this._renderStorageCategories(host, summary);
    } else if (this.storageSubTab === 'folders') {
      this._renderStorageFolders(host, summary);
    } else {
      this._renderStorageApps(host, summary);
    }
  }

  _renderStorageCategories(host, summary) {
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
        dlBtn.addEventListener('click', () => void this.downloadEntry({ name: f.name, path: f.path, size: f.size, isDir: false }));
        const delBtn = createEl('button', 'btn-pill small danger-ghost', t('fm.btnDelete'));
        delBtn.type = 'button';
        delBtn.addEventListener('click', async () => {
          await this.confirmDelete([{ name: f.name, path: f.path }]);
          void this.scanStorage(true);
        });
        right.append(dlBtn, delBtn);
        row.append(info, right);
        list.appendChild(row);
      }
      host.appendChild(list);
    }
  }

  _renderStorageFolders(host, summary) {
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
        this.currentPath = normalizeRemotePath(folder.path);
        this.selectTab('files');
      });
      right.appendChild(openBtn);
      row.addEventListener('click', () => {
        this.currentPath = normalizeRemotePath(folder.path);
        this.selectTab('files');
      });
      row.append(info, right);
      list.appendChild(row);
    }
    host.appendChild(list);
  }

  _renderStorageApps(host, summary) {
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
        popBtn.addEventListener('click', () => {
          this.app.launchPopoutByPackage(app.pkg, app.appLabel || app.pkg);
        });
        right.appendChild(popBtn);
      }
      row.append(info, right);
      list.appendChild(row);
    }
    host.appendChild(list);
  }

  // ==================== WIRELESS APK INSTALLER ====================

  _renderApkProgress({ stageLabel, progress, detail = '', tone = '' }) {
    const card = $('fmApkProgressCard');
    if (!card) return;
    card.hidden = false;
    card.className = `fm-apk-progress-card${tone ? ` tone-${tone}` : ''}`;
    if ($('fmApkStageText')) $('fmApkStageText').textContent = stageLabel;
    if ($('fmApkDetailText')) $('fmApkDetailText').textContent = detail;
    if ($('fmApkProgressFill')) $('fmApkProgressFill').style.width = `${Math.max(0, Math.min(100, progress))}%`;
  }

  async uploadAndInstallApk(file) {
    if (this.apkInstallBusy) {
      this.app.showToast(t('fm.apkBusyToast'));
      return;
    }
    const format = inferApkFormat(file.name);
    if (!format) {
      this.app.showToast(t('fm.apkBadFormatToast'));
      return;
    }
    this.apkInstallBusy = true;
    this._renderApkProgress({
      stageLabel: t('fm.apkUploading', { name: file.name }),
      progress: 5,
      detail: t('fm.apkUploadDetail', { size: formatBytes(file.size), format: format.toUpperCase() }),
    });

    try {
      const ack = await this.client.uploadFile(file, {
        onProgress: (sent, total) => {
          const pct = total > 0 ? Math.round((sent / total) * 45) : 45;
          this._renderApkProgress({
            stageLabel: t('fm.apkUploadingProgress', { name: file.name, pct: Math.round((sent / Math.max(1, total)) * 100) }),
            progress: pct,
            detail: `${formatBytes(sent)} / ${formatBytes(total)}`,
          });
        },
      });
      await this._runApkInstall(ack.remotePath, format);
    } catch (err) {
      this._renderApkProgress({
        stageLabel: t('fm.apkUploadFail'),
        progress: 100,
        detail: err?.message || t('common.unknownError'),
        tone: 'error',
      });
    } finally {
      this.apkInstallBusy = false;
    }
  }

  async installRemoteApk(remotePath) {
    this.selectTab('apk');
    if (this.apkInstallBusy) {
      this.app.showToast(t('fm.apkBusyToast'));
      return;
    }
    const format = inferApkFormat(remotePath);
    if (!format) {
      this.app.showToast(t('fm.apkUnsupportedToast'));
      return;
    }
    this.apkInstallBusy = true;
    try {
      await this._runApkInstall(remotePath, format);
    } catch (err) {
      this._renderApkProgress({
        stageLabel: t('fm.apkInstallFail'),
        progress: 100,
        detail: err?.message || t('common.unknownError'),
        tone: 'error',
      });
    } finally {
      this.apkInstallBusy = false;
    }
  }

  async _runApkInstall(remotePath, format) {
    const replaceExisting = $('fmApkReplaceCheck') ? $('fmApkReplaceCheck').checked : true;
    const allowDowngrade = $('fmApkDowngradeCheck') ? $('fmApkDowngradeCheck').checked : false;
    this._renderApkProgress({
      stageLabel: t('fm.apkSentPreparing'),
      progress: 50,
      detail: remotePath,
    });
    const res = await this.client.installApk(remotePath, {
      format,
      replaceExisting,
      allowDowngrade,
      onStatus: (st) => {
        const mappedPct = 50 + Math.round((st.progress || 0) * 0.5);
        const stageKey = APK_STAGE_KEYS[st.stage];
        this._renderApkProgress({
          stageLabel: stageKey ? t(stageKey) : (st.stage || t('fm.apkInstallingFallback')),
          progress: mappedPct,
          detail: st.message || st.packageName || remotePath,
          tone: st.stage === 'succeeded' ? 'ok' : st.stage === 'failed' ? 'error' : '',
        });
      },
    });
    this._renderApkProgress({
      stageLabel: t('fm.apkStageSucceeded'),
      progress: 100,
      detail: res.packageName ? t('fm.apkSuccessDetailPkg', { pkg: res.packageName }) : (res.message || t('fm.apkSuccessDetailDefault')),
      tone: 'ok',
    });
    this.app.showToast(t('fm.apkSuccessToast', { pkg: res.packageName ? ` (${res.packageName})` : '' }));
  }
}
