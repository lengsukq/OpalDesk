package io.github.lengsukq.opaldesk.daemon.input;

import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, pure Java 11 unified keyboard &amp; mouse interception/remapping engine.
 *
 * <p>Implements an anti-stuck-key paired press/release state machine: when a key or mouse button
 * press matches an enabled {@link InputBindingRule}, the engine records that code as actively
 * intercepted and silently consumes the matching release event even if modifiers were released
 * earlier or rules changed mid-press. Also supports live capture mode for arbitrary keyboard
 * keycodes and extended mouse side-button bitmasks ({@code 0x04}, {@code 0x08}, {@code 0x10},
 * {@code 0x20}, {@code 0x40}, {@code 0x80}, ...).
 */
public final class InputBindingEngine {
    // Standard Android KeyEvent action values (kept pure-JVM without android.view imports)
    public static final int KEY_ACTION_DOWN = 0;
    public static final int KEY_ACTION_UP = 1;

    // Standard Android KeyEvent meta masks
    private static final int ANDROID_META_SHIFT_MASK = 0x01 | 0x40 | 0x80;
    private static final int ANDROID_META_ALT_MASK = 0x02 | 0x10 | 0x20;
    private static final int ANDROID_META_CTRL_MASK = 0x1000 | 0x2000 | 0x4000;
    private static final int ANDROID_META_SUPER_MASK = 0x10000 | 0x20000 | 0x40000;

    // Standard Android modifier-only keycodes ignored as standalone capture triggers
    private static final int KEYCODE_ALT_LEFT = 57;
    private static final int KEYCODE_ALT_RIGHT = 58;
    private static final int KEYCODE_SHIFT_LEFT = 59;
    private static final int KEYCODE_SHIFT_RIGHT = 60;
    private static final int KEYCODE_CTRL_LEFT = 113;
    private static final int KEYCODE_CTRL_RIGHT = 114;
    private static final int KEYCODE_META_LEFT = 117;
    private static final int KEYCODE_META_RIGHT = 118;

    public static final class MatchResult {
        public static final MatchResult NOT_CONSUMED = new MatchResult(false, null, null);
        public static final MatchResult CONSUMED_SILENT = new MatchResult(true, null, null);

        public final boolean consumed;
        public final InputBindingRule rule;
        public final String actionId;

        public MatchResult(boolean consumed, InputBindingRule rule, String actionId) {
            this.consumed = consumed;
            this.rule = rule;
            this.actionId = actionId;
        }
    }

    private volatile List<InputBindingRule> rules;
    private volatile boolean captureMode = false;
    private final Set<Integer> activeInterceptedKeys = ConcurrentHashMap.newKeySet();
    private final Set<Integer> activeInterceptedMouseButtons = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> lastCapturedEventJson = new AtomicReference<>(null);

    public InputBindingEngine() {
        this(InputBindingRule.defaultRules());
    }

    public InputBindingEngine(List<InputBindingRule> initialRules) {
        setRules(initialRules != null ? initialRules : InputBindingRule.defaultRules());
    }

    public void setRules(List<InputBindingRule> nextRules) {
        if (nextRules == null || nextRules.isEmpty()) {
            this.rules = Collections.emptyList();
            return;
        }
        List<InputBindingRule> copy = new ArrayList<>(Math.min(nextRules.size(), InputBindingRule.MAX_RULES));
        for (InputBindingRule r : nextRules) {
            if (copy.size() >= InputBindingRule.MAX_RULES) break;
            if (r != null && r.isValid()) {
                copy.add(r);
            }
        }
        this.rules = Collections.unmodifiableList(copy);
    }

    public List<InputBindingRule> getRules() {
        return rules;
    }

    public String getRulesJson() {
        return InputBindingRule.toJsonList(rules);
    }

    public void setRulesFromJson(String json) {
        setRules(InputBindingRule.fromJsonList(json));
    }

    public boolean hasEnabledKeyBinding() {
        return hasEnabledKeyBinding(this.rules);
    }

    public static boolean hasEnabledKeyBinding(List<InputBindingRule> rules) {
        if (rules == null || rules.isEmpty()) return false;
        for (InputBindingRule r : rules) {
            if (r != null && r.enabled && r.isValid() && InputBindingRule.TRIGGER_KEY.equals(r.triggerType)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasEnabledKeyBindingJson(String json) {
        return hasEnabledKeyBinding(InputBindingRule.fromJsonList(json));
    }

    public void setCaptureMode(boolean active) {
        this.captureMode = active;
        if (active) {
            lastCapturedEventJson.set(null);
        }
    }

    public boolean isCaptureMode() {
        return captureMode;
    }

    /** Returns and clears the most recently captured raw input event JSON, or {@code null} if none. */
    public String pollLastCapturedEventJson() {
        return lastCapturedEventJson.getAndSet(null);
    }

    public void clearActiveHeldState() {
        activeInterceptedKeys.clear();
        activeInterceptedMouseButtons.clear();
    }

    public static int normalizeAndroidMetaState(int androidMeta) {
        int norm = InputBindingRule.META_NONE;
        if ((androidMeta & ANDROID_META_ALT_MASK) != 0) norm |= InputBindingRule.META_ALT;
        if ((androidMeta & ANDROID_META_CTRL_MASK) != 0) norm |= InputBindingRule.META_CTRL;
        if ((androidMeta & ANDROID_META_SHIFT_MASK) != 0) norm |= InputBindingRule.META_SHIFT;
        if ((androidMeta & ANDROID_META_SUPER_MASK) != 0) norm |= InputBindingRule.META_SUPER;
        return norm;
    }

    public MatchResult onKeyEvent(int action, int keyCode, int androidMeta) {
        return onKeyEvent(action, keyCode, androidMeta, 0);
    }

    public MatchResult onKeyEvent(int action, int keyCode, int androidMeta, int repeatCount) {
        if (keyCode <= 0) return MatchResult.NOT_CONSUMED;
        int normMeta = normalizeAndroidMetaState(androidMeta);

        if (captureMode) {
            if (action == KEY_ACTION_DOWN) {
                if (!isModifierOnlyKeyCode(keyCode)) {
                    if (repeatCount > 0 || activeInterceptedKeys.contains(keyCode)) {
                        activeInterceptedKeys.add(keyCode);
                        return MatchResult.CONSUMED_SILENT;
                    }
                    activeInterceptedKeys.add(keyCode);
                    recordCapturedEvent(InputBindingRule.TRIGGER_KEY, keyCode, normMeta);
                }
                return MatchResult.CONSUMED_SILENT;
            }
            if (action == KEY_ACTION_UP) {
                activeInterceptedKeys.remove(keyCode);
                return MatchResult.CONSUMED_SILENT;
            }
            return MatchResult.CONSUMED_SILENT;
        }

        if (action == KEY_ACTION_UP) {
            if (activeInterceptedKeys.remove(keyCode)) {
                return MatchResult.CONSUMED_SILENT;
            }
            return MatchResult.NOT_CONSUMED;
        }

        if (action == KEY_ACTION_DOWN) {
            if (activeInterceptedKeys.contains(keyCode)) {
                return MatchResult.CONSUMED_SILENT;
            }
            InputBindingRule matched = findMatchingRule(InputBindingRule.TRIGGER_KEY, keyCode, normMeta);
            if (matched != null) {
                boolean firstPress = activeInterceptedKeys.add(keyCode);
                if (repeatCount > 0 || !firstPress) {
                    return MatchResult.CONSUMED_SILENT;
                }
                logIntercepted(InputBindingRule.TRIGGER_KEY, keyCode, normMeta, matched.actionId);
                return new MatchResult(true, matched, matched.actionId);
            }
        }
        return MatchResult.NOT_CONSUMED;
    }

    public MatchResult onMouseButtonEvent(boolean isDown, int buttonMask, int androidMeta) {
        if (buttonMask <= 0) return MatchResult.NOT_CONSUMED;
        int normMeta = normalizeAndroidMetaState(androidMeta);

        if (captureMode) {
            if (isDown) {
                activeInterceptedMouseButtons.add(buttonMask);
                recordCapturedEvent(InputBindingRule.TRIGGER_MOUSE, buttonMask, normMeta);
            } else {
                activeInterceptedMouseButtons.remove(buttonMask);
            }
            return MatchResult.CONSUMED_SILENT;
        }

        if (!isDown) {
            if (activeInterceptedMouseButtons.remove(buttonMask)) {
                return MatchResult.CONSUMED_SILENT;
            }
            return MatchResult.NOT_CONSUMED;
        }

        InputBindingRule matched = findMatchingRule(InputBindingRule.TRIGGER_MOUSE, buttonMask, normMeta);
        if (matched != null) {
            activeInterceptedMouseButtons.add(buttonMask);
            logIntercepted(InputBindingRule.TRIGGER_MOUSE, buttonMask, normMeta, matched.actionId);
            return new MatchResult(true, matched, matched.actionId);
        }
        return MatchResult.NOT_CONSUMED;
    }

    private InputBindingRule findMatchingRule(String triggerType, int code, int normMeta) {
        List<InputBindingRule> snapshot = this.rules;
        for (int i = 0, n = snapshot.size(); i < n; i++) {
            InputBindingRule r = snapshot.get(i);
            if (r.matches(triggerType, code, normMeta)) {
                return r;
            }
        }
        return null;
    }

    private void recordCapturedEvent(String triggerType, int code, int normMeta) {
        long nowMs = System.currentTimeMillis();
        String label = formatTriggerLabel(triggerType, code, normMeta);
        String json = new SimpleJson.ObjectBuilder()
                .put("triggerType", triggerType)
                .put("code", code)
                .put("metaMask", normMeta)
                .put("label", label)
                .put("timestampMs", nowMs)
                .build();
        lastCapturedEventJson.set(json);
        System.out.println("[InputBinding] CAPTURED trigger=" + triggerType
                + " code=0x" + Integer.toHexString(code)
                + " meta=0x" + Integer.toHexString(normMeta));
    }

    private static void logIntercepted(String triggerType, int code, int normMeta, String actionId) {
        System.out.println("[InputBinding] INTERCEPTED trigger=" + triggerType
                + " code=0x" + Integer.toHexString(code)
                + " meta=0x" + Integer.toHexString(normMeta)
                + " -> action=" + actionId);
    }

    private static boolean isModifierOnlyKeyCode(int keyCode) {
        return keyCode == KEYCODE_ALT_LEFT || keyCode == KEYCODE_ALT_RIGHT
                || keyCode == KEYCODE_SHIFT_LEFT || keyCode == KEYCODE_SHIFT_RIGHT
                || keyCode == KEYCODE_CTRL_LEFT || keyCode == KEYCODE_CTRL_RIGHT
                || keyCode == KEYCODE_META_LEFT || keyCode == KEYCODE_META_RIGHT;
    }

    public static String formatTriggerLabel(String triggerType, int code, int normMeta) {
        StringBuilder sb = new StringBuilder();
        if ((normMeta & InputBindingRule.META_CTRL) != 0) sb.append("Ctrl+");
        if ((normMeta & InputBindingRule.META_ALT) != 0) sb.append("Alt+");
        if ((normMeta & InputBindingRule.META_SHIFT) != 0) sb.append("Shift+");
        if ((normMeta & InputBindingRule.META_SUPER) != 0) sb.append("Meta+");
        if (InputBindingRule.TRIGGER_MOUSE.equals(triggerType)) {
            if (code == InputBindingRule.MOUSE_BUTTON_SECONDARY) sb.append("Mouse Right");
            else if (code == InputBindingRule.MOUSE_BUTTON_TERTIARY) sb.append("Mouse Middle");
            else if (code == InputBindingRule.MOUSE_BUTTON_BACK) sb.append("Mouse Back");
            else if (code == InputBindingRule.MOUSE_BUTTON_FORWARD) sb.append("Mouse Forward");
            else sb.append("Mouse 0x").append(Integer.toHexString(code).toUpperCase(java.util.Locale.US));
        } else {
            sb.append("Key #").append(code);
        }
        return sb.toString();
    }
}
