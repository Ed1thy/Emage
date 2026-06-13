package net.ed1thy.emage.network;

import net.ed1thy.emage.config.ConfigManager;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ImageDownloader {

    private final EHttpClient httpClient;
    private final DnsResolver dnsChecker;
    private final long maxFileSizeBytes;
    private final int maxRedirects;

    public ImageDownloader(@NotNull EHttpClient httpClient, @NotNull DnsResolver dnsChecker, @NotNull ConfigManager configManager) {
        this.httpClient = httpClient;
        this.dnsChecker = dnsChecker;
        this.maxFileSizeBytes = (long) configManager.maxFileSizeMb * 1024 * 1024;
        this.maxRedirects = configManager.maxRedirects;
    }

    @NotNull
    public CompletableFuture<InputStream> downloadImageStream(@NotNull String url) {
        return executeWithRedirects(url, 0);
    }

    private CompletableFuture<InputStream> executeWithRedirects(@NotNull String targetUrl, int redirectCount) {
        if (redirectCount > maxRedirects) {
            return CompletableFuture.failedFuture(new RuntimeException("Exceeded maximum allowed redirects (" + maxRedirects + ")"));
        }

        try {
            URI uri = URI.create(targetUrl);
            String host = uri.getHost();

            dnsChecker.verifyHostSafety(host);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(httpClient.getReadTimeoutSeconds()))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) EmagePlugin/1.0")
                    .header("Accept", "image/png, image/jpeg, image/gif, image/webp")
                    .GET()
                    .build();

            return httpClient.getClient().sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenCompose(response -> handleResponse(response, redirectCount, host));

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<InputStream> handleResponse(HttpResponse<InputStream> response, int redirectCount, String originalHost) {
        int statusCode = response.statusCode();

        if (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308) {
            Optional<String> locationHeader = response.headers().firstValue("Location");
            if (locationHeader.isPresent()) {
                closeStreamQuietly(response.body());
                String loc = locationHeader.get();
                if (!loc.startsWith("http://") && !loc.startsWith("https://")) {
                    loc = "https://" + originalHost + (loc.startsWith("/") ? "" : "/") + loc;
                }
                return executeWithRedirects(loc, redirectCount + 1);
            } else {
                closeStreamQuietly(response.body());
                return CompletableFuture.failedFuture(new RuntimeException("Received redirect status code without a Location header."));
            }
        }

        if (statusCode < 200 || statusCode >= 300) {
            closeStreamQuietly(response.body());
            return CompletableFuture.failedFuture(new RuntimeException("HTTP Error: " + statusCode));
        }

        Optional<String> contentLengthOpt = response.headers().firstValue("Content-Length");
        if (contentLengthOpt.isPresent()) {
            try {
                long size = Long.parseLong(contentLengthOpt.get());
                if (size > maxFileSizeBytes) {
                    closeStreamQuietly(response.body());
                    return CompletableFuture.failedFuture(new RuntimeException("Image file size exceeds the limit."));
                }
            } catch (NumberFormatException ignored) {}
        }

        Optional<String> contentTypeOpt = response.headers().firstValue("Content-Type");
        if (contentTypeOpt.isEmpty() || (!contentTypeOpt.get().startsWith("image/") && !contentTypeOpt.get().equals("application/octet-stream"))) {
            closeStreamQuietly(response.body());
            return CompletableFuture.failedFuture(new RuntimeException("The URL did not return a valid image MIME type."));
        }

        BoundedInputStream boundedStream = new BoundedInputStream(response.body(), maxFileSizeBytes);
        BufferedInputStream bufferedStream = new BufferedInputStream(boundedStream);

        try {
            PreFlightImageValidator.validate(bufferedStream);
        } catch (Exception e) {
            closeStreamQuietly(bufferedStream);
            return CompletableFuture.failedFuture(e);
        }

        return CompletableFuture.completedFuture(bufferedStream);
    }

    private void closeStreamQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {}
        }
    }

    private static class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead = 0;

        protected BoundedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int val = super.read();
            if (val != -1) {
                bytesRead++;
                checkLimit();
            }
            return val;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read != -1) {
                bytesRead += read;
                checkLimit();
            }
            return read;
        }

        private void checkLimit() throws IOException {
            if (bytesRead > maxBytes) {
                throw new IOException("Security Exception: Download size limit exceeded (" + maxBytes + " bytes). Stream aborted to prevent out-of-memory.");
            }
        }
    }
}