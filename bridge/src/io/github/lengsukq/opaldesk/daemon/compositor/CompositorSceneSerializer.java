package io.github.lengsukq.opaldesk.daemon.compositor;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure JVM scene JSON serializer and read-only window collection queries for {@link CompositorEngine}.
 */
final class CompositorSceneSerializer {
    private CompositorSceneSerializer() {}

    static String toSceneJson(
            Collection<WindowSession> sessions,
            long seq,
            DaemonConfig config,
            VirtualDisplayPool vdPool,
            int kbdRoutedDisplayId,
            boolean comboKbdOuter) {
        List<WindowSession> sorted = new ArrayList<>(sessions);
        sorted.sort(Comparator.comparingInt(w -> w.zOrder));
        SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
        for (WindowSession ws : sorted) {
            arr.addRaw(ws.toJson());
        }
        return new SimpleJson.ObjectBuilder()
                .put("seq", seq)
                .put("canvasW", config.getCanvasWidth())
                .put("canvasH", config.getCanvasHeight())
                .put("canvasDpi", config.getCanvasDpi())
                .put("activeVdCount", vdPool.getActiveCount())
                .put("maxVdCount", vdPool.getEffectiveMaxDisplays())
                .put("kbdRoutedDisplayId", kbdRoutedDisplayId)
                .put("comboKbdOuter", comboKbdOuter)
                .putRaw("windows", arr.build())
                .build();
    }

    static String getFullscreenWindowTitle(Collection<WindowSession> sessions) {
        for (WindowSession ws : sessions) {
            if (ws.isMinimized() || !BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)) continue;
            if (ws.title != null && !ws.title.isEmpty()) return ws.title;
            if (ws.pkg != null && !ws.pkg.isEmpty()) return ws.pkg;
            return "Fullscreen";
        }
        return "";
    }

    static Set<String> getOpenWindowPackages(Collection<WindowSession> sessions) {
        Set<String> out = new LinkedHashSet<>();
        for (WindowSession ws : sessions) {
            if (ws != null && ws.pkg != null && !ws.pkg.isEmpty()) {
                out.add(ws.pkg);
            }
        }
        return out;
    }

    static List<CompositorEngine.WindowAppTarget> getOpenWindowAppTargets(Collection<WindowSession> sessions) {
        Map<String, CompositorEngine.WindowAppTarget> dedup = new LinkedHashMap<>();
        for (WindowSession ws : sessions) {
            if (ws != null && ws.pkg != null && !ws.pkg.isEmpty()) {
                String key = ws.appKey();
                if (!dedup.containsKey(key)) {
                    dedup.put(key, new CompositorEngine.WindowAppTarget(ws.pkg, ws.userId));
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    static WindowSession getFocusedLiveWindow(Collection<WindowSession> sessions) {
        WindowSession best = null;
        for (WindowSession ws : sessions) {
            if (ws == null || ws.isMinimized() || !ws.isLive() || ws.displayId <= 0) continue;
            if (ws.focused) return ws;
            if (best == null || ws.zOrder > best.zOrder) {
                best = ws;
            }
        }
        return best;
    }
}
