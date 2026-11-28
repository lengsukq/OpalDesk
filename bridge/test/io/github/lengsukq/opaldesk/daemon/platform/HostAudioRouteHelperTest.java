package io.github.lengsukq.opaldesk.daemon.platform;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostAudioRouteHelperTest {

    private static final class FakeAudioRouteBackend implements HostAudioRouteHelper.AudioRouteBackend {
        boolean strategySetOk = true;
        boolean strategyClearOk = true;
        boolean forceUseOk = true;
        boolean stillRoutingToHdmi = false;

        boolean throwOnStrategySet = false;
        boolean throwOnStrategyClear = false;
        boolean throwOnForceUse = false;
        boolean throwOnHdmiCheck = false;

        int strategySetCalls = 0;
        int strategyClearCalls = 0;
        int hdmiCheckCalls = 0;
        final List<String> forceUseCalls = new ArrayList<>();

        @Override
        public boolean setPreferredSpeakerForMediaStrategy() {
            strategySetCalls++;
            if (throwOnStrategySet) throw new RuntimeException("IAudioService unavailable");
            return strategySetOk;
        }

        @Override
        public boolean clearPreferredDevicesForMediaStrategy() {
            strategyClearCalls++;
            if (throwOnStrategyClear) throw new RuntimeException("IAudioService clear failed");
            return strategyClearOk;
        }

        @Override
        public boolean setAudioSystemForceUse(int usage, int config) {
            forceUseCalls.add(usage + ":" + config);
            if (throwOnForceUse) throw new RuntimeException("AudioSystem.setForceUse denied");
            return forceUseOk;
        }

        @Override
        public boolean isMediaRoutingToExternalDisplay() {
            hdmiCheckCalls++;
            if (throwOnHdmiCheck) throw new RuntimeException("AudioSystem.getDevicesForStream denied");
            return stillRoutingToHdmi;
        }
    }

    @Test
    public void enablingForcePhoneSpeakerAppliesStrategyAndForceUse() {
        FakeAudioRouteBackend backend = new FakeAudioRouteBackend();
        HostAudioRouteHelper helper = new HostAudioRouteHelper(backend);

        assertFalse(helper.isForcePhoneSpeakerActive());
        HostAudioRouteHelper.RouteResult res = helper.applyForcePhoneSpeaker(true, false);

        assertTrue(res.ok);
        assertTrue(res.forcePhone);
        assertEquals("strategy+forceUse", res.method);
        assertTrue(helper.isForcePhoneSpeakerActive());
        assertTrue(helper.isRouteModified());
        assertEquals(1, backend.strategySetCalls);
        assertEquals(1, backend.forceUseCalls.size());
        assertEquals(HostAudioRouteHelper.FOR_MEDIA + ":" + HostAudioRouteHelper.FORCE_SPEAKER,
                backend.forceUseCalls.get(0));
    }

    @Test
    public void disablingForcePhoneSpeakerCleanlyRestoresDefaultRoute() {
        FakeAudioRouteBackend backend = new FakeAudioRouteBackend();
        HostAudioRouteHelper helper = new HostAudioRouteHelper(backend);

        assertTrue(helper.setForcePhoneSpeaker(true));
        HostAudioRouteHelper.RouteResult res = helper.applyForcePhoneSpeaker(false, false);

        assertTrue(res.ok);
        assertFalse(res.forcePhone);
        assertEquals("restore_strategy+forceUse", res.method);
        assertFalse(helper.isForcePhoneSpeakerActive());
        assertFalse(helper.isRouteModified());
        assertEquals(1, backend.strategyClearCalls);
        assertEquals(HostAudioRouteHelper.FOR_MEDIA + ":" + HostAudioRouteHelper.FORCE_NONE,
                backend.forceUseCalls.get(backend.forceUseCalls.size() - 1));
    }

    @Test
    public void hdmiFallbackTriggersOnlyWhenMediaStillRoutesToExternalDisplay() {
        FakeAudioRouteBackend backend = new FakeAudioRouteBackend();
        backend.stillRoutingToHdmi = true;
        HostAudioRouteHelper helper = new HostAudioRouteHelper(backend);

        HostAudioRouteHelper.RouteResult res = helper.applyForcePhoneSpeaker(true, false);

        assertTrue(res.ok);
        assertEquals("strategy+forceUse+hdmiFallback", res.method);
        assertEquals(3, backend.forceUseCalls.size());
        assertEquals(HostAudioRouteHelper.FOR_MEDIA + ":" + HostAudioRouteHelper.FORCE_SPEAKER,
                backend.forceUseCalls.get(0));
        assertEquals(HostAudioRouteHelper.FOR_HDMI_SYSTEM_AUDIO + ":" + HostAudioRouteHelper.FORCE_NONE,
                backend.forceUseCalls.get(1));
        assertEquals(HostAudioRouteHelper.FOR_MEDIA + ":" + HostAudioRouteHelper.FORCE_SPEAKER,
                backend.forceUseCalls.get(2));
    }

    @Test
    public void reapplyIfEnabledNoOpsWhenDisabledAndReappliesWhenEnabled() {
        FakeAudioRouteBackend backend = new FakeAudioRouteBackend();
        HostAudioRouteHelper helper = new HostAudioRouteHelper(backend);

        assertFalse(helper.reapplyIfEnabled());
        assertEquals(0, backend.strategySetCalls);
        assertEquals(0, backend.forceUseCalls.size());

        assertTrue(helper.setForcePhoneSpeaker(true));
        assertTrue(helper.reapplyIfEnabled());
        assertEquals(2, backend.strategySetCalls);
        assertEquals(2, backend.forceUseCalls.size());
    }

    @Test
    public void restoreDefaultOnShutdownCleansUpOnlyWhenActiveOrModified() {
        FakeAudioRouteBackend backend = new FakeAudioRouteBackend();
        HostAudioRouteHelper helper = new HostAudioRouteHelper(backend);

        helper.restoreDefaultOnShutdown();
        assertEquals(0, backend.strategyClearCalls);
        assertEquals(0, backend.forceUseCalls.size());

        assertTrue(helper.setForcePhoneSpeaker(true));
        helper.restoreDefaultOnShutdown();
        assertFalse(helper.isForcePhoneSpeakerActive());
        assertFalse(helper.isRouteModified());
        assertEquals(1, backend.strategyClearCalls);
        assertEquals(HostAudioRouteHelper.FOR_MEDIA + ":" + HostAudioRouteHelper.FORCE_NONE,
                backend.forceUseCalls.get(backend.forceUseCalls.size() - 1));
    }

    @Test
    public void reflectionFailuresDegradeSafelyWithoutCrashing() {
        FakeAudioRouteBackend strategyFails = new FakeAudioRouteBackend();
        strategyFails.throwOnStrategySet = true;
        strategyFails.throwOnHdmiCheck = true;
        HostAudioRouteHelper helper1 = new HostAudioRouteHelper(strategyFails);

        HostAudioRouteHelper.RouteResult r1 = helper1.applyForcePhoneSpeaker(true, false);
        assertTrue(r1.ok);
        assertEquals("forceUse", r1.method);

        FakeAudioRouteBackend allFail = new FakeAudioRouteBackend();
        allFail.throwOnStrategySet = true;
        allFail.throwOnStrategyClear = true;
        allFail.throwOnForceUse = true;
        allFail.throwOnHdmiCheck = true;
        HostAudioRouteHelper helper2 = new HostAudioRouteHelper(allFail);

        HostAudioRouteHelper.RouteResult r2 = helper2.applyForcePhoneSpeaker(true, false);
        assertFalse(r2.ok);
        assertEquals("none", r2.method);

        HostAudioRouteHelper.RouteResult r3 = helper2.applyForcePhoneSpeaker(false, false);
        assertFalse(r3.ok);
        assertEquals("restore_none", r3.method);
        assertFalse(helper2.isForcePhoneSpeakerActive());

        // Default reflection backend on pure JVM (no Android framework classes) must degrade cleanly.
        HostAudioRouteHelper jvmHelper = new HostAudioRouteHelper();
        assertFalse(jvmHelper.setForcePhoneSpeaker(true));
        assertFalse(jvmHelper.setForcePhoneSpeaker(false));
    }

    @Test
    public void externalDisplayDeviceMaskClassification() {
        assertTrue(HostAudioRouteHelper.isExternalDisplayDeviceMask(HostAudioRouteHelper.DEVICE_OUT_AUX_DIGITAL));
        assertTrue(HostAudioRouteHelper.isExternalDisplayDeviceMask(HostAudioRouteHelper.DEVICE_OUT_HDMI_ARC));
        assertTrue(HostAudioRouteHelper.isExternalDisplayDeviceMask(HostAudioRouteHelper.DEVICE_OUT_HDMI_EARC));
        assertTrue(HostAudioRouteHelper.isExternalDisplayDeviceMask(
                HostAudioRouteHelper.DEVICE_OUT_AUX_DIGITAL | 0x2));

        // Built-in speaker (0x2), earpiece (0x1), Bluetooth A2DP (0x80), USB headset (0x4000000) are not HDMI.
        assertFalse(HostAudioRouteHelper.isExternalDisplayDeviceMask(0));
        assertFalse(HostAudioRouteHelper.isExternalDisplayDeviceMask(0x1));
        assertFalse(HostAudioRouteHelper.isExternalDisplayDeviceMask(0x2));
        assertFalse(HostAudioRouteHelper.isExternalDisplayDeviceMask(0x80));
        assertFalse(HostAudioRouteHelper.isExternalDisplayDeviceMask(0x4000000));
    }
}
