/**
 * OpalDesk Remote File Manager, 3-Tab Storage Analyzer & Wireless APK Installer UI Controller.
 * Shared across Windows Tauri Desktop (streaming disk writes + Explorer reveal + local quick preview)
 * and Web browsers (Blob download + inline image preview).
 */

import {
  DEFAULT_REMOTE_ROOT,
  QUICK_NAV_SHORTCUTS,
  normalizeRemotePath,
  joinRemotePath,
  getParentRemotePath,
  buildPathBreadcrumbs,
  formatBytes,
  formatFileTimestamp,
  classifyEntryKind,
  isPreviewableImageEntry,
  sortRemoteEntries,
  getStorageCategoryMeta,
} from './file_browser_client.js';
import { inferApkFormat } from './protocol.js';

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

const APK_STAGE_LABELS = Object.freeze({
  uploading: '正在上传安装包…',
  extracting: '正在解压分包资源…',
  installing: '正在静默安装应用…',
  succeeded: '安装成功',
  failed: '安装失败',
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
    this.activeTransferNote = '';
    this.apkInstallBusy = false;
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
    $('fmViewToggleBtn')?.addEventListener('click', () => {
      this.viewMode = this.viewMode === 'list' ? 'grid' : 'list';
      $('fmViewToggleBtn').textContent = this.viewMode === 'list' ? '网格' : '列表';
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
      this.app.showToast('请先连接手机（投屏或后台随行模式）后再管理文件');
      return;
    }
    const overlay = $('fileManagerOverlay');
    if (!overlay) return;
    overlay.hidden = false;
    if (initialPath) {
      this.currentPath = normalizeRemotePath(initialPath);
    }
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
      const btn = createEl('button', 'fm-quick-nav-btn', item.label);
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
      const btn = createEl('button', 'fm-crumb-btn', crumb.label);
      btn.type = 'button';
      btn.disabled = idx === crumbs.length - 1;
      btn.addEventListener('click', () => void this.navigate(crumb.path));
      host.appendChild(btn);
    });
  }

  async refreshCurrentDir() {
    if (!this.app.hasControlChannel()) {
      this.setStatusBanner('当前未连接手机控制通道', 'warn');
      return;
    }
    this.loading = true;
    this.setStatusBanner(`正在读取 ${this.currentPath}…`);
    try {
      const resp = await this.client.list(this.currentPath);
      this.entries = resp.entries || [];
      this.setStatusBanner('');
    } catch (err) {
      this.entries = [];
      this.setStatusBanner(err?.message || '读取目录失败', 'error');
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
    if (label) label.textContent = `已选择 ${count} 项`;
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
      host.appendChild(createEl('div', 'fm-empty', this.loading ? '正在加载…' : '当前目录为空'));
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
        } else if (isPreviewableImageEntry(entry)) {
          void this.previewImage(entry);
        } else {
          void this.downloadEntry(entry);
        }
      });

      const actions = createEl('div', 'fm-entry-actions');
      if (!entry.isDir) {
        if (kind === 'apk') {
          const instBtn = createEl('button', 'btn-pill small primary', '安装');
          instBtn.type = 'button';
          instBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            void this.installRemoteApk(entry.path);
          });
          actions.appendChild(instBtn);
        } else if (isPreviewableImageEntry(entry)) {
          const prevBtn = createEl('button', 'btn-pill small', '预览');
          prevBtn.type = 'button';
          prevBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            void this.previewImage(entry);
          });
          actions.appendChild(prevBtn);
        }
        const dlBtn = createEl('button', 'btn-pill small', '下载');
        dlBtn.type = 'button';
        dlBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          void this.downloadEntry(entry);
        });
        actions.appendChild(dlBtn);
      }

      const renBtn = createEl('button', 'btn-pill small ghost', '重命名');
      renBtn.type = 'button';
      renBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        void this.promptRename(entry);
      });
      const delBtn = createEl('button', 'btn-pill small danger-ghost', '删除');
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
    this.setStatusBanner(`正在加载 ${entry.name} 预览…`);
    try {
      const b64 = this.thumbnailCache.get(entry.path) || (await this.client.getThumbnail(entry.path));
      if (!b64) throw new Error('无法生成该图片的缩略预览');
      this.thumbnailCache.set(entry.path, b64);
      const img = $('fmPreviewImg');
      const title = $('fmPreviewTitle');
      if (img) img.src = b64.startsWith('data:') ? b64 : `data:image/jpeg;base64,${b64}`;
      if (title) title.textContent = `${entry.name} (${formatBytes(entry.size)})`;
      if ($('fmPreviewOverlay')) $('fmPreviewOverlay').hidden = false;
      this.setStatusBanner('');
    } catch (err) {
      this.setStatusBanner(err?.message || '预览失败', 'error');
    }
  }

  async downloadEntry(entry) {
    if (!entry || entry.isDir) return;
    const desktop = this.app.desktop;
    const useNativeStream = desktop?.enabled && typeof desktop.beginFileDownload === 'function';
    let sessionId = '';
    this.setStatusBanner(`正在下载 ${entry.name}…`);

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
          this.setStatusBanner(`正在下载 ${entry.name} (${pct}% · ${formatBytes(received)})`);
        },
      });

      if (useNativeStream) {
        const done = await desktop.finishFileDownload({ sessionId });
        sessionId = '';
        this.setStatusBanner(`已下载到 ${done.savedPath}`, 'ok');
        this.app.showToast(`已下载 ${entry.name}`, {
          actionText: '在文件夹中显示',
          actionFn: () => void desktop.revealInExplorer(done.savedPath),
        });
      } else if (result.bytes) {
        triggerBrowserBlobDownload(result.bytes, result.fileName || entry.name);
        this.setStatusBanner(`已下载 ${entry.name} (${formatBytes(result.receivedBytes)})`, 'ok');
      }
    } catch (err) {
      if (useNativeStream && sessionId) {
        await desktop.abortFileDownload({ sessionId }).catch(() => {});
      }
      this.setStatusBanner(err?.message || `下载 ${entry.name} 失败`, 'error');
    }
  }

  async downloadSelectedEntries() {
    const selected = this.entries.filter((e) => this.selectedPaths.has(e.path) && !e.isDir);
    if (!selected.length) {
      this.app.showToast('请至少选择一个文件（不支持直接批量下载文件夹）');
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
      this.setStatusBanner(`正在上传 ${file.name}…`);
      try {
        await this.client.uploadFile(file, {
          onProgress: (sent, total) => {
            const pct = total > 0 ? Math.round((sent / total) * 100) : 100;
            this.setStatusBanner(`正在上传 ${file.name} (${pct}%)`);
          },
        });
        this.app.showToast(`已上传 ${file.name} 到手机下载目录`);
      } catch (err) {
        this.setStatusBanner(err?.message || `上传 ${file.name} 失败`, 'error');
        return;
      }
    }
    this.setStatusBanner('上传完成', 'ok');
    if (this.currentPath.startsWith('/sdcard/Download')) {
      await this.refreshCurrentDir();
    }
  }

  async promptMkdir() {
    const name = window.prompt('输入新文件夹名称：', '');
    if (!name || !name.trim()) return;
    try {
      await this.client.mkdir(joinRemotePath(this.currentPath, name.trim()));
      await this.refreshCurrentDir();
      this.app.showToast(`已创建文件夹 ${name.trim()}`);
    } catch (err) {
      this.setStatusBanner(err?.message || '新建文件夹失败', 'error');
    }
  }

  async promptRename(entry) {
    const nextName = window.prompt('重命名为：', entry.name);
    if (!nextName || !nextName.trim() || nextName.trim() === entry.name) return;
    const parent = getParentRemotePath(entry.path);
    const nextPath = joinRemotePath(parent, nextName.trim());
    try {
      await this.client.rename(entry.path, nextPath);
      await this.refreshCurrentDir();
      this.app.showToast(`已重命名为 ${nextName.trim()}`);
    } catch (err) {
      this.setStatusBanner(err?.message || '重命名失败', 'error');
    }
  }

  async confirmDelete(list) {
    const msg = list.length === 1
      ? `确定删除「${list[0].name}」吗？此操作无法撤销。`
      : `确定删除选中的 ${list.length} 个项目吗？此操作无法撤销。`;
    if (!window.confirm(msg)) return;
    for (const item of list) {
      try {
        await this.client.deletePath(item.path);
        this.selectedPaths.delete(item.path);
      } catch (err) {
        this.setStatusBanner(err?.message || `删除 ${item.name} 失败`, 'error');
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
    this.setStatusBanner('正在扫描手机存储空间（按类型 / 文件夹 / 应用排行）…');
    try {
      this.storageSummary = await this.client.scanStorage(DEFAULT_REMOTE_ROOT, 4);
      this.setStatusBanner('');
    } catch (err) {
      this.setStatusBanner(err?.message || '存储空间扫描失败', 'error');
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
        head.appendChild(createEl('div', 'fm-empty', this.storageLoading ? '正在扫描中…' : '点击「重新扫描」分析手机存储'));
      } else {
        const pct = summary.totalBytes > 0 ? Math.min(100, Math.round((summary.usedBytes / summary.totalBytes) * 100)) : 0;
        const stats = createEl('div', 'fm-storage-stats');
        stats.append(
          createEl('strong', '', `已用 ${formatBytes(summary.usedBytes)} / ${formatBytes(summary.totalBytes)} (${pct}%)`),
          createEl('span', 'muted', `可用 ${formatBytes(summary.freeBytes)}`)
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
      const card = createEl('div', 'fm-cat-card');
      const dot = createEl('span', 'fm-cat-dot');
      dot.style.background = meta.color;
      const top = createEl('div', 'fm-cat-top');
      top.append(dot, createEl('strong', '', meta.label));
      const val = createEl('div', 'fm-cat-val', formatBytes(cat.bytes));
      const sub = createEl('div', 'fm-cat-sub', `${cat.fileCount} 个文件 · ${((cat.bytes / total) * 100).toFixed(1)}%`);
      card.append(top, val, sub);
      grid.appendChild(card);
    }
    host.appendChild(grid);

    if (summary.largeFiles?.length) {
      host.appendChild(createEl('h4', 'fm-sub-heading', '大文件排行 Top 20'));
      const list = createEl('div', 'fm-rank-list');
      for (const f of summary.largeFiles.slice(0, 20)) {
        const row = createEl('div', 'fm-rank-row');
        const info = createEl('div', 'fm-rank-info');
        info.append(createEl('div', 'fm-rank-title', f.name), createEl('div', 'fm-rank-path mono', f.path));
        const right = createEl('div', 'fm-rank-right');
        right.appendChild(createEl('span', 'fm-rank-size mono', formatBytes(f.size)));
        const dlBtn = createEl('button', 'btn-pill small', '下载');
        dlBtn.type = 'button';
        dlBtn.addEventListener('click', () => void this.downloadEntry({ name: f.name, path: f.path, size: f.size, isDir: false }));
        const delBtn = createEl('button', 'btn-pill small danger-ghost', '删除');
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
      host.appendChild(createEl('div', 'fm-empty', '暂无文件夹排行数据'));
      return;
    }
    const maxBytes = Math.max(1, folders[0]?.bytes || 1);
    const list = createEl('div', 'fm-rank-list');
    for (const folder of folders.slice(0, 25)) {
      const row = createEl('div', 'fm-rank-row clickable');
      const info = createEl('div', 'fm-rank-info');
      info.append(
        createEl('div', 'fm-rank-title', `📁 ${folder.name || folder.path}`),
        createEl('div', 'fm-rank-path mono', `${folder.path} · ${folder.fileCount} 个文件`)
      );
      const bar = createEl('div', 'fm-mini-bar');
      const fill = createEl('div', 'fm-mini-bar-fill');
      fill.style.width = `${Math.max(4, Math.round((folder.bytes / maxBytes) * 100))}%`;
      bar.appendChild(fill);
      info.appendChild(bar);

      const right = createEl('div', 'fm-rank-right');
      right.appendChild(createEl('span', 'fm-rank-size mono', formatBytes(folder.bytes)));
      const openBtn = createEl('button', 'btn-pill small primary', '查看');
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
      host.appendChild(createEl('div', 'fm-empty', '暂无应用存储排行数据（需手机端守护进程支持 StorageStats）'));
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
          `应用 ${formatBytes(app.apkBytes)} · 数据 ${formatBytes(app.dataBytes)} · 缓存 ${formatBytes(app.cacheBytes)}`
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
        const popBtn = createEl('button', 'btn-pill small', '小窗打开');
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
      this.app.showToast('当前已有安装包正在安装，请稍候');
      return;
    }
    const format = inferApkFormat(file.name);
    if (!format) {
      this.app.showToast('请选择 .apk / .apks / .apkm / .xapk 格式的安装包');
      return;
    }
    this.apkInstallBusy = true;
    this._renderApkProgress({
      stageLabel: `正在上传 ${file.name}…`,
      progress: 5,
      detail: `${formatBytes(file.size)} · 格式 ${format.toUpperCase()}`,
    });

    try {
      const ack = await this.client.uploadFile(file, {
        onProgress: (sent, total) => {
          const pct = total > 0 ? Math.round((sent / total) * 45) : 45;
          this._renderApkProgress({
            stageLabel: `正在上传 ${file.name} (${Math.round((sent / Math.max(1, total)) * 100)}%)`,
            progress: pct,
            detail: `${formatBytes(sent)} / ${formatBytes(total)}`,
          });
        },
      });
      await this._runApkInstall(ack.remotePath, format);
    } catch (err) {
      this._renderApkProgress({
        stageLabel: '上传或安装失败',
        progress: 100,
        detail: err?.message || '未知错误',
        tone: 'error',
      });
    } finally {
      this.apkInstallBusy = false;
    }
  }

  async installRemoteApk(remotePath) {
    this.selectTab('apk');
    if (this.apkInstallBusy) {
      this.app.showToast('当前已有安装包正在安装，请稍候');
      return;
    }
    const format = inferApkFormat(remotePath);
    if (!format) {
      this.app.showToast('不支持的安装包格式');
      return;
    }
    this.apkInstallBusy = true;
    try {
      await this._runApkInstall(remotePath, format);
    } catch (err) {
      this._renderApkProgress({
        stageLabel: '安装失败',
        progress: 100,
        detail: err?.message || '未知错误',
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
      stageLabel: '已发送到手机端，正在准备安装…',
      progress: 50,
      detail: remotePath,
    });
    const res = await this.client.installApk(remotePath, {
      format,
      replaceExisting,
      allowDowngrade,
      onStatus: (st) => {
        const mappedPct = 50 + Math.round((st.progress || 0) * 0.5);
        this._renderApkProgress({
          stageLabel: APK_STAGE_LABELS[st.stage] || st.stage || '安装中…',
          progress: mappedPct,
          detail: st.message || st.packageName || remotePath,
          tone: st.stage === 'succeeded' ? 'ok' : st.stage === 'failed' ? 'error' : '',
        });
      },
    });
    this._renderApkProgress({
      stageLabel: '安装成功',
      progress: 100,
      detail: res.packageName ? `包名：${res.packageName}` : (res.message || '应用已安装到手机'),
      tone: 'ok',
    });
    this.app.showToast(`APK 安装成功${res.packageName ? ` (${res.packageName})` : ''}`);
  }
}
