package com.traffic.client;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class NetworkUtils {

    /**
     * Finds the first non-loopback IPv4 address for the local machine.
     * @return A string containing the IP address, or "127.0.0.1" as a fallback.
     */
    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress ia = inetAddresses.nextElement();
                    // Filter out loopback, link-local, and IPv6 addresses
                    if (!ia.isLoopbackAddress() && !ia.isLinkLocalAddress() && ia.isSiteLocalAddress()) {
                        // Found a non-loopback, site-local (private network) address
                        return ia.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Could not determine local IP address: " + e.getMessage());
        }
        // Fallback to localhost if no suitable IP was found
        return "127.0.0.1";
    }
}