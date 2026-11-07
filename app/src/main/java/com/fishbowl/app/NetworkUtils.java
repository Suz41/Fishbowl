package com.fishbowl.app;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public class NetworkUtils {

    public static class LanEndpoint {
        public final String interfaceName;
        public final String ip;
        public final String label;

        public LanEndpoint(String interfaceName, String ip, String label) {
            this.interfaceName = interfaceName;
            this.ip = ip;
            this.label = label;
        }

        public String getUrl(int port, String webPath) {
            String path = (webPath == null || webPath.equals("/")) ? "" : webPath;
            return "http://" + ip + ":" + port + path;
        }

        public String getDisplayLabel() {
            return "[" + label + "] " + ip;
        }
    }

    /**
     * Discovers all active, usable LAN IPv4 endpoints across physical and tethered interfaces.
     * Excludes carrier cellular CGNAT (192.0.0.2 / rmnet).
     */
    public static List<LanEndpoint> getAllAvailableLanEndpoints() {
        List<LanEndpoint> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return list;
            while (ifaces.hasMoreElements()) {
                NetworkInterface intf = ifaces.nextElement();
                if (!intf.isUp() || intf.isLoopback()) continue;
                String name = intf.getName() != null ? intf.getName().toLowerCase(Locale.ROOT) : "";

                // Exclude cellular, carrier CGNAT, and internal Qualcomm dummy interfaces
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
                        || name.startsWith("radio") || name.startsWith("dummy") || name.startsWith("tun")
                        || name.startsWith("sit") || name.startsWith("gre") || name.startsWith("ifb")
                        || name.startsWith("ip_vti")) {
                    continue;
                }

                String label;
                if (name.startsWith("wlan")) {
                    label = "Wi-Fi";
                } else if (name.startsWith("rndis") || name.startsWith("usb")) {
                    label = "USB Tether";
                } else if (name.startsWith("eth")) {
                    label = "Ethernet";
                } else if (name.startsWith("ap") || name.startsWith("softap")) {
                    label = "Hotspot";
                } else if (name.startsWith("tailscale")) {
                    label = "Tailscale";
                } else {
                    label = "LAN (" + name + ")";
                }

                Enumeration<InetAddress> addrs = intf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip == null) continue;
                        if (ip.startsWith("192.0.0.") || ip.startsWith("100.64.")) continue; // cellular / CLAT
                        if (isTailscaleIp(ip)) {
                            list.add(new LanEndpoint(name, ip, "Tailscale"));
                        } else {
                            list.add(new LanEndpoint(name, ip, label));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    /**
     * Determines whether an IPv4 address belongs to the Tailscale CGNAT range (100.64.0.0/10).
     */
    public static boolean isTailscaleIp(String ip) {
        if (ip == null) return false;
        if (ip.startsWith("100.")) {
            try {
                String[] parts = ip.split("\\.");
                if (parts.length == 4) {
                    int secondOctet = Integer.parseInt(parts[1]);
                    return secondOctet >= 64 && secondOctet <= 127;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Resolves the primary LAN IPv4 address of the device using unified interface prioritization:
     * 1. Wi-Fi interface (wlan*)
     * 2. USB Tethering / Ethernet (rndis*, usb*, eth*)
     * 3. Access Point / Hotspot (ap*, softap*)
     *
     * Filters out cellular interfaces (rmnet, ccmni, pdp, radio), virtual/tunnel interfaces
     * (tun, tailscale, dummy, sit, gre, ifb, ip_vti), and CGNAT cellular ranges.
     */
    public static String getRawLanIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            String tetherIp = null;
            String fallbackIp = null;
            while (ifaces.hasMoreElements()) {
                NetworkInterface intf = ifaces.nextElement();
                if (!intf.isUp() || intf.isLoopback()) continue;
                String name = intf.getName() != null ? intf.getName().toLowerCase(Locale.ROOT) : "";

                // Exclude cellular, vpn, tunnel, and loopback virtual interfaces
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
                        || name.startsWith("radio") || name.startsWith("dummy") || name.startsWith("tun")
                        || name.startsWith("tailscale") || name.startsWith("sit") || name.startsWith("gre")
                        || name.startsWith("ifb") || name.startsWith("ip_vti")) {
                    continue;
                }

                Enumeration<InetAddress> addrs = intf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip == null) continue;
                        if (isTailscaleIp(ip)) continue;
                        if (ip.startsWith("192.0.0.") || ip.startsWith("100.64.")) continue; // CGNAT / cellular

                        // Priority 1: Wi-Fi interface (wlan)
                        if (name.startsWith("wlan")) {
                            return ip;
                        }
                        // Priority 2: USB Tethering / Ethernet (rndis, usb, eth)
                        if (name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("eth")) {
                            tetherIp = ip;
                        } else if (name.startsWith("ap") || name.startsWith("softap")) {
                            fallbackIp = ip;
                        } else if (fallbackIp == null) {
                            fallbackIp = ip;
                        }
                    }
                }
            }
            if (tetherIp != null) return tetherIp;
            return fallbackIp;
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Resolves the full HTTP LAN URL for a given port using the unified LAN IP resolution.
     */
    public static String getLanAddress(int port) {
        String ip = getRawLanIp();
        return ip != null ? "http://" + ip + ":" + port : null;
    }

    /**
     * Resolves the primary Jellyfin HTTP LAN URL (port 8096).
     */
    public static String getLanAddress() {
        return getLanAddress(8096);
    }

    /**
     * Resolves the active Tailscale IPv4 address if connected to a Tailnet.
     */
    public static String getRawTailscaleIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            while (ifaces.hasMoreElements()) {
                NetworkInterface intf = ifaces.nextElement();
                if (!intf.isUp() || intf.isLoopback()) continue;
                Enumeration<InetAddress> addrs = intf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (isTailscaleIp(ip)) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Resolves the Tailscale HTTP URL for a given port.
     */
    public static String getTailscaleAddress(int port) {
        String ip = getRawTailscaleIp();
        return ip != null ? "http://" + ip + ":" + port : null;
    }

    /**
     * Resolves the Tailscale HTTP URL for Jellyfin (port 8096).
     */
    public static String getTailscaleAddress() {
        return getTailscaleAddress(8096);
    }
}
