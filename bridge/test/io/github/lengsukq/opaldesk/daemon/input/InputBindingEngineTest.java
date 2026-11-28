package io.github.lengsukq.opaldesk.daemon.input;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure JVM unit tests for {@link InputBindingRule}, {@link InputBindingEngine}, and
 * {@link DaemonConfig} input-binding persistence.
 */
public final class InputBindingEngineTest {
    private static final int ANDROID_META_ALT_LEFT = 0x10;
    private static final int ANDROID_META_CTRL_ON = 0x1000;
    private static final int ANDROID_META_SHIFT_RIGHT = 0x80;
    private static final int ANDROID_META_SUPER_LEFT = 0x20000;

    @Rule
    public final TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testDefaultRulesAndRightClickPassthroughWhenDisabled() {
        InputBindingEngine engine = new InputBindingEngine();
        List<InputBindingRule> defaults = engine.getRules();
        assertEquals(2, defaults.size());

        // Default MOUSE 0x08 (BUTTON_BACK) -> back
        InputBindingEngine.MatchResult backDown = engine.onMouseButtonEvent(true, 0x08, 0);
        assertTrue(backDown.consumed);
        assertEquals(InputBindingRule.ACTION_BACK, backDown.actionId);
        InputBindingEngine.MatchResult backUp = engine.onMouseButtonEvent(false, 0x08, 0);
        assertTrue(backUp.consumed);
        assertNull(backUp.actionId);

        // Default MOUSE 0x02 (BUTTON_SECONDARY) -> back
        InputBindingEngine.MatchResult rightDown = engine.onMouseButtonEvent(true, 0x02, 0);
        assertTrue(rightDown.consumed);
        assertEquals(InputBindingRule.ACTION_BACK, rightDown.actionId);
        assertTrue(engine.onMouseButtonEvent(false, 0x02, 0).consumed);

        // Disabling right-click rule passes right-click through to apps
        InputBindingRule disabledRight = new InputBindingRule(
                "default_mouse_right_back", false, InputBindingRule.TRIGGER_MOUSE,
                0x02, InputBindingRule.META_NONE, InputBindingRule.ACTION_BACK, "Mouse Right -> Back");
        engine.setRules(Arrays.asList(defaults.get(0), disabledRight));
        InputBindingEngine.MatchResult passthroughDown = engine.onMouseButtonEvent(true, 0x02, 0);
        assertFalse(passthroughDown.consumed);
        assertNull(passthroughDown.actionId);
        assertFalse(engine.onMouseButtonEvent(false, 0x02, 0).consumed);
    }

    @Test
    public void testKeyboardMatchMetaNormalizationAndAntiStuckKey() {
        assertEquals(
                InputBindingRule.META_ALT | InputBindingRule.META_CTRL
                        | InputBindingRule.META_SHIFT | InputBindingRule.META_SUPER,
                InputBindingEngine.normalizeAndroidMetaState(
                        ANDROID_META_ALT_LEFT | ANDROID_META_CTRL_ON
                                | ANDROID_META_SHIFT_RIGHT | ANDROID_META_SUPER_LEFT));

        InputBindingRule snapLeftRule = new InputBindingRule(
                "k1", true, InputBindingRule.TRIGGER_KEY,
                21, // KEYCODE_DPAD_LEFT
                InputBindingRule.META_CTRL | InputBindingRule.META_ALT,
                InputBindingRule.ACTION_SNAP_LEFT,
                "Ctrl+Alt+Left");
        InputBindingEngine engine = new InputBindingEngine(Collections.singletonList(snapLeftRule));

        // Non-matching meta passes through
        assertFalse(engine.onKeyEvent(InputBindingEngine.KEY_ACTION_DOWN, 21, 0).consumed);

        // Matching Ctrl+Alt+Left intercepts DOWN
        InputBindingEngine.MatchResult down = engine.onKeyEvent(
                InputBindingEngine.KEY_ACTION_DOWN, 21, ANDROID_META_CTRL_ON | ANDROID_META_ALT_LEFT);
        assertTrue(down.consumed);
        assertEquals(snapLeftRule, down.rule);
        assertEquals(InputBindingRule.ACTION_SNAP_LEFT, down.actionId);

        // Even if user releases Ctrl/Alt BEFORE releasing Left key, UP is still silently consumed
        InputBindingEngine.MatchResult up = engine.onKeyEvent(InputBindingEngine.KEY_ACTION_UP, 21, 0);
        assertTrue("Paired UP must be consumed to prevent stuck keys", up.consumed);
        assertNull(up.rule);
        assertNull(up.actionId);

        // Subsequent stray UP is not consumed
        assertFalse(engine.onKeyEvent(InputBindingEngine.KEY_ACTION_UP, 21, 0).consumed);
    }

    @Test
    public void testKeyRepeatSuppressionPreventsDuplicateActionUntilReleased() {
        InputBindingRule maxRule = new InputBindingRule(
                "k_max", true, InputBindingRule.TRIGGER_KEY,
                41, // KEYCODE_M
                InputBindingRule.META_CTRL | InputBindingRule.META_ALT,
                InputBindingRule.ACTION_TOGGLE_MAXIMIZE,
                "Ctrl+Alt+M");
        InputBindingEngine engine = new InputBindingEngine(Collections.singletonList(maxRule));
        int meta = ANDROID_META_CTRL_ON | ANDROID_META_ALT_LEFT;

        // Initial press fires action once
        InputBindingEngine.MatchResult firstDown = engine.onKeyEvent(
                InputBindingEngine.KEY_ACTION_DOWN, 41, meta, 0);
        assertTrue(firstDown.consumed);
        assertEquals(InputBindingRule.ACTION_TOGGLE_MAXIMIZE, firstDown.actionId);

        // Long-press repeat DOWNs (both repeatCount == 0 and repeatCount > 0) stay consumed with null actionId
        InputBindingEngine.MatchResult repeatZero = engine.onKeyEvent(
                InputBindingEngine.KEY_ACTION_DOWN, 41, meta, 0);
        assertTrue(repeatZero.consumed);
        assertNull(repeatZero.actionId);
        assertNull(repeatZero.rule);

        InputBindingEngine.MatchResult repeatHeld = engine.onKeyEvent(
                InputBindingEngine.KEY_ACTION_DOWN, 41, meta, 3);
        assertTrue(repeatHeld.consumed);
        assertNull(repeatHeld.actionId);
        assertNull(repeatHeld.rule);

        // Release consumes UP silently
        InputBindingEngine.MatchResult up = engine.onKeyEvent(
                InputBindingEngine.KEY_ACTION_UP, 41, meta, 0);
        assertTrue(up.consumed);
        assertNull(up.actionId);

        // Pressing again after release triggers the action once more
        InputBindingEngine.MatchResult secondDown = engine.onKeyEvent(
                InputBindingEngine.KEY_ACTION_DOWN, 41, meta, 0);
        assertTrue(secondDown.consumed);
        assertEquals(InputBindingRule.ACTION_TOGGLE_MAXIMIZE, secondDown.actionId);
    }

    @Test
    public void testExtendedMouseSideButtonsAndPairedRelease() {
        InputBindingRule side20 = new InputBindingRule(
                "m20", true, InputBindingRule.TRIGGER_MOUSE,
                0x20, InputBindingRule.META_NONE, InputBindingRule.ACTION_TILE_2_SPLIT, "Side 0x20");
        InputBindingRule side40 = new InputBindingRule(
                "m40", true, InputBindingRule.TRIGGER_MOUSE,
                0x40, InputBindingRule.META_CTRL, InputBindingRule.ACTION_SPOTLIGHT, "Ctrl+Side 0x40");
        InputBindingEngine engine = new InputBindingEngine(Arrays.asList(side20, side40));

        // 0x20 side button press & release
        InputBindingEngine.MatchResult m20Down = engine.onMouseButtonEvent(true, 0x20, 0);
        assertTrue(m20Down.consumed);
        assertEquals(InputBindingRule.ACTION_TILE_2_SPLIT, m20Down.actionId);
        InputBindingEngine.MatchResult m20Up = engine.onMouseButtonEvent(false, 0x20, 0);
        assertTrue(m20Up.consumed);
        assertNull(m20Up.actionId);

        // Ctrl + 0x40 side button press, then release Ctrl before releasing 0x40
        InputBindingEngine.MatchResult m40Down = engine.onMouseButtonEvent(true, 0x40, ANDROID_META_CTRL_ON);
        assertTrue(m40Down.consumed);
        assertEquals(InputBindingRule.ACTION_SPOTLIGHT, m40Down.actionId);
        InputBindingEngine.MatchResult m40Up = engine.onMouseButtonEvent(false, 0x40, 0);
        assertTrue(m40Up.consumed);
        assertNull(m40Up.actionId);

        // Unmapped 0x80 passes through
        assertFalse(engine.onMouseButtonEvent(true, 0x80, 0).consumed);
    }

    @Test
    public void testCaptureModeRecordsNicheMouseSideButtonAndKeyCombo() {
        InputBindingEngine engine = new InputBindingEngine();
        engine.setCaptureMode(true);
        assertTrue(engine.isCaptureMode());
        assertNull(engine.pollLastCapturedEventJson());

        // Standalone modifier key press is consumed silently without recording
        InputBindingEngine.MatchResult modDown = engine.onKeyEvent(
                InputBindingEngine.KEY_ACTION_DOWN, 113, ANDROID_META_CTRL_ON);
        assertTrue(modDown.consumed);
        assertNull(modDown.actionId);
        assertNull(engine.pollLastCapturedEventJson());

        // Capture extended mouse side button 0x20
        InputBindingEngine.MatchResult capMouse = engine.onMouseButtonEvent(true, 0x20, 0);
        assertTrue(capMouse.consumed);
        assertNull(capMouse.actionId);
        String mouseJson = engine.pollLastCapturedEventJson();
        assertNotNull(mouseJson);
        SimpleJson.JsonObjectView parsedMouse = SimpleJson.parseObject(mouseJson);
        assertEquals("mouse", parsedMouse.getString("triggerType", ""));
        assertEquals(0x20, parsedMouse.getInt("code", 0));
        assertEquals(0, parsedMouse.getInt("metaMask", -1));
        assertTrue(parsedMouse.getLong("timestampMs", 0L) > 0L);
        assertNull("pollLastCapturedEventJson must clear after read", engine.pollLastCapturedEventJson());

        // Capture Ctrl + Key 45 (KEYCODE_Q)
        InputBindingEngine.MatchResult capKey = engine.onKeyEvent(
                InputBindingEngine.KEY_ACTION_DOWN, 45, ANDROID_META_CTRL_ON);
        assertTrue(capKey.consumed);
        String keyJson = engine.pollLastCapturedEventJson();
        assertNotNull(keyJson);
        SimpleJson.JsonObjectView parsedKey = SimpleJson.parseObject(keyJson);
        assertEquals("key", parsedKey.getString("triggerType", ""));
        assertEquals(45, parsedKey.getInt("code", 0));
        assertEquals(InputBindingRule.META_CTRL, parsedKey.getInt("metaMask", 0));

        // Exiting capture mode still swallows pending UP of captured keys/buttons
        engine.setCaptureMode(false);
        assertTrue(engine.onMouseButtonEvent(false, 0x20, 0).consumed);
        assertTrue(engine.onKeyEvent(InputBindingEngine.KEY_ACTION_UP, 45, 0).consumed);
    }

    @Test
    public void testJsonRoundTripAndDaemonConfigPersistence() {
        List<InputBindingRule> original = Arrays.asList(
                new InputBindingRule("r1", true, InputBindingRule.TRIGGER_MOUSE, 0x08, 0,
                        InputBindingRule.ACTION_BACK, "Mouse Back"),
                new InputBindingRule("r2", false, InputBindingRule.TRIGGER_MOUSE, 0x40,
                        InputBindingRule.META_SHIFT, InputBindingRule.ACTION_SWITCH_WINDOW, "Shift+Side40"),
                new InputBindingRule("r3", true, InputBindingRule.TRIGGER_KEY, 61,
                        InputBindingRule.META_ALT, InputBindingRule.ACTION_TOGGLE_FULLSCREEN, "Alt+Tab"));
        String json = InputBindingRule.toJsonList(original);
        List<InputBindingRule> decoded = InputBindingRule.fromJsonList(json);
        assertEquals(original, decoded);
        assertTrue(InputBindingRule.fromJsonList("[]").isEmpty());

        // Persist via DaemonConfig and reload in a fresh instance
        File confF = new File(tmpDir.getRoot(), "bindings_main.conf");
        File profF = new File(tmpDir.getRoot(), "bindings_prof.conf");
        DaemonConfig cfg1 = new DaemonConfig(confF, profF);
        cfg1.loadAll();
        assertEquals(InputBindingRule.defaultRules(),
                InputBindingRule.fromJsonList(cfg1.getInputBindingsJson()));

        cfg1.setInputBindingsJson(json);

        DaemonConfig cfg2 = new DaemonConfig(confF, profF);
        cfg2.loadAll();
        assertEquals(original, InputBindingRule.fromJsonList(cfg2.getInputBindingsJson()));
    }

    @Test
    public void testHasEnabledKeyBindingAndYuyanImeCoexistenceRoutingPolicy() {
        assertFalse(InputBindingEngine.hasEnabledKeyBinding(InputBindingRule.defaultRules()));
        List<InputBindingRule> disabledKey = Collections.singletonList(
                new InputBindingRule("k_off", false, InputBindingRule.TRIGGER_KEY, 131, 0,
                        InputBindingRule.ACTION_SPOTLIGHT, "F1"));
        assertFalse(InputBindingEngine.hasEnabledKeyBinding(disabledKey));
        List<InputBindingRule> enabledKey = Arrays.asList(
                new InputBindingRule("m1", true, InputBindingRule.TRIGGER_MOUSE, 0x08, 0,
                        InputBindingRule.ACTION_BACK, "Mouse Back"),
                new InputBindingRule("k_on", true, InputBindingRule.TRIGGER_KEY, 131, 0,
                        InputBindingRule.ACTION_SPOTLIGHT, "F1"));
        assertTrue(InputBindingEngine.hasEnabledKeyBinding(enabledKey));
        assertTrue(InputBindingEngine.hasEnabledKeyBindingJson(InputBindingRule.toJsonList(enabledKey)));

        // Without custom key bindings, Yuyan/bridge IME keeps direct window keyboard routing
        assertTrue(MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                true, false, false, 0, 42, true, true, false));
        assertFalse(MouseRoutingPolicy.shouldAssociateDeviceToOuterDisplay(
                false, true, true, MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, true, false));

        // With enabled custom key bindings, keyboard routes through outer compositor display first
        // so custom bindings work alongside Yuyan IME without opening Control Center
        assertFalse(MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                true, false, false, 0, 42, true, true, true));
        assertTrue(MouseRoutingPolicy.shouldAssociateDeviceToOuterDisplay(
                false, true, true, MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, true, true));
    }
}
