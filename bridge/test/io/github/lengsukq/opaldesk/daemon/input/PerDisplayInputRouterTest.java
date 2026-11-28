package io.github.lengsukq.opaldesk.daemon.input;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.SystemInputInjectorResolver;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PerDisplayInputRouterTest {

    public static final class FakeEvent {}

    public static final class LegacyIInputManager {
        public boolean injectInputEvent(FakeEvent ev, int mode) { return true; }
    }

    public static final class Api37TargetOnlyIInputManager {
        public boolean injectInputEventToTarget(FakeEvent ev, int mode, int targetUid) { return true; }
    }

    public static final class EmptyIInputManager {}

    public static final class PublicInputManagerFallback {
        public boolean injectInputEvent(FakeEvent ev, int mode) { return true; }
    }

    private static DaemonConfig configWithMode(int mode) {
        DaemonConfig cfg = new DaemonConfig();
        cfg.updateRuntimeConfig(mode, -1, -1, -1, -1);
        return cfg;
    }

    @Test
    public void autoModeFallsBackToUhidWhenSystemReturnsFalseSilently() {
        DaemonConfig cfg = configWithMode(BridgeProtocol.INPUT_BACKEND_AUTO);
        AtomicInteger systemCalls = new AtomicInteger(0);
        AtomicInteger uhidCalls = new AtomicInteger(0);

        InputFallbackDispatcher dispatcher = new InputFallbackDispatcher(cfg, new InputFallbackDispatcher.BackendAdapter() {
            @Override public boolean isSystemInjectorAvailable() { return true; }
            @Override public boolean injectViaSystem(Object ev, int displayId) {
                systemCalls.incrementAndGet();
                return false; // HyperOS / Android 17 silent denial without throwing SecurityException
            }
            @Override public boolean injectViaUhid(Object ev, int displayId, String vdUniqueId, int dispW, int dispH) {
                uhidCalls.incrementAndGet();
                return true;
            }
        });

        List<Integer> statusChanges = new ArrayList<>();
        dispatcher.setStatusListener(statusChanges::add);

        assertTrue(dispatcher.dispatch(new FakeEvent(), 20, "virtual:opal:20", 1848, 712));
        assertEquals(1, systemCalls.get());
        assertEquals(1, uhidCalls.get());
        assertEquals(BridgeProtocol.INJECT_STATUS_UHID, dispatcher.getLastInjectStatus());
        assertEquals(1, statusChanges.size());
        assertEquals(Integer.valueOf(BridgeProtocol.INJECT_STATUS_UHID), statusChanges.get(0));

        // Subsequent events stay latched on UHID without retrying failing system injector
        assertTrue(dispatcher.dispatch(new FakeEvent(), 20, "virtual:opal:20", 1848, 712));
        assertEquals(1, systemCalls.get());
        assertEquals(2, uhidCalls.get());
    }

    @Test
    public void autoModeFallsBackToUhidWhenSystemThrowsOrIsUnavailable() {
        DaemonConfig cfg1 = configWithMode(BridgeProtocol.INPUT_BACKEND_AUTO);
        InputFallbackDispatcher throwingDispatcher = new InputFallbackDispatcher(cfg1, new InputFallbackDispatcher.BackendAdapter() {
            @Override public boolean isSystemInjectorAvailable() { return true; }
            @Override public boolean injectViaSystem(Object ev, int displayId) throws Exception {
                throw new InvocationTargetException(new IllegalArgumentException("signature mismatch"));
            }
            @Override public boolean injectViaUhid(Object ev, int displayId, String vdUniqueId, int dispW, int dispH) {
                return true;
            }
        });
        assertTrue(throwingDispatcher.dispatch(new FakeEvent(), 5, "virtual:opal:5", 1080, 1920));
        assertEquals(BridgeProtocol.INJECT_STATUS_UHID, throwingDispatcher.getLastInjectStatus());

        DaemonConfig cfg2 = configWithMode(BridgeProtocol.INPUT_BACKEND_AUTO);
        InputFallbackDispatcher missingDispatcher = new InputFallbackDispatcher(cfg2, new InputFallbackDispatcher.BackendAdapter() {
            @Override public boolean isSystemInjectorAvailable() { return false; }
            @Override public boolean injectViaSystem(Object ev, int displayId) { return false; }
            @Override public boolean injectViaUhid(Object ev, int displayId, String vdUniqueId, int dispW, int dispH) {
                return true;
            }
        });
        assertTrue(missingDispatcher.dispatch(new FakeEvent(), 6, "virtual:opal:6", 1080, 1920));
        assertEquals(BridgeProtocol.INJECT_STATUS_UHID, missingDispatcher.getLastInjectStatus());
    }

    @Test
    public void autoModeReportsBlockedWithoutDoubleCallingUhidWhenBothFail() {
        DaemonConfig cfg = configWithMode(BridgeProtocol.INPUT_BACKEND_AUTO);
        AtomicInteger uhidCalls = new AtomicInteger(0);
        AtomicBoolean uhidShouldWork = new AtomicBoolean(true);

        InputFallbackDispatcher dispatcher = new InputFallbackDispatcher(cfg, new InputFallbackDispatcher.BackendAdapter() {
            @Override public boolean isSystemInjectorAvailable() { return true; }
            @Override public boolean injectViaSystem(Object ev, int displayId) { return false; }
            @Override public boolean injectViaUhid(Object ev, int displayId, String vdUniqueId, int dispW, int dispH) {
                uhidCalls.incrementAndGet();
                return uhidShouldWork.get();
            }
        });

        // First call latches UHID
        assertTrue(dispatcher.dispatch(new FakeEvent(), 8, "virtual:opal:8", 1280, 720));
        assertEquals(1, uhidCalls.get());

        // Now UHID fails on a new display: should try UHID once, try system once, and NOT retry UHID a second time
        uhidShouldWork.set(false);
        assertFalse(dispatcher.dispatch(new FakeEvent(), 9, "virtual:opal:9", 1280, 720));
        assertEquals(2, uhidCalls.get());
        assertEquals(BridgeProtocol.INJECT_STATUS_BLOCKED, dispatcher.getLastInjectStatus());
    }

    @Test
    public void systemOnlyModeDoesNotFallbackToUhid() {
        DaemonConfig cfg = configWithMode(BridgeProtocol.INPUT_BACKEND_SYSTEM);
        AtomicInteger uhidCalls = new AtomicInteger(0);
        InputFallbackDispatcher dispatcher = new InputFallbackDispatcher(cfg, new InputFallbackDispatcher.BackendAdapter() {
            @Override public boolean isSystemInjectorAvailable() { return true; }
            @Override public boolean injectViaSystem(Object ev, int displayId) { return false; }
            @Override public boolean injectViaUhid(Object ev, int displayId, String vdUniqueId, int dispW, int dispH) {
                uhidCalls.incrementAndGet();
                return true;
            }
        });

        assertFalse(dispatcher.dispatch(new FakeEvent(), 11, "virtual:opal:11", 1280, 720));
        assertEquals(0, uhidCalls.get());
        assertEquals(BridgeProtocol.INJECT_STATUS_BLOCKED, dispatcher.getLastInjectStatus());
    }

    @Test
    public void systemInputInjectorResolvesAcrossAndroid11To17Signatures() {
        SystemInputInjectorResolver.ResolvedInjector legacy = SystemInputInjectorResolver.resolve(
                LegacyIInputManager.class, PublicInputManagerFallback.class, FakeEvent.class);
        assertNotNull(legacy);
        assertEquals(SystemInputInjectorResolver.Kind.IINPUT_MANAGER_2_ARG, legacy.kind);

        SystemInputInjectorResolver.ResolvedInjector api37 = SystemInputInjectorResolver.resolve(
                Api37TargetOnlyIInputManager.class, PublicInputManagerFallback.class, FakeEvent.class);
        assertNotNull(api37);
        assertEquals(SystemInputInjectorResolver.Kind.IINPUT_MANAGER_TARGET_3_ARG, api37.kind);

        SystemInputInjectorResolver.ResolvedInjector imFallback = SystemInputInjectorResolver.resolve(
                EmptyIInputManager.class, PublicInputManagerFallback.class, FakeEvent.class);
        assertNotNull(imFallback);
        assertEquals(SystemInputInjectorResolver.Kind.INPUT_MANAGER_2_ARG, imFallback.kind);

        assertNull(SystemInputInjectorResolver.resolve(
                EmptyIInputManager.class, EmptyIInputManager.class, FakeEvent.class));
    }

    @Test
    public void passiveHoverFailureDoesNotLatchUhidAndShortCircuitsWhenUhidActive() {
        DaemonConfig cfg = configWithMode(BridgeProtocol.INPUT_BACKEND_AUTO);
        AtomicInteger systemCalls = new AtomicInteger(0);
        AtomicInteger uhidCalls = new AtomicInteger(0);

        InputFallbackDispatcher dispatcher = new InputFallbackDispatcher(cfg, new InputFallbackDispatcher.BackendAdapter() {
            @Override public boolean isSystemInjectorAvailable() { return true; }
            @Override public boolean injectViaSystem(Object ev, int displayId) {
                systemCalls.incrementAndGet();
                return false;
            }
            @Override public boolean injectViaUhid(Object ev, int displayId, String vdUniqueId, int dispW, int dispH) {
                uhidCalls.incrementAndGet();
                return true;
            }
        });

        assertFalse(dispatcher.isUsingUhid());
        // Passive hover failing on a booting VirtualDisplay must NOT latch UHID or mark BLOCKED
        assertFalse(dispatcher.dispatch(new FakeEvent(), 21, "virtual:opal:21", 1080, 1920, false));
        assertEquals(1, systemCalls.get());
        assertEquals(0, uhidCalls.get());
        assertFalse(dispatcher.isUsingUhid());
        assertEquals(BridgeProtocol.INJECT_STATUS_SYSTEM, dispatcher.getLastInjectStatus());

        // Real interactive event (allowAutoUhidFallback = true) falls back and latches UHID
        assertTrue(dispatcher.dispatch(new FakeEvent(), 21, "virtual:opal:21", 1080, 1920, true));
        assertEquals(2, systemCalls.get());
        assertEquals(1, uhidCalls.get());
        assertTrue(dispatcher.isUsingUhid());
        assertEquals(BridgeProtocol.INJECT_STATUS_UHID, dispatcher.getLastInjectStatus());

        // Subsequent passive hover under active UHID short-circuits cleanly without invoking UHID
        assertTrue(dispatcher.dispatch(new FakeEvent(), 22, "virtual:opal:22", 1080, 1920, false));
        assertEquals(2, systemCalls.get());
        assertEquals(1, uhidCalls.get());
    }

    @Test
    public void localImeActiveDropsPhysicalWindowKeysAndPreventsDisplayFocusSteal() {
        // Physical/remote keyboard events (deviceId > 0) must never leak into a background window
        // while a local host search box / text field is focused.
        assertTrue(MouseRoutingPolicy.shouldDropWindowKeyInjectionDuringLocalIme(true, 1));
        assertTrue(MouseRoutingPolicy.shouldDropWindowKeyInjectionDuringLocalIme(true, 12));
        // Host soft-IME proxy keys use VIRTUAL_KEYBOARD (-1) and remain allowed without stealing focus.
        assertFalse(MouseRoutingPolicy.shouldDropWindowKeyInjectionDuringLocalIme(true, -1));
        assertFalse(MouseRoutingPolicy.shouldDropWindowKeyInjectionDuringLocalIme(false, 1));
        assertFalse(MouseRoutingPolicy.shouldDropWindowKeyInjectionDuringLocalIme(false, -1));

        // Never call focusDisplay / focusTopTask on a background window while localImeActive is true.
        assertFalse(MouseRoutingPolicy.shouldFocusWindowDisplayOnKeyInjection(true));
        assertTrue(MouseRoutingPolicy.shouldFocusWindowDisplayOnKeyInjection(false));
    }
}
