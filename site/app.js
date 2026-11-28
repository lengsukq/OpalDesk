/* ==========================================================================
   OpalDesk 2D Interactive Showcase Controller
   - Live Draggable 120fps Multi-Window Compositor Simulator
   - Real-Device Xiaomi Pad 8 Pro & Xiaomi 14 Pro Interactive Gallery + Lightbox
   - Architecture Blueprint Inspector · 4-Channel Activation Studio
   - Shared Dual-Theme Circular Iris Transition & ZH/EN Bilingual Sync
   ========================================================================== */

import {
  SHOTS,
  TABLET_VIEWS,
  PHONE_VIEWS,
  ARCH_DETAILS,
  CODE_SNIPPETS,
  I18N,
} from './app_data.js';

const THEME_KEY = 'opaldesk-showcase-theme-v1';
  const LANG_KEY = 'opaldesk-showcase-lang-v1';

  function safeGet(k, fb = null) {
    try { const v = window.localStorage.getItem(k); return v !== null ? v : fb; } catch (_) { return fb; }
  }
  function safeSet(k, v) { try { window.localStorage.setItem(k, v); } catch (_) {} }

  const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const savedTheme = safeGet(THEME_KEY);
  const state = {
    theme: (savedTheme === 'pure-frost' || savedTheme === 'prism-pulse')
      ? savedTheme
      : (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'prism-pulse' : 'pure-frost'),
    lang: safeGet(LANG_KEY) === 'en' ? 'en' : 'zh',
    layout: 'split2',
    filter: 'all',
    archId: 'daemon',
    codeTab: 'wireless',
    lbIndex: 0
  };

  /* Toast Helper */
  const toastEl = document.getElementById('glassToastIsland');
  const toastText = document.getElementById('glassToastText');
  let toastTimer = 0;
  function showToast(msg) {
    if (!toastEl || !toastText) return;
    toastText.textContent = msg;
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('show'), 2200);
  }

  /* ========================================================================
     1. Interactive Multi-Window Compositor Simulator
     ======================================================================== */
  const simCanvas = document.getElementById('simCanvas');
  const simFocusedApp = document.getElementById('simFocusedApp');
  const simTelemetry = document.getElementById('simTelemetry');
  const PROFILES = ['桌面', '竖屏', '平板 4:3'];

  const SIM_WINDOWS = [
    { id: 'clock', zh: '世界时钟', en: 'World Clock', kpi: '14:51:31', subZh: '伦敦 06:51 · 纽约 01:51', subEn: 'London 06:51 · New York 01:51', profileIdx: 0, hibernated: false, z: 2 },
    { id: 'calc', zh: '科学计算器', en: 'Calculator', kpi: '120 × 100 = 12,000', subZh: '1:1 宿主 DPI 超采样 · 零裁切', subEn: '1:1 Host-DPI Supersampling', profileIdx: 0, hibernated: false, z: 3 },
    { id: 'browser', zh: 'OpalDesk GitHub', en: 'OpalDesk Repo', kpi: 'MIT Open Source', subZh: 'Wayland 式独立虚拟屏合成器', subEn: 'Universal Wayland Compositor', profileIdx: 0, hibernated: false, z: 1 },
    { id: 'phone', zh: '手机悬浮小窗', en: 'Phone Float App', kpi: '23116PN5BC', subZh: 'Wi-Fi TLS 1.3 · 560dpi 竖屏', subEn: 'Wi-Fi TLS 1.3 · 560dpi Portrait', profileIdx: 1, hibernated: true, z: 4 }
  ];

  let topZ = 5;
  const winEls = new Map();

  function computeLayoutFrames(mode) {
    const pad = 16;
    const w = simCanvas.clientWidth || 1000;
    const h = simCanvas.clientHeight || 440;
    const usableW = w - pad * 2;
    const usableH = h - pad * 2;
    const g = 12;

    if (mode === 'split2') {
      const half = (usableW - g) / 2;
      return {
        clock: { x: pad, y: pad, w: half, h: usableH, show: true },
        calc: { x: pad + half + g, y: pad, w: half, h: usableH, show: true },
        browser: { x: pad + 40, y: pad + 40, w: half * 0.85, h: usableH * 0.8, show: false },
        phone: { x: w - 260, y: pad + 18, w: 230, h: usableH - 36, show: false }
      };
    }
    if (mode === 'master21') {
      const left = (usableW - g) * 0.64;
      const right = usableW - g - left;
      const rh = (usableH - g) / 2;
      return {
        browser: { x: pad, y: pad, w: left, h: usableH, show: true },
        clock: { x: pad + left + g, y: pad, w: right, h: rh, show: true },
        calc: { x: pad + left + g, y: pad + rh + g, w: right, h: rh, show: true },
        phone: { x: w - 250, y: pad + 20, w: 220, h: usableH - 40, show: false }
      };
    }
    if (mode === 'cols3') {
      const col = (usableW - g * 2) / 3;
      return {
        clock: { x: pad, y: pad, w: col, h: usableH, show: true },
        calc: { x: pad + col + g, y: pad, w: col, h: usableH, show: true },
        browser: { x: pad + (col + g) * 2, y: pad, w: col, h: usableH, show: true },
        phone: { x: pad + 20, y: pad + 20, w: 220, h: usableH - 40, show: false }
      };
    }
    if (mode === 'grid4') {
      const cw = (usableW - g) / 2;
      const ch = (usableH - g) / 2;
      return {
        clock: { x: pad, y: pad, w: cw, h: ch, show: true },
        calc: { x: pad + cw + g, y: pad, w: cw, h: ch, show: true },
        browser: { x: pad, y: pad + ch + g, w: cw, h: ch, show: true },
        phone: { x: pad + cw + g, y: pad + ch + g, w: cw, h: ch, show: true }
      };
    }
    // cascade
    return {
      browser: { x: pad + 12, y: pad + 10, w: Math.min(480, usableW * 0.56), h: usableH * 0.78, show: true },
      clock: { x: pad + 130, y: pad + 42, w: Math.min(430, usableW * 0.48), h: usableH * 0.76, show: true },
      calc: { x: pad + 270, y: pad + 72, w: Math.min(400, usableW * 0.44), h: usableH * 0.74, show: true },
      phone: { x: Math.max(pad + 30, usableW - 230), y: pad + 24, w: 224, h: usableH * 0.84, show: true }
    };
  }

  function updateSimTelemetry() {
    const visible = SIM_WINDOWS.filter((w) => {
      const el = winEls.get(w.id);
      return el && el.style.display !== 'none';
    });
    const live = visible.filter((w) => !w.hibernated).length;
    const hib = visible.filter((w) => w.hibernated).length;
    if (simTelemetry) {
      simTelemetry.textContent = `RenderNode: 120fps · Drag IPC: 0 · Active VD: ${live}/6 · Snapshot: ${hib}`;
    }
  }

  function renderSimWindows() {
    if (!simCanvas) return;
    simCanvas.innerHTML = '';
    winEls.clear();

    SIM_WINDOWS.forEach((win) => {
      const el = document.createElement('div');
      el.className = `sim-win${win.hibernated ? ' hibernated' : ''}`;
      el.style.zIndex = String(win.z);
      el.innerHTML = `
        <div class="sim-win-bar">
          <span class="win-lights"><span></span><span></span><span></span></span>
          <span class="sim-win-title">${state.lang === 'en' ? win.en : win.zh}</span>
          <button type="button" class="win-profile-btn mono">${PROFILES[win.profileIdx]}</button>
        </div>
        <div class="sim-win-body">
          <div>
            <div class="sim-win-kpi mono">${win.kpi}</div>
            <div class="sim-win-sub">${state.lang === 'en' ? win.subEn : win.subZh}</div>
          </div>
          <span class="sim-win-badge mono">VirtualDisplay · FULLSCREEN (1)</span>
          ${win.hibernated ? `
            <div class="sim-hib-overlay">
              <span class="sim-hib-pill mono">TaskSnapshot 休眠中 (0 VD)</span>
              <span class="sim-win-sub">${state.lang === 'en' ? 'Click for 150ms instant wake' : '点击 150ms 无缝唤醒'}</span>
            </div>` : ''}
        </div>
      `;

      // Profile toggle button
      const profBtn = el.querySelector('.win-profile-btn');
      profBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        win.profileIdx = (win.profileIdx + 1) % PROFILES.length;
        profBtn.textContent = PROFILES[win.profileIdx];
        if (window.OpalSound) window.OpalSound.playClick('pill');
        showToast(`${state.lang === 'en' ? win.en : win.zh} → ${PROFILES[win.profileIdx]}`);
      });

      // Wake hibernated window on click
      const overlay = el.querySelector('.sim-hib-overlay');
      if (overlay) {
        overlay.addEventListener('click', () => {
          win.hibernated = false;
          win.z = ++topZ;
          if (window.OpalSound) window.OpalSound.playPulse(2, 0);
          showToast(state.lang === 'en' ? `${win.en} woke from TaskSnapshot in 150ms` : `${win.zh} 已从 TaskSnapshot 150ms 唤醒`);
          renderSimWindows();
          applySimLayout(state.layout);
        });
      }

      // Focus & GPU-matrix drag on titlebar
      el.addEventListener('pointerdown', () => {
        win.z = ++topZ;
        el.style.zIndex = String(win.z);
        if (simFocusedApp) simFocusedApp.textContent = state.lang === 'en' ? win.en : win.zh;
      });

      const bar = el.querySelector('.sim-win-bar');
      bar.addEventListener('pointerdown', (e) => {
        if (e.target.closest('button')) return;
        e.preventDefault();
        el.classList.add('dragging');
        const startX = e.clientX;
        const startY = e.clientY;
        const origL = parseFloat(el.style.left) || 0;
        const origT = parseFloat(el.style.top) || 0;
        const onMove = (ev) => {
          const maxL = Math.max(0, simCanvas.clientWidth - el.offsetWidth);
          const maxT = Math.max(0, simCanvas.clientHeight - el.offsetHeight);
          const nl = Math.max(0, Math.min(maxL, origL + (ev.clientX - startX)));
          const nt = Math.max(0, Math.min(maxT, origT + (ev.clientY - startY)));
          el.style.left = `${nl}px`;
          el.style.top = `${nt}px`;
        };
        const onUp = () => {
          el.classList.remove('dragging');
          window.removeEventListener('pointermove', onMove);
          window.removeEventListener('pointerup', onUp);
        };
        window.addEventListener('pointermove', onMove);
        window.addEventListener('pointerup', onUp);
      });

      simCanvas.appendChild(el);
      winEls.set(win.id, el);
    });
  }

  function applySimLayout(mode) {
    state.layout = mode;
    const frames = computeLayoutFrames(mode);
    SIM_WINDOWS.forEach((win) => {
      const el = winEls.get(win.id);
      const f = frames[win.id];
      if (!el || !f) return;
      el.style.display = f.show ? 'flex' : 'none';
      el.style.left = `${f.x}px`;
      el.style.top = `${f.y}px`;
      el.style.width = `${f.w}px`;
      el.style.height = `${f.h}px`;
    });
    document.querySelectorAll('.preset-chip[data-layout]').forEach((btn) => {
      btn.classList.toggle('active', btn.getAttribute('data-layout') === mode);
    });
    updateSimTelemetry();
  }

  document.querySelectorAll('.preset-chip[data-layout]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const lay = btn.getAttribute('data-layout');
      if (window.OpalSound) window.OpalSound.playClick('pill');
      applySimLayout(lay);
    });
  });

  const simHibernateBtn = document.getElementById('simHibernateBtn');
  if (simHibernateBtn) {
    simHibernateBtn.addEventListener('click', () => {
      const target = SIM_WINDOWS[2]; // browser
      target.hibernated = !target.hibernated;
      if (state.layout === 'split2') state.layout = 'cols3';
      if (window.OpalSound) window.OpalSound.playPulse(2, 0.2);
      renderSimWindows();
      applySimLayout(state.layout);
      showToast(target.hibernated
        ? (state.lang === 'en' ? 'Background window hibernated to TaskSnapshot (0 VD)' : '后台窗口已冻结为 TaskSnapshot 快照（释放 1 个虚拟屏槽位）')
        : (state.lang === 'en' ? 'Woke VirtualDisplay in 150ms' : '已 150ms 热唤醒实时虚拟屏'));
    });
  }

  const simResetBtn = document.getElementById('simResetBtn');
  if (simResetBtn) {
    simResetBtn.addEventListener('click', () => {
      SIM_WINDOWS.forEach((w, i) => { w.hibernated = i === 3; });
      renderSimWindows();
      applySimLayout(state.layout);
      if (window.OpalSound) window.OpalSound.playClick('pill');
    });
  }

  document.querySelectorAll('[data-dock-win]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const id = btn.getAttribute('data-dock-win');
      const win = SIM_WINDOWS.find((w) => w.id === id);
      const el = winEls.get(id);
      if (!win || !el) return;
      if (el.style.display === 'none') applySimLayout('cascade');
      if (win.hibernated) {
        win.hibernated = false;
        renderSimWindows();
        applySimLayout(state.layout);
      }
      const nextEl = winEls.get(id);
      if (nextEl) {
        win.z = ++topZ;
        nextEl.style.zIndex = String(win.z);
      }
      if (window.OpalSound) window.OpalSound.playClick('pill');
    });
  });

  window.addEventListener('resize', () => applySimLayout(state.layout));

  /* ========================================================================
     2. Featured Hardware Frames & Real-Device Gallery + Lightbox
     ======================================================================== */
  const featuredTabletImg = document.getElementById('featuredTabletImg');
  const featuredTabletCaption = document.getElementById('featuredTabletCaption');
  const featuredPhoneImg = document.getElementById('featuredPhoneImg');
  const featuredPhoneCaption = document.getElementById('featuredPhoneCaption');
  let curTabletView = 'light';
  let curPhoneView = 'trackpad';

  function setTabletView(key) {
    curTabletView = key;
    const v = TABLET_VIEWS[key] || TABLET_VIEWS.light;
    if (featuredTabletImg) featuredTabletImg.src = v.src;
    if (featuredTabletCaption) featuredTabletCaption.textContent = state.lang === 'en' ? v.en : v.zh;
    document.querySelectorAll('[data-hw-tablet]').forEach((b) => b.classList.toggle('active', b.getAttribute('data-hw-tablet') === key));
  }
  function setPhoneView(key) {
    curPhoneView = key;
    const v = PHONE_VIEWS[key] || PHONE_VIEWS.trackpad;
    if (featuredPhoneImg) featuredPhoneImg.src = v.src;
    if (featuredPhoneCaption) featuredPhoneCaption.textContent = state.lang === 'en' ? v.en : v.zh;
    document.querySelectorAll('[data-hw-phone]').forEach((b) => b.classList.toggle('active', b.getAttribute('data-hw-phone') === key));
  }
  document.querySelectorAll('[data-hw-tablet]').forEach((b) => b.addEventListener('click', () => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    setTabletView(b.getAttribute('data-hw-tablet'));
  }));
  document.querySelectorAll('[data-hw-phone]').forEach((b) => b.addEventListener('click', () => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    setPhoneView(b.getAttribute('data-hw-phone'));
  }));

  document.getElementById('featuredTabletWrap')?.addEventListener('click', () => openLightbox(TABLET_VIEWS[curTabletView].shotIdx));
  document.getElementById('featuredPhoneWrap')?.addEventListener('click', () => openLightbox(PHONE_VIEWS[curPhoneView].shotIdx));

  const shotGrid = document.getElementById('shotGrid');
  function renderShotGrid() {
    if (!shotGrid) return;
    shotGrid.innerHTML = '';
    SHOTS.forEach((shot, idx) => {
      if (state.filter !== 'all' && shot.cat !== state.filter) return;
      const card = document.createElement('article');
      card.className = 'shot-card';
      card.innerHTML = `
        <div class="shot-thumb${shot.portrait ? ' portrait-thumb' : ''}">
          <img src="${shot.src}" alt="${state.lang === 'en' ? shot.enTitle : shot.zhTitle}" loading="lazy" />
        </div>
        <div class="shot-meta">
          <span class="shot-device-inline mono">${shot.device}</span>
          <h4>${state.lang === 'en' ? shot.enTitle : shot.zhTitle}</h4>
          <p>${state.lang === 'en' ? shot.enDesc : shot.zhDesc}</p>
        </div>
      `;
      card.addEventListener('click', () => openLightbox(idx));
      shotGrid.appendChild(card);
    });
  }

  document.querySelectorAll('.filter-pill').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.filter = btn.getAttribute('data-filter') || 'all';
      document.querySelectorAll('.filter-pill').forEach((b) => b.classList.toggle('active', b === btn));
      if (window.OpalSound) window.OpalSound.playClick('pill');
      renderShotGrid();
    });
  });

  /* Lightbox */
  const lightbox = document.getElementById('lightbox');
  const lbImg = document.getElementById('lbImg');
  const lbTitle = document.getElementById('lbTitle');
  const lbDevice = document.getElementById('lbDevice');
  const lbCaption = document.getElementById('lbCaption');

  function updateLightbox() {
    const s = SHOTS[state.lbIndex] || SHOTS[0];
    lbImg.src = s.src;
    lbImg.alt = state.lang === 'en' ? s.enTitle : s.zhTitle;
    lbTitle.textContent = state.lang === 'en' ? s.enTitle : s.zhTitle;
    lbDevice.textContent = s.device;
    lbCaption.textContent = state.lang === 'en' ? s.enDesc : s.zhDesc;
  }
  function openLightbox(idx) {
    state.lbIndex = (idx + SHOTS.length) % SHOTS.length;
    updateLightbox();
    lightbox.hidden = false;
    document.body.style.overflow = 'hidden';
    if (window.OpalSound) window.OpalSound.playClick('pill');
  }
  function closeLightbox() {
    lightbox.hidden = true;
    document.body.style.overflow = '';
  }
  document.getElementById('lbClose')?.addEventListener('click', closeLightbox);
  document.getElementById('lbPrev')?.addEventListener('click', () => { state.lbIndex = (state.lbIndex - 1 + SHOTS.length) % SHOTS.length; updateLightbox(); });
  document.getElementById('lbNext')?.addEventListener('click', () => { state.lbIndex = (state.lbIndex + 1) % SHOTS.length; updateLightbox(); });
  lightbox?.addEventListener('click', (e) => { if (e.target === lightbox) closeLightbox(); });
  window.addEventListener('keydown', (e) => {
    if (lightbox.hidden) return;
    if (e.key === 'Escape') closeLightbox();
    if (e.key === 'ArrowLeft') { state.lbIndex = (state.lbIndex - 1 + SHOTS.length) % SHOTS.length; updateLightbox(); }
    if (e.key === 'ArrowRight') { state.lbIndex = (state.lbIndex + 1) % SHOTS.length; updateLightbox(); }
  });

  /* ========================================================================
     3. Architecture Blueprint Inspector & Code Studio
     ======================================================================== */
  function renderArchInspector() {
    const info = ARCH_DETAILS[state.archId] || ARCH_DETAILS.daemon;
    document.getElementById('archPath').textContent = info.path;
    document.getElementById('archTitle').textContent = state.lang === 'en' ? info.enTitle : info.zhTitle;
    document.getElementById('archDesc').textContent = state.lang === 'en' ? info.enDesc : info.zhDesc;
    const ul = document.getElementById('archBullets');
    ul.innerHTML = '';
    (state.lang === 'en' ? info.enBullets : info.zhBullets).forEach((item) => {
      const li = document.createElement('li');
      li.textContent = item;
      ul.appendChild(li);
    });
    document.querySelectorAll('.arch-node').forEach((n) => n.classList.toggle('active', n.getAttribute('data-arch') === state.archId));
  }
  document.querySelectorAll('.arch-node').forEach((n) => {
    n.addEventListener('click', () => {
      state.archId = n.getAttribute('data-arch') || 'daemon';
      if (window.OpalSound) window.OpalSound.playClick('pill');
      renderArchInspector();
    });
  });

  function renderCodeStudio() {
    const block = document.getElementById('studioCodeBlock');
    const snip = CODE_SNIPPETS[state.codeTab] || CODE_SNIPPETS.wireless;
    if (block) block.textContent = state.lang === 'en' ? snip.en : snip.zh;
    document.querySelectorAll('.studio-tab').forEach((t) => t.classList.toggle('active', t.getAttribute('data-code') === state.codeTab));
  }
  document.querySelectorAll('.studio-tab').forEach((t) => {
    t.addEventListener('click', () => {
      state.codeTab = t.getAttribute('data-code') || 'wireless';
      if (window.OpalSound) window.OpalSound.playClick('pill');
      renderCodeStudio();
    });
  });
  document.getElementById('copyCodeBtn')?.addEventListener('click', () => {
    const text = document.getElementById('studioCodeBlock')?.textContent || '';
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => showToast(state.lang === 'en' ? 'Copied to clipboard' : '已复制到剪贴板'));
    } else {
      showToast(state.lang === 'en' ? 'Ready to copy' : '已选中命令');
    }
    if (window.OpalSound) window.OpalSound.playClick('primary');
  });

  /* ========================================================================
     4. Theme, Bilingual, Nav Glider & Scroll Progress
     ======================================================================== */
  const themeBtn = document.getElementById('themeToggleBtn');
  const themeLabel = document.getElementById('themeToggleLabel');
  const langBtn = document.getElementById('langToggleBtn');

  function applyTheme(next, evt) {
    state.theme = next;
    safeSet(THEME_KEY, next);
    const commit = () => {
      document.documentElement.setAttribute('data-theme', next);
      if (themeLabel) themeLabel.textContent = next === 'pure-frost' ? I18N[state.lang].theme_light : I18N[state.lang].theme_dark;
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
  themeBtn?.addEventListener('click', (e) => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    applyTheme(state.theme === 'pure-frost' ? 'prism-pulse' : 'pure-frost', e);
  });

  function applyLang(next) {
    state.lang = next;
    safeSet(LANG_KEY, next);
    document.documentElement.lang = next === 'en' ? 'en' : 'zh-CN';
    if (langBtn) langBtn.textContent = next === 'en' ? '中文' : 'EN';
    const dict = I18N[next];
    document.querySelectorAll('[data-i18n]').forEach((el) => {
      const k = el.getAttribute('data-i18n');
      if (dict[k] !== undefined) el.innerHTML = dict[k];
    });
    if (themeLabel) themeLabel.textContent = state.theme === 'pure-frost' ? dict.theme_light : dict.theme_dark;
    renderSimWindows();
    applySimLayout(state.layout);
    setTabletView(curTabletView);
    setPhoneView(curPhoneView);
    renderShotGrid();
    renderArchInspector();
    renderCodeStudio();
    requestAnimationFrame(moveNavGlider);
  }
  langBtn?.addEventListener('click', () => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    applyLang(state.lang === 'zh' ? 'en' : 'zh');
  });

  /* Nav Glider & Scroll Spy */
  const navLinks = Array.from(document.querySelectorAll('#navLinks a'));
  const mobileLinks = Array.from(document.querySelectorAll('.mobile-section-bar a'));
  const navGlider = document.getElementById('navGlider');
  const scrollBeam = document.getElementById('scrollBeam');
  const sections = navLinks.map((a) => document.querySelector(a.getAttribute('href'))).filter(Boolean);

  function moveNavGlider() {
    if (!navGlider) return;
    const active = document.querySelector('#navLinks a.active') || navLinks[0];
    if (!active || active.offsetWidth === 0) return;
    navGlider.style.width = `${active.offsetWidth}px`;
    navGlider.style.transform = `translateX(${active.offsetLeft - 3}px)`;
  }

  function onScroll() {
    const maxS = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
    if (scrollBeam) scrollBeam.style.width = `${Math.min(100, (window.scrollY / maxS) * 100)}%`;

    let activeIdx = 0;
    const probe = window.scrollY + 180;
    sections.forEach((sec, i) => {
      if (sec.offsetTop <= probe) activeIdx = i;
    });
    navLinks.forEach((a, i) => a.classList.toggle('active', i === activeIdx));
    mobileLinks.forEach((a, i) => a.classList.toggle('active', i === activeIdx));
    moveNavGlider();
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', moveNavGlider);

  /* Boot */
  applyLang(state.lang);
  onScroll();
