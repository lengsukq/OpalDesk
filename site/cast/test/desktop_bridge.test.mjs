import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildStandaloneWindowQuery,
  normalizeCastPrefs,
  OpalDesktopBridge,
  sanitizeCustomAdbPath,
} from '../js/desktop_bridge.js';

test('scanUsbTetherDevices stays no-op outside Tauri desktop environment', async () => {
  const bridge = new OpalDesktopBridge({});
  assert.equal(bridge.enabled, false);
  const res = await bridge.scanUsbTetherDevices(19878);
  assert.deepEqual(res, []);
});

test('scanUsbTetherDevices invokes native Tauri command in desktop environment', async () => {
  const calls = [];
  const fakeWin = {
    __TAURI__: {
      core: {
        async invoke(cmd, args) {
          calls.push({ cmd, args });
          return [
            {
              id: 'usb-tether:192.168.42.129:19878',
              displayName: 'Xiaomi 14 Pro',
              host: '192.168.42.129',
              port: 19878,
              interfaceAlias: '以太网 3',
              interfaceDescription: 'USB NCM Host Device',
              fingerprint: 'a'.repeat(64),
              shortFingerprint: 'AAAA-AAAA-AAAA',
            },
          ];
        },
      },
    },
  };
  const bridge = new OpalDesktopBridge(fakeWin);
  assert.equal(bridge.enabled, true);
  const list = await bridge.scanUsbTetherDevices(19878);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].cmd, 'scan_usb_tether_devices');
  assert.deepEqual(calls[0].args, { port: 19878 });
  assert.equal(list.length, 1);
  assert.equal(list[0].host, '192.168.42.129');
});

test('buildStandaloneWindowQuery encodes appLabel, deviceKey, and pin flag', () => {
  const q = buildStandaloneWindowQuery({
    host: '192.168.1.99',
    port: 19878,
    transport: 'wifi',
    pkg: 'com.tencent.mm',
    cls: '.ui.LauncherUI',
    label: '微信',
    deviceKey: '192.168.1.99:19878',
    alwaysOnTop: true,
    certFingerprint: 'a'.repeat(64),
  });
  const params = new URLSearchParams(q);
  assert.equal(params.get('role'), 'standalone_app');
  assert.equal(params.get('pkg'), 'com.tencent.mm');
  assert.equal(params.get('appLabel'), '微信');
  assert.equal(params.get('deviceKey'), '192.168.1.99:19878');
  assert.equal(params.get('pin'), '1');
});

test('desktop bridge forwards standalone pin, reload, and feedback Tauri commands', async () => {
  const calls = [];
  const fakeWin = {
    __TAURI__: {
      core: {
        async invoke(cmd, args) {
          calls.push({ cmd, args });
          if (cmd === 'open_standalone_window') {
            return { status: 'already_open', label: 'standalone-com-demo', existingTitle: 'Demo', activeCount: 1, maxCount: 3 };
          }
          if (cmd === 'set_current_window_always_on_top') {
            return Boolean(args.alwaysOnTop);
          }
          if (cmd === 'submit_feedback_report') {
            return { success: true, eventId: 'a'.repeat(32), shortCode: 'AAAAAAAA', errorMessage: '' };
          }
          return [];
        },
      },
    },
  };

  const bridge = new OpalDesktopBridge(fakeWin);
  const openRes = await bridge.openStandaloneWindow({
    pkg: 'com.demo',
    label: 'Demo',
    host: '192.168.1.10',
    port: 19878,
    alwaysOnTop: true,
    forceReload: true,
  });
  assert.equal(openRes.status, 'already_open');
  assert.equal(calls[0].cmd, 'open_standalone_window');
  assert.equal(calls[0].args.alwaysOnTop, true);
  assert.equal(calls[0].args.forceReload, true);

  const pinned = await bridge.setCurrentWindowAlwaysOnTop(true);
  assert.equal(pinned, true);
  assert.equal(calls[1].cmd, 'set_current_window_always_on_top');
  assert.deepEqual(calls[1].args, { alwaysOnTop: true });

  const fb = await bridge.submitFeedbackReport({
    reporterName: 'Leo',
    problemDescription: '希望支持置顶',
    kind: 'feature',
    includeLogs: false,
  });
  assert.equal(fb.success, true);
  assert.equal(calls[2].cmd, 'submit_feedback_report');
  assert.equal(calls[2].args.kind, 'feature');
  assert.equal(calls[2].args.includeLogs, false);
});

test('normalizeCastPrefs validates codec, fps, bitrate, cursor, and ADB path; bridge updates native prefs', async () => {
  const norm = normalizeCastPrefs({
    preferredCodec: 'hevc',
    targetFps: 120,
    bitrateKbps: 22000,
    audioEnabled: false,
    hideLocalCursor: true,
    customAdbPath: '  C:\\Android\\adb.exe  ',
  });
  assert.deepEqual(norm, {
    preferredCodec: 'hevc',
    targetFps: 120,
    bitrateKbps: 22000,
    audioEnabled: false,
    hideLocalCursor: true,
    customAdbPath: 'C:\\Android\\adb.exe',
  });

  const invalid = normalizeCastPrefs({
    preferredCodec: 'vp9',
    targetFps: 75,
    bitrateKbps: 999999,
  });
  assert.equal(invalid.preferredCodec, '');
  assert.equal(invalid.targetFps, 0);
  assert.equal(invalid.bitrateKbps, 0);

  const calls = [];
  const fakeWin = {
    __TAURI__: {
      core: {
        async invoke(cmd, args) {
          calls.push({ cmd, args });
          return { ...norm, ...(args?.patch || {}) };
        },
      },
    },
  };
  const bridge = new OpalDesktopBridge(fakeWin);
  const updated = await bridge.updateClientPrefs({ targetFps: 90, bitrateKbps: 32000 });
  assert.equal(calls[0].cmd, 'update_client_prefs');
  assert.deepEqual(calls[0].args, { patch: { targetFps: 90, bitrateKbps: 32000 } });
  assert.equal(updated.targetFps, 90);
});

test('sanitizeCustomAdbPath strips Windows Copy-as-path quotes and bridge preserves remembered path on init/save', async () => {
  assert.equal(
    sanitizeCustomAdbPath('  "C:\\Android\\platform-tools\\adb.exe"  '),
    'C:\\Android\\platform-tools\\adb.exe'
  );
  assert.equal(sanitizeCustomAdbPath("  'D:\\Tools\\adb.exe' "), 'D:\\Tools\\adb.exe');
  assert.equal(sanitizeCustomAdbPath('  ""  '), '');
  assert.equal(
    normalizeCastPrefs({ customAdbPath: ' "D:\\platform-tools\\adb.exe" ' }).customAdbPath,
    'D:\\platform-tools\\adb.exe'
  );

  const calls = [];
  const fakeWin = {
    __TAURI__: {
      core: {
        async invoke(cmd, args) {
          calls.push({ cmd, args });
          if (cmd === 'desktop_runtime_info') {
            return {
              clientId: 'win-1234567890ab',
              adb: {
                available: true,
                adbPath: 'D:\\platform-tools\\adb.exe',
                customAdbPath: 'D:\\platform-tools\\adb.exe',
              },
              prefs: {
                customAdbPath: 'D:\\platform-tools\\adb.exe',
                preferredCodec: 'hevc',
                targetFps: 120,
                bitrateKbps: 22000,
                audioEnabled: true,
                hideLocalCursor: false,
              },
            };
          }
          if (cmd === 'list_mdns_devices') {
            throw new Error('transient mDNS error');
          }
          if (cmd === 'list_trusted_devices') {
            return [];
          }
          if (cmd === 'set_custom_adb_path') {
            return {
              available: Boolean(args.path),
              adbPath: args.path,
              customAdbPath: args.path,
            };
          }
          return null;
        },
      },
    },
  };

  const bridge = new OpalDesktopBridge(fakeWin);
  const info = await bridge.initialize();
  assert.equal(info.adb.customAdbPath, 'D:\\platform-tools\\adb.exe');
  assert.equal(bridge.adbStatus.customAdbPath, 'D:\\platform-tools\\adb.exe');
  assert.equal(bridge.clientPrefs.customAdbPath, 'D:\\platform-tools\\adb.exe');

  const saved = await bridge.setCustomAdbPath('  "E:\\Android\\platform-tools\\adb.exe"  ');
  assert.equal(saved.customAdbPath, 'E:\\Android\\platform-tools\\adb.exe');
  assert.equal(bridge.clientPrefs.customAdbPath, 'E:\\Android\\platform-tools\\adb.exe');
  assert.deepEqual(calls.at(-1), {
    cmd: 'set_custom_adb_path',
    args: { path: 'E:\\Android\\platform-tools\\adb.exe' },
  });

  const cleared = await bridge.setCustomAdbPath('  ""  ');
  assert.equal(cleared.customAdbPath, null);
  assert.equal(bridge.clientPrefs.customAdbPath, null);
  assert.deepEqual(calls.at(-1), {
    cmd: 'set_custom_adb_path',
    args: { path: null },
  });
});
