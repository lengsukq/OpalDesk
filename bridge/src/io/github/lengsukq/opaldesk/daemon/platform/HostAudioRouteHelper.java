package io.github.lengsukq.opaldesk.daemon.platform;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Forces global media playback to the host phone's built-in speaker when an external HDMI / USB-C
 * portable monitor lacks speakers, and cleanly restores system default routing when disabled or
 * when the daemon / external desktop session ends.
 *
 * <p>Compatible with Android 10–17 under {@code uid=2000} ({@code com.android.shell}) via pure
 * AOSP reflection with zero vendor-specific APIs:
 * <ol>
 *   <li>Primary (Android 12–17): {@code IAudioService.setDevicesRoleForStrategy} with
 *       {@code AudioDeviceAttributes(ROLE_OUTPUT, TYPE_BUILTIN_SPEAKER, "")} on
 *       {@code STRATEGY_MEDIA} / {@code DEVICE_ROLE_PREFERRED}.</li>
 *   <li>Compat (Android 10–17 &amp; OEM ROMs): {@code AudioSystem.setForceUse(FOR_MEDIA, FORCE_SPEAKER)}.</li>
 *   <li>Safe fallback: if media stream still routes to HDMI/AUX_DIGITAL, clears
 *       {@code FOR_HDMI_SYSTEM_AUDIO} and re-asserts {@code FOR_MEDIA -> FORCE_SPEAKER}.</li>
 * </ol>
 */
public final class HostAudioRouteHelper {
    public static final int STRATEGY_MEDIA = 0;
    public static final int DEVICE_ROLE_PREFERRED = 1;
    public static final int ROLE_OUTPUT = 2;
    public static final int TYPE_BUILTIN_SPEAKER = 2;

    public static final int FOR_MEDIA = 1;
    public static final int FOR_HDMI_SYSTEM_AUDIO = 5;
    public static final int FORCE_NONE = 0;
    public static final int FORCE_SPEAKER = 1;

    public static final int STREAM_MUSIC = 3;
    public static final int DEVICE_OUT_AUX_DIGITAL = 0x400;
    public static final int DEVICE_OUT_HDMI_ARC = 0x40000;
    public static final int DEVICE_OUT_HDMI_EARC = 0x40001;

    /** Testable platform seam for Android audio service &amp; AudioSystem hidden APIs. */
    public interface AudioRouteBackend {
        boolean setPreferredSpeakerForMediaStrategy();
        boolean clearPreferredDevicesForMediaStrategy();
        boolean setAudioSystemForceUse(int usage, int config);
        boolean isMediaRoutingToExternalDisplay();
    }

    /** Result snapshot returned by apply/restore operations for diagnostics &amp; unit tests. */
    public static final class RouteResult {
        public final boolean forcePhone;
        public final String method;
        public final boolean ok;

        public RouteResult(boolean forcePhone, String method, boolean ok) {
            this.forcePhone = forcePhone;
            this.method = method != null ? method : "none";
            this.ok = ok;
        }
    }

    private final AudioRouteBackend backend;
    private volatile boolean forcePhoneSpeakerActive = false;
    private volatile boolean routeModified = false;

    public HostAudioRouteHelper() {
        this(new ReflectionAudioRouteBackend());
    }

    public HostAudioRouteHelper(AudioRouteBackend backend) {
        this.backend = (backend != null) ? backend : new ReflectionAudioRouteBackend();
    }

    public boolean isForcePhoneSpeakerActive() {
        return forcePhoneSpeakerActive;
    }

    public boolean isRouteModified() {
        return routeModified;
    }

    /**
     * Enables or disables forced built-in speaker routing for media playback.
     */
    public synchronized boolean setForcePhoneSpeaker(boolean enabled) {
        return applyForcePhoneSpeaker(enabled, false).ok;
    }

    /**
     * Re-applies forced built-in speaker routing if currently active (e.g. after HDMI/USB-C
     * monitor hotplug when Android AudioService tries to switch media output to HDMI).
     */
    public synchronized boolean reapplyIfEnabled() {
        if (!forcePhoneSpeakerActive) return false;
        return applyForcePhoneSpeaker(true, true).ok;
    }

    /**
     * Cleanly restores system default audio routing on daemon shutdown or helper disconnect
     * so headphones / Bluetooth devices are never left stuck on the built-in speaker.
     */
    public synchronized void restoreDefaultOnShutdown() {
        if (!forcePhoneSpeakerActive && !routeModified) return;
        applyForcePhoneSpeaker(false, false);
    }

    synchronized RouteResult applyForcePhoneSpeaker(boolean enabled, boolean isReapply) {
        String suffix = isReapply ? "_reapply" : "";
        if (enabled) {
            boolean strategyOk = safeCall("setPreferredSpeakerForMediaStrategy",
                    backend::setPreferredSpeakerForMediaStrategy);
            boolean forceUseOk = safeCall("setForceUse(FOR_MEDIA,FORCE_SPEAKER)",
                    () -> backend.setAudioSystemForceUse(FOR_MEDIA, FORCE_SPEAKER));
            boolean fallbackOk = false;
            boolean stillHdmi = safeCall("isMediaRoutingToExternalDisplay",
                    backend::isMediaRoutingToExternalDisplay);
            if (stillHdmi) {
                boolean hdmiCleared = safeCall("setForceUse(FOR_HDMI_SYSTEM_AUDIO,FORCE_NONE)",
                        () -> backend.setAudioSystemForceUse(FOR_HDMI_SYSTEM_AUDIO, FORCE_NONE));
                boolean reassertOk = safeCall("setForceUse(FOR_MEDIA,FORCE_SPEAKER)_fallback",
                        () -> backend.setAudioSystemForceUse(FOR_MEDIA, FORCE_SPEAKER));
                fallbackOk = hdmiCleared || reassertOk;
            }
            boolean ok = strategyOk || forceUseOk || fallbackOk;
            String method = formatEnableMethod(strategyOk, forceUseOk, fallbackOk) + suffix;
            forcePhoneSpeakerActive = true;
            if (ok) {
                routeModified = true;
            }
            logRoute(true, method, ok);
            return new RouteResult(true, method, ok);
        } else {
            boolean strategyCleared = safeCall("clearPreferredDevicesForMediaStrategy",
                    backend::clearPreferredDevicesForMediaStrategy);
            boolean forceCleared = safeCall("setForceUse(FOR_MEDIA,FORCE_NONE)",
                    () -> backend.setAudioSystemForceUse(FOR_MEDIA, FORCE_NONE));
            boolean ok = strategyCleared || forceCleared;
            String method = formatRestoreMethod(strategyCleared, forceCleared);
            forcePhoneSpeakerActive = false;
            routeModified = false;
            logRoute(false, method, ok);
            return new RouteResult(false, method, ok);
        }
    }

    static boolean isExternalDisplayDeviceMask(int deviceMask) {
        if (deviceMask == 0) return false;
        return (deviceMask & DEVICE_OUT_AUX_DIGITAL) != 0
                || (deviceMask & DEVICE_OUT_HDMI_ARC) != 0
                || deviceMask == DEVICE_OUT_HDMI_EARC;
    }

    private static String formatEnableMethod(boolean strategyOk, boolean forceUseOk, boolean fallbackOk) {
        if (strategyOk && forceUseOk) {
            return fallbackOk ? "strategy+forceUse+hdmiFallback" : "strategy+forceUse";
        }
        if (strategyOk) return fallbackOk ? "strategy+hdmiFallback" : "strategy";
        if (forceUseOk) return fallbackOk ? "forceUse+hdmiFallback" : "forceUse";
        if (fallbackOk) return "hdmiFallback";
        return "none";
    }

    private static String formatRestoreMethod(boolean strategyCleared, boolean forceCleared) {
        if (strategyCleared && forceCleared) return "restore_strategy+forceUse";
        if (strategyCleared) return "restore_strategy";
        if (forceCleared) return "restore_forceUse";
        return "restore_none";
    }

    private static void logRoute(boolean forcePhone, String method, boolean ok) {
        System.out.println("OPALDESK_AUDIO_ROUTE forcePhone=" + forcePhone
                + " method=" + method
                + " ok=" + ok);
    }

    private interface BoolOp {
        boolean run() throws Throwable;
    }

    private static boolean safeCall(String op, BoolOp action) {
        try {
            return action.run();
        } catch (Throwable t) {
            System.err.println("OPALDESK_AUDIO_ROUTE_WARN op=" + op + " err=" + AndroidServices.unwrapMessage(t));
            return false;
        }
    }

    /** Default Android 10–17 reflection backend targeting IAudioService + AudioSystem. */
    static final class ReflectionAudioRouteBackend implements AudioRouteBackend {
        private volatile Object cachedAudioService;
        private volatile int cachedMediaStrategyId = -1;

        private Object getAudioService() {
            Object svc = cachedAudioService;
            if (svc != null) return svc;
            svc = AndroidServices.getServiceInterface("audio", "android.media.IAudioService$Stub");
            if (svc != null) cachedAudioService = svc;
            return svc;
        }

        private int resolveMediaStrategyId() {
            int cached = cachedMediaStrategyId;
            if (cached >= 0) return cached;
            int resolved = STRATEGY_MEDIA;
            try {
                Class<?> apsCls = Class.forName("android.media.audiopolicy.AudioProductStrategy");
                Method getAll = apsCls.getMethod("getAudioProductStrategies");
                Object listObj = getAll.invoke(null);
                if (listObj instanceof List) {
                    Class<?> aaBuilderCls = Class.forName("android.media.AudioAttributes$Builder");
                    Object builder = aaBuilderCls.getConstructor().newInstance();
                    aaBuilderCls.getMethod("setUsage", int.class).invoke(builder, 1); // USAGE_MEDIA
                    Object mediaAttrs = aaBuilderCls.getMethod("build").invoke(builder);
                    Method supports = apsCls.getMethod("supportsAudioAttributes",
                            Class.forName("android.media.AudioAttributes"));
                    Method getId = apsCls.getMethod("getId");
                    for (Object strat : (List<?>) listObj) {
                        if (strat == null) continue;
                        Object match = supports.invoke(strat, mediaAttrs);
                        if (Boolean.TRUE.equals(match)) {
                            Object idObj = getId.invoke(strat);
                            if (idObj instanceof Number) {
                                resolved = ((Number) idObj).intValue();
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                resolved = STRATEGY_MEDIA;
            }
            cachedMediaStrategyId = resolved;
            return resolved;
        }

        private Object createBuiltinSpeakerAttributes() throws ReflectiveOperationException {
            Class<?> adaCls = Class.forName("android.media.AudioDeviceAttributes");
            Constructor<?> ctor = adaCls.getConstructor(int.class, int.class, String.class);
            return ctor.newInstance(ROLE_OUTPUT, TYPE_BUILTIN_SPEAKER, "");
        }

        @Override
        public boolean setPreferredSpeakerForMediaStrategy() {
            Object iAudio = getAudioService();
            if (iAudio == null) return false;
            int strategyId = resolveMediaStrategyId();
            try {
                Object speakerAttr = createBuiltinSpeakerAttributes();
                List<Object> devices = Collections.singletonList(speakerAttr);
                Method setRole = AndroidServices.findMethodOrNull(
                        iAudio.getClass(), "setDevicesRoleForStrategy", int.class, int.class, List.class);
                if (setRole != null) {
                    Object res = setRole.invoke(iAudio, strategyId, DEVICE_ROLE_PREFERRED, devices);
                    return isSuccessReturn(res);
                }
                Method setPref = AndroidServices.findMethodOrNull(
                        iAudio.getClass(), "setPreferredDeviceForStrategy",
                        int.class, speakerAttr.getClass());
                if (setPref != null) {
                    Object res = setPref.invoke(iAudio, strategyId, speakerAttr);
                    return isSuccessReturn(res);
                }
                return false;
            } catch (Throwable t) {
                System.err.println("OPALDESK_AUDIO_ROUTE_WARN op=setDevicesRoleForStrategy err="
                        + AndroidServices.unwrapMessage(t));
                return false;
            }
        }

        @Override
        public boolean clearPreferredDevicesForMediaStrategy() {
            Object iAudio = getAudioService();
            if (iAudio == null) return false;
            int strategyId = resolveMediaStrategyId();
            boolean cleared = false;
            try {
                Method clearRole = AndroidServices.findMethodOrNull(
                        iAudio.getClass(), "clearDevicesRoleForStrategy", int.class, int.class);
                if (clearRole != null) {
                    Object res = clearRole.invoke(iAudio, strategyId, DEVICE_ROLE_PREFERRED);
                    cleared = isSuccessReturn(res);
                }
            } catch (Throwable t) {
                System.err.println("OPALDESK_AUDIO_ROUTE_WARN op=clearDevicesRoleForStrategy err="
                        + AndroidServices.unwrapMessage(t));
            }
            if (!cleared) {
                try {
                    Object speakerAttr = createBuiltinSpeakerAttributes();
                    Method removeRole = AndroidServices.findMethodOrNull(
                            iAudio.getClass(), "removeDevicesRoleForStrategy",
                            int.class, int.class, List.class);
                    if (removeRole != null) {
                        Object res = removeRole.invoke(iAudio, strategyId, DEVICE_ROLE_PREFERRED,
                                Collections.singletonList(speakerAttr));
                        cleared = isSuccessReturn(res);
                    }
                } catch (Throwable t) {
                    System.err.println("OPALDESK_AUDIO_ROUTE_WARN op=removeDevicesRoleForStrategy err="
                            + AndroidServices.unwrapMessage(t));
                }
            }
            if (!cleared) {
                try {
                    Method removePref = AndroidServices.findMethodOrNull(
                            iAudio.getClass(), "removePreferredDeviceForStrategy", int.class);
                    if (removePref != null) {
                        Object res = removePref.invoke(iAudio, strategyId);
                        cleared = isSuccessReturn(res);
                    }
                } catch (Throwable t) {
                    System.err.println("OPALDESK_AUDIO_ROUTE_WARN op=removePreferredDeviceForStrategy err="
                            + AndroidServices.unwrapMessage(t));
                }
            }
            return cleared;
        }

        @Override
        public boolean setAudioSystemForceUse(int usage, int config) {
            try {
                Class<?> audioSys = Class.forName("android.media.AudioSystem");
                Method setForceUse = audioSys.getMethod("setForceUse", int.class, int.class);
                Object res = setForceUse.invoke(null, usage, config);
                return isSuccessReturn(res);
            } catch (Throwable t) {
                System.err.println("OPALDESK_AUDIO_ROUTE_WARN op=AudioSystem.setForceUse("
                        + usage + "," + config + ") err=" + AndroidServices.unwrapMessage(t));
                return false;
            }
        }

        @Override
        public boolean isMediaRoutingToExternalDisplay() {
            try {
                Class<?> audioSys = Class.forName("android.media.AudioSystem");
                Method getDevices = audioSys.getMethod("getDevicesForStream", int.class);
                Object res = getDevices.invoke(null, STREAM_MUSIC);
                if (res instanceof Number) {
                    return isExternalDisplayDeviceMask(((Number) res).intValue());
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        private static boolean isSuccessReturn(Object res) {
            if (res == null) return true;
            if (res instanceof Integer) return ((Integer) res) == 0;
            if (res instanceof Boolean) return (Boolean) res;
            return true;
        }
    }
}
