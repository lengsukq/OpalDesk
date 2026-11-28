/**
 * LAN Web Activation UI & client controller for OpalDesk Web Cast.
 */

import { LanActivationClient, probeHttpsHealth } from './activation_client.js';
import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);

export function createActivationController(app) {
  return new LanActivationClient({
    onStatus: (_st, msg) => appendActLog(msg),
    onAuthResult: (res) => {
      if (res.ok) {
        setActError('');
        $('actTrustBox').hidden = true;
        $('actWorkspace').hidden = false;
        $('disconnectActBtn').hidden = false;
        appendActLog(t('activate.authPassedLog'));
      } else {
        setActError(res.message || t('activate.errAuthFailed'));
        appendActLog(t('activate.authFailedLog', { msg: res.message || '' }));
      }
    },
    onState: (state) => renderActivationState(state),
    onProgress: (msg) => appendActLog(`▸ ${msg}`),
    onResult: (res) => {
      appendActLog(`${res.ok ? '✓' : '✗'} ${res.message || ''}`);
      setActBusy(false);
      if (!res.ok) setActError(res.message || t('activate.errNotSucceeded'));
    },
    onReadyForCast: (info) => {
      appendActLog(`★ ${info.message || 'Ready'}`);
      if (info.ok && info.host) {
        app.showToast(t('activate.readyForCastToast'));
        app.activation.disconnect();
        $('castHostInput').value = info.host;
        $('castPortInput').value = String(info.castPort || 19878);
        app.selectLobbyTab('cast');
        app.selectTransportMode('wifi');
        setTimeout(() => app.startCastConnection(), 450);
      }
    },
    onError: (msg) => {
      setActError(msg);
      appendActLog(`! ${msg}`);
    },
    onClose: (reason) => {
      appendActLog(t('activate.closeLog', { reason }));
      $('disconnectActBtn').hidden = true;
    },
  });
}

export function bindActivationEvents(app) {
  $('connectActBtn').addEventListener('click', () => startActivationConnection(app));
  $('disconnectActBtn').addEventListener('click', () => {
    app.activation.disconnect();
    $('actWorkspace').hidden = true;
  });
  $('openActTrustBtn').addEventListener('click', () => {
    const host = $('actHostInput').value.trim();
    const port = Number($('actPortInput').value) || 19879;
    app.openTrustPopup(host, port);
  });
  $('actPairStartBtn').addEventListener('click', () => {
    const pairPort = Number($('adbPairPortInput').value) || 0;
    const pairCode = $('adbPairCodeInput').value.trim();
    const connectPort = Number($('adbConnPortInput').value) || 0;
    if (!pairPort || pairCode.length !== 6) {
      setActError(t('activate.errPairRequired'));
      return;
    }
    setActError('');
    setActBusy(true);
    app.activation.submitPairAndStart({
      pairPort,
      pairCode,
      connectPort,
      autoEnableCast: $('actAutoCastCheck').checked,
    });
  });
  $('actDirectConnBtn').addEventListener('click', () => {
    const connectPort = Number($('adbConnPortInput').value) || 0;
    if (!connectPort) {
      setActError(t('activate.errConnPortRequired'));
      return;
    }
    setActError('');
    setActBusy(true);
    app.activation.submitDirectConnect({
      connectPort,
      autoEnableCast: $('actAutoCastCheck').checked,
    });
  });
  $('actShizukuBtn').addEventListener('click', () => {
    setActError('');
    setActBusy(true);
    app.activation.submitShizukuActivate({ autoEnableCast: $('actAutoCastCheck').checked });
  });
  $('actRefreshStateBtn').addEventListener('click', () => app.activation.requestStateRefresh());
  $('copyActFallbackBtn').addEventListener('click', () => {
    app.copyText($('actFallbackCmd').textContent || '', t('activate.copyFallbackToast'));
  });
}

export async function startActivationConnection(app) {
  setActError('');
  $('actTrustBox').hidden = true;

  const host = $('actHostInput').value.trim();
  const port = Number($('actPortInput').value) || 19879;
  const code = $('actCodeInput').value.trim();

  if (!host) {
    setActError(t('activate.errMissingHost'));
    return;
  }
  if (!/^\d{6}$/.test(code)) {
    setActError(t('activate.errInvalidCode'));
    return;
  }

  $('connectActBtn').disabled = true;
  $('connectActBtn').textContent = t('activate.probingBtn');
  if (app.desktop.enabled) {
    try {
      const tunnel = await app.ensureDesktopTlsTunnel(host, port, '/activate');
      $('connectActBtn').disabled = false;
      $('connectActBtn').textContent = t('activate.connectBtn');
      if (!tunnel) return;
      $('actLogOutput').textContent = '';
      app.activation.connect(host, port, code, tunnel.localWsUrl);
    } catch (err) {
      $('connectActBtn').disabled = false;
      $('connectActBtn').textContent = t('activate.connectBtn');
      setActError(t('activate.errConnectFailed', { host, port, error: err?.message || err }));
    }
    return;
  }
  const probe = await probeHttpsHealth(host, port);
  $('connectActBtn').disabled = false;
  $('connectActBtn').textContent = t('activate.connectBtn');

  if (!probe.ok) {
    if (probe.needsTrust) {
      $('actTrustBox').hidden = false;
    } else {
      setActError(t('activate.errConnectFailed', { host, port, error: probe.error }));
    }
    return;
  }

  $('actLogOutput').textContent = '';
  app.activation.connect(host, port, code);
}

export function renderActivationState(st) {
  $('actDeviceTitle').textContent = `${st.deviceName || 'Android'} (${st.lanIp || ''})`;
  const pPort = st.discovery?.pairingPort || 0;
  const cPort = st.discovery?.connectPort || 0;
  $('actDiscoverySummary').textContent = t('activate.discoverySummary', {
    pairPort: pPort || t('activate.waitingPairPopup'),
    connPort: cPort || t('activate.notFound'),
  });

  if (pPort > 0 && !$('adbPairPortInput').matches(':focus')) {
    $('adbPairPortInput').value = String(pPort);
  }
  if (cPort > 0 && !$('adbConnPortInput').matches(':focus')) {
    $('adbConnPortInput').value = String(cPort);
  }

  const badge = $('actDaemonBadge');
  badge.textContent = st.daemonConnected ? t('activate.daemonActive') : t('activate.daemonInactive');
  badge.className = `status-pill ${st.daemonConnected ? 'ok' : 'warn'}`;
  $('actShizukuBtn').hidden = !st.shizukuGranted;
  setActBusy(Boolean(st.busy));

  const ip = st.lanIp || $('actHostInput').value.trim();
  const fallback = cPort > 0
    ? `adb connect ${ip}:${cPort} && ${st.pcAdbCmd || ''}`
    : (st.pcAdbCmd || '');
  $('actFallbackCmd').textContent = fallback;
}

export function setActBusy(busy) {
  $('actPairStartBtn').disabled = busy;
  $('actDirectConnBtn').disabled = busy;
  $('actShizukuBtn').disabled = busy;
}

export function appendActLog(line) {
  if (!line) return;
  const el = $('actLogOutput');
  const ts = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  el.textContent += `[${ts}] ${line}\n`;
  el.scrollTop = el.scrollHeight;
}

export function setActError(msg) {
  const box = $('actErrorBox');
  box.hidden = !msg;
  box.textContent = msg || '';
}
