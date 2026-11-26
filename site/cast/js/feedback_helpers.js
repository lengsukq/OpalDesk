/**
 * Diagnostics log filtering, feedback badge resolution, and submit guard helpers.
 * Aligned with Android DiagnosticsLogTab / FeedbackTrackingSection & macOS DiagnosticsLogView.
 */

export const QQ_GROUP_NUMBER = '1042317917';
export const MAX_DAILY_REPORTS = 5;
export const MAX_WEB_LOCAL_LOGS = 600;

export function normalizeFeedbackKind(raw) {
  return String(raw || '').trim().toLowerCase() === 'feature' ? 'feature' : 'bug';
}

export function normalizeShortCode(raw) {
  const cleaned = String(raw || '')
    .trim()
    .replace(/^#/, '')
    .toUpperCase();
  return /^[0-9A-F]{8}$/.test(cleaned) ? cleaned : '';
}

export function extractSemver(raw) {
  let candidate = String(raw || '').trim();
  if (!candidate) return '';
  const atIdx = candidate.lastIndexOf('@');
  if (atIdx >= 0 && atIdx + 1 < candidate.length) {
    candidate = candidate.slice(atIdx + 1);
  }
  const plusIdx = candidate.indexOf('+');
  if (plusIdx >= 0) {
    candidate = candidate.slice(0, plusIdx);
  }
  candidate = candidate.replace(/^[vV]/, '').trim();
  return /^\d+\.\d+(?:\.\d+)?(?:-[0-9A-Za-z.-]+)?$/.test(candidate) ? candidate : '';
}

function parseSemverParts(version) {
  const clean = extractSemver(version) || String(version || '').replace(/^[vV]/, '').trim();
  const [core, pre = ''] = clean.split('-', 2);
  const nums = core.split('.').map((p) => parseInt(p, 10) || 0);
  return { nums, pre };
}

export function compareSemver(lhs, rhs) {
  const l = parseSemverParts(lhs);
  const r = parseSemverParts(rhs);
  const len = Math.max(l.nums.length, r.nums.length);
  for (let i = 0; i < len; i++) {
    const a = l.nums[i] || 0;
    const b = r.nums[i] || 0;
    if (a !== b) return a < b ? -1 : 1;
  }
  if (Boolean(l.pre) !== Boolean(r.pre)) {
    return l.pre ? -1 : 1;
  }
  if (l.pre === r.pre) return 0;
  return l.pre < r.pre ? -1 : 1;
}

export function resolveFeedbackBadge(record, currentVersion = '') {
  const kind = normalizeFeedbackKind(record?.kind);
  const status = String(record?.status || 'open').trim().toLowerCase();
  const fixedVer = extractSemver(record?.fixedVersion);
  const curVer = extractSemver(currentVersion);
  const isNewer = Boolean(fixedVer && curVer && compareSemver(fixedVer, curVer) > 0);

  if (kind === 'feature') {
    switch (status) {
      case 'fixed_pending_release':
        return { label: '已规划 · 待下版', tone: 'warn', hint: '已纳入迭代计划，敬请期待下个版本' };
      case 'fixed_in_version':
        return isNewer
          ? { label: `已实现 v${fixedVer}`, tone: 'ok', hint: `当前版本 v${curVer}，更新至 v${fixedVer} 即可体验` }
          : { label: fixedVer ? `已实现 v${fixedVer}` : '已实现', tone: 'ok', hint: '' };
      case 'resolved':
        return { label: '已采纳', tone: 'ok', hint: '' };
      case 'ignored':
        return { label: '暂不考虑', tone: 'muted', hint: '' };
      default:
        return { label: '评估中', tone: 'info', hint: '' };
    }
  }

  switch (status) {
    case 'fixed_pending_release':
      return { label: '已修复 · 待下版发布', tone: 'warn', hint: '修复已合并，将在下个版本发布' };
    case 'fixed_in_version':
      return isNewer
        ? { label: `已在 v${fixedVer} 修复`, tone: 'ok', hint: `当前版本 v${curVer}，建议更新至 v${fixedVer}` }
        : { label: fixedVer ? `已修复 v${fixedVer}` : '已修复', tone: 'ok', hint: '' };
    case 'resolved':
      return { label: '已处理', tone: 'ok', hint: '' };
    case 'ignored':
      return { label: '已归档', tone: 'muted', hint: '' };
    default:
      return { label: '排查中', tone: 'info', hint: '' };
  }
}

export function filterLogEntries(entries, { warningsOnly = false, query = '', newestFirst = true } = {}) {
  const q = String(query || '').trim().toLowerCase();
  const filtered = (Array.isArray(entries) ? entries : []).filter((entry) => {
    if (!entry) return false;
    const lvl = String(entry.level || 'I').toUpperCase();
    if (warningsOnly && lvl !== 'W' && lvl !== 'E') {
      return false;
    }
    if (q) {
      const hay = `${entry.tag || ''} ${entry.message || ''} ${lvl}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });

  if (newestFirst) {
    return [...filtered].reverse();
  }
  return filtered;
}

export function formatLogTime(timestampMs) {
  const n = Number(timestampMs) || 0;
  if (n <= 0) return '--:--:--';
  const d = new Date(n);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  const ms = String(d.getMilliseconds()).padStart(3, '0');
  return `${hh}:${mm}:${ss}.${ms}`;
}

export function formatFeedbackTime(timestampMs) {
  const n = Number(timestampMs) || 0;
  if (n <= 0) return '';
  const d = new Date(n);
  const mo = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${mo}-${day} ${hh}:${mm}`;
}

export function isFeedbackSubmitAllowed({
  submitting = false,
  quotaExhausted = false,
  sentryConfigured = false,
  lastUploadSucceeded = false,
  currentDescription = '',
  lastSubmittedDescription = null,
  currentKind = 'bug',
  lastSubmittedKind = null,
} = {}) {
  if (submitting || quotaExhausted || !sentryConfigured) return false;
  if (
    lastUploadSucceeded &&
    lastSubmittedDescription !== null &&
    String(currentDescription).trim() === String(lastSubmittedDescription).trim() &&
    (lastSubmittedKind === null || normalizeFeedbackKind(currentKind) === normalizeFeedbackKind(lastSubmittedKind))
  ) {
    return false;
  }
  return true;
}

export function buildWebDiagnosticReport(entries, meta = {}) {
  const lines = [
    '========== OpalDesk Web Client Diagnostic Report ==========',
    `GeneratedAt: ${new Date().toISOString()}`,
    `UserAgent: ${meta.userAgent || (typeof navigator !== 'undefined' ? navigator.userAgent : 'unknown')}`,
    `LogsCaptured: ${Array.isArray(entries) ? entries.length : 0}`,
    '===========================================================',
    '',
  ];
  for (const e of entries || []) {
    lines.push(`[${formatLogTime(e.timestampMs)}] [${e.level || 'I'}] [${e.tag || 'Web'}] ${e.message || ''}`);
  }
  return lines.join('\n');
}
