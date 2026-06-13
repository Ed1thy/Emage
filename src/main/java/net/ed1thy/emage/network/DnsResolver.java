package net.ed1thy.emage.network;

import net.ed1thy.emage.config.ConfigManager;
import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class DnsResolver {

    private final boolean blockInternalUrls;

    public DnsResolver(@NotNull ConfigManager configManager) {
        this.blockInternalUrls = configManager.blockInternalUrls;
    }

    @NotNull
    public InetAddress verifyHostSafety(@NotNull String hostname) throws UnknownHostException, SecurityException {
        InetAddress[] addresses = InetAddress.getAllByName(hostname);

        if (addresses.length == 0) {
            throw new UnknownHostException("Could not resolve host: " + hostname);
        }

        InetAddress address = addresses[0];

        if (blockInternalUrls) {
            byte[] ip = address.getAddress();

            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new SecurityException("Hostname '" + hostname + "' resolved to a blocked internal IP: " + address.getHostAddress());
            }

            if (ip.length == 4) {
                int octet1 = ip[0] & 0xFF;
                int octet2 = ip[1] & 0xFF;

                if (octet1 == 0) {
                    throw new SecurityException("Hostname resolved to blocked 0.0.0.0/8 range.");
                }

                if (octet1 == 169 && octet2 == 254) {
                    throw new SecurityException("Hostname resolved to a protected Cloud Metadata endpoint.");
                }

                if (octet1 == 127) {
                    throw new SecurityException("Hostname resolved to blocked localhost range.");
                }
            }
        }

        return address;
    }
}