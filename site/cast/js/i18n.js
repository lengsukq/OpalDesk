/**
 * Zero-dependency ES Module i18n engine for OpalDesk Web Cast & Windows Tauri clients.
 * Follows `docs/I18N_SPEC.md`:
 *  - Supported locales: `en` (canonical default/fallback) and `zh-CN` (Simplified Chinese).
 *  - Default preference: `system` (any `zh*` system tag -> `zh-CN`, all others -> `en`).
 *  - Persisted manual override in `localStorage` (`opaldesk-cast-lang-v1`).
 */

import enDict from './locales/en.js';
import zhCnDict from './locales/zh-CN.js';

export const LANG_SYSTEM = 'system';
export const LANG_EN = 'en';
export const LANG_ZH_CN = 'zh-CN';
export const STORAGE_LANGUAGE_PREF = 'opaldesk-cast-lang-v1';

export const LOCALE_DICTS = Object.freeze({
  [LANG_EN]: enDict,
  [LANG_ZH_CN]: zhCnDict,
});

const SMS_REJECT_TEMPLATES = Object.freeze({
  [LANG_EN]: Object.freeze([
    "I'm in a meeting right now. I'll call you back shortly.",
    "Can't talk right now. I'll get back to you soon.",
    'Please send me a message instead. Thanks!',
  ]),
  [LANG_ZH_CN]: Object.freeze([
    '我现在正在开会，稍后给您回电。',
    '正在忙，暂时不方便接听，稍后联系您。',
    '请发消息联系，谢谢。',
  ]),
});

const listeners = new Set();
let cachedPref = null;
let cachedLocale = null;

function readSystemLanguages() {
  const nav = globalThis.navigator;
  if (!nav) return [];
  if (Array.isArray(nav.languages) && nav.languages.length > 0) {
    return nav.languages.filter((item) => typeof item === 'string' && item.trim().length > 0);
  }
  if (typeof nav.language === 'string' && nav.language.trim().length > 0) {
    return [nav.language];
  }
  return [];
}

function readStoredPref() {
  try {
    return globalThis.localStorage?.getItem(STORAGE_LANGUAGE_PREF) || '';
  } catch {
    return '';
  }
}

function writeStoredPref(pref) {
  try {
    globalThis.localStorage?.setItem(STORAGE_LANGUAGE_PREF, pref);
  } catch {
    // Ignore storage quota or private-mode restrictions
  }
}

/**
 * Normalizes a user or persisted language preference into `'system' | 'zh-CN' | 'en'`.
 */
export function normalizeLanguagePref(raw) {
  const cleaned = String(raw ?? '').trim();
  if (!cleaned) return LANG_SYSTEM;
  const lower = cleaned.toLowerCase().replace(/_/g, '-');
  if (lower === LANG_SYSTEM) return LANG_SYSTEM;
  if (lower === 'zh' || lower === 'zh-cn' || lower === 'zh-hans' || lower.startsWith('zh-hans-')) {
    return LANG_ZH_CN;
  }
  if (lower === 'en' || lower.startsWith('en-')) {
    return LANG_EN;
  }
  return LANG_SYSTEM;
}

/**
 * Resolves the effective locale (`'zh-CN'` or `'en'`) from a preference and system language tags.
 */
export function resolveLocale(pref = LANG_SYSTEM, systemLanguages = readSystemLanguages()) {
  const normalized = normalizeLanguagePref(pref);
  if (normalized === LANG_ZH_CN) return LANG_ZH_CN;
  if (normalized === LANG_EN) return LANG_EN;

  const tags = Array.isArray(systemLanguages)
    ? systemLanguages
    : typeof systemLanguages === 'string' && systemLanguages.trim()
      ? [systemLanguages]
      : [];

  const hasChinese = tags.some((tag) => {
    const normTag = String(tag ?? '').trim().toLowerCase().replace(/_/g, '-');
    return normTag === 'zh' || normTag.startsWith('zh-');
  });
  return hasChinese ? LANG_ZH_CN : LANG_EN;
}

/**
 * Interpolates `{key}` placeholders in a message template.
 */
export function formatMessage(template, params = null) {
  const raw = String(template ?? '');
  if (!params || typeof params !== 'object') return raw;
  return raw.replace(/\{([a-zA-Z0-9_]+)\}/g, (match, key) => {
    if (Object.prototype.hasOwnProperty.call(params, key) && params[key] !== undefined && params[key] !== null) {
      return String(params[key]);
    }
    return match;
  });
}

export function getLanguagePref() {
  if (cachedPref === null) {
    cachedPref = normalizeLanguagePref(readStoredPref());
  }
  return cachedPref;
}

export function getActiveLocale() {
  if (cachedLocale === null) {
    cachedLocale = resolveLocale(getLanguagePref());
  }
  return cachedLocale;
}

export function setLanguagePref(pref, { persist = true, notify = true, systemLanguages } = {}) {
  const nextPref = normalizeLanguagePref(pref);
  const nextLocale = resolveLocale(nextPref, systemLanguages);
  const changed = nextPref !== cachedPref || nextLocale !== cachedLocale;
  cachedPref = nextPref;
  cachedLocale = nextLocale;
  if (persist) {
    writeStoredPref(nextPref);
  }
  if (notify && changed) {
    for (const cb of listeners) {
      try {
        cb(nextLocale, nextPref);
      } catch {
        // Isolate subscriber errors
      }
    }
  }
  return nextLocale;
}

export function onLocaleChange(cb) {
  if (typeof cb !== 'function') return () => {};
  listeners.add(cb);
  return () => listeners.delete(cb);
}

/**
 * Translates a dictionary key for the active locale (or an optional explicit locale),
 * falling back to English (`en`) and then the raw key itself.
 */
export function t(key, params = null, localeOverride = null) {
  const locale = localeOverride ? resolveLocale(localeOverride, []) : getActiveLocale();
  const activeDict = LOCALE_DICTS[locale] || enDict;
  const template = Object.prototype.hasOwnProperty.call(activeDict, key)
    ? activeDict[key]
    : Object.prototype.hasOwnProperty.call(enDict, key)
      ? enDict[key]
      : key;
  return formatMessage(template, params);
}

/**
 * Applies `data-i18n`, `data-i18n-placeholder`, `data-i18n-title`, `data-i18n-aria`,
 * and `data-i18n-content` attributes across `root` (defaults to `document`).
 */
export function applyDocumentI18n(root = globalThis.document) {
  if (!root || typeof root.querySelectorAll !== 'function') return;
  const locale = getActiveLocale();
  if (root.documentElement) {
    root.documentElement.lang = locale;
  }

  for (const el of root.querySelectorAll('[data-i18n]')) {
    const key = el.getAttribute('data-i18n');
    if (key) el.textContent = t(key);
  }
  for (const el of root.querySelectorAll('[data-i18n-placeholder]')) {
    const key = el.getAttribute('data-i18n-placeholder');
    if (key) el.setAttribute('placeholder', t(key));
  }
  for (const el of root.querySelectorAll('[data-i18n-title]')) {
    const key = el.getAttribute('data-i18n-title');
    if (key) el.setAttribute('title', t(key));
  }
  for (const el of root.querySelectorAll('[data-i18n-aria]')) {
    const key = el.getAttribute('data-i18n-aria');
    if (key) el.setAttribute('aria-label', t(key));
  }
  for (const el of root.querySelectorAll('[data-i18n-content]')) {
    const key = el.getAttribute('data-i18n-content');
    if (key) el.setAttribute('content', t(key));
  }
}

export function getSmsRejectTemplates(locale = getActiveLocale()) {
  const resolved = resolveLocale(locale, []);
  return SMS_REJECT_TEMPLATES[resolved] || SMS_REJECT_TEMPLATES[LANG_EN];
}
