/**
 * Client for Android `:helper` WebActivationService (`:19879`).
 * Handles TLS `/health` probing, `/trust` one-click certificate approval, session-code auth,
 * live mDNS wireless ADB discovery streaming, activation commands, and handover to Web Cast.
 */

import { t } from './i18n.js';

export async function probeHttpsHealth(host, port, timeoutMs = 3500) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const url = `https://${host}:${port}/health`;
  try {
    const resp = await fetch(url, {
      method: 'GET',
      mode: 'cors',
      cache: 'no-store',
      signal: controller.signal,
    });
    if (!resp.ok) {
      return { ok: false, needsTrust: false, error: `HTTP ${resp.status}` };
    }
    const data = await resp.json();
    return { ok: true, needsTrust: false, data };
  } catch (err) {
    // Self-signed TLS failure, PNA denial, or unreachable host all throw TypeError in browsers
    return {
      ok: false,
      needsTrust: err?.name !== 'AbortError',
      timedOut: err?.name === 'AbortError',
      error: err?.message || t('activate.probeFailed'),
    };
  } finally {
    clearTimeout(timer);
  }
}

export function buildTrustPageUrl(host, port, returnUrl = window.location.href) {
  return `https://${host}:${port}/trust?return=${encodeURIComponent(returnUrl)}`;
}

export class LanActivationClient {
  constructor({ onStatus, onAuthResult, onState, onProgress, onResult, onReadyForCast, onError, onClose } = {}) {
    this.onStatus = onStatus || (() => {});
    this.onAuthResult = onAuthResult || (() => {});
    this.onState = onState || (() => {});
    this.onProgress = onProgress || (() => {});
    this.onResult = onResult || (() => {});
    this.onReadyForCast = onReadyForCast || (() => {});
    this.onError = onError || (() => {});
    this.onClose = onClose || (() => {});

    this.ws = null;
    this.pingTimer = null;
    this.authenticated = false;
  }

  connect(host, port, sessionCode, wsUrlOverride = '') {
    this.disconnect();
    const url = wsUrlOverride || `wss://${host}:${port}/activate`;
    this.onStatus('connecting', t('activate.clientConnecting', { host, port }));

    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onopen = () => {
      this.onStatus('authenticating', t('activate.clientAuthenticating'));
      this.sendJson({ type: 'auth', sessionCode: String(sessionCode || '').trim() });
      this.pingTimer = setInterval(() => {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
          this.sendJson({ type: 'ping' });
        }
      }, 15000);
    };

    ws.onmessage = (ev) => {
      let msg;
      try {
        msg = JSON.parse(String(ev.data));
      } catch {
        return;
      }
      switch (msg.type) {
        case 'auth_result':
          this.authenticated = Boolean(msg.ok);
          this.onAuthResult(msg);
          if (msg.ok) {
            this.onStatus('connected', t('activate.clientConnected'));
          } else {
            this.onStatus('auth_failed', msg.message || t('activate.clientAuthFailed'));
          }
          break;
        case 'state':
          this.onState(msg);
          break;
        case 'progress':
          this.onProgress(msg.message || '');
          break;
        case 'result':
          this.onResult(msg);
          break;
        case 'ready_for_cast':
          this.onReadyForCast(msg);
          break;
        case 'error':
          this.onError(msg.message || t('activate.clientOpFailed'));
          break;
        default:
          break;
      }
    };

    ws.onerror = () => {
      this.onError(t('activate.clientWsError'));
    };

    ws.onclose = (ev) => {
      this.clearTimers();
      this.authenticated = false;
      this.onClose(ev.reason || (ev.wasClean ? t('activate.clientClosed') : t('activate.clientDisconnected')));
    };
  }

  submitPairAndStart({ pairPort, pairCode, connectPort = 0, autoEnableCast = true }) {
    this.sendJson({
      type: 'pair_and_start',
      pairPort: Number(pairPort) || 0,
      pairCode: String(pairCode || '').trim(),
      connectPort: Number(connectPort) || 0,
      autoEnableCast: Boolean(autoEnableCast),
    });
  }

  submitDirectConnect({ connectPort, autoEnableCast = true }) {
    this.sendJson({
      type: 'direct_connect',
      connectPort: Number(connectPort) || 0,
      autoEnableCast: Boolean(autoEnableCast),
    });
  }

  submitShizukuActivate({ autoEnableCast = true } = {}) {
    this.sendJson({
      type: 'shizuku_activate',
      autoEnableCast: Boolean(autoEnableCast),
    });
  }

  requestStateRefresh() {
    this.sendJson({ type: 'refresh_state' });
  }

  sendJson(obj) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj));
    }
  }

  clearTimers() {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  disconnect() {
    this.clearTimers();
    this.authenticated = false;
    if (this.ws) {
      try {
        this.ws.close(1000, 'client_disconnect');
      } catch {
        // Ignore close race
      }
      this.ws = null;
    }
  }
}
