/**
 * OPD1 Downlink frame router and remote clipboard handler for OpalDesk Web Cast.
 */

import {
  decodeAppList,
  decodeAudioConfig,
  decodeAuthResult,
  decodeCameraState,
  decodeClipboardPush,
  decodeDeviceState,
  decodeFileTransferAck,
  decodeImeRequest,
  decodeMicState,
  decodePingPong,
  decodeVideoConfig,
  encodeNotificationAction,
  FILE_ACK_OK,
  SESSION_ROLE_DESKTOP,
  SESSION_ROLE_STANDALONE_APP,
  TYPE_APP_LIST,
  TYPE_AUDIO_CONFIG,
  TYPE_AUDIO_FRAME,
  TYPE_AUTH_RESULT,
  TYPE_CALL_STATE,
  TYPE_CAMERA_STATE,
  TYPE_CLIPBOARD_PUSH,
  TYPE_DEVICE_STATE,
  TYPE_FILE_TRANSFER_ACK,
  TYPE_IME_REQUEST,
  TYPE_MEDIA_STATE,
  TYPE_MIC_STATE,
  TYPE_NOTIFICATION_ACTION,
  TYPE_NOTIFICATION_EVENT,
  TYPE_PONG,
  TYPE_VIDEO_CONFIG,
  TYPE_VIDEO_FRAME,
} from './protocol.js';
import { getSavedToken, saveDeviceRecord } from './cast_storage.js';
import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);

export function handleRemoteClipboardText(app, text) {
  if (document.hasFocus() && navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(
      () => app.showToast(t('cast.clipboardSyncedToast')),
      () => app.showToast(t('cast.clipboardReceivedToast'), t('cast.clipboardCopyAction'), () => app.copyText(text, t('cast.clipboardCopiedToast')))
    );
  } else {
    app.showToast(t('cast.clipboardReceivedToast'), t('cast.clipboardCopyAction'), () => app.copyText(text, t('cast.clipboardCopiedToast')));
  }
}

export function handleRemoteClipboardImage(app, pngData) {
  const writeImg = () => {
    if (typeof ClipboardItem === 'undefined' || !navigator.clipboard?.write) {
      app.showToast(t('cast.clipboardImgUnsupportedToast'));
      return;
    }
    const blob = new Blob([pngData], { type: 'image/png' });
    navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]).then(
      () => app.showToast(t('cast.clipboardImgSyncedToast')),
      () => app.showToast(t('cast.clipboardImgReceivedToast'), t('cast.clipboardImgCopyAction'), () => writeImg())
    );
  };
  if (document.hasFocus() && typeof ClipboardItem !== 'undefined' && navigator.clipboard?.write) {
    writeImg();
  } else {
    app.showToast(t('cast.clipboardImgReceivedToast'), t('cast.clipboardImgCopyAction'), () => writeImg());
  }
}

export function handleDownlinkFrame(app, frame) {
  if (app.fileBrowserClient?.handleFrame(frame)) return;
  switch (frame.type) {
    case TYPE_AUTH_RESULT: {
      const auth = decodeAuthResult(frame.payload);
      if (!auth.ok) {
        app.disconnectCast(auth.message || t('cast.errAuthRejected'));
        return;
      }
      app.authenticated = true;
      $('approvalOverlay').hidden = true;
      $('stagePeerName').textContent =
        app.sessionRole === SESSION_ROLE_STANDALONE_APP && app.standaloneAppLabel
          ? app.standaloneAppLabel
          : (auth.deviceName || app.activeHost);
      app.input.setEnabled(true);
      app.startHeartbeat();
      app.bumpToolbarVisibility();
      app.standalone.syncStandaloneToolbarPrefsUi();
      if (app.sessionRole === SESSION_ROLE_DESKTOP) {
        app.sendOpalFrame(TYPE_NOTIFICATION_ACTION, 0, encodeNotificationAction({ op: 'sync' }));
        app.companion.renderCompanionHub();
      }
      app.diagnostics?.log(
        'I',
        'Session',
        `连接认证通过 role=${app.sessionRole} peer=${auth.deviceName || app.activeHost} transport=${app.transportMode}`
      );

      if (app.transportMode !== 'usb') {
        const prevToken =
          (app.desktop.enabled && app.desktop.getTrustedToken(app.activeHost, app.activePort)) ||
          getSavedToken(app.activeHost, app.activePort);
        saveDeviceRecord({
          host: app.activeHost,
          port: app.activePort,
          deviceName: auth.deviceName || app.activeHost,
          authToken: auth.authToken || prevToken,
          permanent: auth.permanent || Boolean(prevToken),
        });
        if (app.desktop.enabled && app.activeVerifiedFingerprint) {
          app.desktop
            .upsertTrustedDevice({
              host: app.activeHost,
              port: app.activePort,
              displayName: auth.deviceName || app.activeHost,
              model: '',
              certFingerprint: app.activeVerifiedFingerprint,
              authToken: auth.authToken || prevToken,
              permanent: auth.permanent || Boolean(prevToken),
              lastConnectedAtMs: Date.now(),
            })
            .then(() => app.renderRecentDevices())
            .catch(() => {});
        }
        app.renderRecentDevices();
      }
      app.showToast(t('cast.connectedToast', { name: auth.deviceName || app.activeHost }));
      break;
    }

    case TYPE_VIDEO_CONFIG: {
      const cfg = decodeVideoConfig(frame.payload);
      app.remoteCodecId = cfg.codec;
      app.video.configure(cfg);
      break;
    }

    case TYPE_VIDEO_FRAME: {
      app.video.decodeFrame(frame.payload, frame.flags, frame.timestampUs);
      break;
    }

    case TYPE_AUDIO_CONFIG: {
      const acfg = decodeAudioConfig(frame.payload);
      app.audio.configure(acfg);
      break;
    }

    case TYPE_AUDIO_FRAME: {
      app.audio.decodeFrame(frame.payload, frame.timestampUs);
      break;
    }

    case TYPE_DEVICE_STATE: {
      try {
        const st = decodeDeviceState(frame.payload);
        if (st.deviceName) $('stagePeerName').textContent = st.deviceName;
        if (typeof st.blackout === 'boolean') {
          app.blackoutActive = st.blackout;
          app.syncBlackoutUi();
        }
        if (typeof st.fullscreenWindowTitle === 'string') {
          const nextFsTitle = st.fullscreenWindowTitle.trim();
          const enteredFs = !app.remoteFullscreenTitle && Boolean(nextFsTitle);
          app.remoteFullscreenTitle = nextFsTitle;
          app.syncRemoteFullscreenUi();
          if (enteredFs) app.bumpToolbarVisibility();
        }
        if (typeof st.injectStatus === 'number') {
          app.injectStatus = st.injectStatus;
          app.updateTelemetry();
          if (st.injectStatus === -1 && !app.inputBlockedWarned) {
            app.inputBlockedWarned = true;
            app.showToast(t('cast.inputBlockedToast'));
          } else if (st.injectStatus > 0) {
            app.inputBlockedWarned = false;
          }
        }
        app.companion.handleDeviceState(st);
      } catch {
        // Ignore malformed telemetry
      }
      break;
    }

    case TYPE_NOTIFICATION_EVENT: {
      app.companion.handleNotificationFrame(frame.payload);
      break;
    }

    case TYPE_MEDIA_STATE: {
      app.companion.handleMediaStateFrame(frame.payload);
      break;
    }

    case TYPE_CALL_STATE: {
      app.companion.handleCallStateFrame(frame.payload);
      break;
    }

    case TYPE_IME_REQUEST: {
      try {
        const req = decodeImeRequest(frame.payload);
        app.input.handleRemoteImeRequest(req.show, req.inputType);
      } catch {
        // Ignore
      }
      break;
    }

    case TYPE_MIC_STATE: {
      try {
        const st = decodeMicState(frame.payload);
        if (!st.active) {
          app.mic.stop();
          app.micEnabled = false;
        } else {
          app.micEnabled = true;
        }
        app.syncMicUi();
        if (st.message) app.showToast(st.message);
      } catch {
        // Ignore
      }
      break;
    }

    case TYPE_CAMERA_STATE: {
      try {
        const st = decodeCameraState(frame.payload);
        if (!st.active) {
          app.camera.stop();
          app.cameraEnabled = false;
        } else {
          app.cameraEnabled = true;
          if ((st.facing === 'front' || st.facing === 'back') && app.camera.facing !== st.facing) {
            app.camera.start(st.facing).catch(() => {});
          }
        }
        app.syncCameraUi();
        if (st.message) app.showToast(st.message);
      } catch {
        // Ignore
      }
      break;
    }

    case TYPE_CLIPBOARD_PUSH: {
      try {
        const clip = decodeClipboardPush(frame.payload, frame.flags);
        if (clip.isImage && clip.pngData && clip.pngData.byteLength > 0) {
          handleRemoteClipboardImage(app, clip.pngData);
        } else if (!clip.isImage && clip.text) {
          handleRemoteClipboardText(app, clip.text);
        }
      } catch {
        // Ignore
      }
      break;
    }

    case TYPE_APP_LIST: {
      try {
        app.currentApps = decodeAppList(frame.payload);
        app.renderAppList();
      } catch {
        // Ignore
      }
      break;
    }

    case TYPE_FILE_TRANSFER_ACK: {
      try {
        const ack = decodeFileTransferAck(frame.payload);
        if (ack.status === FILE_ACK_OK) {
          app.showToast(t('cast.fileSentToast', { path: ack.pathOrError }));
        } else {
          app.showToast(t('cast.fileFailToast', { error: ack.pathOrError }));
        }
      } catch {
        // Ignore
      }
      break;
    }

    case TYPE_PONG: {
      try {
        const id = decodePingPong(frame.payload);
        const sentAt = app.pingSentMap.get(id);
        if (sentAt !== undefined) {
          app.pingSentMap.delete(id);
          app.lastRttMs = Math.max(1, Math.round(performance.now() - sentAt));
          app.updateTelemetry();
        }
      } catch {
        // Ignore
      }
      break;
    }
    default:
      break;
  }
}
