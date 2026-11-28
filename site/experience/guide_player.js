/* ==========================================================================
   OpalDesk 3D Stage Guide Player, DOM Controls & 2D Canvas Fallback
   ========================================================================== */

import {
  I18N,
  LANG_KEY,
  POOL_SLOTS,
  STAGE_TAGS,
  THEME_KEY,
  TOPO_PRESETS,
} from './scene_data.js';

function safeGet(k, fb = null) {
  try { const v = window.localStorage.getItem(k); return v !== null ? v : fb; } catch (_) { return fb; }
}
function safeSet(k, v) { try { window.localStorage.setItem(k, v); } catch (_) {} }

export function createGuidePlayer() {
  const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const savedTheme = safeGet(THEME_KEY);
  const initialTheme = (savedTheme === 'pure-frost' || savedTheme === 'prism-pulse')
    ? savedTheme
    : (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'prism-pulse' : 'pure-frost');

  const state = {
    theme: initialTheme,
    lang: safeGet(LANG_KEY) === 'en' ? 'en' : 'zh',
    topo: 'cast',
    activeScene: 0,
    scrollNorm: 0,
    morph: 0,
    explodeToggle: false,
    explodePulse: 0,
    castPulse: 0,
    autoPlaying: false,
    pointerX: 0,
    pointerY: 0,
    pointerOverUI: false,
    orbitYaw: 0,
    orbitPitch: 0,
    dragging: false
  };

  const container = document.getElementById('canvasContainer');
  const bootVeil = document.getElementById('bootVeil');
  const bootBarFill = document.getElementById('bootBarFill');
  const stageMorphBtn = document.getElementById('stageMorphBtn');
  const stageMorphTag = document.getElementById('stageMorphTag');
  const chapterPopover = document.getElementById('chapterPopover');
  const themeToggleBtn = document.getElementById('themeToggleBtn');
  const themeToggleLabel = document.getElementById('themeToggleLabel');
  const soundToggleBtn = document.getElementById('soundToggleBtn');
  const soundToggleLabel = document.getElementById('soundToggleLabel');
  const filmPlayBtn = document.getElementById('filmPlayBtn');
  const filmPlayLabel = document.getElementById('filmPlayLabel');
  const langToggleBtn = document.getElementById('langToggleBtn');
  const timecodeIndex = document.getElementById('timecodeIndex');
  const timecodeFill = document.getElementById('timecodeFill');
  const railDroplet = document.getElementById('railDroplet');
  const cursorAura = document.getElementById('cursorAura');
  const cursorNodeLabel = document.getElementById('cursorNodeLabel');
  const poolSlotList = document.getElementById('poolSlotList');
  const poolSummaryLine = document.getElementById('poolSummaryLine');
  const topoDetailQuote = document.getElementById('topoDetailQuote');
  const topoDetailMeta = document.getElementById('topoDetailMeta');
  const scenes = Array.from(document.querySelectorAll('.film-scene'));
  const tcDots = Array.from(document.querySelectorAll('.tc-dot'));
  const menuItems = Array.from(document.querySelectorAll('.chapter-menu-item'));

  const callbacks = { onThemeChange: null, onLayoutChange: null };

  function updateThemeLabel() {
    const dict = I18N[state.lang];
    themeToggleLabel.textContent = state.theme === 'pure-frost' ? dict.theme_light : dict.theme_dark;
  }

  function applyTheme(nextTheme, evt) {
    state.theme = nextTheme;
    safeSet(THEME_KEY, nextTheme);
    const commit = () => {
      document.documentElement.setAttribute('data-theme', nextTheme);
      updateThemeLabel();
      if (callbacks.onThemeChange) callbacks.onThemeChange(nextTheme);
    };
    if (!reducedMotion && evt && typeof document.startViewTransition === 'function') {
      const x = evt.clientX || window.innerWidth * 0.8;
      const y = evt.clientY || 32;
      const maxR = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y)) + 90;
      document.documentElement.style.setProperty('--vt-x', `${x}px`);
      document.documentElement.style.setProperty('--vt-y', `${y}px`);
      document.documentElement.style.setProperty('--vt-max-r', `${maxR}px`);
      document.startViewTransition(commit);
    } else {
      commit();
    }
  }

  function updateStageLabels(idx) {
    const tags = STAGE_TAGS[state.lang] || STAGE_TAGS.zh;
    stageMorphTag.textContent = tags[idx] || tags[0];
    timecodeIndex.textContent = `0${idx}`;
    tcDots.forEach((d, i) => {
      d.classList.toggle('active', i === idx);
      if (i === idx) d.setAttribute('aria-current', 'step');
      else d.removeAttribute('aria-current');
    });
    menuItems.forEach((m, i) => m.classList.toggle('active', i === idx));
    if (railDroplet) railDroplet.style.transform = `translateY(${idx * 26}px)`;
  }

  function renderPoolSlots() {
    if (!poolSlotList) return;
    poolSlotList.innerHTML = '';
    let liveCount = 0;
    POOL_SLOTS.forEach((slot, idx) => {
      if (slot.live) liveCount++;
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `pool-chip ${slot.live ? 'live' : 'hibernated'}`;
      btn.innerHTML = `<span class="chip-dot"></span><span>${state.lang === 'en' ? slot.en : slot.zh}</span>`;
      btn.addEventListener('click', () => {
        slot.live = !slot.live;
        state.explodePulse = 1.0;
        if (window.OpalSound) window.OpalSound.playPulse(2, (idx / 5) * 2 - 1);
        renderPoolSlots();
      });
      poolSlotList.appendChild(btn);
    });
    const hibernated = POOL_SLOTS.length - liveCount;
    poolSummaryLine.textContent = state.lang === 'en'
      ? `Active VirtualDisplays: ${liveCount} / 6 · TaskSnapshot Hibernated: ${hibernated} (0 VD cost, 150ms wake)`
      : `当前活跃实时虚拟屏：${liveCount} / 6 上限 · TaskSnapshot 快照休眠：${hibernated} 窗（0 虚拟屏配额 · 150ms 唤醒）`;
  }

  function renderTopoDetails() {
    const preset = TOPO_PRESETS[state.topo] || TOPO_PRESETS.cast;
    topoDetailQuote.textContent = state.lang === 'en' ? preset.enQuote : preset.zhQuote;
    topoDetailMeta.textContent = state.lang === 'en' ? preset.enMeta : preset.zhMeta;
    document.querySelectorAll('.topo-option').forEach((btn) => {
      const active = btn.getAttribute('data-topo') === state.topo;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-checked', active ? 'true' : 'false');
    });
  }

  function updateSoundUI() {
    if (!window.OpalSound || !soundToggleBtn) return;
    const s = window.OpalSound.getStatus();
    soundToggleBtn.setAttribute('aria-pressed', s.enabled ? 'true' : 'false');
    soundToggleBtn.classList.toggle('sound-live', s.enabled);
    soundToggleLabel.textContent = s.enabled ? I18N[state.lang].sound_on : I18N[state.lang].sound_off;
  }

  function applyLang(nextLang) {
    state.lang = nextLang;
    safeSet(LANG_KEY, nextLang);
    document.documentElement.lang = nextLang === 'en' ? 'en' : 'zh-CN';
    langToggleBtn.textContent = nextLang === 'en' ? '中文' : 'EN';
    const dict = I18N[nextLang];
    document.querySelectorAll('[data-i18n]').forEach((el) => {
      const key = el.getAttribute('data-i18n');
      if (dict[key] !== undefined) el.innerHTML = dict[key];
    });
    updateThemeLabel();
    updateStageLabels(state.activeScene);
    renderPoolSlots();
    renderTopoDetails();
    updateSoundUI();
    filmPlayLabel.textContent = state.autoPlaying ? dict.btn_pause : dict.btn_play;
    if (callbacks.onLayoutChange) requestAnimationFrame(callbacks.onLayoutChange);
  }

  function setPopoverOpen(open) {
    chapterPopover.hidden = !open;
    stageMorphBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  function jumpToScene(idx) {
    const safeIdx = Math.max(0, Math.min(scenes.length - 1, idx));
    const target = scenes[safeIdx];
    if (!target) return;
    setPopoverOpen(false);
    const destY = safeIdx === 0 ? 0 : target.offsetTop;
    window.scrollTo({ top: destY, behavior: reducedMotion ? 'auto' : 'smooth' });
    if (window.OpalSound) window.OpalSound.playClick('pill');
  }

  let autoRaf = 0;
  function stopAutoPlay() {
    state.autoPlaying = false;
    filmPlayBtn.classList.remove('playing');
    filmPlayLabel.textContent = I18N[state.lang].btn_play;
    if (autoRaf) cancelAnimationFrame(autoRaf);
  }

  function updateScrollState() {
    const maxScroll = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
    const norm = Math.max(0, Math.min(1, window.scrollY / maxScroll));
    state.scrollNorm = norm;
    state.morph = norm * 5.0;
    if (timecodeFill) timecodeFill.style.height = `${(norm * 100).toFixed(1)}%`;

    const nextIdx = Math.max(0, Math.min(5, Math.round(state.morph)));
    scenes.forEach((sec, i) => sec.classList.toggle('is-active', i === nextIdx));
    if (nextIdx !== state.activeScene) {
      state.activeScene = nextIdx;
      updateStageLabels(nextIdx);
      if (window.OpalSound) window.OpalSound.playSceneShift(nextIdx);
    }
  }

  // Wire UI listeners
  document.querySelectorAll('.topo-option').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.topo = btn.getAttribute('data-topo') || 'cast';
      state.castPulse = 1.25;
      if (window.OpalSound) window.OpalSound.playPulse(3, 0.2);
      renderTopoDetails();
    });
  });
  if (window.OpalSound) {
    window.OpalSound.subscribe(updateSoundUI);
    soundToggleBtn.addEventListener('click', () => window.OpalSound.setEnabled(!window.OpalSound.getStatus().enabled));
  }
  themeToggleBtn.addEventListener('click', (e) => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    applyTheme(state.theme === 'pure-frost' ? 'prism-pulse' : 'pure-frost', e);
  });
  langToggleBtn.addEventListener('click', () => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    applyLang(state.lang === 'zh' ? 'en' : 'zh');
  });
  stageMorphBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (window.OpalSound) window.OpalSound.playClick('pill');
    setPopoverOpen(chapterPopover.hidden);
  });
  document.addEventListener('click', () => setPopoverOpen(false));
  document.querySelectorAll('[data-jump]').forEach((el) => {
    el.addEventListener('click', () => jumpToScene(Number(el.getAttribute('data-jump'))));
  });
  filmPlayBtn.addEventListener('click', () => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    if (state.autoPlaying) { stopAutoPlay(); return; }
    state.autoPlaying = true;
    filmPlayBtn.classList.add('playing');
    filmPlayLabel.textContent = I18N[state.lang].btn_pause;
    const maxS = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
    if (window.scrollY >= maxS - 24) window.scrollTo({ top: 0, behavior: 'auto' });
    const step = () => {
      if (!state.autoPlaying) return;
      const ms = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
      if (window.scrollY >= ms - 4) { stopAutoPlay(); return; }
      window.scrollBy(0, 1.5);
      autoRaf = requestAnimationFrame(step);
    };
    autoRaf = requestAnimationFrame(step);
  });
  ['wheel', 'touchstart'].forEach((ev) => window.addEventListener(ev, () => { if (state.autoPlaying) stopAutoPlay(); }, { passive: true }));

  document.getElementById('heroPulseBtn')?.addEventListener('click', () => {
    state.explodeToggle = !state.explodeToggle;
    state.explodePulse = 1.2;
    if (window.OpalSound) window.OpalSound.playPulse(state.explodeToggle ? 1 : 0, 0);
  });
  document.getElementById('igniteCastBtn')?.addEventListener('click', () => {
    state.castPulse = 1.45;
    if (window.OpalSound) window.OpalSound.playPulse(3, 0);
  });
  document.querySelectorAll('[data-glass-card]').forEach((card) => {
    card.addEventListener('pointermove', (e) => {
      const r = card.getBoundingClientRect();
      card.style.setProperty('--mx', `${((e.clientX - r.left) / r.width) * 100}%`);
      card.style.setProperty('--my', `${((e.clientY - r.top) / r.height) * 100}%`);
    }, { passive: true });
  });

  window.addEventListener('scroll', updateScrollState, { passive: true });
  window.addEventListener('keydown', (e) => {
    if (e.target && ['INPUT', 'TEXTAREA'].includes(e.target.tagName)) return;
    if (e.key === 'ArrowDown') { e.preventDefault(); jumpToScene(state.activeScene + 1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); jumpToScene(state.activeScene - 1); }
    else if (e.key >= '1' && e.key <= '6') { jumpToScene(Number(e.key) - 1); }
  });

  let dragStartX = 0;
  let dragStartY = 0;
  window.addEventListener('pointerdown', (e) => {
    if (e.target.closest('.glass-surface, .header-capsule, .timecode-rail, a, button')) return;
    state.dragging = true;
    dragStartX = e.clientX;
    dragStartY = e.clientY;
    if (state.autoPlaying) stopAutoPlay();
  });
  window.addEventListener('pointermove', (e) => {
    state.pointerX = (e.clientX / window.innerWidth) * 2 - 1;
    state.pointerY = -(e.clientY / window.innerHeight) * 2 + 1;
    state.pointerOverUI = Boolean(e.target && e.target.closest && e.target.closest('.glass-surface, .header-capsule, .timecode-rail, .chapter-popover'));
    if (cursorAura) {
      cursorAura.style.transform = `translate3d(${e.clientX}px, ${e.clientY}px, 0)`;
      cursorAura.classList.toggle('flip-left', e.clientX > window.innerWidth - 310);
    }
    if (state.dragging) {
      const dx = (e.clientX - dragStartX) * 0.0055;
      const dy = (e.clientY - dragStartY) * 0.0045;
      dragStartX = e.clientX;
      dragStartY = e.clientY;
      state.orbitYaw = Math.max(-0.95, Math.min(0.95, state.orbitYaw + dx));
      state.orbitPitch = Math.max(-0.45, Math.min(0.45, state.orbitPitch + dy));
    }
  }, { passive: true });
  window.addEventListener('pointerup', () => { state.dragging = false; });

  return {
    state,
    dom: { container, bootVeil, bootBarFill, cursorAura, cursorNodeLabel, scenes },
    callbacks,
    applyLang,
    updateScrollState,
  };
}

/* Graceful Canvas 2D Fallback (Also Uses Real Hardware Screenshots!) */
export function mountCanvasFallback(container, state) {
  const canvas = document.createElement('canvas');
  container.appendChild(canvas);
  const ctx = canvas.getContext('2d');
  const imgLight = new Image();
  imgLight.src = './assets/screenshots/dex-workstation-light.jpg';
  const imgDark = new Image();
  imgDark.src = './assets/screenshots/dex-workstation-dark.jpg';
  const imgPhone = new Image();
  imgPhone.src = './assets/screenshots/phone-trackpad.jpg';

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = window.innerWidth * dpr;
    canvas.height = window.innerHeight * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  resize();
  window.addEventListener('resize', resize);

  function draw(now) {
    requestAnimationFrame(draw);
    const t = now * 0.001;
    const w = window.innerWidth;
    const h = window.innerHeight;
    const light = state.theme === 'pure-frost';
    ctx.fillStyle = light ? '#EDF2FA' : '#090C12';
    ctx.fillRect(0, 0, w, h);

    const cx = w * 0.5 + state.pointerX * 18;
    const cy = h * 0.40 + Math.sin(t) * 6;
    const tw = Math.min(640, w * 0.62);
    const th = tw * 0.625;
    const tabImg = light ? imgLight : imgDark;

    if (tabImg.complete && tabImg.naturalWidth > 0) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.28)';
      ctx.shadowBlur = 42;
      ctx.shadowOffsetY = 22;
      ctx.fillStyle = '#14171F';
      ctx.beginPath();
      ctx.roundRect(cx - tw / 2 - 10, cy - th / 2 - 10, tw + 20, th + 20, 22);
      ctx.fill();
      ctx.restore();

      ctx.save();
      ctx.beginPath();
      ctx.roundRect(cx - tw / 2, cy - th / 2, tw, th, 14);
      ctx.clip();
      ctx.drawImage(tabImg, cx - tw / 2, cy - th / 2, tw, th);
      ctx.restore();
    }
    if (imgPhone.complete && imgPhone.naturalWidth > 0 && w > 640) {
      const pw = 148;
      const ph = pw * 2.15;
      const px = cx + tw * 0.42;
      const py = cy + th * 0.08;
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.34)';
      ctx.shadowBlur = 32;
      ctx.fillStyle = '#14171F';
      ctx.beginPath();
      ctx.roundRect(px - 7, py - 7, pw + 14, ph + 14, 24);
      ctx.fill();
      ctx.beginPath();
      ctx.roundRect(px, py, pw, ph, 18);
      ctx.clip();
      ctx.drawImage(imgPhone, px, py, pw, ph);
      ctx.restore();
    }
  }
  requestAnimationFrame(draw);
}
