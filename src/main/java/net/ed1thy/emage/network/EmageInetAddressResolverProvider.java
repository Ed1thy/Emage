package net.ed1thy.emage.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

public class EmageInetAddressResolverProvider extends InetAddressResolverProvider {

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtinResolver = configuration.builtinResolver();

        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
                if (DnsResolver.EMAGE_HTTP_FLAG.get()) {
                    InetAddress safeAddress = DnsResolver.getSafeAddressForHost(host);
                    if (safeAddress != null) {
                        return Stream.of(safeAddress);
                    }
                }
                return builtinResolver.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtinResolver.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "Emage DNS Resolver";
    }
}