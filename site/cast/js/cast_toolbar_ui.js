/**
 * Stage toolbar, popovers, remote mic/camera/blackout/audio/fullscreen controls,
 * keyboard shortcuts, and recent devices bar for OpalDesk Web Cast.
 */

import { DEFAULT_CAMERA_FPS, DEFAULT_CAMERA_HEIGHT, DEFAULT_CAMERA_WIDTH } from './camera_capturer.js';
import { formatShortFingerprint } from './desktop_bridge.js';
import { captureCanvasScreenshot } from './media_capture.js';
import { DEFAULT_MIC_CHANNELS, DEFAULT_MIC_SAMPLE_RATE } from './mic_capturer.js';
import {
  encodeCameraConfig,
  encodeMicConfig,
  encodeSystemAction,
  SESSION_ROLE_STANDALONE_APP,
  SYS_BACK,
  SYS_HOME,
  SYS_SET_AUDIO_ENABLED,
  SYS_TOGGLE_BLACKOUT,
  TYPE_CAMERA_CONFIG,
  TYPE_LIST_APPS,
  TYPE_MIC_CONFIG,
  TYPE_SYSTEM_ACTION,
  VIDEO_CODEC_HEVC,
} from './protocol.js';
import { loadSavedDevices, REVEAL_HANDLE_HOVER_DELAY_MS } from './cast_storage.js';
import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);

export function bindStageAndGlobalEvents(app) {
  $('cancelConnectBtn').addEventListener('click', () => {
    if (app.sessionRole === SESSION_ROLE_STANDALONE_APP && app.desktop.enabled) {
      app.disconnectCast('');
      app.desktop.closeCurrentWindow().catch(() => {});
      return;
    }
    app.disconnectCast(t('cast.cancelledToast'));
  });
  $('tbDisconnectBtn').addEventListener('click', () => {
    if (app.sessionRole === SESSION_ROLE_STANDALONE_APP && app.desktop.enabled) {
      app.disconnectCast('');
      app.desktop.closeCurrentWindow().catch(() => {});
      return;
    }
    app.disconnectCast(t('cast.disconnectedToast'));
  });
  $('tbBackBtn').addEventListener('click', () => {
    app.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_BACK));
    app.input.focusIme();
  });
  $('tbHomeBtn').addEventListener('click', () => {
    app.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_HOME));
    if (app.input.virtualKeyboardDevice) {
      app.input.dismissSoftKeyboard();
    } else {
      app.input.focusIme();
    }
  });
  $('tbExitAppFsBtn')?.addEventListener('click', () => {
    app.remoteFullscreenTitle = '';
    syncRemoteFullscreenUi(app);
    app.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_HOME));
    bumpToolbarVisibility(app);
    app.input.focusIme();
  });
  $('tbBlackoutBtn')?.addEventListener('click', () => {
    toggleBlackout(app);
    bumpToolbarVisibility(app);
    app.input.focusIme();
  });
  $('tbFilesBtn')?.addEventListener('click', () => {
    $('qualityPopover').hidden = true;
    $('appsPopover').hidden = true;
    app.fileBrowser.open('files');
  });
  $('tbDialerBtn')?.addEventListener('click', () => {
    $('qualityPopover').hidden = true;
    $('appsPopover').hidden = true;
    app.companion.openDialerModal();
  });
  $('tbScreenshotBtn')?.addEventListener('click', async () => {
    try {
      const label = app.sessionRole === SESSION_ROLE_STANDALONE_APP && app.standaloneAppLabel
        ? app.standaloneAppLabel
        : 'OpalDesk';
      const res = await captureCanvasScreenshot($('castCanvas'), { desktop: app.desktop, label });
      const clipSuffix = res.copiedToClipboard ? t('cast.screenshotClipboardSuffix') : '';
      if (res.savedPath && app.desktop.enabled) {
        app.showToast(t('cast.screenshotSavedDesktop', { suffix: clipSuffix }), {
          actionText: t('cast.openLocationAction'),
          actionFn: () => void app.desktop.revealInExplorer(res.savedPath),
        });
      } else {
        app.showToast(t('cast.screenshotSavedFile', { name: res.fileName, suffix: clipSuffix }));
      }
    } catch (err) {
      app.showToast(err?.message || t('cast.screenshotFailed'));
    }
    bumpToolbarVisibility(app);
    app.input.focusIme();
  });
  $('tbRecordBtn')?.addEventListener('click', () => {
    app.recorder.toggle();
    bumpToolbarVisibility(app);
    app.input.focusIme();
  });
  $('tbInputModeBtn').addEventListener('click', () => {
    const nextTouch = !app.input.primaryLeftAsTouch;
    app.input.setPrimaryLeftAsTouch(nextTouch);
    const btn = $('tbInputModeBtn');
    btn.textContent = nextTouch ? t('stage.touchBtn') : t('stage.mouseBtn');
    btn.classList.toggle('active', nextTouch);
    app.showToast(nextTouch ? t('cast.touchModeToast') : t('cast.mouseModeToast'));
    app.input.focusIme();
  });
  $('tbKeyboardBtn').addEventListener('click', () => {
    const open = app.input.toggleSoftKeyboard();
    syncKeyboardUi(open);
    bumpToolbarVisibility(app);
  });
  $('tbMicBtn').addEventListener('click', async () => {
    await toggleRemoteMic(app);
    bumpToolbarVisibility(app);
    app.input.focusIme();
  });
  $('tbCameraBtn').addEventListener('click', async () => {
    await toggleRemoteCamera(app);
    bumpToolbarVisibility(app);
    app.input.focusIme();
  });
  $('tbAudioBtn').addEventListener('click', async () => {
    const wasSuspended = Boolean(app.audio.audioCtx && app.audio.audioCtx.state === 'suspended');
    await app.audio.resumeFromGesture();
    if (wasSuspended && !app.audio.muted) {
      app.input.focusIme();
      return;
    }
    const nextMuted = !app.audio.muted;
    app.audio.setMuted(nextMuted);
    app.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_SET_AUDIO_ENABLED, nextMuted ? 0 : 1));
    app.input.focusIme();
  });
  $('tbFullscreenBtn').addEventListener('click', () => {
    toggleFullscreen();
    app.input.focusIme();
  });
  document.addEventListener('fullscreenchange', () => syncFullscreenUi());

  // Quality popover
  $('tbQualityBtn').addEventListener('click', () => {
    $('appsPopover').hidden = true;
    $('qualityPopover').hidden = !$('qualityPopover').hidden;
    if ($('qualityPopover').hidden) app.input.focusIme();
  });
  $('closeQualityPopBtn').addEventListener('click', () => {
    $('qualityPopover').hidden = true;
    app.input.focusIme();
  });
  $('qualityPopover').querySelectorAll('[data-preset]').forEach((btn) => {
    btn.addEventListener('click', () => {
      app.resizeMode = btn.dataset.preset;
      app.syncResizePresetUi();
      app.persistCastPrefs({ resizeMode: app.resizeMode });
    });
  });
  $('applyQualityBtn').addEventListener('click', () => {
    app.applyLiveResizeConfig();
    $('qualityPopover').hidden = true;
    app.input.focusIme();
  });
  $('hideLocalCursorCheck')?.addEventListener('change', (e) => {
    app.hideLocalCursor = Boolean(e.target.checked);
    app.syncLocalCursorUi();
    app.persistCastPrefs({ hideLocalCursor: app.hideLocalCursor });
    app.showToast(app.hideLocalCursor ? t('cast.hideCursorOnToast') : t('cast.hideCursorOffToast'));
  });

  // App launcher popover
  $('tbAppsBtn').addEventListener('click', () => {
    $('qualityPopover').hidden = true;
    const opening = $('appsPopover').hidden;
    $('appsPopover').hidden = !opening;
    if (opening) {
      app.sendOpalFrame(TYPE_LIST_APPS);
      if (!app.input.virtualKeyboardDevice) {
        $('appSearchInput').focus();
      }
    } else {
      app.input.focusIme();
    }
  });
  $('closeAppsPopBtn').addEventListener('click', () => {
    $('appsPopover').hidden = true;
    app.input.focusIme();
  });
  $('appSearchInput').addEventListener('input', () => app.renderAppList());

  // Toast action button
  $('toastActionBtn').addEventListener('click', () => {
    if (app.toastActionFn) app.toastActionFn();
    $('toastIsland').hidden = true;
  });

  // Stage toolbar auto-hide + reveal handle
  $('stageRevealHandle').addEventListener('pointerenter', () => {
    clearTimeout(app.revealHoverTimer);
    app.revealHoverTimer = setTimeout(() => bumpToolbarVisibility(app), REVEAL_HANDLE_HOVER_DELAY_MS);
  });
  $('stageRevealHandle').addEventListener('pointerleave', () => {
    clearTimeout(app.revealHoverTimer);
    app.revealHoverTimer = null;
  });
  $('stageRevealHandle').addEventListener('click', () => {
    clearTimeout(app.revealHoverTimer);
    app.revealHoverTimer = null;
    bumpToolbarVisibility(app);
  });
  $('stageToolbar').addEventListener('pointerleave', () => bumpToolbarVisibility(app));

  // Drag-drop visual cue
  let dragDepth = 0;
  $('canvasWrap').addEventListener('dragenter', (e) => {
    if (!app.authenticated) return;
    e.preventDefault();
    dragDepth++;
    $('dropOverlay').hidden = false;
  });
  $('canvasWrap').addEventListener('dragleave', () => {
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) $('dropOverlay').hidden = true;
  });
  $('canvasWrap').addEventListener('drop', () => {
    dragDepth = 0;
    $('dropOverlay').hidden = true;
  });

  // Backdrop-click dismiss for secondary modals
  $('lobbyAppsModalOverlay')?.addEventListener('click', (e) => {
    if (e.target === $('lobbyAppsModalOverlay')) $('closeLobbyAppsModalBtn')?.click();
  });
  $('feedbackModalOverlay')?.addEventListener('click', (e) => {
    if (e.target === $('feedbackModalOverlay')) $('fbCancelBtn')?.click();
  });
  $('fileManagerOverlay')?.addEventListener('click', (e) => {
    if (e.target === $('fileManagerOverlay')) $('closeFileManagerBtn')?.click();
  });
  $('fmPreviewOverlay')?.addEventListener('click', (e) => {
    if (e.target === $('fmPreviewOverlay')) $('fmPreviewCloseBtn')?.click();
  });

  bindGlobalShortcuts(app);
}

function bindGlobalShortcuts(app) {
  window.addEventListener('keydown', (e) => {
    const targetTag = (e.target && e.target.tagName) ? e.target.tagName.toUpperCase() : '';
    const isTyping = targetTag === 'INPUT' || targetTag === 'TEXTAREA' || targetTag === 'SELECT';

    if (e.key === 'Escape') {
      const dismissOrder = [
        ['fmPreviewOverlay', 'fmPreviewCloseBtn'],
        ['fileManagerOverlay', 'closeFileManagerBtn'],
        ['dialerModalOverlay', 'closeDialerModalBtn'],
        ['lobbyAppsModalOverlay', 'closeLobbyAppsModalBtn'],
        ['feedbackModalOverlay', 'fbCancelBtn'],
      ];
      for (const [overlayId, closeBtnId] of dismissOrder) {
        if ($(overlayId) && !$(overlayId).hidden) {
          e.preventDefault();
          $(closeBtnId)?.click();
          return;
        }
      }
      if ($('qualityPopover') && !$('qualityPopover').hidden) {
        e.preventDefault();
        $('qualityPopover').hidden = true;
        app.input.focusIme();
        return;
      }
      if ($('appsPopover') && !$('appsPopover').hidden) {
        e.preventDefault();
        $('appsPopover').hidden = true;
        app.input.focusIme();
        return;
      }
    }

    if (!app.authenticated && !isTyping && (e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey) {
      if (e.key === '1') { e.preventDefault(); app.selectLobbyTab('cast'); return; }
      if (e.key === '2') { e.preventDefault(); app.selectLobbyTab('activate'); return; }
      if (e.key === '3') { e.preventDefault(); app.selectLobbyTab('logs'); return; }
    }

    if (!isTyping && (e.ctrlKey || e.metaKey) && e.shiftKey && !e.altKey && e.code === 'KeyF') {
      if (app.companion?.hasControlChannel?.()) {
        e.preventDefault();
        app.fileBrowser.open('files');
        return;
      }
    }

    if (app.authenticated && !isTyping && (e.ctrlKey || e.metaKey) && e.shiftKey && !e.altKey) {
      if (e.code === 'KeyS') { e.preventDefault(); $('tbScreenshotBtn')?.click(); return; }
      if (e.code === 'KeyV') { e.preventDefault(); $('tbRecordBtn')?.click(); return; }
    }

    if (!app.authenticated || !e.shiftKey || !(e.altKey || e.metaKey)) return;
    if (e.code === 'KeyT' && app.desktop.enabled) {
      e.preventDefault();
      app.standalone.toggleCurrentWindowAlwaysOnTop();
    } else if (e.code === 'KeyB' && app.sessionRole !== SESSION_ROLE_STANDALONE_APP) {
      e.preventDefault();
      toggleBlackout(app);
    } else if (e.code === 'KeyM') {
      e.preventDefault();
      $('tbAudioBtn')?.click();
    } else if (e.code === 'KeyH' && app.sessionRole !== SESSION_ROLE_STANDALONE_APP) {
      e.preventDefault();
      app.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_HOME));
    }
  });
}

export function enterStageView(app, peerLabel, showApprovalWait) {
  const isStandalone = app.sessionRole === SESSION_ROLE_STANDALONE_APP;
  $('lobbyView').hidden = true;
  $('stageView').hidden = false;
  $('stagePeerName').textContent = isStandalone && app.standaloneAppLabel ? app.standaloneAppLabel : peerLabel;
  $('approvalOverlay').hidden = !showApprovalWait;
  $('tbHomeBtn').hidden = isStandalone;
  if ($('tbBlackoutBtn')) $('tbBlackoutBtn').hidden = isStandalone;
  $('tbAppsBtn').hidden = isStandalone;
  $('tbAudioBtn').hidden = false;
  if ($('tbDisconnectBtn')) {
    $('tbDisconnectBtn').textContent = isStandalone && app.desktop.enabled ? t('stage.closeWindowBtn') : t('stage.disconnectBtn');
    $('tbDisconnectBtn').title = isStandalone && app.desktop.enabled ? t('stage.closeWindowTitle') : t('stage.disconnectTitle');
  }
  app.standalone.syncPinButtonUi();
  $('liveFpsSelect').value = $('castFpsSelect').value;
  $('liveBitrateSelect').value = $('castBitrateSelect').value;
  app.syncBitrateInputsUi('live');
  $('connectCastBtn').disabled = false;
  $('connectCastBtn').textContent = app.desktop.enabled ? t('cast.connectDesktopBtn') : t('cast.connectWebBtn');
}

export function updateTelemetry(app) {
  const parts = [`${app.remoteWidth}×${app.remoteHeight}`];
  if (app.remoteFps > 0) parts.push(`${app.remoteFps}fps`);
  if (app.lastRttMs > 0) parts.push(`${app.lastRttMs}ms`);
  parts.push(app.remoteCodecId === VIDEO_CODEC_HEVC ? 'HEVC' : 'H.264');
  if (app.bitrateMode === 'auto') {
    parts.push(
      app.autoAdaptiveBitrateKbps > 0
        ? t('stage.telemetryAutoBitrate', { mbps: Math.round(app.autoAdaptiveBitrateKbps / 1000) })
        : t('stage.telemetryAutoBitrateShort')
    );
  }
  if (app.injectStatus === 2) parts.push('UHID');
  else if (app.injectStatus === -1) parts.push(t('stage.telemetryTouchLimited'));
  $('stageTelemetry').textContent = parts.join(' · ');
}

export function toggleBlackout(app) {
  if (!app.authenticated || app.sessionRole === SESSION_ROLE_STANDALONE_APP) return;
  app.blackoutActive = !app.blackoutActive;
  syncBlackoutUi(app);
  app.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_TOGGLE_BLACKOUT, app.blackoutActive ? 1 : 0));
  app.showToast(app.blackoutActive ? t('cast.blackoutOnToast') : t('cast.blackoutOffToast'));
}

export function syncBlackoutUi(app) {
  const btn = $('tbBlackoutBtn');
  if (!btn) return;
  btn.classList.toggle('active', Boolean(app.blackoutActive));
  btn.textContent = app.blackoutActive ? t('stage.blackoutOnBtn') : t('stage.blackoutBtn');
}

export async function toggleRemoteMic(app) {
  if (!app.authenticated) {
    app.showToast(t('cast.micNeedConnectToast'));
    return;
  }
  if (app.micEnabled) {
    disableRemoteMic(app, true);
    return;
  }
  const ok = await app.mic.start();
  if (!ok) return;
  app.micEnabled = true;
  syncMicUi(app);
  app.sendOpalFrame(TYPE_MIC_CONFIG, 0, encodeMicConfig({
    enabled: true,
    codec: 'pcm16',
    sampleRate: DEFAULT_MIC_SAMPLE_RATE,
    channels: DEFAULT_MIC_CHANNELS,
  }));
}

export function disableRemoteMic(app, notifyServer = true) {
  app.mic.stop();
  app.micEnabled = false;
  syncMicUi(app);
  if (notifyServer && app.authenticated) {
    app.sendOpalFrame(TYPE_MIC_CONFIG, 0, encodeMicConfig({
      enabled: false,
      codec: 'pcm16',
      sampleRate: DEFAULT_MIC_SAMPLE_RATE,
      channels: DEFAULT_MIC_CHANNELS,
    }));
  }
}

export function syncMicUi(app) {
  const btn = $('tbMicBtn');
  if (!btn) return;
  const on = Boolean(app.micEnabled);
  btn.classList.toggle('active-emerald', on);
  btn.classList.remove('active');
  btn.title = on ? t('stage.micOnTitle') : t('stage.micTitle');
  btn.setAttribute('aria-label', on ? t('stage.micOffAria') : t('stage.micAria'));
}

export async function toggleRemoteCamera(app) {
  if (!app.authenticated) {
    app.showToast(t('cast.camNeedConnectToast'));
    return;
  }
  if (app.cameraEnabled) {
    disableRemoteCamera(app, true);
    return;
  }
  const ok = await app.camera.start();
  if (!ok) return;
  app.cameraEnabled = true;
  syncCameraUi(app);
  app.sendOpalFrame(TYPE_CAMERA_CONFIG, 0, encodeCameraConfig({
    enabled: true,
    codec: 'mjpeg',
    width: DEFAULT_CAMERA_WIDTH,
    height: DEFAULT_CAMERA_HEIGHT,
    fps: DEFAULT_CAMERA_FPS,
    facing: 'front',
  }));
}

export function disableRemoteCamera(app, notifyServer = true) {
  app.camera.stop();
  app.cameraEnabled = false;
  syncCameraUi(app);
  if (notifyServer && app.authenticated) {
    app.sendOpalFrame(TYPE_CAMERA_CONFIG, 0, encodeCameraConfig({
      enabled: false,
      codec: 'mjpeg',
      width: DEFAULT_CAMERA_WIDTH,
      height: DEFAULT_CAMERA_HEIGHT,
      fps: DEFAULT_CAMERA_FPS,
      facing: 'front',
    }));
  }
}

export function syncCameraUi(app) {
  const btn = $('tbCameraBtn');
  if (!btn) return;
  const on = Boolean(app.cameraEnabled);
  btn.classList.toggle('active-emerald', on);
  btn.classList.remove('active');
  btn.title = on ? t('stage.camOnTitle') : t('stage.camTitle');
  btn.setAttribute('aria-label', on ? t('stage.camOffAria') : t('stage.camAria'));
}

export function syncKeyboardUi(open) {
  const btn = $('tbKeyboardBtn');
  if (!btn) return;
  const on = Boolean(open);
  btn.classList.toggle('active', on);
  btn.title = on ? t('stage.keyboardHideTitle') : t('stage.keyboardTitle');
  btn.setAttribute('aria-label', on ? t('stage.keyboardHideAria') : t('stage.keyboardAria'));
}

export function syncAudioUi(app, st) {
  const btn = $('tbAudioBtn');
  if (!btn) return;
  const onIcon = btn.querySelector('.tb-icon-sound-on');
  const offIcon = btn.querySelector('.tb-icon-sound-off');
  const lbl = btn.querySelector('.tb-inline-label');
  if (st.needsGesture) {
    if (onIcon) onIcon.hidden = false;
    if (offIcon) offIcon.hidden = true;
    if (lbl) {
      lbl.textContent = t('stage.audioUnlockLabel');
      lbl.hidden = false;
    }
    btn.classList.add('active');
    btn.title = t('stage.audioUnlockTitle');
    btn.setAttribute('aria-label', t('stage.audioUnlockLabel'));
    app.showToast(t('cast.audioGestureToast'), t('cast.audioUnlockAction'), () => app.audio.resumeFromGesture());
  } else if (st.muted) {
    if (onIcon) onIcon.hidden = true;
    if (offIcon) offIcon.hidden = false;
    if (lbl) lbl.hidden = true;
    btn.classList.remove('active');
    btn.title = t('stage.audioMutedTitle');
    btn.setAttribute('aria-label', t('stage.audioRestoreAria'));
  } else {
    if (onIcon) onIcon.hidden = false;
    if (offIcon) offIcon.hidden = true;
    if (lbl) lbl.hidden = true;
    btn.classList.remove('active');
    btn.title = t('stage.audioTitle');
    btn.setAttribute('aria-label', t('stage.audioAria'));
  }
}

export function syncFullscreenUi() {
  const btn = $('tbFullscreenBtn');
  if (!btn) return;
  const isFs = Boolean(document.fullscreenElement);
  const enterIcon = btn.querySelector('.tb-icon-fs-enter');
  const exitIcon = btn.querySelector('.tb-icon-fs-exit');
  if (enterIcon) enterIcon.hidden = isFs;
  if (exitIcon) exitIcon.hidden = !isFs;
  btn.classList.toggle('active', isFs);
  btn.title = isFs ? t('stage.fsExitTitle') : t('stage.fsEnterTitle');
  btn.setAttribute('aria-label', isFs ? t('stage.fsExitTitle') : t('stage.fsEnterTitle'));
}

export function syncRemoteFullscreenUi(app) {
  const btn = $('tbExitAppFsBtn');
  if (!btn) return;
  const active = Boolean(app.remoteFullscreenTitle) && app.sessionRole !== SESSION_ROLE_STANDALONE_APP;
  btn.hidden = !active;
  if (active) {
    btn.title = t('stage.exitAppFsTitleWithApp', { title: app.remoteFullscreenTitle });
  }
}

export function bumpToolbarVisibility(app) {
  const tb = $('stageToolbar');
  tb.classList.remove('dimmed');
  clearTimeout(app.toolbarFadeTimer);
  app.toolbarFadeTimer = setTimeout(() => {
    if ($('qualityPopover').hidden && $('appsPopover').hidden && !tb.matches(':hover')) {
      tb.classList.add('dimmed');
    }
  }, 2600);
}

export function toggleFullscreen() {
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {});
  } else {
    $('stageView').requestFullscreen().catch(() => {});
  }
}

export function renderRecentDevices(app) {
  const wrap = $('recentDevicesWrap');
  const container = $('recentDevicesList');
  if (app.desktop.enabled) {
    const list = app.desktop.trustedDevices;
    if (!list.length) {
      wrap.hidden = true;
      return;
    }
    wrap.hidden = false;
    container.innerHTML = '';
    for (const dev of list) {
      const group = document.createElement('div');
      group.className = 'recent-chip-group';

      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'recent-chip';
      const shortFp = formatShortFingerprint(dev.certFingerprint);
      btn.title = shortFp ? t('cast.recentFpTitle', { fp: shortFp }) : '';
      btn.textContent = `${dev.displayName || dev.host} (${dev.host}:${dev.port})`;
      btn.addEventListener('click', () => {
        app.selectTransportMode('wifi');
        $('castHostInput').value = dev.host;
        $('castPortInput').value = String(dev.port || 19878);
        app.startCastConnection();
      });

      const appBtn = document.createElement('button');
      appBtn.type = 'button';
      appBtn.className = 'recent-revoke-btn';
      appBtn.title = t('cast.recentStandaloneTitle');
      appBtn.textContent = t('cast.recentStandaloneBtn');
      appBtn.addEventListener('click', () => {
        app.standalone.openLobbyAppPicker({
          transport: 'wifi',
          host: dev.host,
          port: dev.port || 19878,
          deviceKey: `${dev.host}:${dev.port || 19878}`,
          deviceName: dev.displayName || dev.host,
          certFingerprint: dev.certFingerprint || '',
        });
      });

      const revokeBtn = document.createElement('button');
      revokeBtn.type = 'button';
      revokeBtn.className = 'recent-revoke-btn';
      revokeBtn.title = t('cast.recentRevokeTitle');
      revokeBtn.textContent = '×';
      revokeBtn.addEventListener('click', async () => {
        await app.desktop.revokeTrustedDevice(dev.id);
        renderRecentDevices(app);
        app.showToast(t('cast.recentRemovedToast', { name: dev.displayName || dev.host }));
      });

      group.append(btn, appBtn, revokeBtn);
      container.appendChild(group);
    }
    return;
  }

  const list = loadSavedDevices();
  if (!list.length) {
    wrap.hidden = true;
    return;
  }
  wrap.hidden = false;
  container.innerHTML = '';
  for (const dev of list) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'recent-chip';
    btn.textContent = `${dev.deviceName || dev.host} (${dev.host}:${dev.port})`;
    btn.addEventListener('click', () => {
      app.selectTransportMode('wifi');
      $('castHostInput').value = dev.host;
      $('castPortInput').value = String(dev.port || 19878);
      app.startCastConnection();
    });
    container.appendChild(btn);
  }
}
