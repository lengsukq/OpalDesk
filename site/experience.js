/* ==========================================================================
   OpalDesk — Signature 3D Spatial Hardware & Wayland Compositor Stage
   - Precision Chamfered 3D Tablet (Xiaomi Pad 8 Pro) & Phone (Xiaomi 14 Pro)
   - Real Captured OpalDesk Screen Textures (Auto-Synced Light / Dark!)
   - Exploded Z-Depth Wayland Multi-Window RenderNode Layers
   - Interactive VirtualDisplayPool Spatial Window Carousel (Live vs Snapshot)
   - Live Multi-Device Casting Desk with Optical Ribbon & Floating App Flight
   - Four-Wafer Isometric Silicon-Style Architecture Stack
   - Procedural Studio Softbox Environment Map + Interactive Orbit Inspect
   ========================================================================== */

import { loadThree } from './experience/scene_data.js';
import { createGuidePlayer, mountCanvasFallback } from './experience/guide_player.js';
import {
  buildStageEntities,
  makeDockTexture,
  makeStudioEnvTexture,
} from './experience/window_sim.js';

const player = createGuidePlayer();
const { state, dom, callbacks } = player;
const { container, bootVeil, bootBarFill, cursorAura, cursorNodeLabel, scenes } = dom;

function lerp(a, b, k) { return a + (b - a) * k; }
function clamp01(v) { return Math.max(0, Math.min(1, v)); }
function smoothstep(a, b, x) { const t = clamp01((x - a) / (b - a)); return t * t * (3 - 2 * t); }

function mountSignature3D(THREE) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance', alpha: false });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(window.innerWidth, window.innerHeight);
  if ('toneMapping' in renderer) {
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.08;
  }
  container.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  scene.environment = makeStudioEnvTexture(THREE, renderer);

  const camera = new THREE.PerspectiveCamera(35, window.innerWidth / window.innerHeight, 0.1, 100);
  camera.position.set(0, 0, 11.4);

  let targetBlend = state.theme === 'pure-frost' ? 1.0 : 0.0;
  let themeBlend = targetBlend;
  const darkBg = new THREE.Color(0x090c12);
  const lightBg = new THREE.Color(0xedf2fa);
  const liveBg = darkBg.clone().lerp(lightBg, themeBlend);
  scene.background = liveBg;

  const hemi = new THREE.HemisphereLight(0xf0f7ff, 0x1e293b, 1.35);
  scene.add(hemi);
  const keyLight = new THREE.DirectionalLight(0xffffff, 2.2);
  keyLight.position.set(5.5, 8.5, 7.5);
  scene.add(keyLight);
  const fillLight = new THREE.DirectionalLight(0x7dd3fc, 0.95);
  fillLight.position.set(-6.5, 2.5, 4.0);
  scene.add(fillLight);
  const rimLight = new THREE.DirectionalLight(0x6366f1, 1.15);
  rimLight.position.set(2.0, -4.0, -6.5);
  scene.add(rimLight);

  const entities = buildStageEntities(THREE, { scene, renderer, state, themeBlend });
  const {
    SHOTS, scrW, haloUniforms, raycastTargets,
    tabletRig, tabletScreenMat, layerClock, layerCalc, layerCtrl, layerDock, dockMat,
    phoneRig, phScreenMat, floatAppCard,
    macRig, macFrameMat, macScreenMat, standMat,
    poolCarousel, poolCards,
    waferGroup, waferRecords, pillarMat, archBeads,
  } = entities;

  callbacks.onThemeChange = (nextTheme) => {
    targetBlend = nextTheme === 'pure-frost' ? 1.0 : 0.0;
    const isLight = nextTheme === 'pure-frost';
    dockMat.map = makeDockTexture(THREE, isLight);
    dockMat.needsUpdate = true;
    waferRecords.forEach((w) => {
      w.topMat.map = isLight ? w.topTexLight : w.topTexDark;
      w.topMat.needsUpdate = true;
    });
  };

  const raycaster = new THREE.Raycaster();
  const ptrVec = new THREE.Vector2();
  let lastHitUuid = null;

  function updateHover() {
    if (state.pointerOverUI) {
      lastHitUuid = null;
      cursorAura.classList.remove('hovering-node');
      return;
    }
    ptrVec.set(state.pointerX, state.pointerY);
    raycaster.setFromCamera(ptrVec, camera);
    const hits = raycaster.intersectObjects(raycastTargets, false).filter((h) => {
      let obj = h.object;
      while (obj) {
        if (!obj.visible) return false;
        obj = obj.parent;
      }
      const m = h.object.material;
      return !m.transparent || m.opacity > 0.28;
    });
    if (hits.length > 0) {
      const obj = hits[0].object;
      const ud = obj.userData || {};
      let text = ud.label || '';
      if (ud.kind === 'pool') text = state.lang === 'en' ? `${ud.slot.titleEn} · ${ud.slot.sub}` : `${ud.slot.titleZh} · ${ud.slot.sub}`;
      if (ud.kind === 'wafer') text = state.lang === 'en' ? ud.spec.en : ud.spec.zh;
      if (text) {
        cursorNodeLabel.textContent = text;
        cursorAura.classList.add('hovering-node');
        if (lastHitUuid !== obj.uuid) {
          lastHitUuid = obj.uuid;
          if (window.OpalSound) window.OpalSound.playHover(ud.index || 0);
        }
        return;
      }
    }
    lastHitUuid = null;
    cursorAura.classList.remove('hovering-node');
  }

  let safeBoxes = null;
  function measureSafeBoxes() {
    const W = Math.max(320, window.innerWidth);
    const H = Math.max(400, window.innerHeight);
    const isSplit = W > 960;
    const halfH0 = Math.tan(THREE.MathUtils.degToRad(camera.fov * 0.5)) * camera.position.z;
    const halfW0 = halfH0 * (W / H);

    const headerEl = document.querySelector('.header-capsule');
    const headerBottom = headerEl ? Math.max(54, headerEl.getBoundingClientRect().bottom) : 64;
    const topSafe = headerBottom + 14;

    const railEl = document.querySelector('.timecode-rail');
    const railLeft = (isSplit && railEl && railEl.offsetWidth > 0)
      ? Math.min(W - 56, railEl.getBoundingClientRect().left - 14)
      : W - 16;

    function toWorldBox(pxL, pxR, pxT, pxB) {
      const l = Math.max(12, pxL);
      const r = Math.max(l + 120, Math.min(W - 12, pxR));
      const t = Math.max(topSafe, pxT);
      const b = Math.max(t + 120, Math.min(H - 12, pxB));
      const ndcX = ((l + r) * 0.5 / W) * 2 - 1;
      const ndcY = 1 - ((t + b) * 0.5 / H) * 2;
      return {
        cx: ndcX * halfW0,
        cy: ndcY * halfH0,
        availW: ((r - l) / W) * (2 * halfW0),
        availH: ((b - t) / H) * (2 * halfH0)
      };
    }

    function measureSceneBox(secIdx, side) {
      const sec = scenes[secIdx];
      const copy = sec ? sec.querySelector('.scene-copy') : null;
      const cs = sec ? window.getComputedStyle(sec) : null;
      const padL = cs ? parseFloat(cs.paddingLeft) || 24 : 24;
      const padR = cs ? parseFloat(cs.paddingRight) || 24 : 24;
      const padB = cs ? parseFloat(cs.paddingBottom) || 24 : 24;
      const cardW = copy ? copy.offsetWidth : Math.min(460, W * 0.4);
      const cardH = copy ? copy.offsetHeight : 250;

      if (side === 'bottom' || !isSplit) {
        const bottomLimit = Math.max(topSafe + 140, H - padB - cardH - 14);
        return toWorldBox(16, isSplit ? railLeft : W - 16, topSafe, bottomLimit);
      }
      if (side === 'right') {
        return toWorldBox(padL + cardW + 24, railLeft, topSafe + 8, H - 24);
      }
      return toWorldBox(24, W - padR - cardW - 24, topSafe + 8, H - 24);
    }

    safeBoxes = {
      isSplit,
      b0: measureSceneBox(0, 'bottom'),
      b1: measureSceneBox(1, 'right'),
      b2: measureSceneBox(2, 'left'),
      b3: measureSceneBox(3, 'right'),
      b4: measureSceneBox(4, 'left'),
      b5: measureSceneBox(5, 'bottom')
    };
  }

  callbacks.onLayoutChange = measureSafeBoxes;
  measureSafeBoxes();
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(measureSafeBoxes).catch(() => {});
  }
  window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
    measureSafeBoxes();
  });

  const clock = new THREE.Clock();
  let smoothMorph = 0;
  let smoothExplode = 0;

  function animate() {
    requestAnimationFrame(animate);
    const dt = Math.min(clock.getDelta(), 0.05);
    const t = clock.elapsedTime;

    themeBlend = lerp(themeBlend, targetBlend, Math.min(1, dt * 6));
    liveBg.copy(darkBg).lerp(lightBg, themeBlend);
    scene.background = liveBg;

    if (!state.dragging) {
      state.orbitYaw = lerp(state.orbitYaw, 0, Math.min(1, dt * 2.2));
      state.orbitPitch = lerp(state.orbitPitch, 0, Math.min(1, dt * 2.2));
    }

    smoothMorph = lerp(smoothMorph, state.morph, Math.min(1, dt * 6.0));
    state.explodePulse = Math.max(0, state.explodePulse - dt * 1.5);
    state.castPulse = Math.max(0, state.castPulse - dt * 1.1);

    haloUniforms.uMorph.value = smoothMorph;
    haloUniforms.uLight.value = themeBlend;
    haloUniforms.uPulse.value = state.explodePulse + state.castPulse * 0.5;

    if (!safeBoxes) measureSafeBoxes();
    const { b0, b1, b2, b3, b4, b5 } = safeBoxes;

    const s0 = Math.max(0.26, Math.min(0.95, b0.availW / 6.65, b0.availH / 3.35));
    const s1 = Math.max(0.26, Math.min(0.95, b1.availW / 5.75, b1.availH / 3.85));
    const s2 = Math.max(0.26, Math.min(0.98, b2.availW / 4.95, b2.availH / 4.15));
    const s3 = Math.max(0.26, Math.min(0.95, b3.availW / 5.95, b3.availH / 3.85));
    const s4 = Math.max(0.26, Math.min(0.98, b4.availW / 4.75, b4.availH / 4.35));
    const s5 = Math.max(0.26, Math.min(0.95, b5.availW / 6.65, b5.availH / 3.35));

    const w0 = 1 - smoothstep(0.15, 0.85, smoothMorph);
    const w1 = smoothstep(0.25, 0.95, smoothMorph) * (1 - smoothstep(1.25, 1.9, smoothMorph));
    const w2 = smoothstep(1.3, 1.95, smoothMorph) * (1 - smoothstep(2.25, 2.9, smoothMorph));
    const w3 = smoothstep(2.3, 2.95, smoothMorph) * (1 - smoothstep(3.25, 3.9, smoothMorph));
    const w4 = smoothstep(3.3, 3.95, smoothMorph) * (1 - smoothstep(4.25, 4.9, smoothMorph));
    const w5 = smoothstep(4.35, 4.95, smoothMorph);

    /* 1. Tablet Rig Choreography */
    const tabSum = w0 + w1 + w3 + w5;
    const tabVis = clamp01(tabSum);
    tabletRig.visible = tabVis > 0.02;
    if (tabletRig.visible) {
      let desiredTex = state.theme === 'pure-frost' ? SHOTS.dexLight : SHOTS.dexDark;
      if (w3 > 0.45) desiredTex = state.topo === 'float' ? SHOTS.castFloat : SHOTS.castDesktop;
      else if (w1 > 0.45 && state.theme === 'prism-pulse') desiredTex = SHOTS.dexDark;
      if (tabletScreenMat.map !== desiredTex) {
        tabletScreenMat.map = desiredTex;
        tabletScreenMat.needsUpdate = true;
      }
      const winTex = state.theme === 'pure-frost' ? SHOTS.dexLight : SHOTS.dexDark;
      if (layerClock.screenMat.map !== winTex) {
        layerClock.screenMat.map = winTex;
        layerCalc.screenMat.map = winTex;
      }

      const n0 = w0 / tabSum, n1 = w1 / tabSum, n3 = w3 / tabSum, n5 = w5 / tabSum;
      const isFlt = state.topo === 'float';
      const tx = n0 * (b0.cx - 0.82 * s0) + n1 * (b1.cx - 0.14 * s1) + n3 * (b3.cx + (isFlt ? 0.30 : 0.50) * s3) + n5 * (b5.cx - 0.82 * s5);
      const ty = n0 * (b0.cy + 0.02 * s0) + n1 * b1.cy + n3 * (b3.cy + 0.16 * s3) + n5 * (b5.cy + 0.02 * s5) + Math.sin(t * 1.1) * 0.035;
      const tz = n0 * -0.35 + n1 * -0.38 + n3 * (isFlt ? -0.55 : -0.42) + n5 * -0.35;
      const ry = (n0 * 0.15) + (n1 * -0.30) + (n3 * -0.18) + (n5 * 0.15) + state.pointerX * 0.12 + state.orbitYaw;
      const rx = (n0 * -0.05) + (n1 * 0.12) + (n3 * 0.05) + (n5 * -0.05) - state.pointerY * 0.07 + state.orbitPitch;
      const sc = (n0 * 0.86 * s0 + n1 * 0.90 * s1 + n3 * (isFlt ? 0.84 : 0.86) * s3 + n5 * 0.86 * s5) * (0.65 + 0.35 * tabVis);

      tabletRig.position.set(tx, ty, tz);
      tabletRig.rotation.set(rx, ry, 0);
      tabletRig.scale.setScalar(sc);

      const targetExp = clamp01(w1 + (w0 > 0.5 && state.explodeToggle ? 0.95 : 0) + state.explodePulse * 0.25);
      smoothExplode = lerp(smoothExplode, targetExp, Math.min(1, dt * 6.5));
      const showExp = smoothExplode > 0.04;
      layerClock.grp.visible = showExp;
      layerCalc.grp.visible = showExp;
      layerCtrl.grp.visible = showExp;
      layerDock.visible = showExp;

      layerClock.grp.position.z = 0.09 + smoothExplode * 0.56;
      layerClock.grp.position.x = -scrW * 0.248 - smoothExplode * 0.10;
      layerClock.screenMat.opacity = smoothExplode * 0.96;
      layerClock.backMat.opacity = smoothExplode * 0.72;
      layerClock.rimMat.opacity = smoothExplode * 0.78;

      layerCalc.grp.position.z = 0.09 + smoothExplode * 0.94;
      layerCalc.grp.position.x = scrW * 0.248 + smoothExplode * 0.08;
      layerCalc.screenMat.opacity = smoothExplode * 0.96;
      layerCalc.backMat.opacity = smoothExplode * 0.72;
      layerCalc.rimMat.opacity = smoothExplode * 0.78;

      layerCtrl.grp.position.z = 0.12 + smoothExplode * 1.36;
      layerCtrl.grp.position.x = scrW * 0.29 + smoothExplode * 0.12;
      layerCtrl.screenMat.opacity = smoothExplode * 0.94;
      layerCtrl.backMat.opacity = smoothExplode * 0.78;
      layerCtrl.rimMat.opacity = smoothExplode * 0.68;

      layerDock.position.z = 0.14 + smoothExplode * 1.66;
      dockMat.opacity = smoothExplode * 0.96;
    }

    /* 2. Phone Rig Choreography */
    const phSum = w0 + w3 + w5;
    const phVis = clamp01(phSum);
    phoneRig.visible = phVis > 0.02;
    if (phoneRig.visible) {
      const desiredPh = (w3 > 0.4 && state.topo === 'float') ? SHOTS.phoneCasting : SHOTS.phoneTrackpad;
      if (phScreenMat.map !== desiredPh) {
        phScreenMat.map = desiredPh;
        phScreenMat.needsUpdate = true;
      }
      const p0 = w0 / phSum, p3 = w3 / phSum, p5 = w5 / phSum;
      const px = p0 * (b0.cx + 2.08 * s0) + p3 * (b3.cx - 1.62 * s3) + p5 * (b5.cx + 2.08 * s5);
      const py = p0 * (b0.cy - 0.04 * s0) + p3 * (b3.cy - 0.46 * s3) + p5 * (b5.cy - 0.04 * s5) + Math.cos(t * 1.25) * 0.035;
      const pz = p0 * 0.35 + p3 * 0.72 + p5 * 0.35;
      const prx = (p0 * -0.05) + (p3 * -0.40) + (p5 * -0.05) - state.pointerY * 0.07 + state.orbitPitch;
      const pry = (p0 * -0.22) + (p3 * 0.24) + (p5 * -0.22) + state.pointerX * 0.14 + state.orbitYaw;
      const psc = (p0 * 0.76 * s0 + p3 * 0.74 * s3 + p5 * 0.76 * s5) * (0.65 + 0.35 * phVis);

      phoneRig.position.set(px, py, pz);
      phoneRig.rotation.set(prx, pry, p3 * -0.06);
      phoneRig.scale.setScalar(psc);
    }

    /* 3. Floating App & External Mac Display */
    const fltW = w3 * (state.topo === 'float' ? 1.0 : 0.0);
    floatAppCard.grp.visible = fltW > 0.03;
    if (floatAppCard.grp.visible) {
      floatAppCard.screenMat.opacity = fltW * 0.98;
      floatAppCard.backMat.opacity = fltW * 0.85;
      floatAppCard.rimMat.opacity = fltW * 0.90;
      floatAppCard.grp.scale.setScalar(0.82 * s3);
      floatAppCard.grp.position.set(
        b3.cx + 0.82 * s3 + Math.sin(t * 1.4) * 0.04,
        b3.cy + 0.16 * s3 + Math.cos(t * 1.6) * 0.04,
        0.90
      );
      floatAppCard.grp.rotation.set(0.05 - state.pointerY * 0.07, -0.20 + state.pointerX * 0.11 + state.orbitYaw, 0);
    }

    const macW = w3 * (state.topo === 'mac' ? 1.0 : 0.0);
    macRig.visible = macW > 0.03;
    if (macRig.visible) {
      macFrameMat.opacity = macW * 0.95;
      macScreenMat.opacity = macW * 0.98;
      standMat.opacity = macW * 0.95;
      macRig.scale.setScalar(0.90 * s3);
      macRig.position.set(b3.cx + 0.44 * s3, b3.cy + 0.22 * s3 + Math.sin(t) * 0.035, -0.35);
      macRig.rotation.set(0.05 - state.pointerY * 0.06, -0.18 + state.pointerX * 0.11 + state.orbitYaw, 0);
      if (state.topo === 'mac') tabletRig.visible = false;
    }

    /* 4. VirtualDisplayPool Carousel */
    poolCarousel.visible = w2 > 0.03;
    if (poolCarousel.visible) {
      poolCarousel.position.set(b2.cx, b2.cy, 0.0);
      poolCarousel.scale.setScalar(s2);
      poolCarousel.rotation.y = 0.26 + state.pointerX * 0.12 + state.orbitYaw;
      poolCarousel.rotation.x = -0.04 - state.pointerY * 0.06 + state.orbitPitch;

      let liveRank = 0;
      let hibRank = 0;
      const totalLive = poolCards.filter((x) => x.slot.live).length;
      const totalHib = poolCards.length - totalLive;

      poolCards.forEach((c) => {
        const isLive = c.slot.live;
        let targetX, targetY, targetZ, targetRotY;
        if (isLive) {
          const centerShift = (liveRank - (totalLive - 1) * 0.5);
          targetX = centerShift * 0.52 + 0.32;
          targetY = centerShift * -0.34 + 0.34 + Math.sin(t * 1.1 + c.idx) * 0.03;
          targetZ = 0.88 - liveRank * 0.34;
          targetRotY = -0.14 + centerShift * 0.05;
          liveRank++;
        } else {
          const centerShift = (hibRank - (totalHib - 1) * 0.5);
          targetX = -0.82 + centerShift * 0.50;
          targetY = -1.06 + centerShift * -0.14 + Math.cos(t * 1.0 + c.idx) * 0.025;
          targetZ = -0.72 - hibRank * 0.32;
          targetRotY = 0.20;
          hibRank++;
        }

        c.grp.position.x = lerp(c.grp.position.x, targetX, Math.min(1, dt * 7));
        c.grp.position.y = lerp(c.grp.position.y, targetY, Math.min(1, dt * 7));
        c.grp.position.z = lerp(c.grp.position.z, targetZ, Math.min(1, dt * 7));
        c.grp.rotation.y = lerp(c.grp.rotation.y, targetRotY, Math.min(1, dt * 7));
        c.grp.scale.setScalar(lerp(c.grp.scale.x, isLive ? 1.0 : 0.78, Math.min(1, dt * 7)));

        c.screenMat.opacity = w2 * (isLive ? 0.98 : 0.68);
        c.backMat.opacity = w2 * (isLive ? 0.78 : 0.42);
        c.frostMat.opacity = w2 * (isLive ? 0.0 : 0.22);
        c.rimMat.color.setHex(isLive ? 0x2997ff : 0x30d158);
        c.rimMat.opacity = w2 * (isLive ? 0.94 : 0.80);
      });
    }

    /* 5. Architectural Glass Slabs */
    waferGroup.visible = w4 > 0.03;
    if (waferGroup.visible) {
      waferGroup.position.set(b4.cx, b4.cy, 0.15);
      waferGroup.scale.setScalar(s4);
      waferGroup.rotation.set(
        0.04 - state.pointerY * 0.06 + state.orbitPitch,
        0.20 + state.pointerX * 0.13 + Math.sin(t * 0.35) * 0.035 + state.orbitYaw,
        0
      );
      pillarMat.opacity = w4 * 0.48;
      archBeads.forEach((b) => {
        b.mat.opacity = w4 * 0.90;
        const u = ((t * 0.42 + b.phase) % 1.0);
        b.mesh.position.set(b.x, -1.56 + u * 3.12, -0.46);
      });
      waferRecords.forEach((w, i) => {
        w.slabMat.opacity = w4 * (themeBlend > 0.5 ? 0.34 : 0.46);
        w.topMat.opacity = w4 * 0.98;
        w.rimMat.opacity = w4 * 0.65;
        w.grp.position.y = w.spec.y + Math.sin(t * 1.05 + i * 0.7) * 0.03;
        w.grp.position.z = w.spec.z;
      });
    }

    updateHover();
    renderer.render(scene, camera);
  }
  animate();
}

/* Boot */
player.applyLang(state.lang);
player.updateScrollState();
if (bootBarFill) bootBarFill.style.width = '72%';

loadThree()
  .then((THREE) => {
    if (THREE) {
      try { mountSignature3D(THREE); }
      catch (_) { mountCanvasFallback(container, state); }
    } else {
      mountCanvasFallback(container, state);
    }
  })
  .finally(() => {
    if (bootBarFill) bootBarFill.style.width = '100%';
    setTimeout(() => { if (bootVeil) bootVeil.classList.add('ready'); }, 220);
  });
