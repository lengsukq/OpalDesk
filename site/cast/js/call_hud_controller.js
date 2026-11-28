/**
 * Floating Call Control HUD Window Controller (`call-hud.html`).
 * Runs inside the dedicated non-focus-stealing Tauri `call-hud` window and
 * communicates with the main OpalDesk window via Tauri IPC + events.
 */

import { formatCallDuration } from './companion_core.js';
import { resolveTauriInvoke } from './desktop_bridge.js';
import {
  applyDocumentI18n,
  getSmsRejectTemplates,
  onLocaleChange,
  setLanguagePref,
  STORAGE_LANGUAGE_PREF,
  t,
} from './i18n.js';

const STORAGE_THEME = 'opaldesk-showcase-theme-v1';

const $ = (id) => document.getElementById(id);

function syncThemeFromStorage() {
  try {
    const saved = window.localStorage.getItem(STORAGE_THEME);
    const theme = saved === 'pure-frost' || saved === 'prism-pulse'
      ? saved
      : (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'prism-pulse' : 'pure-frost');
    document.documentElement.setAttribute('data-theme', theme);
  } catch {
    // Ignore storage access errors
  }
}

let currentCall = null;
let durationTimer = null;
let smsBarOpen = false;

const invoke = resolveTauriInvoke();

function dispatchAction(action) {
  if (!invoke) return;
  void invoke('dispatch_call_hud_action', { action }).catch(() => {});
}

function renderCallState(state) {
  currentCall = state;
  clearInterval(durationTimer);
  durationTimer = null;

  if (!state || state.state === 'idle') {
    smsBarOpen = false;
    if ($('hudSmsBar')) $('hudSmsBar').hidden = true;
    return;
  }

  const isRinging = state.state === 'ringing';
  const isDialing = state.state === 'dialing';
  const isOffhook = state.state === 'offhook';

  const badge = $('hudStateBadge');
  if (badge) {
    badge.textContent = isRinging
      ? t('call.ringing')
      : isDialing
        ? t('call.dialing')
        : t('call.offhook');
    badge.className = `hud-pill ${state.state}`;
  }
  const callerLabel = state.displayCaller || state.contactName || state.number || t('call.unknownCaller');
  if ($('hudCallerText')) {
    $('hudCallerText').textContent = callerLabel;
  }
  const avatar = $('hudAvatar');
  if (avatar) {
    avatar.className = `hud-avatar ${state.state}`;
  }
  const dot = $('hudLiveDot');
  if (dot) {
    dot.className = `hud-live-dot ${state.state}`;
  }
  const updateTimer = () => {
    if (!$('hudTimerText')) return;
    if (isOffhook) {
      $('hudTimerText').textContent = formatCallDuration(state.startedAtMs);
    } else if (state.contactName && state.number && state.contactName !== state.number) {
      $('hudTimerText').textContent = state.number;
    } else {
      $('hudTimerText').textContent = '';
    }
  };
  updateTimer();
  if (isOffhook) {
    durationTimer = setInterval(updateTimer, 1000);
  }

  if ($('hudRingingActions')) $('hudRingingActions').hidden = !isRinging;
  if ($('hudActiveActions')) $('hudActiveActions').hidden = isRinging;
  if ($('hudSmsBar')) $('hudSmsBar').hidden = !(isRinging && smsBarOpen);

  const muteBtn = $('hudMuteBtn');
  if (muteBtn) {
    const label = state.isMuted ? t('call.mutedBtn') : t('call.muteBtn');
    muteBtn.title = label;
    muteBtn.setAttribute('aria-label', label);
    muteBtn.classList.toggle('active-amber', Boolean(state.isMuted));
  }
  const spkBtn = $('hudSpeakerBtn');
  if (spkBtn) {
    const label = state.isSpeakerOn ? t('call.speakerOnBtn') : t('call.speakerBtn');
    spkBtn.title = label;
    spkBtn.setAttribute('aria-label', label);
    spkBtn.classList.toggle('active', Boolean(state.isSpeakerOn));
  }
}

function initSmsTemplates() {
  const bar = $('hudSmsBar');
  if (!bar) return;
  bar.textContent = '';
  const templates = getSmsRejectTemplates();
  for (const tpl of templates) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'hud-sms-chip';
    chip.textContent = tpl.slice(0, 18) + (tpl.length > 18 ? '…' : '');
    chip.title = tpl;
    chip.addEventListener('click', () => {
      smsBarOpen = false;
      bar.hidden = true;
      dispatchAction({ op: 'reject_sms', smsText: tpl });
    });
    bar.appendChild(chip);
  }
}

function bindEvents() {
  $('hudAnswerBtn')?.addEventListener('click', () => dispatchAction({ op: 'answer' }));
  $('hudRejectBtn')?.addEventListener('click', () => dispatchAction({ op: 'reject' }));
  $('hudSmsToggleBtn')?.addEventListener('click', () => {
    smsBarOpen = !smsBarOpen;
    if ($('hudSmsBar')) $('hudSmsBar').hidden = !smsBarOpen;
  });
  $('hudMuteBtn')?.addEventListener('click', () => dispatchAction({ op: 'toggle_mute' }));
  $('hudSpeakerBtn')?.addEventListener('click', () => dispatchAction({ op: 'toggle_speaker' }));
  $('hudHangupBtn')?.addEventListener('click', () => dispatchAction({ op: 'hangup' }));
  $('hudDismissBtn')?.addEventListener('click', () => dispatchAction({ op: 'dismiss_hud' }));
}

function refreshLocalizedUi() {
  applyDocumentI18n();
  initSmsTemplates();
  if (currentCall) renderCallState(currentCall);
}

async function boot() {
  onLocaleChange(() => refreshLocalizedUi());
  syncThemeFromStorage();
  window.addEventListener('storage', (ev) => {
    if (ev.key === STORAGE_LANGUAGE_PREF) {
      setLanguagePref(ev.newValue || 'system', { persist: false });
    } else if (ev.key === STORAGE_THEME) {
      syncThemeFromStorage();
    }
  });
  if (invoke) {
    try {
      const prefs = await invoke('get_client_prefs');
      if (prefs?.language) {
        setLanguagePref(prefs.language, { persist: true, notify: false });
      }
    } catch {
      // Ignore if prefs unavailable
    }
  }
  refreshLocalizedUi();
  bindEvents();

  const evApi = window.__TAURI__?.event;
  if (evApi && typeof evApi.listen === 'function') {
    await evApi.listen('opaldesk:call-state', (ev) => {
      if (ev?.payload) renderCallState(ev.payload);
    });
    await evApi.listen('opaldesk:language-changed', (ev) => {
      if (ev?.payload?.language) {
        setLanguagePref(ev.payload.language, { persist: true });
      }
    });
  }
  if (invoke) {
    try {
      const initial = await invoke('get_call_hud_state');
      if (initial) renderCallState(initial);
    } catch {
      // Ignore if no active call yet
    }
  }
}

void boot();
