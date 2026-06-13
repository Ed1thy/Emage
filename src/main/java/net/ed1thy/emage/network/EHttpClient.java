package net.ed1thy.emage.network;

import net.ed1thy.emage.config.ConfigManager;
import org.jetbrains.annotations.NotNull;

import java.net.http.HttpClient;
import java.security.Security;
import java.time.Duration;

public class EHttpClient {

    private final HttpClient client;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;

    public EHttpClient(@NotNull ConfigManager configManager) {
        this.connectTimeoutSeconds = configManager.connectTimeoutSeconds;
        this.readTimeoutSeconds = configManager.readTimeoutSeconds;

        Security.setProperty("networkaddress.cache.ttl", "30");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");

        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @NotNull
    public HttpClient getClient() {
        return client;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }
}