/**
 * TLS TOFU trust negotiation, WebSocket session transport, adaptive bitrate
 * & file drop transfer for OpalDesk Web Cast.
 */

import { probeHttpsHealth } from './activation_client.js';
import {
  computeAdaptiveAutoBitrateStep,
  evaluateCertificateTrust,
} from './desktop_bridge.js';
import {
  decodeFrame,
  DISPLAY_MODE_DEX,
  encodeFileTransferChunk,
  encodeHello,
  encodePingPong,
  encodeResizeConfig,
  encodeSystemAction,
  FILE_FLAG_DATA,
  FILE_FLAG_END,
  FILE_FLAG_START,
  fitDimensionsToMaxBounds,
  SESSION_ROLE_DESKTOP,
  SESSION_ROLE_STANDALONE_APP,
  shouldRequestSessionAudio,
  SYS_DISCONNECT,
  TYPE_FILE_TRANSFER_CHUNK,
  TYPE_HELLO,
  TYPE_PING,
  TYPE_RESIZE_CONFIG,
  TYPE_SYSTEM_ACTION,
  VIDEO_CODEC_HEVC,
} from './protocol.js';
import { resolvePopoutLaunchGeometry } from './standalone_prefs.js';
import {
  detectBrowserName,
  FILE_CHUNK_SIZE,
  getSavedToken,
  isSafariBrowser,
} from './cast_storage.js';
import { flushPendingCustomAdbPath } from './cast_desktop_discovery.js';
import { t } from './i18n.js';

export {
  flushPendingCustomAdbPath,
  refreshDesktopUsbPanel,
  refreshDesktopUsbTetherPanel,
  renderDesktopMdnsDevices,
  renderDesktopUsbTetherDevices,
  restoreRememberedAdbPath,
  saveCustomAdbPath,
  syncCustomAdbPathUi,
  syncDesktopUsbTetherUi,
} from './cast_desktop_discovery.js';

const $ = (id) => document.getElementById(id);

export function promptTofuConfirmation(app, host, port, evalRes) {
  return new Promise((resolve) => {
    app.tofuResolver = resolve;
    const isChanged = evalRes.status === 'changed';
    $('tofuModalTitle').textContent = isChanged ? t('tofu.titleChanged') : t('tofu.titleFirst');
    $('tofuModalDesc').textContent = isChanged
      ? t('tofu.descChanged', { host, port, expected: evalRes.expectedShort })
      : t('tofu.descFirst', { name: evalRes.mdnsDevice?.displayName || host, host, port });
    const mdnsBadge = $('tofuMdnsBadge');
    mdnsBadge.hidden = false;
    if (evalRes.mdnsVerified) {
      mdnsBadge.className = 'status-pill ok mono';
      mdnsBadge.textContent = t('tofu.badgeMdnsOk');
    } else {
      mdnsBadge.className = 'status-pill warn mono';
      mdnsBadge.textContent = isChanged ? t('tofu.badgeChanged') : t('tofu.badgeManual');
    }
    $('tofuShortFp').textContent = evalRes.actualShort || '----';
    $('tofuFullFp').textContent = evalRes.actualFingerprint || '';
    $('tofuModalOverlay').hidden = false;
  });
}

export function resolveTofuModal(app, approved) {
  $('tofuModalOverlay').hidden = true;
  const fn = app.tofuResolver;
  app.tofuResolver = null;
  if (fn) fn(Boolean(approved));
}

export async function ensureDesktopTlsTunnel(app, host, port, wsPath, explicitPin = '') {
  await app.desktop.refreshTrustedDevices();
  const probed = await app.desktop.probeCertificate(host, port, 4500);
  const evalRes = evaluateCertificateTrust({
    host,
    port,
    probedFingerprint: probed.fingerprint,
    trustedDevices: app.desktop.trustedDevices,
    mdnsDevices: app.desktop.mdnsDevices,
    explicitPin,
  });
  if (evalRes.status === 'invalid') {
    throw new Error(t('cast.errInvalidCert'));
  }
  if (evalRes.status === 'mismatch') {
    throw new Error(t('cast.errCertMismatch', { expected: evalRes.expectedShort, actual: evalRes.actualShort }));
  }
  if (evalRes.status === 'tofu_required' || evalRes.status === 'changed') {
    const ok = await promptTofuConfirmation(app, host, port, evalRes);
    if (!ok) return null;
    await app.desktop.upsertTrustedDevice({
      host,
      port,
      displayName: evalRes.mdnsDevice?.displayName || host,
      model: evalRes.mdnsDevice?.model || '',
      certFingerprint: evalRes.actualFingerprint,
      authToken: app.desktop.getTrustedToken(host, port) || getSavedToken(host, port),
      permanent: false,
      lastConnectedAtMs: Date.now(),
    });
    app.renderRecentDevices();
  }
  return app.desktop.startTlsWsTunnel(host, port, wsPath, evalRes.actualFingerprint);
}

export async function startCastConnection(app) {
  app.setCastError('');
  $('castTrustBox').hidden = true;

  const isUsb = app.transportMode === 'usb';
  const host = isUsb ? '127.0.0.1' : $('castHostInput').value.trim();
  const port = Number($('castPortInput').value) || 19878;
  if (!host) {
    app.setCastError(t('cast.errMissingHost'));
    return;
  }

  $('connectCastBtn').disabled = true;
  $('connectCastBtn').textContent = t('cast.probingBtn');
  const defaultBtnLabel = app.desktop.enabled ? t('cast.connectDesktopBtn') : t('cast.connectWebBtn');

  if (app.desktop.enabled) {
    try {
      if (isUsb) {
        if (app.sessionRole === SESSION_ROLE_STANDALONE_APP && port !== 19878) {
          app.activeHost = '127.0.0.1';
          app.activePort = port;
          app.activeUsbLocalPort = port;
          openCastWebSocket(app, `ws://127.0.0.1:${port}/ws`, '127.0.0.1', port, true);
          return;
        }
        await flushPendingCustomAdbPath(app);
        const status = await app.desktop.refreshAdbStatus();
        if (status.available) {
          const devices = await app.desktop.scanAdbDevices();
          const chosen =
            devices.find((d) => d.serial === app.selectedAdbSerial && d.state === 'device') ||
            devices.find((d) => d.state === 'device');
          if (!chosen) throw new Error(t('cast.errNoUsbDevice'));
          $('connectCastBtn').textContent = t('cast.usbBootstrappingBtn');
          const fwd = await app.desktop.bootstrapAndForwardUsb(chosen.serial);
          app.selectedAdbSerial = chosen.serial;
          app.activeHost = '127.0.0.1';
          app.activePort = fwd.localPort;
          app.activeUsbLocalPort = fwd.localPort;
          openCastWebSocket(app, fwd.localWsUrl, '127.0.0.1', fwd.localPort, true);
          return;
        }
        app.activeHost = '127.0.0.1';
        app.activePort = port;
        openCastWebSocket(app, `ws://127.0.0.1:${port}/ws`, '127.0.0.1', port, true);
        return;
      }

      const tunnel = await ensureDesktopTlsTunnel(app, host, port, '/ws', app.explicitCertPin);
      if (!tunnel) {
        $('connectCastBtn').disabled = false;
        $('connectCastBtn').textContent = defaultBtnLabel;
        return;
      }
      app.activeHost = host;
      app.activePort = port;
      app.activeVerifiedFingerprint = tunnel.verifiedFingerprint;
      openCastWebSocket(app, tunnel.localWsUrl, host, port, false);
    } catch (err) {
      $('connectCastBtn').disabled = false;
      $('connectCastBtn').textContent = defaultBtnLabel;
      app.setCastError(String(err?.message || err || t('cast.errDesktopConnectFailed')));
    }
    return;
  }

  if (!isUsb) {
    const probe = await probeHttpsHealth(host, port);
    if (!probe.ok) {
      $('connectCastBtn').disabled = false;
      $('connectCastBtn').textContent = defaultBtnLabel;
      if (probe.needsTrust) {
        $('castTrustBox').hidden = false;
        $('castTrustMsg').textContent = t('cast.trustProbeMsg', { host, port });
      } else {
        app.setCastError(t('cast.errProbeFailed', { host, port, error: probe.error }));
      }
      return;
    }
  }

  app.activeHost = host;
  app.activePort = port;
  const wsUrl = isUsb ? `ws://${host}:${port}/ws` : `wss://${host}:${port}/ws`;
  openCastWebSocket(app, wsUrl, host, port, isUsb);
}

export function openCastWebSocket(app, wsUrl, host, port, isUsb) {
  if (app.sessionRole === SESSION_ROLE_DESKTOP) {
    app.companion.disconnect('', { quiet: true });
  }
  teardownCastSocket(app);
  let ws;
  try {
    ws = new WebSocket(wsUrl);
  } catch (err) {
    $('connectCastBtn').disabled = false;
    $('connectCastBtn').textContent = app.desktop.enabled ? t('cast.connectDesktopBtn') : t('cast.connectWebBtn');
    const detail = err?.message || String(err || '');
    if (isUsb) {
      app.setCastError(
        isSafariBrowser()
          ? t('cast.errSafariUsb', { port, detail })
          : t('cast.errWsInitUsb', { url: wsUrl, detail })
      );
    } else {
      app.setCastError(t('cast.errWsInit', { url: wsUrl, detail }));
    }
    return;
  }
  ws.binaryType = 'arraybuffer';
  app.ws = ws;

  const reqSpec = computeInitialStreamSpec(app);
  const savedToken = isUsb
    ? ''
    : ((app.desktop.enabled && app.desktop.getTrustedToken(host, port)) || getSavedToken(host, port));
  const wantAudio = shouldRequestSessionAudio(app.sessionRole, Boolean($('castAudioCheck')?.checked));
  app.audio.setMuted(!wantAudio);
  app.enterStageView(host, !savedToken && !isUsb);

  ws.onopen = () => {
    app.connected = true;
    const helloPayload = encodeHello({
      clientName: detectBrowserName(),
      clientId: app.clientId,
      preferredCodec: $('castCodecSelect').value || 'h264',
      width: reqSpec.width,
      height: reqSpec.height,
      dpi: reqSpec.dpi,
      fps: reqSpec.fps,
      bitrateKbps: reqSpec.bitrateKbps,
      displayMode: DISPLAY_MODE_DEX,
      audioEnabled: wantAudio,
      authToken: savedToken,
      transportHint: isUsb ? 'usb' : 'wifi_tls',
      clientPlatform: app.desktop.enabled ? 'windows' : 'web',
      sessionRole: app.sessionRole,
      appPackage: app.standaloneAppPkg,
      appActivity: app.standaloneAppCls,
      appUserId: app.standaloneAppUserId,
    });
    app.sendOpalFrame(TYPE_HELLO, 0, helloPayload);
  };

  ws.onmessage = (ev) => {
    if (!(ev.data instanceof ArrayBuffer)) return;
    let frame;
    try {
      frame = decodeFrame(ev.data);
    } catch {
      return;
    }
    app.handleDownlinkFrame(frame);
  };

  ws.onerror = () => {
    if (!app.authenticated) {
      if (isUsb) {
        const usbTip = app.desktop.enabled
          ? t('cast.errUsbHandshakeDesktop', { port })
          : t('cast.errUsbHandshakeWeb', { port });
        disconnectCast(app, usbTip);
      } else {
        disconnectCast(app, t('cast.errWsHandshake'));
        if (!app.desktop.enabled) $('castTrustBox').hidden = false;
      }
    }
  };

  ws.onclose = (ev) => {
    const wasAuth = app.authenticated;
    if (!app.ws) return;
    const defaultFail = isUsb ? t('cast.errUsbSessionFail', { port }) : t('cast.errSessionFail');
    const reason = ev.reason || (wasAuth ? t('cast.errDisconnected') : defaultFail);
    disconnectCast(app, reason);
  };
}

export function computeInitialStreamSpec(app) {
  const isStandalone = app.sessionRole === SESSION_ROLE_STANDALONE_APP;
  const isWired = app.transportMode === 'usb';
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const vw = Math.max(640, Math.round(window.innerWidth * dpr));
  const vh = Math.max(480, Math.round(window.innerHeight * dpr));
  let width;
  let height;
  let standaloneGeo = null;
  if (isStandalone) {
    standaloneGeo = resolvePopoutLaunchGeometry(app.standalone.getCurrentStandalonePref());
    width = standaloneGeo.streamWidth;
    height = standaloneGeo.streamHeight;
  } else if (app.resizeMode && app.resizeMode !== 'auto') {
    const [pw, ph] = app.resizeMode.split('x').map(Number);
    width = pw || 1920;
    height = ph || 1080;
  } else {
    const maxW = isWired ? 3840 : 2560;
    const maxH = isWired ? 2160 : 1600;
    const fitted = fitDimensionsToMaxBounds(vw, vh, maxW, maxH, 8);
    width = fitted.width;
    height = fitted.height;
  }
  const savedDpi = Number($('liveDpiSelect')?.value) || 0;
  const dpi = isStandalone
    ? (standaloneGeo?.dpi || 360)
    : (savedDpi > 0 ? savedDpi : Math.max(160, Math.min(320, Math.round(160 * dpr))));
  const fps = Number($('castFpsSelect').value) || 60;
  const brCfg = app.resolveSelectedBitrateConfig('castBitrateSelect', 'castCustomBitrateInput');
  app.bitrateMode = brCfg.mode;
  app.autoAdaptiveBitrateKbps = 0;
  app.autoHealthyStreak = 0;
  return { width, height, dpi, fps, bitrateKbps: brCfg.bitrateKbps };
}

export function applyLiveResizeConfig(app, { bitrateOverrideKbps = null, silent = false } = {}) {
  if (!app.authenticated) return;
  let w;
  let h;
  if (app.resizeMode === 'auto') {
    const rect = $('canvasWrap').getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const isStandalone = app.sessionRole === SESSION_ROLE_STANDALONE_APP;
    const isWired = app.transportMode === 'usb';
    const minW = isStandalone ? 480 : 640;
    const rawW = Math.max(minW, Math.round(rect.width * dpr));
    const rawH = Math.max(480, Math.round(rect.height * dpr));
    const maxW = isStandalone ? 2560 : (isWired ? 3840 : 2560);
    const maxH = isStandalone ? 2560 : (isWired ? 2160 : 1600);
    const fitted = fitDimensionsToMaxBounds(rawW, rawH, maxW, maxH, 8);
    w = fitted.width;
    h = fitted.height;
  } else {
    const [pw, ph] = app.resizeMode.split('x').map(Number);
    w = pw || 1920;
    h = ph || 1080;
  }
  const fps = Number($('liveFpsSelect').value) || Number($('castFpsSelect').value) || 60;
  const dpi = Number($('liveDpiSelect')?.value) || 0;
  let bitrateKbps;
  if (typeof bitrateOverrideKbps === 'number' && bitrateOverrideKbps > 0) {
    bitrateKbps = bitrateOverrideKbps;
  } else {
    const brCfg = app.resolveSelectedBitrateConfig('liveBitrateSelect', 'liveCustomBitrateInput', {
      notifyOnClamp: !silent,
    });
    app.bitrateMode = brCfg.mode;
    bitrateKbps = brCfg.bitrateKbps;
    if (brCfg.mode === 'auto') {
      app.autoAdaptiveBitrateKbps = 0;
      app.autoHealthyStreak = 0;
    }
    if ($('castFpsSelect')?.querySelector(`option[value="${fps}"]`)) {
      $('castFpsSelect').value = String(fps);
    }
    if ($('castBitrateSelect')) {
      $('castBitrateSelect').value = brCfg.mode === 'auto'
        ? 'auto'
        : (brCfg.mode === 'custom' ? 'custom' : String(bitrateKbps));
    }
    app.syncBitrateInputsUi('both');
    app.persistCastPrefs({
      targetFps: fps,
      bitrateMode: brCfg.mode,
      bitrateKbps,
      resizeMode: app.resizeMode,
      liveDpi: dpi,
    });
  }
  app.sendOpalFrame(TYPE_RESIZE_CONFIG, 0, encodeResizeConfig({ width: w, height: h, dpi, fps, bitrateKbps }));
}

export async function sendDroppedFiles(app, files) {
  for (const file of files) {
    const transferId = app.transferSeq++;
    app.showToast(t('cast.fileSendingToast', { name: file.name, mb: (file.size / 1024 / 1024).toFixed(2) }));
    const buf = new Uint8Array(await file.arrayBuffer());
    let offset = 0;
    let isFirst = true;
    while (offset < buf.byteLength || isFirst) {
      const end = Math.min(buf.byteLength, offset + FILE_CHUNK_SIZE);
      const slice = buf.subarray(offset, end);
      let flags = FILE_FLAG_DATA;
      if (isFirst) flags |= FILE_FLAG_START;
      if (end >= buf.byteLength) flags |= FILE_FLAG_END;
      app.sendOpalFrame(
        TYPE_FILE_TRANSFER_CHUNK,
        0,
        encodeFileTransferChunk(transferId, flags, {
          fileName: file.name,
          totalSize: buf.byteLength,
          data: slice,
        })
      );
      isFirst = false;
      offset = end;
      if (app.ws && app.ws.bufferedAmount > 2 * 1024 * 1024) {
        await new Promise((r) => setTimeout(r, 30));
      }
    }
  }
}

export function disconnectCast(app, reasonMessage = '') {
  if (app.ws && app.ws.readyState === WebSocket.OPEN && app.authenticated) {
    try {
      app.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_DISCONNECT));
    } catch {
      // Ignore send race
    }
  }
  app.mic.stop();
  app.micEnabled = false;
  app.syncMicUi();
  app.camera.stop();
  app.cameraEnabled = false;
  app.syncCameraUi();
  app.blackoutActive = false;
  app.remoteFullscreenTitle = '';
  app.injectStatus = 0;
  app.inputBlockedWarned = false;
  app.autoAdaptiveBitrateKbps = 0;
  app.autoHealthyStreak = 0;
  if (app.recorder?.isRecording) {
    app.recorder.stop();
  }
  app.fileBrowserClient?.reset(t('cast.errDisconnected'));
  app.syncBlackoutUi();
  app.syncRemoteFullscreenUi();
  teardownCastSocket(app);
  app.companion?.renderCompanionHub();
  app.input.setEnabled(false);
  app.video.reset();
  app.audio.reset();
  $('stageView').hidden = true;
  $('lobbyView').hidden = false;
  $('approvalOverlay').hidden = true;
  $('qualityPopover').hidden = true;
  $('appsPopover').hidden = true;
  $('connectCastBtn').disabled = false;
  $('connectCastBtn').textContent = app.desktop.enabled ? t('cast.connectDesktopBtn') : t('cast.connectWebBtn');
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {});
  }
  if (reasonMessage) {
    app.setCastError(reasonMessage);
  }
}

export function teardownCastSocket(app) {
  clearInterval(app.pingTimer);
  app.pingTimer = null;
  clearTimeout(app.toolbarFadeTimer);
  clearTimeout(app.revealHoverTimer);
  app.revealHoverTimer = null;
  clearTimeout(app.resizeDebounceTimer);
  app.pingSentMap.clear();
  app.connected = false;
  app.authenticated = false;
  if (app.ws) {
    app.ws.onopen = null;
    app.ws.onmessage = null;
    app.ws.onerror = null;
    app.ws.onclose = null;
    try { app.ws.close(1000, 'bye'); } catch {}
    app.ws = null;
  }
}

export function startHeartbeat(app) {
  clearInterval(app.pingTimer);
  app.pingTimer = setInterval(() => {
    if (!app.authenticated) return;
    const id = app.pingIdSeq++;
    app.pingSentMap.set(id, performance.now());
    if (app.pingSentMap.size > 16) {
      const firstKey = app.pingSentMap.keys().next().value;
      app.pingSentMap.delete(firstKey);
    }
    app.sendOpalFrame(TYPE_PING, 0, encodePingPong(id));
    evaluateAutoAdaptiveBitrate(app);
  }, 3000);
}

export function evaluateAutoAdaptiveBitrate(app) {
  if (!app.authenticated || app.bitrateMode !== 'auto') return;
  const targetFps = Number($('liveFpsSelect')?.value) || Number($('castFpsSelect')?.value) || 60;
  const step = computeAdaptiveAutoBitrateStep({
    currentKbps: app.autoAdaptiveBitrateKbps,
    targetFps,
    actualFps: app.remoteFps,
    rttMs: app.lastRttMs,
    isWired: app.transportMode === 'usb',
    width: app.remoteWidth,
    height: app.remoteHeight,
    preferHevc: app.remoteCodecId === VIDEO_CODEC_HEVC,
    healthyStreak: app.autoHealthyStreak,
  });
  app.autoHealthyStreak = step.healthyStreak;
  if (app.autoAdaptiveBitrateKbps <= 0) {
    app.autoAdaptiveBitrateKbps = step.nextKbps;
  }
  if (step.changed) {
    app.autoAdaptiveBitrateKbps = step.nextKbps;
    applyLiveResizeConfig(app, { bitrateOverrideKbps: step.nextKbps, silent: true });
    app.updateTelemetry();
  }
}
