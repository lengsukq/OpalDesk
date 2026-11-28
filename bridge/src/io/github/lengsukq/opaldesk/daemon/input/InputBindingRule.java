package io.github.lengsukq.opaldesk.daemon.input;

import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure Java 11 immutable rule model for unified keyboard &amp; mouse input interception/remapping.
 *
 * <p>Supports Android {@code KEYCODE_*} values when {@link #triggerType} is {@link #TRIGGER_KEY}
 * and arbitrary positive integer mouse button bitmasks (e.g. {@code 0x02}, {@code 0x04},
 * {@code 0x08}, {@code 0x10}, {@code 0x20}, {@code 0x40}, {@code 0x80}) when
 * {@link #triggerType} is {@link #TRIGGER_MOUSE}.
 */
public final class InputBindingRule {
    public static final String TRIGGER_KEY = "key";
    public static final String TRIGGER_MOUSE = "mouse";

    // Normalized modifier bitmasks
    public static final int META_NONE = 0;
    public static final int META_ALT = 1;
    public static final int META_CTRL = 2;
    public static final int META_SHIFT = 4;
    public static final int META_SUPER = 8;
    public static final int META_ALL_MASK = META_ALT | META_CTRL | META_SHIFT | META_SUPER;

    // Standard mouse button bitmasks (matching OpalCastProtocol & Android MotionEvent)
    public static final int MOUSE_BUTTON_SECONDARY = 0x02;
    public static final int MOUSE_BUTTON_TERTIARY = 0x04;
    public static final int MOUSE_BUTTON_BACK = 0x08;
    public static final int MOUSE_BUTTON_FORWARD = 0x10;

    // Built-in action IDs
    public static final String ACTION_BACK = "back";
    public static final String ACTION_TOGGLE_MAXIMIZE = "toggle_maximize";
    public static final String ACTION_TOGGLE_FULLSCREEN = "toggle_fullscreen";
    public static final String ACTION_SNAP_LEFT = "snap_left";
    public static final String ACTION_SNAP_RIGHT = "snap_right";
    public static final String ACTION_TILE_2_SPLIT = "tile_2_split";
    public static final String ACTION_CLOSE_WINDOW = "close_window";
    public static final String ACTION_MINIMIZE_WINDOW = "minimize_window";
    public static final String ACTION_SWITCH_WINDOW = "switch_window";
    public static final String ACTION_SPOTLIGHT = "spotlight";

    public static final int MAX_RULES = 128;

    public final String id;
    public final boolean enabled;
    public final String triggerType;
    public final int code;
    public final int metaMask;
    public final String actionId;
    public final String label;

    public InputBindingRule(
            String id,
            boolean enabled,
            String triggerType,
            int code,
            int metaMask,
            String actionId,
            String label) {
        this.id = (id != null) ? id.trim() : "";
        this.enabled = enabled;
        this.triggerType = normalizeTriggerType(triggerType);
        this.code = code;
        this.metaMask = metaMask & META_ALL_MASK;
        this.actionId = normalizeActionId(actionId);
        this.label = (label != null) ? label.trim() : "";
    }

    public boolean isValid() {
        return !triggerType.isEmpty() && code > 0 && !actionId.isEmpty();
    }

    public boolean matches(String eventTriggerType, int eventCode, int normalizedMetaMask) {
        return enabled
                && code == eventCode
                && metaMask == (normalizedMetaMask & META_ALL_MASK)
                && triggerType.equals(eventTriggerType);
    }

    public String toJson() {
        return new SimpleJson.ObjectBuilder()
                .put("id", id)
                .put("enabled", enabled)
                .put("triggerType", triggerType)
                .put("code", code)
                .put("metaMask", metaMask)
                .put("actionId", actionId)
                .put("label", label)
                .build();
    }

    public static String toJsonList(List<InputBindingRule> rules) {
        SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
        if (rules != null) {
            for (InputBindingRule rule : rules) {
                if (rule != null && rule.isValid()) {
                    arr.addRaw(rule.toJson());
                }
            }
        }
        return arr.build();
    }

    public static List<InputBindingRule> fromJsonList(String json) {
        if (json == null) return Collections.emptyList();
        String trimmed = json.trim();
        if (trimmed.isEmpty() || "[]".equals(trimmed)) return Collections.emptyList();
        String wrapped = trimmed.startsWith("[") ? ("{\"rules\":" + trimmed + "}") : trimmed;
        SimpleJson.JsonObjectView root = SimpleJson.parseObject(wrapped);
        List<SimpleJson.JsonObjectView> items = root.getObjectList("rules");
        if (items.isEmpty()) return Collections.emptyList();
        List<InputBindingRule> out = new ArrayList<>(Math.min(items.size(), MAX_RULES));
        for (SimpleJson.JsonObjectView item : items) {
            if (out.size() >= MAX_RULES) break;
            if (item == null) continue;
            String id = item.getString("id", "");
            boolean enabled = item.getBoolean("enabled", true);
            String triggerType = item.getString("triggerType", "");
            int code = item.getInt("code", 0);
            int metaMask = item.getInt("metaMask", META_NONE);
            String actionId = item.getString("actionId", "");
            String label = item.getString("label", "");
            InputBindingRule rule = new InputBindingRule(id, enabled, triggerType, code, metaMask, actionId, label);
            if (rule.isValid()) {
                out.add(rule);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Default compatibility rules preserving legacy right-click &amp; mouse-back behavior while
     * allowing users to disable or remap either rule independently.
     */
    public static List<InputBindingRule> defaultRules() {
        List<InputBindingRule> defaults = new ArrayList<>(2);
        defaults.add(new InputBindingRule(
                "default_mouse_back",
                true,
                TRIGGER_MOUSE,
                MOUSE_BUTTON_BACK,
                META_NONE,
                ACTION_BACK,
                "Mouse Back -> Back"));
        defaults.add(new InputBindingRule(
                "default_mouse_right_back",
                true,
                TRIGGER_MOUSE,
                MOUSE_BUTTON_SECONDARY,
                META_NONE,
                ACTION_BACK,
                "Mouse Right -> Back"));
        return Collections.unmodifiableList(defaults);
    }

    public static String normalizeTriggerType(String raw) {
        if (raw == null) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (TRIGGER_KEY.equals(lower) || TRIGGER_MOUSE.equals(lower)) return lower;
        return "";
    }

    public static String normalizeActionId(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InputBindingRule)) return false;
        InputBindingRule that = (InputBindingRule) o;
        return enabled == that.enabled
                && code == that.code
                && metaMask == that.metaMask
                && Objects.equals(id, that.id)
                && Objects.equals(triggerType, that.triggerType)
                && Objects.equals(actionId, that.actionId)
                && Objects.equals(label, that.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, enabled, triggerType, code, metaMask, actionId, label);
    }

    @Override
    public String toString() {
        return "InputBindingRule{" + triggerType + ":0x" + Integer.toHexString(code)
                + " meta=0x" + Integer.toHexString(metaMask) + " -> " + actionId
                + " enabled=" + enabled + "}";
    }
}
