/**
 * DOM view renderers for Companion Media Remote, Web Call HUD, Call Banner & T9 Dialer Keypad.
 */

import { T9_DIALPAD_KEYS, formatCallDuration, sanitizeDialNumber } from './companion_core.js';
import { getSmsRejectTemplates, t } from './i18n.js';

const $ = (id) => document.getElementById(id);

function createEl(tag, className = '', text = '') {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text) el.textContent = text;
  return el;
}

export function renderWebCallHud(callState, show) {
  const hud = $('webCallHud');
  if (!hud) return;
  hud.hidden = !show;
  if (!show || !callState) return;
  const st = callState;
  if ($('webCallHudCaller')) $('webCallHudCaller').textContent = st.displayCaller;
  if ($('webCallHudSub')) {
    $('webCallHudSub').textContent =
      st.state === 'ringing' ? `${t('call.ringing')} · ${st.number || ''}`
        : st.state === 'dialing' ? `${t('call.dialing')} · ${st.number || ''}`
          : `${t('call.offhook')} · ${formatCallDuration(st.startedAtMs)}`;
  }
  const isRinging = st.state === 'ringing';
  if ($('webCallHudAnswerBtn')) $('webCallHudAnswerBtn').hidden = !isRinging;
  if ($('webCallHudRejectBtn')) $('webCallHudRejectBtn').hidden = !isRinging;
  if ($('webCallHudMuteBtn')) {
    $('webCallHudMuteBtn').hidden = isRinging;
    $('webCallHudMuteBtn').classList.toggle('active', st.isMuted);
    $('webCallHudMuteBtn').textContent = st.isMuted ? t('call.mutedBtn') : t('call.muteBtn');
  }
  if ($('webCallHudSpeakerBtn')) {
    $('webCallHudSpeakerBtn').hidden = isRinging;
    $('webCallHudSpeakerBtn').classList.toggle('active', st.isSpeakerOn);
    $('webCallHudSpeakerBtn').textContent = st.isSpeakerOn ? t('call.speakerOnBtn') : t('call.speakerBtn');
  }
  if ($('webCallHudHangupBtn')) $('webCallHudHangupBtn').hidden = isRinging;
}

export function refreshCallDurationTick(callState) {
  if (!callState || callState.state !== 'offhook') return;
  const dur = formatCallDuration(callState.startedAtMs);
  if ($('webCallHudSub') && !$('webCallHud')?.hidden) {
    $('webCallHudSub').textContent = `${t('call.offhook')} · ${dur}`;
  }
  const bannerDur = $('companionCallTimer');
  if (bannerDur) bannerDur.textContent = dur;
}

export function renderT9Keypad() {
  const grid = $('dialerKeypadGrid');
  if (!grid) return;
  grid.textContent = '';
  for (const key of T9_DIALPAD_KEYS) {
    const btn = createEl('button', 'dialer-key-btn');
    btn.type = 'button';
    btn.append(createEl('span', 'dialer-key-digit', key.digit));
    if (key.letters) btn.append(createEl('span', 'dialer-key-letters', key.letters));
    btn.addEventListener('click', () => {
      const inp = $('dialerNumberInput');
      if (!inp) return;
      inp.value = sanitizeDialNumber(inp.value + key.digit);
      inp.focus();
    });
    grid.appendChild(btn);
  }
}

export function renderCompanionCallBanner(container, call, onCallAction) {
  container.textContent = '';
  const left = createEl('div', 'companion-call-info');
  const badge = createEl(
    'span',
    `status-pill ${call.state === 'ringing' ? 'warn' : 'ok'}`,
    call.state === 'ringing' ? t('call.ringing') : call.state === 'dialing' ? t('call.dialing') : t('call.offhook')
  );
  const caller = createEl('strong', '', call.displayCaller);
  const timer = createEl('span', 'mono muted', call.state === 'offhook' ? formatCallDuration(call.startedAtMs) : call.number);
  timer.id = 'companionCallTimer';
  left.append(badge, caller, timer);

  const btns = createEl('div', 'companion-call-btns');
  if (call.state === 'ringing') {
    const ans = createEl('button', 'btn-pill small emerald', t('call.answerBtn'));
    ans.type = 'button';
    ans.addEventListener('click', () => onCallAction({ op: 'answer' }));
    const rej = createEl('button', 'btn-pill small danger', t('call.rejectBtn'));
    rej.type = 'button';
    rej.addEventListener('click', () => onCallAction({ op: 'reject' }));
    const sms = createEl('button', 'btn-pill small', t('call.smsRejectBtn'));
    sms.type = 'button';
    sms.addEventListener('click', () => onCallAction({ op: 'reject_sms', smsText: getSmsRejectTemplates()[0] }));
    btns.append(ans, rej, sms);
  } else {
    const mute = createEl('button', `btn-pill small${call.isMuted ? ' primary' : ''}`, call.isMuted ? t('call.mutedBtn') : t('call.muteBtn'));
    mute.type = 'button';
    mute.addEventListener('click', () => onCallAction({ op: 'toggle_mute' }));
    const spk = createEl('button', `btn-pill small${call.isSpeakerOn ? ' primary' : ''}`, call.isSpeakerOn ? t('call.speakerOnBtn') : t('call.speakerBtn'));
    spk.type = 'button';
    spk.addEventListener('click', () => onCallAction({ op: 'toggle_speaker' }));
    const hang = createEl('button', 'btn-pill small danger', t('call.hangupBtn'));
    hang.type = 'button';
    hang.addEventListener('click', () => onCallAction({ op: 'hangup' }));
    btns.append(mute, spk, hang);
  }
  container.append(left, btns);
}

export function renderCompanionMediaBox(mediaState) {
  const mediaBox = $('companionMediaBox');
  if (!mediaBox) return;
  const m = mediaState;
  mediaBox.hidden = !(m && m.active);
  if (!(m && m.active)) return;
  if ($('companionMediaTitle')) $('companionMediaTitle').textContent = m.title || t('companion.mediaNowPlaying');
  if ($('companionMediaArtist')) {
    $('companionMediaArtist').textContent = [m.artist, m.appLabel].filter(Boolean).join(' · ');
  }
  if ($('companionMediaPlayBtn')) {
    $('companionMediaPlayBtn').textContent = m.playing ? t('companion.mediaPause') : t('companion.mediaPlay');
  }
  const art = $('companionMediaArt');
  if (art) {
    if (m.artworkBase64) {
      art.src = m.artworkBase64.startsWith('data:') ? m.artworkBase64 : `data:image/jpeg;base64,${m.artworkBase64}`;
      art.hidden = false;
    } else {
      art.hidden = true;
    }
  }
}
