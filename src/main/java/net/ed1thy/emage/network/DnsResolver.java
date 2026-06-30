package net.ed1thy.emage.network;

import net.ed1thy.emage.config.ConfigManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;

public class DnsResolver {

    private final boolean blockInternalUrls;
    private static final ConcurrentHashMap<String, CachedResolution> safeCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5000;

    public static final ThreadLocal<Boolean> EMAGE_HTTP_FLAG = ThreadLocal.withInitial(() -> false);

    private record CachedResolution(InetAddress address, long timestamp) {}

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

        safeCache.put(hostname, new CachedResolution(address, System.currentTimeMillis()));
        return address;
    }

    @Nullable
    public static InetAddress getSafeAddressForHost(@Nullable String host) {
        if (host == null) return null;
        CachedResolution cached = safeCache.get(host);
        if (cached != null) {
            if (System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
                return cached.address();
            }
            safeCache.remove(host, cached);
        }
        return null;
    }
}