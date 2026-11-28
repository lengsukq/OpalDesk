package io.github.lengsukq.opaldesk.daemon.platform;

import android.content.ClipData;
import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.input.ImmsDumpInspector;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Package-private {@code IClipboard} binder reflection, IME settings/switching, and in-process
 * {@code input_method} pipe dump helper extracted from {@link AndroidServices}.
 */
final class AndroidClipboardAndImeHelper {
    private static final Object CLIPBOARD_LOCK = new Object();
    private static final ExecutorService IMMS_DUMP_WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OpalImmsDumpWriter");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean sClipboardInitAttempted;
    private static volatile Object sIClipboard;
    private static volatile Method sSetPrimaryClipMethod;
    private static volatile int sSetPrimaryClipArgCount;
    private static volatile Method sGetPrimaryClipMethod;
    private static volatile int sGetPrimaryClipArgCount;

    private AndroidClipboardAndImeHelper() {}

    static boolean setPrimaryClip(ClipData clip) {
        if (clip == null) return false;
        ensureClipboardInit();
        Object cb = sIClipboard;
        Method m = sSetPrimaryClipMethod;
        if (cb == null || m == null) return false;
        try {
            switch (sSetPrimaryClipArgCount) {
                case 5: m.invoke(cb, clip, AndroidServices.SHELL_PACKAGE, null, 0, 0); return true;
                case 4: m.invoke(cb, clip, AndroidServices.SHELL_PACKAGE, null, 0); return true;
                case 3: m.invoke(cb, clip, AndroidServices.SHELL_PACKAGE, 0); return true;
                case 2: m.invoke(cb, clip, AndroidServices.SHELL_PACKAGE); return true;
                default: return false;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_CLIPBOARD_SET_ERR: " + AndroidServices.unwrapMessage(e));
            return false;
        }
    }

    static ClipData getPrimaryClip() {
        ensureClipboardInit();
        Object cb = sIClipboard;
        Method m = sGetPrimaryClipMethod;
        if (cb == null || m == null) return null;
        try {
            Object res;
            switch (sGetPrimaryClipArgCount) {
                case 4: res = m.invoke(cb, AndroidServices.SHELL_PACKAGE, null, 0, 0); break;
                case 3: res = m.invoke(cb, AndroidServices.SHELL_PACKAGE, null, 0); break;
                case 2: res = m.invoke(cb, AndroidServices.SHELL_PACKAGE, 0); break;
                case 1: res = m.invoke(cb, AndroidServices.SHELL_PACKAGE); break;
                default: return null;
            }
            return (res instanceof ClipData) ? (ClipData) res : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_CLIPBOARD_GET_ERR: " + AndroidServices.unwrapMessage(e));
            return null;
        }
    }

    private static void ensureClipboardInit() {
        if (sClipboardInitAttempted) return;
        synchronized (CLIPBOARD_LOCK) {
            if (sClipboardInitAttempted) return;
            sClipboardInitAttempted = true;
            Object cb = AndroidServices.getServiceInterface("clipboard", "android.content.IClipboard$Stub");
            if (cb == null) return;
            sIClipboard = cb;
            for (Method m : cb.getClass().getMethods()) {
                String name = m.getName();
                Class<?>[] pt = m.getParameterTypes();
                if ("setPrimaryClip".equals(name) && sSetPrimaryClipMethod == null) {
                    if (pt.length == 5 && pt[0] == ClipData.class && pt[1] == String.class
                            && pt[2] == String.class && pt[3] == int.class && pt[4] == int.class) {
                        sSetPrimaryClipMethod = m; sSetPrimaryClipArgCount = 5;
                    } else if (pt.length == 4 && pt[0] == ClipData.class && pt[1] == String.class
                            && pt[2] == String.class && pt[3] == int.class) {
                        sSetPrimaryClipMethod = m; sSetPrimaryClipArgCount = 4;
                    } else if (pt.length == 3 && pt[0] == ClipData.class && pt[1] == String.class
                            && pt[2] == int.class) {
                        sSetPrimaryClipMethod = m; sSetPrimaryClipArgCount = 3;
                    } else if (pt.length == 2 && pt[0] == ClipData.class && pt[1] == String.class) {
                        sSetPrimaryClipMethod = m; sSetPrimaryClipArgCount = 2;
                    }
                } else if ("getPrimaryClip".equals(name) && sGetPrimaryClipMethod == null) {
                    if (pt.length == 4 && pt[0] == String.class && pt[1] == String.class
                            && pt[2] == int.class && pt[3] == int.class) {
                        sGetPrimaryClipMethod = m; sGetPrimaryClipArgCount = 4;
                    } else if (pt.length == 3 && pt[0] == String.class && pt[1] == String.class
                            && pt[2] == int.class) {
                        sGetPrimaryClipMethod = m; sGetPrimaryClipArgCount = 3;
                    } else if (pt.length == 2 && pt[0] == String.class && pt[1] == int.class) {
                        sGetPrimaryClipMethod = m; sGetPrimaryClipArgCount = 2;
                    } else if (pt.length == 1 && pt[0] == String.class) {
                        sGetPrimaryClipMethod = m; sGetPrimaryClipArgCount = 1;
                    }
                }
            }
        }
    }

    static String getDefaultInputMethodId(Context context) {
        if (context != null) {
            try {
                String val = Settings.Secure.getString(
                        context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
                if (val != null && !val.trim().isEmpty() && !"null".equalsIgnoreCase(val.trim())) {
                    return val.trim();
                }
            } catch (RuntimeException ignored) {
            }
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                1500L, "settings", "get", "secure", "default_input_method");
        if (res.isSuccess()) {
            String trimmed = res.output.trim();
            if (!trimmed.isEmpty() && !"null".equalsIgnoreCase(trimmed)) {
                return trimmed;
            }
        }
        return "";
    }

    static List<String> listEnabledInputMethodIds(Context context) {
        ShellCommand.Result res = ShellCommand.runWithTimeout(2000L, "ime", "list", "-s");
        if (res.isSuccess() && !res.output.trim().isEmpty()) {
            List<String> parsed = parseLineImeIds(res.output);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        String raw = "";
        if (context != null) {
            try {
                String v = Settings.Secure.getString(
                        context.getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS);
                if (v != null) raw = v;
            } catch (RuntimeException ignored) {
            }
        }
        if (raw.isEmpty()) {
            ShellCommand.Result secRes = ShellCommand.runWithTimeout(
                    1500L, "settings", "get", "secure", "enabled_input_methods");
            if (secRes.isSuccess()) raw = secRes.output.trim();
        }
        return parseEnabledImeSetting(raw);
    }

    static boolean setDefaultInputMethodId(Context context, String imeId) {
        if (!DaemonConfig.PackagePolicy.isValidImeId(imeId)) {
            return false;
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(2500L, "ime", "set", imeId);
        if (isImeSetSuccessful(context, res, imeId)) {
            return true;
        }
        // Enable first in case the user picked an installed-but-disabled IME, then retry.
        ShellCommand.runWithTimeout(2000L, "ime", "enable", imeId);
        ShellCommand.Result retry = ShellCommand.runWithTimeout(2500L, "ime", "set", imeId);
        if (isImeSetSuccessful(context, retry, imeId)) {
            return true;
        }
        ShellCommand.Result cmdRes = ShellCommand.runWithTimeout(2500L, "cmd", "input_method", "set", imeId);
        if (isImeSetSuccessful(context, cmdRes, imeId)) {
            return true;
        }
        if (context != null) {
            try {
                boolean ok = Settings.Secure.putString(
                        context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, imeId);
                if (ok) return true;
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_IME_SET_SECURE_WARN id=" + imeId + ": " + AndroidServices.unwrapMessage(e));
            }
        }
        System.err.println("OPALDESK_IME_SET_FAIL id=" + imeId
                + " out=" + (retry.output != null ? retry.output.trim() : ""));
        return false;
    }

    static int getShowImeWithHardKeyboard(Context context) {
        if (context != null) {
            try {
                int v = Settings.Secure.getInt(context.getContentResolver(), "show_ime_with_hard_keyboard", -1);
                if (v == 0 || v == 1) return v;
            } catch (RuntimeException ignored) {
            }
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                1500L, "settings", "get", "secure", "show_ime_with_hard_keyboard");
        if (res.isSuccess()) {
            String trimmed = res.output.trim();
            if ("0".equals(trimmed)) return 0;
            if ("1".equals(trimmed)) return 1;
        }
        return -1;
    }

    static boolean setShowImeWithHardKeyboard(Context context, int value) {
        if (value != 0 && value != 1) return false;
        if (context != null) {
            try {
                if (Settings.Secure.putInt(context.getContentResolver(), "show_ime_with_hard_keyboard", value)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
            }
        }
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                1500L, "settings", "put", "secure", "show_ime_with_hard_keyboard", String.valueOf(value));
        return res.isSuccess();
    }

    private static boolean isImeSetSuccessful(Context context, ShellCommand.Result res, String expectedImeId) {
        if (res == null || res.timedOut) return false;
        String out = (res.output != null) ? res.output.trim() : "";
        if (res.exitCode == 0 && !out.startsWith("Unknown") && !out.contains("cannot be selected")) {
            return true;
        }
        return expectedImeId.equals(getDefaultInputMethodId(context));
    }

    private static List<String> parseLineImeIds(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\r?\n")) {
            String trimmed = line.trim();
            if (DaemonConfig.PackagePolicy.isValidImeId(trimmed) && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static List<String> parseEnabledImeSetting(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty() || "null".equalsIgnoreCase(raw.trim())) return out;
        for (String entry : raw.split(":")) {
            int semi = entry.indexOf(';');
            String id = (semi >= 0 ? entry.substring(0, semi) : entry).trim();
            if (DaemonConfig.PackagePolicy.isValidImeId(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    static ImmsDumpInspector.Snapshot inspectInputMethodService(int targetDisplayId) {
        if (targetDisplayId <= 0) return null;
        IBinder immsBinder = AndroidServices.getServiceBinder("input_method");
        if (immsBinder == null) return null;
        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor readEnd = pipe[0];
            final ParcelFileDescriptor writeEnd = pipe[1];
            IMMS_DUMP_WRITER.execute(() -> {
                try {
                    immsBinder.dump(writeEnd.getFileDescriptor(), new String[0]);
                } catch (RemoteException | RuntimeException ignored) {
                } finally {
                    try {
                        writeEnd.close();
                    } catch (IOException ignored) {
                    }
                }
            });
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(readEnd), StandardCharsets.UTF_8),
                    8192)) {
                return ImmsDumpInspector.parseReader(br, targetDisplayId);
            }
        } catch (IOException | RuntimeException e) {
            if (pipe != null) {
                try { pipe[0].close(); } catch (IOException ignored) {}
                try { pipe[1].close(); } catch (IOException ignored) {}
            }
            return null;
        }
    }
}
