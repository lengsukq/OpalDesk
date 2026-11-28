/* ==========================================================================
   OpalDesk 3D Procedural Geometry, Textures & Hardware/Window Scene Builder
   ========================================================================== */

import { POOL_SLOTS, WAFER_SPECS } from './scene_data.js';

export function roundedRectShape(THREE, w, h, r) {
  const s = new THREE.Shape();
  const x = -w / 2;
  const y = -h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  return s;
}

export function makeRoundedPlaneGeo(THREE, w, h, r) {
  const shape = roundedRectShape(THREE, w, h, r);
  const geo = new THREE.ShapeGeometry(shape, 16);
  const pos = geo.attributes.position;
  const uvs = new Float32Array(pos.count * 2);
  for (let i = 0; i < pos.count; i++) {
    uvs[i * 2] = (pos.getX(i) + w / 2) / w;
    uvs[i * 2 + 1] = (pos.getY(i) + h / 2) / h;
  }
  geo.setAttribute('uv', new THREE.BufferAttribute(uvs, 2));
  return geo;
}

export function makeStudioEnvTexture(THREE, renderer) {
  const c = document.createElement('canvas');
  c.width = 512;
  c.height = 256;
  const g = c.getContext('2d');
  const bg = g.createLinearGradient(0, 0, 0, 256);
  bg.addColorStop(0, '#eef5ff');
  bg.addColorStop(0.48, '#a6c0e4');
  bg.addColorStop(0.52, '#243044');
  bg.addColorStop(1, '#0c111b');
  g.fillStyle = bg;
  g.fillRect(0, 0, 512, 256);

  g.fillStyle = '#ffffff';
  g.fillRect(46, 22, 160, 44);
  g.fillStyle = 'rgba(180, 228, 255, 0.92)';
  g.fillRect(280, 34, 175, 36);
  g.fillStyle = 'rgba(125, 211, 252, 0.55)';
  g.fillRect(110, 150, 290, 20);

  const tex = new THREE.CanvasTexture(c);
  tex.mapping = THREE.EquirectangularReflectionMapping;
  if ('colorSpace' in tex && THREE.SRGBColorSpace) tex.colorSpace = THREE.SRGBColorSpace;
  try {
    const pmrem = new THREE.PMREMGenerator(renderer);
    const envRT = pmrem.fromEquirectangular(tex);
    pmrem.dispose();
    tex.dispose();
    return envRT.texture;
  } catch (_) {
    return tex;
  }
}

export function makeSoftShadowTexture(THREE) {
  const c = document.createElement('canvas');
  c.width = 256;
  c.height = 256;
  const g = c.getContext('2d');
  const grad = g.createRadialGradient(128, 128, 12, 128, 128, 122);
  grad.addColorStop(0, 'rgba(8, 18, 42, 0.42)');
  grad.addColorStop(0.45, 'rgba(8, 18, 42, 0.16)');
  grad.addColorStop(1, 'rgba(8, 18, 42, 0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, 256, 256);
  return new THREE.CanvasTexture(c);
}

export function makeWaferTexture(THREE, spec, isLight) {
  const c = document.createElement('canvas');
  c.width = 1600;
  c.height = 320;
  const g = c.getContext('2d');

  const bg = g.createLinearGradient(0, 0, 1600, 320);
  if (isLight) {
    bg.addColorStop(0, 'rgba(255, 255, 255, 0.96)');
    bg.addColorStop(1, 'rgba(241, 246, 255, 0.92)');
  } else {
    bg.addColorStop(0, 'rgba(26, 33, 48, 0.96)');
    bg.addColorStop(1, 'rgba(18, 23, 35, 0.94)');
  }
  g.fillStyle = bg;
  g.fillRect(0, 0, 1600, 320);

  g.strokeStyle = isLight ? 'rgba(15, 28, 52, 0.08)' : 'rgba(255, 255, 255, 0.14)';
  g.lineWidth = 4;
  g.strokeRect(2, 2, 1596, 316);

  g.fillStyle = spec.accent;
  g.beginPath();
  g.roundRect(26, 36, 14, 248, 7);
  g.fill();

  g.fillStyle = isLight ? 'rgba(15, 28, 52, 0.06)' : 'rgba(255, 255, 255, 0.09)';
  g.beginPath();
  g.roundRect(68, 42, 210, 44, 22);
  g.fill();
  g.fillStyle = spec.accent;
  g.font = '700 24px "SF Mono", ui-monospace, monospace';
  g.fillText(spec.tag, 90, 72);

  g.font = '600 26px "SF Mono", ui-monospace, monospace';
  const pillW = Math.max(230, g.measureText(spec.pill).width + 54);
  const pillX = 1554 - pillW;
  g.fillStyle = isLight ? 'rgba(0, 113, 227, 0.09)' : 'rgba(77, 163, 255, 0.16)';
  g.strokeStyle = spec.accent;
  g.lineWidth = 2.5;
  g.beginPath();
  g.roundRect(pillX, 44, pillW, 52, 26);
  g.fill();
  g.stroke();
  g.fillStyle = isLight ? '#16181D' : '#F4F7FB';
  g.fillText(spec.pill, pillX + 27, 79);

  g.fillStyle = isLight ? '#11151E' : '#F8FAFC';
  g.font = '700 54px -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", sans-serif';
  g.fillText(spec.title, 68, 178);

  g.fillStyle = isLight ? '#475569' : '#B4C0D3';
  g.font = '500 32px -apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", sans-serif';
  g.fillText(spec.sub, 68, 252);

  const tex = new THREE.CanvasTexture(c);
  if ('colorSpace' in tex && THREE.SRGBColorSpace) tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 8;
  return tex;
}

export function makeDockTexture(THREE, isLight) {
  const c = document.createElement('canvas');
  c.width = 768;
  c.height = 144;
  const g = c.getContext('2d');
  g.clearRect(0, 0, 768, 144);

  g.fillStyle = isLight ? 'rgba(255,255,255,0.88)' : 'rgba(24,30,44,0.88)';
  g.strokeStyle = isLight ? 'rgba(255,255,255,0.96)' : 'rgba(255,255,255,0.24)';
  g.lineWidth = 4;
  g.beginPath();
  g.roundRect(12, 14, 744, 116, 58);
  g.fill();
  g.stroke();

  const colors = ['#0071E3', '#22B8CF', '#5E7CE2', '#30D158', '#FF9F0A', '#EC4899'];
  colors.forEach((col, i) => {
    const x = 78 + i * 106;
    g.fillStyle = col;
    g.beginPath();
    g.roundRect(x, 34, 76, 76, 22);
    g.fill();
    g.fillStyle = 'rgba(255,255,255,0.28)';
    g.beginPath();
    g.roundRect(x + 6, 40, 64, 24, 12);
    g.fill();
  });

  const tex = new THREE.CanvasTexture(c);
  if ('colorSpace' in tex && THREE.SRGBColorSpace) tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

export function buildStageEntities(THREE, { scene, renderer, state, themeBlend }) {
  const loader = new THREE.TextureLoader();
  const maxAniso = renderer.capabilities.getMaxAnisotropy ? Math.min(8, renderer.capabilities.getMaxAnisotropy()) : 4;
  function loadShot(file) {
    const tex = loader.load(`./assets/screenshots/${file}`);
    if ('colorSpace' in tex && THREE.SRGBColorSpace) tex.colorSpace = THREE.SRGBColorSpace;
    tex.anisotropy = maxAniso;
    return tex;
  }
  const SHOTS = {
    dexLight: loadShot('dex-workstation-light.jpg'),
    dexDark: loadShot('dex-workstation-dark.jpg'),
    dexCtrlDark: loadShot('dex-control-dark.jpg'),
    castDesktop: loadShot('cast-desktop-phone-to-tablet.jpg'),
    castFloat: loadShot('cast-floating-app.jpg'),
    phoneTrackpad: loadShot('phone-trackpad.jpg'),
    phoneCasting: loadShot('phone-casting-console.jpg'),
    console: loadShot('tablet-console.jpg'),
    settings: loadShot('tablet-settings.jpg')
  };

  const haloUniforms = { uMorph: { value: 0 }, uLight: { value: themeBlend }, uPulse: { value: 0 } };
  const haloMat = new THREE.ShaderMaterial({
    uniforms: haloUniforms,
    transparent: true,
    depthWrite: false,
    depthTest: false,
    vertexShader: `varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
    fragmentShader: `
      uniform float uMorph;
      uniform float uLight;
      uniform float uPulse;
      varying vec2 vUv;
      void main() {
        vec2 p = (vUv - 0.5) * 2.0;
        float r = length(p);
        if (r > 0.98) discard;
        float g1 = exp(-dot(p - vec2(-0.28, 0.18), p - vec2(-0.28, 0.18)) * 3.0);
        float g2 = exp(-dot(p - vec2(0.32, -0.14), p - vec2(0.32, -0.14)) * 2.8);
        vec3 cyan = vec3(0.16, 0.66, 0.98);
        vec3 indigo = vec3(0.46, 0.44, 0.94);
        vec3 pearl = vec3(0.92, 0.54, 0.78);
        vec3 col = cyan * g1 + mix(indigo, pearl, clamp(uMorph * 0.18, 0.0, 0.7)) * g2;
        float fade = smoothstep(0.98, 0.25, r);
        float a = (g1 * 0.24 + g2 * 0.20) * fade * (1.0 + uPulse * 0.35) * mix(0.85, 0.38, uLight);
        gl_FragColor = vec4(col, a);
      }
    `
  });
  const haloPlane = new THREE.Mesh(new THREE.PlaneGeometry(24, 16), haloMat);
  haloPlane.position.set(0, 0.5, -5.0);
  haloPlane.renderOrder = -10;
  scene.add(haloPlane);

  const root = new THREE.Group();
  scene.add(root);
  const raycastTargets = [];
  const shadowTex = makeSoftShadowTexture(THREE);

  /* 1. Xiaomi Pad 8 Pro */
  const tabletRig = new THREE.Group();
  root.add(tabletRig);

  const TAB_IMG_ASPECT = 3040 / 1904;
  const scrH = 2.72;
  const scrW = scrH * TAB_IMG_ASPECT;
  const tabW = scrW + 0.24;
  const tabH = scrH + 0.24;
  const tabDepth = 0.14;
  const bodyGeo = new THREE.ExtrudeGeometry(roundedRectShape(THREE, tabW, tabH, 0.24), {
    depth: tabDepth,
    bevelEnabled: true,
    bevelThickness: 0.032,
    bevelSize: 0.028,
    bevelSegments: 5,
    curveSegments: 20
  });
  bodyGeo.center();

  const metalChassisMat = new THREE.MeshStandardMaterial({
    color: 0xcbd5e1,
    metalness: 0.86,
    roughness: 0.22,
    envMapIntensity: 1.35
  });
  const tabletBody = new THREE.Mesh(bodyGeo, metalChassisMat);
  tabletBody.userData = { label: 'Xiaomi Pad 8 Pro · 3040×1904 @ 120Hz' };
  raycastTargets.push(tabletBody);
  tabletRig.add(tabletBody);

  const bezelMat = new THREE.MeshPhysicalMaterial({
    color: 0x0b0e14,
    roughness: 0.08,
    metalness: 0.1,
    clearcoat: 1.0,
    clearcoatRoughness: 0.06
  });
  const tabletBezel = new THREE.Mesh(makeRoundedPlaneGeo(THREE, tabW - 0.07, tabH - 0.07, 0.20), bezelMat);
  tabletBezel.position.z = tabDepth * 0.5 + 0.034;
  tabletRig.add(tabletBezel);

  const tabletScreenMat = new THREE.MeshBasicMaterial({ map: state.theme === 'pure-frost' ? SHOTS.dexLight : SHOTS.dexDark });
  const tabletScreen = new THREE.Mesh(makeRoundedPlaneGeo(THREE, scrW, scrH, 0.13), tabletScreenMat);
  tabletScreen.position.z = tabDepth * 0.5 + 0.039;
  tabletScreen.userData = { label: 'OpalDesk · 单 RenderNode 120fps 合成画面' };
  raycastTargets.push(tabletScreen);
  tabletRig.add(tabletScreen);

  const tabShadow = new THREE.Mesh(
    new THREE.PlaneGeometry(5.8, 2.2),
    new THREE.MeshBasicMaterial({ map: shadowTex, transparent: true, opacity: 0.42, depthWrite: false })
  );
  tabShadow.rotation.x = -Math.PI / 2;
  tabShadow.position.set(0, -1.94, 0);
  tabletRig.add(tabShadow);

  function makeCroppedWindowPlane(w, h, r, tex, u0, v0, u1, v1, label) {
    const geo = makeRoundedPlaneGeo(THREE, w, h, r);
    const uv = geo.attributes.uv;
    for (let i = 0; i < uv.count; i++) {
      const u = uv.getX(i);
      const v = uv.getY(i);
      uv.setXY(i, u0 + u * (u1 - u0), v0 + v * (v1 - v0));
    }
    const grp = new THREE.Group();

    const slabGeo = new THREE.ExtrudeGeometry(roundedRectShape(THREE, w + 0.03, h + 0.03, r + 0.012), {
      depth: 0.028,
      bevelEnabled: true,
      bevelThickness: 0.008,
      bevelSize: 0.008,
      bevelSegments: 2
    });
    slabGeo.center();
    const backMat = new THREE.MeshPhysicalMaterial({
      color: 0xe2e8f0,
      roughness: 0.14,
      metalness: 0.35,
      clearcoat: 1.0,
      transparent: true,
      opacity: 0
    });
    const backSlab = new THREE.Mesh(slabGeo, backMat);
    backSlab.position.z = -0.024;
    grp.add(backSlab);

    const screenMat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, opacity: 0 });
    const plane = new THREE.Mesh(geo, screenMat);
    plane.userData = { label };
    raycastTargets.push(plane);
    grp.add(plane);

    const rimPts = roundedRectShape(THREE, w + 0.022, h + 0.022, r + 0.01).getPoints(44);
    const rimGeo = new THREE.BufferGeometry().setFromPoints(rimPts.map((p) => new THREE.Vector3(p.x, p.y, 0.008)));
    const rimMat = new THREE.LineBasicMaterial({ color: 0x38bdf8, transparent: true, opacity: 0 });
    grp.add(new THREE.LineLoop(rimGeo, rimMat));

    return { grp, plane, screenMat, rimMat, backMat };
  }

  const layerClock = makeCroppedWindowPlane(
    scrW * (0.495 - 0.006), scrH * (0.955 - 0.10), 0.095, SHOTS.dexLight,
    0.006, 0.10, 0.495, 0.955,
    'OpalWin-1 · 世界时钟 · 独立 VirtualDisplay #1'
  );
  layerClock.grp.position.set(-scrW * 0.248, 0.06, 0.08);
  tabletRig.add(layerClock.grp);

  const layerCalc = makeCroppedWindowPlane(
    scrW * (0.994 - 0.504), scrH * (0.955 - 0.10), 0.095, SHOTS.dexLight,
    0.504, 0.10, 0.994, 0.955,
    'OpalWin-2 · 科学计算器 · 独立 VirtualDisplay #2'
  );
  layerCalc.grp.position.set(scrW * 0.248, 0.06, 0.08);
  tabletRig.add(layerCalc.grp);

  const layerCtrl = makeCroppedWindowPlane(
    scrW * (0.992 - 0.652), scrH * (0.96 - 0.03), 0.105, SHOTS.dexCtrlDark,
    0.652, 0.03, 0.992, 0.96,
    'Control Center · 120Hz / DPI 热刷新 / 7 套分屏'
  );
  layerCtrl.grp.position.set(scrW * 0.31, 0.01, 0.12);
  tabletRig.add(layerCtrl.grp);

  const dockMat = new THREE.MeshBasicMaterial({ map: makeDockTexture(THREE, state.theme === 'pure-frost'), transparent: true, opacity: 0 });
  const layerDock = new THREE.Mesh(new THREE.PlaneGeometry(2.15, 0.40), dockMat);
  layerDock.position.set(0, -scrH * 0.44, 0.14);
  layerDock.userData = { label: 'Floating Liquid Glass Dock · ClipOp.Difference 外扩散阴影' };
  raycastTargets.push(layerDock);
  tabletRig.add(layerDock);

  /* 2. Xiaomi 14 Pro */
  const phoneRig = new THREE.Group();
  root.add(phoneRig);

  const PH_IMG_ASPECT = 1440 / 3200;
  const phScrH = 2.80;
  const phScrW = phScrH * PH_IMG_ASPECT;
  const phW = phScrW + 0.14;
  const phH = phScrH + 0.14;
  const phDepth = 0.13;
  const phGeo = new THREE.ExtrudeGeometry(roundedRectShape(THREE, phW, phH, 0.20), {
    depth: phDepth,
    bevelEnabled: true,
    bevelThickness: 0.028,
    bevelSize: 0.024,
    bevelSegments: 5,
    curveSegments: 18
  });
  phGeo.center();

  const titaniumMat = new THREE.MeshStandardMaterial({
    color: 0x94a3b8,
    metalness: 0.90,
    roughness: 0.20,
    envMapIntensity: 1.4
  });
  const phoneBody = new THREE.Mesh(phGeo, titaniumMat);
  phoneBody.userData = { label: 'Xiaomi 14 Pro · 1440×3200 · 精密触控板与投屏源' };
  raycastTargets.push(phoneBody);
  phoneRig.add(phoneBody);

  const phBezel = new THREE.Mesh(makeRoundedPlaneGeo(THREE, phW - 0.05, phH - 0.05, 0.17), bezelMat);
  phBezel.position.z = phDepth * 0.5 + 0.030;
  phoneRig.add(phBezel);

  const phScreenMat = new THREE.MeshBasicMaterial({ map: SHOTS.phoneTrackpad });
  const phScreen = new THREE.Mesh(makeRoundedPlaneGeo(THREE, phScrW, phScrH, 0.13), phScreenMat);
  phScreen.position.z = phDepth * 0.5 + 0.034;
  phScreen.userData = { label: 'Xiaomi 14 Pro · 120Hz 液态毛玻璃精密触控板' };
  raycastTargets.push(phScreen);
  phoneRig.add(phScreen);

  const fltU0 = 0.525, fltV0 = 0.04, fltU1 = 0.885, fltV1 = 0.855;
  const fltAspect = TAB_IMG_ASPECT * (fltU1 - fltU0) / (fltV1 - fltV0);
  const fltH = 2.02;
  const fltW = fltH * fltAspect;
  const floatAppCard = makeCroppedWindowPlane(
    fltW, fltH, 0.10, SHOTS.castFloat,
    fltU0, fltV0, fltU1, fltV1,
    '独立应用悬浮小窗 · 来自 Xiaomi 14 Pro · 1:1 宿主 DPI'
  );
  root.add(floatAppCard.grp);

  /* 3. External 2K / OpalDeskMac Monitor */
  const macRig = new THREE.Group();
  root.add(macRig);
  const macScrH = 2.28;
  const macScrW = macScrH * TAB_IMG_ASPECT;
  const macFrameGeo = new THREE.ExtrudeGeometry(roundedRectShape(THREE, macScrW + 0.24, macScrH + 0.24, 0.16), {
    depth: 0.09,
    bevelEnabled: true,
    bevelThickness: 0.02,
    bevelSize: 0.02,
    bevelSegments: 4
  });
  macFrameGeo.center();
  const macFrameMat = new THREE.MeshStandardMaterial({ color: 0xe2e8f0, metalness: 0.85, roughness: 0.24, transparent: true, opacity: 0 });
  macRig.add(new THREE.Mesh(macFrameGeo, macFrameMat));
  const macScreenMat = new THREE.MeshBasicMaterial({ map: SHOTS.dexDark, transparent: true, opacity: 0 });
  const macScreen = new THREE.Mesh(makeRoundedPlaneGeo(THREE, macScrW, macScrH, 0.10), macScreenMat);
  macScreen.position.z = 0.072;
  macScreen.userData = { label: 'OpalDeskMac (Swift 6) · 2K/4K 100Hz+ 独立外接工作站' };
  raycastTargets.push(macScreen);
  macRig.add(macScreen);
  const standMat = new THREE.MeshStandardMaterial({ color: 0xcbd5e1, metalness: 0.88, roughness: 0.22, transparent: true, opacity: 0 });
  const standNeck = new THREE.Mesh(new THREE.BoxGeometry(0.46, 0.72, 0.06), standMat);
  standNeck.position.set(0, -1.52, -0.06);
  const standBase = new THREE.Mesh(new THREE.BoxGeometry(1.38, 0.045, 0.56), standMat);
  standBase.position.set(0, -1.86, 0.06);
  macRig.add(standNeck, standBase);
  macRig.position.set(0.6, 0.25, -1.4);

  /* 4. Stage 02 VirtualDisplayPool Stage-Manager Stack */
  const poolCarousel = new THREE.Group();
  root.add(poolCarousel);
  const poolCardH = 1.34;
  const poolCardW = poolCardH * TAB_IMG_ASPECT;
  const poolCards = POOL_SLOTS.map((slot, idx) => {
    const tex = SHOTS[slot.shot] || SHOTS.dexLight;
    const card = makeCroppedWindowPlane(poolCardW, poolCardH, 0.10, tex, 0, 0, 1, 1, slot.zh);
    card.plane.userData = { kind: 'pool', slot, index: idx };

    const frostMat = new THREE.MeshBasicMaterial({ color: 0x10b981, transparent: true, opacity: 0 });
    const frostPlane = new THREE.Mesh(makeRoundedPlaneGeo(THREE, poolCardW, poolCardH, 0.10), frostMat);
    frostPlane.position.z = 0.006;
    card.grp.add(frostPlane);

    poolCarousel.add(card.grp);
    return { ...card, frostMat, slot, idx };
  });

  /* 5. Stage 04 Architectural Glass Slabs */
  const waferGroup = new THREE.Group();
  root.add(waferGroup);
  const slabW = 4.20;
  const slabH = 0.84;
  const slabDepth = 0.18;
  const waferGeo = new THREE.ExtrudeGeometry(roundedRectShape(THREE, slabW, slabH, 0.14), {
    depth: slabDepth,
    bevelEnabled: true,
    bevelThickness: 0.022,
    bevelSize: 0.020,
    bevelSegments: 4
  });
  waferGeo.center();

  const waferRecords = WAFER_SPECS.map((spec, idx) => {
    const grp = new THREE.Group();
    const slabMat = new THREE.MeshPhysicalMaterial({
      color: new THREE.Color(spec.accent),
      roughness: 0.18,
      metalness: 0.32,
      clearcoat: 1.0,
      transparent: true,
      opacity: 0
    });
    grp.add(new THREE.Mesh(waferGeo, slabMat));

    const topTexLight = makeWaferTexture(THREE, spec, true);
    const topTexDark = makeWaferTexture(THREE, spec, false);
    const topMat = new THREE.MeshBasicMaterial({
      map: state.theme === 'pure-frost' ? topTexLight : topTexDark,
      transparent: true,
      opacity: 0
    });
    const frontPlane = new THREE.Mesh(makeRoundedPlaneGeo(THREE, slabW - 0.06, slabH - 0.06, 0.11), topMat);
    frontPlane.position.z = slabDepth * 0.5 + 0.025;
    frontPlane.userData = { kind: 'wafer', spec, index: idx };
    raycastTargets.push(frontPlane);
    grp.add(frontPlane);

    const rimPts = roundedRectShape(THREE, slabW + 0.01, slabH + 0.01, 0.145).getPoints(44);
    const rimMat = new THREE.LineBasicMaterial({ color: new THREE.Color(spec.accent), transparent: true, opacity: 0 });
    const rim = new THREE.LineLoop(
      new THREE.BufferGeometry().setFromPoints(rimPts.map((p) => new THREE.Vector3(p.x, p.y, slabDepth * 0.5 + 0.028))),
      rimMat
    );
    grp.add(rim);

    grp.position.set(0, spec.y, spec.z);
    waferGroup.add(grp);
    return { grp, slabMat, topMat, rimMat, topTexLight, topTexDark, spec, idx };
  });

  const pillarMat = new THREE.MeshBasicMaterial({ color: 0x38bdf8, transparent: true, opacity: 0 });
  const archBeads = [];
  [-1.45, 0, 1.45].forEach((px, pIdx) => {
    const p = new THREE.Mesh(new THREE.CylinderGeometry(0.015, 0.015, 3.25, 10), pillarMat);
    p.position.set(px, 0, -0.46);
    waferGroup.add(p);
    const bm = new THREE.MeshBasicMaterial({ color: 0x38bdf8, transparent: true, opacity: 0 });
    const bead = new THREE.Mesh(new THREE.SphereGeometry(0.052, 12, 12), bm);
    waferGroup.add(bead);
    archBeads.push({ mesh: bead, mat: bm, x: px, phase: pIdx * 0.33 });
  });

  return {
    SHOTS,
    scrW,
    haloUniforms,
    raycastTargets,
    tabletRig,
    tabletScreenMat,
    layerClock,
    layerCalc,
    layerCtrl,
    layerDock,
    dockMat,
    phoneRig,
    phScreenMat,
    floatAppCard,
    macRig,
    macFrameMat,
    macScreenMat,
    standMat,
    poolCarousel,
    poolCards,
    waferGroup,
    waferRecords,
    pillarMat,
    archBeads,
  };
}
