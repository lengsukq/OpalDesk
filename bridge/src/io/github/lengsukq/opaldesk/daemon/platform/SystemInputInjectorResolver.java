package io.github.lengsukq.opaldesk.daemon.platform;

import java.lang.reflect.Method;

/**
 * Pure-Java resolver for Android system input injection overloads across API 30–37+.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>{@code IInputManager.injectInputEvent(InputEvent, int)}</li>
 *   <li>{@code IInputManager.injectInputEventToTarget(InputEvent, int, int)} (Android 12–17+, {@code targetUid = -1})</li>
 *   <li>{@code InputManager.injectInputEvent(InputEvent, int)}</li>
 * </ol>
 */
public final class SystemInputInjectorResolver {
    public enum Kind {
        IINPUT_MANAGER_2_ARG,
        IINPUT_MANAGER_TARGET_3_ARG,
        INPUT_MANAGER_2_ARG
    }

    public static final class ResolvedInjector {
        public final Kind kind;
        public final Method method;

        public ResolvedInjector(Kind kind, Method method) {
            this.kind = kind;
            this.method = method;
        }
    }

    private SystemInputInjectorResolver() {}

    public static ResolvedInjector resolve(Class<?> iimCls, Class<?> imCls, Class<?> eventCls) {
        if (eventCls == null) return null;
        if (iimCls != null) {
            Method iim2 = findMethodOrNull(iimCls, "injectInputEvent", eventCls, int.class);
            if (iim2 != null) {
                return new ResolvedInjector(Kind.IINPUT_MANAGER_2_ARG, iim2);
            }
            Method iimTarget3 = findMethodOrNull(iimCls, "injectInputEventToTarget", eventCls, int.class, int.class);
            if (iimTarget3 != null) {
                return new ResolvedInjector(Kind.IINPUT_MANAGER_TARGET_3_ARG, iimTarget3);
            }
            for (Method m : iimCls.getMethods()) {
                if ("injectInputEvent".equals(m.getName()) && m.getParameterCount() == 2) {
                    return new ResolvedInjector(Kind.IINPUT_MANAGER_2_ARG, m);
                }
            }
        }
        if (imCls != null) {
            Method im2 = findMethodOrNull(imCls, "injectInputEvent", eventCls, int.class);
            if (im2 != null) {
                return new ResolvedInjector(Kind.INPUT_MANAGER_2_ARG, im2);
            }
        }
        return null;
    }

    private static Method findMethodOrNull(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) return null;
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
