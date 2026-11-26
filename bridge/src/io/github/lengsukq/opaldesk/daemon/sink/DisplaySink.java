package io.github.lengsukq.opaldesk.daemon.sink;

import android.view.Surface;

/**
 * Unified abstraction over display output sinks (physical HDMI/DP vs remote USB/Wi-Fi stream).
 */
public interface DisplaySink {
    int SINK_NONE = 0;
    int SINK_PHYSICAL = 1;
    int SINK_REMOTE_USB = 2;
    int SINK_REMOTE_WIFI = 3;
    int SINK_REMOTE_WIFI_ADB = 4;
    int SINK_LOCAL = 5;
    int SINK_REMOTE_USB_TETHER = 6;

    static boolean isTlsNetworkSink(int sinkType) {
        return sinkType == SINK_REMOTE_WIFI || sinkType == SINK_REMOTE_USB_TETHER;
    }

    int getSinkType();

    boolean isActive();

    int getWidth();

    int getHeight();

    int getDpi();

    float getFps();

    int getBitrateKbps();

    int getActualFpsX100();

    Surface getInputSurface();

    void requestKeyFrame();

    void updateBitrateKbps(int bitrateKbps);

    void stop();
}
