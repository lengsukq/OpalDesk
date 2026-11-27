/**
 * Floating Call Control HUD Window Controller (`call-hud.html`).
 * Runs inside the dedicated non-focus-stealing Tauri `call-hud` window and
 * communicates with the main OpalDesk window via Tauri IPC + events.
 */

import { DEFAULT_SMS_REJECT_TEMPLATES, formatCallDuration } from './companion_core.js';
import { resolveTauriInvoke } from './desktop_bridge.js';

const $ = (id) => document.getElementById(id);

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
    badge.textContent = isRinging ? '来电响铃' : isDialing ? '正在拨号' : '通话中';
    badge.className = `hud-pill ${state.state}`;
  }
  if ($('hudCallerText')) {
    $('hudCallerText').textContent = state.displayCaller || state.contactName || state.number || '未知来电';
  }
  const updateTimer = () => {
    if (!$('hudTimerText')) return;
    $('hudTimerText').textContent = isOffhook
      ? formatCallDuration(state.startedAtMs)
      : (state.number || '');
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
    muteBtn.textContent = state.isMuted ? '已静音' : '静音';
    muteBtn.classList.toggle('active', Boolean(state.isMuted));
  }
  const spkBtn = $('hudSpeakerBtn');
  if (spkBtn) {
    spkBtn.textContent = state.isSpeakerOn ? '扬声器开' : '扬声器';
    spkBtn.classList.toggle('active', Boolean(state.isSpeakerOn));
  }
}

function initSmsTemplates() {
  const bar = $('hudSmsBar');
  if (!bar) return;
  bar.textContent = '';
  for (const tpl of DEFAULT_SMS_REJECT_TEMPLATES) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'hud-sms-chip';
    chip.textContent = tpl.slice(0, 12) + (tpl.length > 12 ? '…' : '');
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

async function boot() {
  initSmsTemplates();
  bindEvents();

  const evApi = window.__TAURI__?.event;
  if (evApi && typeof evApi.listen === 'function') {
    await evApi.listen('opaldesk:call-state', (ev) => {
      if (ev?.payload) renderCallState(ev.payload);
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
