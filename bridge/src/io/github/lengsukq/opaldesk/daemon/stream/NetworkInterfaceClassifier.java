package io.github.lengsukq.opaldesk.daemon.stream;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Pure-JVM network interface classifier separating regular LAN Wi-Fi/Ethernet addresses from
 * active USB Tethering (CDC-NCM / RNDIS / USB Ethernet) addresses.
 *
 * <p>Preserves exact main-line {@link #selectBestLanIp(List)} priority so Wi-Fi LAN casting
 * behavior never changes when USB Tethering is simultaneously active.
 */
public final class NetworkInterfaceClassifier {
    public static final class InterfaceSnapshot {
        public final String name;
        public final String ipv4;
        public final boolean siteLocal;

        public InterfaceSnapshot(String name, String ipv4, boolean siteLocal) {
            this.name = (name != null) ? name : "";
            this.ipv4 = (ipv4 != null) ? ipv4 : "";
            this.siteLocal = siteLocal;
        }
    }

    public static final class Endpoint {
        public static final Endpoint EMPTY = new Endpoint("", "");

        public final String interfaceName;
        public final String ipv4;

        public Endpoint(String interfaceName, String ipv4) {
            this.interfaceName = (interfaceName != null) ? interfaceName : "";
            this.ipv4 = (ipv4 != null) ? ipv4 : "";
        }

        public boolean isPresent() {
            return !ipv4.isEmpty();
        }
    }

    private NetworkInterfaceClassifier() {}

    public static boolean isIgnoredInterface(String rawName) {
        String name = normalizeName(rawName);
        return name.isEmpty() || name.startsWith("dummy") || name.startsWith("lo");
    }

    public static boolean isWlanInterface(String rawName) {
        String name = normalizeName(rawName);
        return name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap");
    }

    /**
     * Identifies Android kernel USB Tethering interfaces (CDC-NCM {@code ncm*}, legacy
     * {@code rndis*}, and generic USB gadget {@code usb*}).
     */
    public static boolean isUsbTetheringInterface(String rawName) {
        String name = normalizeName(rawName);
        return name.startsWith("ncm") || name.startsWith("rndis") || name.startsWith("usb");
    }

    public static boolean isEthernetOrUsbInterface(String rawName) {
        String name = normalizeName(rawName);
        return name.startsWith("eth") || name.startsWith("en") || isUsbTetheringInterface(name);
    }

    public static boolean isTunnelOrCellularInterface(String rawName) {
        String name = normalizeName(rawName);
        return name.startsWith("rmnet")
                || name.startsWith("ccmni")
                || name.startsWith("tun")
                || name.startsWith("tap")
                || name.startsWith("ppp");
    }

    /**
     * Selects the primary LAN IPv4 address with unchanged main-line priority:
     * Wi-Fi/AP site-local &gt; Ethernet/USB site-local &gt; other site-local &gt; other non-tunnel.
     */
    public static String selectBestLanIp(List<InterfaceSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return "";
        String bestWlan = "";
        String bestEth = "";
        String bestSiteLocal = "";
        String bestOther = "";

        for (InterfaceSnapshot snap : snapshots) {
            if (snap == null || snap.ipv4.isEmpty() || isIgnoredInterface(snap.name)) continue;
            boolean isWlan = isWlanInterface(snap.name);
            boolean isEth = isEthernetOrUsbInterface(snap.name);
            boolean isTunnelOrCell = isTunnelOrCellularInterface(snap.name);

            if (isWlan && snap.siteLocal) {
                if (bestWlan.isEmpty()) bestWlan = snap.ipv4;
            } else if (isEth && snap.siteLocal) {
                if (bestEth.isEmpty()) bestEth = snap.ipv4;
            } else if (!isTunnelOrCell && snap.siteLocal) {
                if (bestSiteLocal.isEmpty()) bestSiteLocal = snap.ipv4;
            } else if (!isTunnelOrCell) {
                if (bestOther.isEmpty()) bestOther = snap.ipv4;
            }
        }

        if (!bestWlan.isEmpty()) return bestWlan;
        if (!bestEth.isEmpty()) return bestEth;
        if (!bestSiteLocal.isEmpty()) return bestSiteLocal;
        return bestOther;
    }

    /**
     * Selects the active USB Tethering IPv4 endpoint ({@code ncm*} / {@code rndis*} / {@code usb*}),
     * preferring RFC1918 site-local addresses. Returns {@link Endpoint#EMPTY} when inactive.
     */
    public static Endpoint selectUsbTetherEndpoint(List<InterfaceSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return Endpoint.EMPTY;
        Endpoint bestSiteLocal = null;
        Endpoint bestFallback = null;

        for (InterfaceSnapshot snap : snapshots) {
            if (snap == null || snap.ipv4.isEmpty() || !isUsbTetheringInterface(snap.name)) continue;
            if (snap.siteLocal) {
                if (bestSiteLocal == null) {
                    bestSiteLocal = new Endpoint(snap.name, snap.ipv4);
                }
            } else if (bestFallback == null) {
                bestFallback = new Endpoint(snap.name, snap.ipv4);
            }
        }
        if (bestSiteLocal != null) return bestSiteLocal;
        return (bestFallback != null) ? bestFallback : Endpoint.EMPTY;
    }

    public static List<InterfaceSnapshot> snapshotSystemInterfaces() {
        List<InterfaceSnapshot> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (nic == null) continue;
                try {
                    if (!nic.isUp() || nic.isLoopback()) continue;
                } catch (SocketException ignored) {
                    continue;
                }
                String name = (nic.getName() != null) ? nic.getName() : "";
                if (isIgnoredInterface(name)) continue;

                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address)) continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.isEmpty()) continue;
                    out.add(new InterfaceSnapshot(name, ip, addr.isSiteLocalAddress()));
                }
            }
        } catch (SocketException e) {
            System.err.println("OPALDESK_LAN_IP_DETECT_ERR: " + e.getMessage());
        }
        return out;
    }

    public static boolean isUsbTetheringLocalAddress(InetAddress localAddr) {
        if (localAddr == null) return false;
        try {
            NetworkInterface nic = NetworkInterface.getByInetAddress(localAddr);
            return nic != null && isUsbTetheringInterface(nic.getName());
        } catch (SocketException ignored) {
            return false;
        }
    }

    private static String normalizeName(String rawName) {
        return (rawName != null) ? rawName.trim().toLowerCase(Locale.US) : "";
    }
}
