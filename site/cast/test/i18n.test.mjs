import test from 'node:test';
import assert from 'node:assert/strict';

import enDict from '../js/locales/en.js';
import zhCnDict from '../js/locales/zh-CN.js';
import {
  LANG_EN,
  LANG_SYSTEM,
  LANG_ZH_CN,
  normalizeLanguagePref,
  resolveLocale,
  formatMessage,
  t,
  getLanguagePref,
  setLanguagePref,
  getActiveLocale,
  onLocaleChange,
  getSmsRejectTemplates,
} from '../js/i18n.js';

test('normalizeLanguagePref sanitizes explicit and invalid inputs deterministically', () => {
  assert.equal(normalizeLanguagePref(''), LANG_SYSTEM);
  assert.equal(normalizeLanguagePref(null), LANG_SYSTEM);
  assert.equal(normalizeLanguagePref('system'), LANG_SYSTEM);
  assert.equal(normalizeLanguagePref('fr-FR'), LANG_SYSTEM);

  assert.equal(normalizeLanguagePref('zh'), LANG_ZH_CN);
  assert.equal(normalizeLanguagePref('zh-CN'), LANG_ZH_CN);
  assert.equal(normalizeLanguagePref('zh_cn'), LANG_ZH_CN);
  assert.equal(normalizeLanguagePref('zh-Hans'), LANG_ZH_CN);
  assert.equal(normalizeLanguagePref('zh-Hans-CN'), LANG_ZH_CN);

  assert.equal(normalizeLanguagePref('en'), LANG_EN);
  assert.equal(normalizeLanguagePref('EN-US'), LANG_EN);
  assert.equal(normalizeLanguagePref('en_GB'), LANG_EN);
});

test('resolveLocale follows I18N_SPEC.md policy for system and manual overrides', () => {
  // Explicit overrides ignore system languages
  assert.equal(resolveLocale('zh-CN', ['en-US']), LANG_ZH_CN);
  assert.equal(resolveLocale('en', ['zh-CN']), LANG_EN);

  // System mode: any zh* tag resolves to zh-CN
  assert.equal(resolveLocale('system', ['zh-CN']), LANG_ZH_CN);
  assert.equal(resolveLocale('system', ['zh-Hans']), LANG_ZH_CN);
  assert.equal(resolveLocale('system', ['zh-SG']), LANG_ZH_CN);
  assert.equal(resolveLocale('system', ['zh-TW']), LANG_ZH_CN);
  assert.equal(resolveLocale('system', ['zh-HK']), LANG_ZH_CN);
  assert.equal(resolveLocale('system', ['zh_CN']), LANG_ZH_CN);
  assert.equal(resolveLocale('system', ['en-US', 'zh-CN']), LANG_ZH_CN);

  // System mode: non-Chinese or empty resolves to en
  assert.equal(resolveLocale('system', ['en-US']), LANG_EN);
  assert.equal(resolveLocale('system', ['ja-JP']), LANG_EN);
  assert.equal(resolveLocale('system', ['de-DE', 'fr-FR']), LANG_EN);
  assert.equal(resolveLocale('system', []), LANG_EN);
});

test('formatMessage interpolates named placeholders and preserves missing keys', () => {
  assert.equal(formatMessage('Connected to {name} ({count})', { name: 'Pixel', count: 3 }), 'Connected to Pixel (3)');
  assert.equal(formatMessage('Missing {unknown} stays intact', { name: 'A' }), 'Missing {unknown} stays intact');
  assert.equal(formatMessage('Plain text', null), 'Plain text');
});

test('t translates keys, falls back to key if unknown, and notifies listeners on locale change', () => {
  const events = [];
  const unsub = onLocaleChange((locale, pref) => events.push({ locale, pref }));

  setLanguagePref('en', { persist: false });
  assert.equal(getLanguagePref(), LANG_EN);
  assert.equal(getActiveLocale(), LANG_EN);
  assert.equal(t('tabs.cast'), 'Wireless / USB Cast');
  assert.equal(t('nonexistent.key.xyz'), 'nonexistent.key.xyz');

  setLanguagePref('zh-CN', { persist: false });
  assert.equal(getLanguagePref(), LANG_ZH_CN);
  assert.equal(getActiveLocale(), LANG_ZH_CN);
  assert.equal(t('tabs.cast'), '无线 / USB 投屏');
  assert.equal(t('cast.connectedToast', { name: 'Xiaomi 15' }), '已连接到 Xiaomi 15');

  assert.equal(events.length >= 1, true);
  assert.deepEqual(events[events.length - 1], { locale: LANG_ZH_CN, pref: LANG_ZH_CN });
  unsub();

  // Reset to system default
  setLanguagePref('system', { persist: false, systemLanguages: ['en-US'] });
  assert.equal(getActiveLocale(), LANG_EN);
});

test('getSmsRejectTemplates returns localized non-empty templates', () => {
  const enTemplates = getSmsRejectTemplates('en');
  const zhTemplates = getSmsRejectTemplates('zh-CN');
  assert.equal(enTemplates.length, 3);
  assert.equal(zhTemplates.length, 3);
  assert.notEqual(enTemplates[0], zhTemplates[0]);
});

test('en.js and zh-CN.js have 100% key parity and non-empty string values', () => {
  const enKeys = Object.keys(enDict).sort();
  const zhKeys = Object.keys(zhCnDict).sort();

  const missingInZh = enKeys.filter((k) => !Object.prototype.hasOwnProperty.call(zhCnDict, k));
  const missingInEn = zhKeys.filter((k) => !Object.prototype.hasOwnProperty.call(enDict, k));

  assert.deepEqual(missingInZh, [], `Keys missing in zh-CN.js: ${missingInZh.join(', ')}`);
  assert.deepEqual(missingInEn, [], `Keys missing in en.js: ${missingInEn.join(', ')}`);
  assert.equal(enKeys.length, zhKeys.length);
  assert.ok(enKeys.length > 200, 'Expected comprehensive locale dictionary');

  for (const key of enKeys) {
    assert.equal(typeof enDict[key], 'string', `en.js[${key}] must be string`);
    assert.equal(typeof zhCnDict[key], 'string', `zh-CN.js[${key}] must be string`);
    assert.ok(enDict[key].trim().length > 0, `en.js[${key}] must be non-empty`);
    assert.ok(zhCnDict[key].trim().length > 0, `zh-CN.js[${key}] must be non-empty`);
  }
});
