/**
 * OpalDesk Remote File Manager, 3-Tab Storage Analyzer & Wireless APK Installer UI Controller.
 * Shared across Windows Tauri Desktop (streaming disk writes + Explorer reveal + local quick preview)
 * and Web browsers (Blob download + inline image preview).
 */

import {
  DEFAULT_REMOTE_ROOT,
  DEFAULT_DIRECT_OPEN_MAX_MB,
  normalizeRemotePath,
  joinRemotePath,
  getParentRemotePath,
  formatBytes,
  isPreviewableImageEntry,
  sanitizeDirectOpenMaxMb,
  sortRemoteEntries,
} from './file_browser_client.js';
import { inferApkFormat } from './protocol.js';
import { t } from './i18n.js';
import {
  APK_STAGE_KEYS,
  triggerBrowserBlobDownload,
  renderDirectOpenSelect,
  renderStatusBanner,
  renderQuickNavList,
  renderBreadcrumbsBar,
  renderEntriesList,
  renderStorageOverview,
  renderStorageCategories,
  renderStorageFolders,
  renderStorageApps,
  renderApkProgressCard,
} from './file_browser_view.js';

const STORAGE_DIRECT_OPEN_MAX_MB = 'opaldesk-direct-open-max-mb-v1';

const $ = (id) => document.getElementById(id);

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
    renderDirectOpenSelect($('fmDirectOpenSelect'), this.directOpenMaxMb);
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
    renderStatusBanner($('fmStatusBanner'), text, tone);
  }

  renderQuickNav() {
    renderQuickNavList($('fmQuickNavList'), (path) => {
      if (this.activeTab !== 'files') this.selectTab('files');
      void this.navigate(path);
    });
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
    renderBreadcrumbsBar($('fmBreadcrumbs'), this.currentPath, (p) => void this.navigate(p));
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
    this.syncBatchBar();
    let list = sortRemoteEntries(this.entries, this.sortBy, this.sortDir);
    if (this.searchQuery) {
      list = list.filter((e) => e.name.toLowerCase().includes(this.searchQuery));
    }
    renderEntriesList($('fmEntriesWrap'), {
      list,
      viewMode: this.viewMode,
      loading: this.loading,
      selectedPaths: this.selectedPaths,
      desktopEnabled: Boolean(this.app.desktop?.enabled),
      directOpenMaxMb: this.directOpenMaxMb,
      onToggleSelect: (path, checked) => {
        if (checked) this.selectedPaths.add(path);
        else this.selectedPaths.delete(path);
        this.syncBatchBar();
      },
      onPopulateGridThumbnail: (entry, iconWrap) => void this._populateGridThumbnail(entry, iconWrap),
      onNavigate: (path) => void this.navigate(path),
      onInstallApk: (path) => void this.installRemoteApk(path),
      onOpenDirectly: (entry) => void this.openEntryDirectly(entry),
      onPreviewImage: (entry) => void this.previewImage(entry),
      onDownload: (entry) => void this.downloadEntry(entry),
      onRename: (entry) => void this.promptRename(entry),
      onDelete: (entries) => void this.confirmDelete(entries),
    });
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
    renderStorageOverview($('fmStorageOverview'), this.storageSummary, this.storageLoading);
    this.renderStorageBody();
  }

  renderStorageBody() {
    const host = $('fmStorageBody');
    if (!host) return;
    host.textContent = '';
    const summary = this.storageSummary;
    if (!summary) return;

    if (this.storageSubTab === 'categories') {
      renderStorageCategories(host, summary, {
        onDownload: (entry) => void this.downloadEntry(entry),
        onDeleteLargeFile: async (f) => {
          await this.confirmDelete([{ name: f.name, path: f.path }]);
          void this.scanStorage(true);
        },
      });
    } else if (this.storageSubTab === 'folders') {
      renderStorageFolders(host, summary, (folderPath) => {
        this.currentPath = normalizeRemotePath(folderPath);
        this.selectTab('files');
      });
    } else {
      renderStorageApps(host, summary, (pkg, label) => this.app.launchPopoutByPackage(pkg, label));
    }
  }

  // ==================== WIRELESS APK INSTALLER ====================

  _renderApkProgress(opts) {
    renderApkProgressCard(opts);
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
