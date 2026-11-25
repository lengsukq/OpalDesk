package io.github.lengsukq.opaldesk.daemon.platform;

import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

/**
 * Encapsulates the strictly optional (~5%) Root enhancements while keeping 95%+ of OpalDesk
 * completely functional under unprivileged ADB / Shizuku ({@code uid=2000}).
 *
 * <p>When activated via Root with {@code --root-enhance}, applies benign system-wide multi-display
 * settings BEFORE {@code OpalDeskDaemon.dropRootFailClosed()} drops the daemon to {@code uid=2000}.
 */
public final class RootEnhancer {
    private RootEnhancer() {}

    public static final class EnhancementReport {
        public final boolean rootEnhanced;
        public final boolean secureCaptureHint;

        public EnhancementReport(boolean rootEnhanced, boolean secureCaptureHint) {
            this.rootEnhanced = rootEnhanced;
            this.secureCaptureHint = secureCaptureHint;
        }
    }

    /**
     * Applies optional pre-drop enhancements if requested via CLI flag {@code --root-enhance}.
     * Safe to invoke under both {@code uid=0} and {@code uid=2000}.
     */
    public static EnhancementReport maybeApplyPreDropEnhancements(String[] args) {
        boolean requested = false;
        if (args != null) {
            for (String arg : args) {
                if ("--root-enhance".equals(arg)) {
                    requested = true;
                    break;
                }
            }
        }
        if (!requested) {
            return new EnhancementReport(false, true);
        }
        try {
            // Standard AOSP global settings allowing stubborn fixed-orientation apps to resize cleanly on secondary displays
            ShellCommand.runOutput("settings", "put", "global", "force_resizable_activities", "1");
            ShellCommand.runOutput("settings", "put", "global", "enable_non_resizable_multi_window", "1");
            // Prepare shared MJPEG camera feed directory accessible after dropping to uid=2000
            ShellCommand.runOutput("mkdir", "-p", "/data/local/tmp/opaldesk_cam");
            ShellCommand.runOutput("chmod", "0777", "/data/local/tmp/opaldesk_cam");
            System.out.println("OPALDESK_OPTIONAL_ROOT_ENHANCEMENTS_APPLIED");
            return new EnhancementReport(true, true);
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_ROOT_ENHANCE_WARN: " + e.getMessage());
            return new EnhancementReport(false, true);
        }
    }
}
