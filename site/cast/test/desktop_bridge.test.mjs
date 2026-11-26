import assert from 'node:assert/strict';
import test from 'node:test';
import { OpalDesktopBridge } from '../js/desktop_bridge.js';

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
