/**
 * Diagnostics Log Console & Bug / Feature Request Feedback Controller.
 * Aligns Windows Desktop Client & Web Receiver with Android DiagnosticsLogTab / ReportIssueModal.
 */

import {
  buildWebDiagnosticReport,
  filterLogEntries,
  formatFeedbackTime,
  formatLogTime,
  isFeedbackSubmitAllowed,
  MAX_DAILY_REPORTS,
  MAX_WEB_LOCAL_LOGS,
  normalizeFeedbackKind,
  normalizeShortCode,
  QQ_GROUP_NUMBER,
  resolveFeedbackBadge,
} from './feedback_helpers.js';
import { t } from './i18n.js';

const STORAGE_REPORTER_NAME = 'opaldesk-feedback-reporter-v1';

const $ = (id) => document.getElementById(id);

export class OpalDiagnosticsController {
  constructor({ desktop, showToast, copyText, getContextSummary }) {
    this.desktop = desktop;
    this.showToast = showToast;
    this.copyText = copyText;
    this.getContextSummary = getContextSummary || (() => '');

    this.logEntries = [];
    this.warningsOnly = false;
    this.newestFirst = true;
    this.logQuery = '';

    this.feedbackState = {
      sentryConfigured: false,
      statusSyncConfigured: false,
      appVersion: 'web',
      installationId: '',
      deviceSummary: navigator.userAgent || 'Browser',
      quota: { dateKey: '', usedToday: 0, maxDaily: MAX_DAILY_REPORTS, remaining: MAX_DAILY_REPORTS, exhausted: false },
      records: [],
      lastSyncAtMs: 0,
    };

    this.modalKind = 'bug';
    this.submitting = false;
    this.lastUploadSucceeded = false;
    this.lastSubmittedDescription = null;
    this.lastSubmittedKind = null;
    this.lastSuccessShortCode = '';
    this.hasAutoRefreshedStatuses = false;
  }

  bindEvents() {
    $('openBugReportBtn')?.addEventListener('click', () => this.openFeedbackModal('bug'));
    $('openFeatureReportBtn')?.addEventListener('click', () => this.openFeedbackModal('feature'));
    $('copyQqGroupBtn')?.addEventListener('click', () => {
      this.copyText(QQ_GROUP_NUMBER, t('diag.qqCopiedToast', { group: QQ_GROUP_NUMBER }));
    });

    $('refreshLogsBtn')?.addEventListener('click', () => this.refreshLogs(true));
    $('copyLogsBtn')?.addEventListener('click', () => this.copyDiagnosticReport());
    $('exportLogsBtn')?.addEventListener('click', () => this.exportDiagnosticReport());
    $('clearLogsBtn')?.addEventListener('click', () => this.clearLogs());

    $('logWarnOnlyBtn')?.addEventListener('click', () => {
      this.warningsOnly = !this.warningsOnly;
      $('logWarnOnlyBtn')?.classList.toggle('active', this.warningsOnly);
      this.renderLogConsole();
    });
    $('logOrderBtn')?.addEventListener('click', () => {
      this.newestFirst = !this.newestFirst;
      const btn = $('logOrderBtn');
      if (btn) {
        btn.classList.toggle('active', this.newestFirst);
        btn.textContent = this.newestFirst ? t('diag.orderNewest') : t('diag.orderOldest');
      }
      this.renderLogConsole();
    });
    $('logSearchInput')?.addEventListener('input', (e) => {
      this.logQuery = e.target.value || '';
      this.renderLogConsole();
    });

    // Feedback tracking section
    $('refreshFeedbackBtn')?.addEventListener('click', () => this.refreshFeedbackStatuses(true));
    $('lookupFeedbackBtn')?.addEventListener('click', () => {
      const bar = $('feedbackLookupBar');
      if (!bar) return;
      bar.hidden = !bar.hidden;
      if (!bar.hidden) {
        $('feedbackLookupInput')?.focus();
      }
    });
    $('cancelFeedbackLookupBtn')?.addEventListener('click', () => {
      if ($('feedbackLookupBar')) $('feedbackLookupBar').hidden = true;
      if ($('feedbackLookupStatus')) $('feedbackLookupStatus').hidden = true;
    });
    $('submitFeedbackLookupBtn')?.addEventListener('click', () => this.lookupByCode());
    $('feedbackLookupInput')?.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        this.lookupByCode();
      }
    });

    // Feedback modal
    $('fbKindBugBtn')?.addEventListener('click', () => this.selectModalKind('bug'));
    $('fbKindFeatureBtn')?.addEventListener('click', () => this.selectModalKind('feature'));
    $('fbDescInput')?.addEventListener('input', () => this.syncModalSubmitButton());
    $('fbReporterInput')?.addEventListener('change', (e) => this.saveReporterName(e.target.value));
    $('fbCancelBtn')?.addEventListener('click', () => this.closeFeedbackModal());
    $('fbSubmitBtn')?.addEventListener('click', () => this.submitFeedback());
    $('fbCopyCodeBtn')?.addEventListener('click', () => {
      if (this.lastSuccessShortCode) {
        this.copyText(this.lastSuccessShortCode, t('diag.codeCopiedToast', { code: this.lastSuccessShortCode }));
      }
    });
    $('feedbackModalOverlay')?.addEventListener('click', (e) => {
      if (e.target === $('feedbackModalOverlay') && !this.submitting) {
        this.closeFeedbackModal();
      }
    });
  }

  async initialize() {
    this.log('I', 'App', this.desktop.enabled ? t('diag.initDesktopLog') : t('diag.initWebLog'));
    if (this.desktop.enabled) {
      await Promise.all([this.refreshLogs(false), this.refreshFeedbackState()]);
    } else {
      this.renderMetrics();
      this.renderLogConsole();
      this.renderFeedbackRecords();
    }
  }

  async onTabActivated() {
    await this.refreshLogs(false);
    if (this.desktop.enabled) {
      await this.refreshFeedbackState();
      if (!this.hasAutoRefreshedStatuses && this.feedbackState.statusSyncConfigured && this.feedbackState.records.length > 0) {
        this.hasAutoRefreshedStatuses = true;
        this.refreshFeedbackStatuses(false);
      }
    }
  }

  log(level, tag, message) {
    const cleanLevel = String(level || 'I').toUpperCase().slice(0, 1);
    const cleanTag = String(tag || 'Web');
    const cleanMsg = String(message || '');
    const entry = {
      id: Date.now() + Math.random(),
      timestampMs: Date.now(),
      level: ['D', 'I', 'W', 'E'].includes(cleanLevel) ? cleanLevel : 'I',
      tag: cleanTag,
      message: cleanMsg,
    };

    if (this.desktop.enabled) {
      this.desktop.appendClientLog(entry.level, entry.tag, entry.message);
    }
    this.logEntries.push(entry);
    if (this.logEntries.length > MAX_WEB_LOCAL_LOGS) {
      this.logEntries = this.logEntries.slice(-MAX_WEB_LOCAL_LOGS);
    }
    if (!$('paneLogs')?.hidden) {
      this.renderMetrics();
      this.renderLogConsole();
    }
  }

  async refreshLogs(showToastOnDone = false) {
    if (this.desktop.enabled) {
      try {
        this.logEntries = await this.desktop.getClientLogs();
      } catch {
        // Keep current cache on transient error
      }
    }
    this.renderMetrics();
    this.renderLogConsole();
    if (showToastOnDone) {
      this.showToast(t('diag.logsRefreshedToast', { count: this.logEntries.length }));
    }
  }

  async refreshFeedbackState() {
    if (!this.desktop.enabled) {
      this.renderMetrics();
      this.renderFeedbackRecords();
      return;
    }
    try {
      const snap = await this.desktop.getFeedbackState();
      if (snap) {
        this.feedbackState = snap;
      }
    } catch {
      // Keep fallback state
    }
    this.renderMetrics();
    this.renderFeedbackRecords();
  }

  renderMetrics() {
    const total = this.logEntries.length;
    const warnCount = this.logEntries.filter((e) => e.level === 'W' || e.level === 'E').length;
    const quota = this.feedbackState.quota || { remaining: MAX_DAILY_REPORTS, maxDaily: MAX_DAILY_REPORTS };

    if ($('diagDeviceMeta')) {
      if (this.desktop.enabled) {
        $('diagDeviceMeta').textContent = t('diag.clientMetaDesktop', {
          version: this.feedbackState.appVersion || '1.0.22',
          summary: this.feedbackState.deviceSummary || 'Desktop',
        });
      } else {
        $('diagDeviceMeta').textContent = t('diag.clientMetaWeb');
      }
    }
    if ($('diagMetricTotal')) $('diagMetricTotal').textContent = String(total);
    if ($('diagMetricWarn')) $('diagMetricWarn').textContent = String(warnCount);
    if ($('diagMetricQuota')) {
      $('diagMetricQuota').textContent = this.desktop.enabled
        ? `${quota.remaining}/${quota.maxDaily}`
        : t('diag.quotaDesktopOnly');
    }
  }

  renderLogConsole() {
    const el = $('diagLogConsole');
    if (!el) return;
    const filtered = filterLogEntries(this.logEntries, {
      warningsOnly: this.warningsOnly,
      query: this.logQuery,
      newestFirst: this.newestFirst,
    });

    el.innerHTML = '';
    if (!filtered.length) {
      const empty = document.createElement('span');
      empty.className = 'hint-text';
      empty.textContent = this.logEntries.length === 0 ? t('diag.logEmpty') : t('diag.logNoMatch');
      el.appendChild(empty);
      return;
    }

    const frag = document.createDocumentFragment();
    for (const entry of filtered.slice(0, 300)) {
      const row = document.createElement('div');
      row.className = 'log-line';

      const ts = document.createElement('span');
      ts.className = 'log-ts';
      ts.textContent = formatLogTime(entry.timestampMs);

      const lvl = document.createElement('span');
      lvl.className = `log-lvl lvl-${entry.level || 'I'}`;
      lvl.textContent = entry.level || 'I';

      const tag = document.createElement('span');
      tag.className = 'log-tag';
      tag.textContent = `[${entry.tag || 'App'}]`;

      const msg = document.createElement('span');
      msg.className = 'log-msg';
      msg.textContent = entry.message || '';

      row.append(ts, lvl, tag, msg);
      frag.appendChild(row);
    }
    el.appendChild(frag);
  }

  renderFeedbackRecords() {
    const listEl = $('feedbackRecordsList');
    const syncMeta = $('feedbackSyncMeta');
    if (!listEl) return;

    if (syncMeta) {
      if (!this.desktop.enabled) {
        syncMeta.textContent = t('diag.syncDesktopOnly');
      } else if (this.feedbackState.lastSyncAtMs > 0) {
        syncMeta.textContent = t('diag.lastSync', { time: formatFeedbackTime(this.feedbackState.lastSyncAtMs) });
      } else {
        syncMeta.textContent = '';
      }
    }

    const records = Array.isArray(this.feedbackState.records) ? this.feedbackState.records : [];
    listEl.innerHTML = '';
    if (!records.length) {
      const hint = document.createElement('span');
      hint.className = 'hint-text';
      hint.textContent = this.desktop.enabled
        ? t('diag.recordsEmptyDesktop')
        : t('diag.recordsEmptyWeb');
      listEl.appendChild(hint);
      return;
    }

    const curVer = this.feedbackState.appVersion || '';
    for (const rec of records) {
      const card = document.createElement('div');
      card.className = 'fb-record-card';

      const head = document.createElement('div');
      head.className = 'fb-record-head';

      const badges = document.createElement('div');
      badges.className = 'fb-record-badges';

      const codeBtn = document.createElement('span');
      codeBtn.className = 'fb-code-tag mono';
      codeBtn.textContent = `#${rec.shortCode || '--------'}`;
      codeBtn.title = t('diag.copyCodeTitle');
      codeBtn.addEventListener('click', () => {
        if (rec.shortCode) this.copyText(rec.shortCode, t('diag.codeCopiedToast', { code: rec.shortCode }));
      });

      const kind = normalizeFeedbackKind(rec.kind);
      const kindPill = document.createElement('span');
      kindPill.className = `fb-kind-pill ${kind}`;
      kindPill.textContent = kind === 'feature' ? t('diag.kindFeature') : t('diag.kindBug');

      const badgeInfo = resolveFeedbackBadge(rec, curVer);
      const statusPill = document.createElement('span');
      statusPill.className = `fb-status-pill tone-${badgeInfo.tone}`;
      statusPill.textContent = badgeInfo.label;

      badges.append(codeBtn, kindPill, statusPill);

      const delBtn = document.createElement('button');
      delBtn.type = 'button';
      delBtn.className = 'hist-icon-btn remove';
      delBtn.title = t('diag.removeLocalTitle');
      delBtn.textContent = '×';
      delBtn.addEventListener('click', async () => {
        if (!this.desktop.enabled) return;
        try {
          const next = await this.desktop.removeFeedbackRecord(rec.eventId);
          this.feedbackState.records = next;
          this.renderFeedbackRecords();
        } catch {
          // Ignore
        }
      });

      head.append(badges, delBtn);

      const summary = document.createElement('div');
      summary.className = 'fb-record-summary';
      summary.textContent = rec.summary || (kind === 'feature' ? t('diag.kindFeature') : t('diag.kindBug'));

      const meta = document.createElement('div');
      meta.className = 'fb-record-meta';
      const metaParts = [];
      if (rec.reportedVersion) metaParts.push(t('diag.reportedVersion', { version: rec.reportedVersion }));
      if (rec.createdAtMs > 0) metaParts.push(formatFeedbackTime(rec.createdAtMs));
      if (badgeInfo.hint) metaParts.push(badgeInfo.hint);
      meta.textContent = metaParts.join(' · ');

      card.append(head, summary, meta);

      if (rec.replyNote) {
        const reply = document.createElement('div');
        reply.className = 'fb-reply-callout';
        reply.textContent = t('diag.devReplyPrefix', { note: rec.replyNote });
        card.appendChild(reply);
      }

      listEl.appendChild(card);
    }
  }

  async copyDiagnosticReport() {
    try {
      let report;
      if (this.desktop.enabled) {
        report = await this.desktop.buildDiagnosticReport({
          contextSummary: this.getContextSummary(),
          warningsOnly: this.warningsOnly,
          query: this.logQuery,
        });
      } else {
        const filtered = filterLogEntries(this.logEntries, {
          warningsOnly: this.warningsOnly,
          query: this.logQuery,
          newestFirst: false,
        });
        report = buildWebDiagnosticReport(filtered);
      }
      this.copyText(report, t('diag.copyReportToast'));
    } catch (err) {
      this.showToast(t('diag.copyReportFail', { error: err?.message || err }));
    }
  }

  async exportDiagnosticReport() {
    try {
      if (this.desktop.enabled) {
        const content = await this.desktop.buildDiagnosticReport({
          contextSummary: this.getContextSummary(),
          warningsOnly: this.warningsOnly,
          query: this.logQuery,
        });
        const savedPath = await this.desktop.exportDiagnosticReport(content);
        this.showToast(t('diag.exportDesktopToast', { path: savedPath }));
        await this.refreshLogs(false);
        return;
      }
      const filtered = filterLogEntries(this.logEntries, {
        warningsOnly: this.warningsOnly,
        query: this.logQuery,
        newestFirst: false,
      });
      const content = buildWebDiagnosticReport(filtered);
      const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `opaldesk_web_diag_${Date.now()}.txt`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      this.showToast(t('diag.exportWebToast'));
    } catch (err) {
      this.showToast(t('diag.exportFail', { error: err?.message || err }));
    }
  }

  async clearLogs() {
    if (this.desktop.enabled) {
      try {
        await this.desktop.clearClientLogs();
      } catch {
        // Ignore
      }
    }
    this.logEntries = [];
    this.renderMetrics();
    this.renderLogConsole();
    this.showToast(t('diag.logsClearedToast'));
  }

  async refreshFeedbackStatuses(force = true) {
    if (!this.desktop.enabled) {
      this.showToast(t('diag.refreshDesktopOnly'));
      return;
    }
    const btn = $('refreshFeedbackBtn');
    if (btn) btn.disabled = true;
    try {
      const outcome = await this.desktop.refreshFeedbackStatuses(force);
      if (Array.isArray(outcome.records)) {
        this.feedbackState.records = outcome.records;
      }
      if (outcome.lastSyncAtMs > 0) {
        this.feedbackState.lastSyncAtMs = outcome.lastSyncAtMs;
      }
      this.renderFeedbackRecords();
      if (force) {
        if (outcome.success) {
          this.showToast(outcome.message || t('diag.syncedToast', { count: outcome.updatedCount || 0 }));
        } else {
          this.showToast(outcome.message || t('diag.syncFailToast'));
        }
      }
    } catch (err) {
      if (force) this.showToast(t('diag.refreshFailToast', { error: err?.message || err }));
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  async lookupByCode() {
    if (!this.desktop.enabled) {
      this.showToast(t('diag.lookupDesktopOnly'));
      return;
    }
    const raw = $('feedbackLookupInput')?.value || '';
    const code = normalizeShortCode(raw);
    const statusEl = $('feedbackLookupStatus');
    if (!code) {
      if (statusEl) {
        statusEl.hidden = false;
        statusEl.textContent = t('diag.lookupInvalidCode');
      }
      return;
    }
    if (statusEl) {
      statusEl.hidden = false;
      statusEl.textContent = t('diag.lookupSearching', { code });
    }
    try {
      const outcome = await this.desktop.lookupFeedbackByCode(code);
      if (Array.isArray(outcome.records)) {
        this.feedbackState.records = outcome.records;
      }
      if (outcome.lastSyncAtMs > 0) {
        this.feedbackState.lastSyncAtMs = outcome.lastSyncAtMs;
      }
      this.renderFeedbackRecords();
      if (outcome.found) {
        if (statusEl) statusEl.textContent = t('diag.lookupFound', { code });
        if ($('feedbackLookupInput')) $('feedbackLookupInput').value = '';
      } else {
        if (statusEl) statusEl.textContent = outcome.message || t('diag.lookupNotFound', { code });
      }
    } catch (err) {
      if (statusEl) statusEl.textContent = t('diag.lookupFail', { error: err?.message || err });
    }
  }

  loadReporterName() {
    try {
      return localStorage.getItem(STORAGE_REPORTER_NAME) || '';
    } catch {
      return '';
    }
  }

  saveReporterName(name) {
    try {
      localStorage.setItem(STORAGE_REPORTER_NAME, String(name || '').trim().slice(0, 64));
    } catch {
      // Ignore storage error
    }
  }

  async openFeedbackModal(initialKind = 'bug') {
    await this.refreshFeedbackState();
    this.lastUploadSucceeded = false;
    this.lastSuccessShortCode = '';
    if ($('fbReporterInput')) {
      $('fbReporterInput').value = this.loadReporterName();
    }
    if ($('fbDescInput')) {
      $('fbDescInput').value = '';
    }
    if ($('fbModalErrorBox')) $('fbModalErrorBox').hidden = true;
    if ($('fbSuccessBox')) $('fbSuccessBox').hidden = true;

    this.selectModalKind(initialKind, true);
    this.syncModalConfigAndQuota();
    if ($('feedbackModalOverlay')) $('feedbackModalOverlay').hidden = false;
    $('fbDescInput')?.focus();
  }

  closeFeedbackModal() {
    if ($('feedbackModalOverlay')) $('feedbackModalOverlay').hidden = true;
  }

  selectModalKind(kind, resetLogToggle = true) {
    this.modalKind = normalizeFeedbackKind(kind);
    const isFeature = this.modalKind === 'feature';
    $('fbKindBugBtn')?.classList.toggle('active', !isFeature);
    $('fbKindFeatureBtn')?.classList.toggle('active', isFeature);
    $('fbKindBugBtn')?.setAttribute('aria-selected', String(!isFeature));
    $('fbKindFeatureBtn')?.setAttribute('aria-selected', String(isFeature));

    if ($('fbDescLabel')) {
      $('fbDescLabel').textContent = isFeature ? t('diag.descFeatureLabel') : t('diag.descBugLabel');
    }
    if ($('fbDescInput')) {
      $('fbDescInput').placeholder = isFeature
        ? t('diag.descFeaturePlaceholder')
        : t('diag.descBugPlaceholder');
    }
    if (resetLogToggle && $('fbIncludeLogsCheck')) {
      // Default ON for bugs, OFF for feature requests (matches Android & macOS)
      $('fbIncludeLogsCheck').checked = !isFeature;
    }
    this.syncModalSubmitButton();
  }

  syncModalConfigAndQuota() {
    const quota = this.feedbackState.quota || { remaining: MAX_DAILY_REPORTS, maxDaily: MAX_DAILY_REPORTS, exhausted: false };
    const pill = $('fbModalQuotaPill');
    if (pill) {
      pill.textContent = this.desktop.enabled
        ? t('diag.modalQuotaRemaining', { remaining: quota.remaining, max: quota.maxDaily })
        : t('diag.modalWebMode');
      pill.className = `status-pill mono ${quota.exhausted ? 'warn' : 'ok'}`;
    }

    const warnBox = $('fbConfigWarnBox');
    if (warnBox) {
      if (!this.desktop.enabled) {
        warnBox.hidden = false;
        warnBox.textContent = t('diag.warnWebMode');
      } else if (!this.feedbackState.sentryConfigured) {
        warnBox.hidden = false;
        warnBox.textContent = t('diag.warnNotConfigured');
      } else if (quota.exhausted) {
        warnBox.hidden = false;
        warnBox.textContent = t('diag.warnQuotaExhausted');
      } else {
        warnBox.hidden = true;
      }
    }
    this.syncModalSubmitButton();
  }

  syncModalSubmitButton() {
    const btn = $('fbSubmitBtn');
    if (!btn) return;
    const desc = $('fbDescInput')?.value || '';
    const quota = this.feedbackState.quota || { exhausted: false };
    const allowed = isFeedbackSubmitAllowed({
      submitting: this.submitting,
      quotaExhausted: Boolean(quota.exhausted),
      sentryConfigured: Boolean(this.desktop.enabled && this.feedbackState.sentryConfigured),
      lastUploadSucceeded: this.lastUploadSucceeded,
      currentDescription: desc,
      lastSubmittedDescription: this.lastSubmittedDescription,
      currentKind: this.modalKind,
      lastSubmittedKind: this.lastSubmittedKind,
    });
    const featureEmpty = this.modalKind === 'feature' && !desc.trim();
    btn.disabled = !allowed || featureEmpty;
    btn.textContent = this.submitting ? t('diag.submittingBtn') : this.modalKind === 'feature' ? t('diag.submitFeatureBtn') : t('diag.submitBugBtn');
  }

  async submitFeedback() {
    if (this.submitting) return;
    const reporterName = ($('fbReporterInput')?.value || '').trim();
    const description = ($('fbDescInput')?.value || '').trim();
    const includeLogs = Boolean($('fbIncludeLogsCheck')?.checked);
    this.saveReporterName(reporterName);

    if (this.modalKind === 'feature' && !description) {
      this.setModalError(t('diag.errFeatureEmpty'));
      return;
    }
    this.setModalError('');
    this.submitting = true;
    this.syncModalSubmitButton();

    try {
      const outcome = await this.desktop.submitFeedbackReport({
        reporterName,
        problemDescription: description,
        kind: this.modalKind,
        includeLogs,
        contextSummary: this.getContextSummary(),
      });
      if (outcome.quota) {
        this.feedbackState.quota = outcome.quota;
      }
      if (outcome.success) {
        this.lastUploadSucceeded = true;
        this.lastSubmittedDescription = description;
        this.lastSubmittedKind = this.modalKind;
        this.lastSuccessShortCode = outcome.shortCode || '';
        if ($('fbSuccessBox')) {
          $('fbSuccessBox').hidden = false;
          $('fbSuccessTitle').textContent =
            this.modalKind === 'feature'
              ? t('diag.successTitleFeature', { code: outcome.shortCode })
              : t('diag.successTitleBug', { code: outcome.shortCode });
          $('fbSuccessDetail').textContent = t('diag.successDetail', { code: outcome.shortCode, group: QQ_GROUP_NUMBER });
        }
        await Promise.all([this.refreshFeedbackState(), this.refreshLogs(false)]);
        this.showToast(t('diag.submittedToast', { code: outcome.shortCode }));
      } else {
        this.setModalError(outcome.errorMessage || t('diag.errSubmitFailed'));
      }
    } catch (err) {
      this.setModalError(String(err?.message || err || t('diag.errSubmitException')));
    } finally {
      this.submitting = false;
      this.syncModalConfigAndQuota();
    }
  }

  setModalError(msg) {
    const box = $('fbModalErrorBox');
    if (!box) return;
    box.hidden = !msg;
    box.textContent = msg || '';
  }
}
