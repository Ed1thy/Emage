package net.edithymaster.emage.Processing;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import net.edithymaster.emage.Config.EmageConfig;
import org.w3c.dom.NodeList;

public final class EmageCore {

    private EmageCore() {}

    private static final Logger logger = Logger.getLogger(EmageCore.class.getName());

    public static final int MAP_WIDTH = 128;
    public static final int MAP_SIZE = MAP_WIDTH * MAP_WIDTH;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static volatile EmageConfig activeConfig = null;

    private static final double MAX_ERROR = 0.15;
    private static final double GLOBAL_DAMPENER = 0.55;
    private static final double CHROMA_DAMPENER = 0.20;

    private static volatile ExecutorService EXECUTOR;

    public static ExecutorService getExecutor() {
        if (EXECUTOR == null || EXECUTOR.isShutdown()) {
            synchronized (EmageCore.class) {
                if (EXECUTOR == null || EXECUTOR.isShutdown()) {
                    EXECUTOR = Executors.newFixedThreadPool(
                            Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
                            r -> {
                                Thread t = new Thread(r, "Emage-Processor-" + THREAD_COUNTER.incrementAndGet());
                                t.setDaemon(true);
                                t.setPriority(Thread.NORM_PRIORITY - 1);
                                return t;
                            }
                    );
                }
            }
        }
        return EXECUTOR;
    }

    public static void setConfig(EmageConfig config) {
        activeConfig = config;
    }

    private static long getMaxDownloadBytes() {
        return activeConfig != null ? activeConfig.getMaxDownloadBytes() : 50L * 1024 * 1024;
    }

    private static int getMaxRedirects() {
        return activeConfig != null ? activeConfig.getMaxRedirects() : 5;
    }

    private static int getConnectTimeout() {
        return activeConfig != null ? activeConfig.getConnectTimeout() : 10000;
    }

    private static int getReadTimeout() {
        return activeConfig != null ? activeConfig.getReadTimeout() : 30000;
    }

    private static boolean shouldBlockInternalUrls() {
        return activeConfig != null ? activeConfig.blockInternalUrls() : true;
    }

    public static void initColorSystem() {
        EmageColors.detectAndSetMaxValidIndex();
        EmageColors.syncWithServer();
    }

    public static void initColorSystemAsync() {
        EmageColors.initCache();
    }

    public static byte matchColor(int r, int g, int b) {
        return EmageColors.matchColor(r, g, b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static BufferedImage resize(BufferedImage src, int targetW, int targetH) {
        if (src.getWidth() == targetW && src.getHeight() == targetH) {
            return src;
        }

        BufferedImage result = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();
        return result;
    }

    public static byte[] dither(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);
        return ditherPixels(pixels, w, h);
    }

    public static byte[] ditherPixels(int[] pixels, int width, int height) {
        return ditherJarvisGammaCorrected(pixels, width, height);
    }

    public static byte[] ditherPixelsStable(int[] pixels, int width, int height, int[] prevPixels, byte[] prevResult) {
        if (prevPixels == null || prevResult == null) {
            return ditherPixels(pixels, width, height);
        }

        int size = width * height;
        boolean[] changed = new boolean[size];
        int changeCount = 0;
        for (int i = 0; i < size; i++) {
            if (pixels[i] != prevPixels[i]) {
                changed[i] = true;
                changeCount++;
            }
        }

        if (changeCount == 0) {
            return prevResult;
        }

        if (changeCount > size / 2) {
            return ditherPixels(pixels, width, height);
        }

        boolean[] dilated = new boolean[size];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!changed[y * width + x]) continue;
                int yMin = Math.max(0, y - 2);
                int yMax = Math.min(height - 1, y + 2);
                int xMin = Math.max(0, x - 2);
                int xMax = Math.min(width - 1, x + 2);
                for (int dy = yMin; dy <= yMax; dy++) {
                    int rowOff = dy * width;
                    for (int dx = xMin; dx <= xMax; dx++) {
                        dilated[rowOff + dx] = true;
                    }
                }
            }
        }

        byte[] fullDither = ditherPixels(pixels, width, height);

        for (int i = 0; i < size; i++) {
            if (!dilated[i]) {
                fullDither[i] = prevResult[i];
            }
        }

        return fullDither;
    }

    private static void calculateConstrainedError(double[] outError, double tr, double tg, double tb, double pr, double pg, double pb) {
        double eR = tr - pr;
        double eG = tg - pg;
        double eB = tb - pb;

        double eY = 0.2126 * eR + 0.7152 * eG + 0.0722 * eB;
        double eCr = eR - eY;
        double eCg = eG - eY;
        double eCb = eB - eY;

        eCr *= CHROMA_DAMPENER;
        eCg *= CHROMA_DAMPENER;
        eCb *= CHROMA_DAMPENER;

        eR = eY + eCr;
        eG = eY + eCg;
        eB = eY + eCb;

        double maxC = Math.max(tr, Math.max(tg, tb));
        double minC = Math.min(tr, Math.min(tg, tb));
        double saturation = (maxC <= 0.001) ? 0.0 : ((maxC - minC) / maxC);

        if (saturation < 0.20) {
            double weight = saturation / 0.20;
            eR = eY * (1.0 - weight) + eR * weight;
            eG = eY * (1.0 - weight) + eG * weight;
            eB = eY * (1.0 - weight) + eB * weight;
        }

        eR *= GLOBAL_DAMPENER;
        eG *= GLOBAL_DAMPENER;
        eB *= GLOBAL_DAMPENER;

        double mag = Math.sqrt(eR * eR + eG * eG + eB * eB);
        if (mag > MAX_ERROR) {
            double scale = MAX_ERROR / mag;
            eR *= scale;
            eG *= scale;
            eB *= scale;
        }

        outError[0] = eR;
        outError[1] = eG;
        outError[2] = eB;
    }

    private static BufferedImage prepareImage(BufferedImage src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height) {
            return src;
        }
        return resize(src, width, height);
    }

    private static byte[] ditherJarvisGammaCorrected(int[] pixels, int width, int height) {
        int size = width * height;
        byte[] result = new byte[size];
        double[] errOut = new double[3];

        double[] linR = new double[size];
        double[] linG = new double[size];
        double[] linB = new double[size];
        boolean[] transparent = new boolean[size];

        for (int i = 0; i < size; i++) {
            int rgb = pixels[i];
            if (((rgb >> 24) & 0xFF) < 128) {
                transparent[i] = true;
                continue;
            }
            linR[i] = EmageColors.linearize((rgb >> 16) & 0xFF);
            linG[i] = EmageColors.linearize((rgb >> 8) & 0xFF);
            linB[i] = EmageColors.linearize(rgb & 0xFF);
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;

                if (transparent[idx]) {
                    result[idx] = 0;
                    continue;
                }

                double clampedR = Math.max(0.0, Math.min(1.0, linR[idx]));
                double clampedG = Math.max(0.0, Math.min(1.0, linG[idx]));
                double clampedB = Math.max(0.0, Math.min(1.0, linB[idx]));

                int sr = EmageColors.delinearize(clampedR);
                int sg = EmageColors.delinearize(clampedG);
                int sb = EmageColors.delinearize(clampedB);

                byte match = matchColor(sr, sg, sb);
                result[idx] = match;

                double[] palLin = EmageColors.getLinearRGB(match);
                calculateConstrainedError(errOut, clampedR, clampedG, clampedB, palLin[0], palLin[1], palLin[2]);
                double eR = errOut[0];
                double eG = errOut[1];
                double eB = errOut[2];

                distributeErrorSafe(linR, linG, linB, transparent, width, height, x + 1, y,     eR, eG, eB, 7.0 / 48.0);
                distributeErrorSafe(linR, linG, linB, transparent, width, height, x + 2, y,     eR, eG, eB, 5.0 / 48.0);

                distributeErrorSafe(linR, linG, linB, transparent, width, height, x - 2, y + 1, eR, eG, eB, 3.0 / 48.0);
                distributeErrorSafe(linR, linG, linB, transparent, width, height, x - 1, y + 1, eR, eG, eB, 5.0 / 48.0);
                distributeErrorSafe(linR, linG, linB, transparent, width, height, x,     y + 1, eR, eG, eB, 7.0 / 48.0);
                distributeErrorSafe(linR, linG, linB, transparent, width, height, x + 1, y + 1, eR, eG, eB, 5.0 / 48.0);
                distributeErrorSafe(linR, linG, linB, transparent, width, height, x + 2, y + 1, eR, eG, eB, 3.0 / 48.0);

                distributeErrorSafe(linR, linG, linB, transparent, width, height, x - 2, y + 2, eR, eG, eB, 1.0 / 48.0);
                distributeErrorSafe(linR, linG, linB, transparent, width, height, x - 1, y + 2, eR, eG, eB, 3.0 / 48.0);
                distributeErrorSafe(linR, linG, linB, transparent, width, height, x,     y + 2, eR, eG, eB, 5.0 / 48.0);
                distributeErrorSafe(linR, linG, linB, transparent, width, height, x + 1, y + 2, eR, eG, eB, 3.0 / 48.0);
                distributeErrorSafe(linR, linG, linB, transparent, width, height, x + 2, y + 2, eR, eG, eB, 1.0 / 48.0);
            }
        }

        return result;
    }

    private static void distributeErrorSafe(double[] linR, double[] linG, double[] linB,
                                            boolean[] transparent, int width, int height,
                                            int x, int y, double eR, double eG, double eB, double weight) {
        if (x < 0 || x >= width || y < 0 || y >= height) return;
        int idx = y * width + x;
        if (transparent[idx]) return;
        linR[idx] += eR * weight;
        linG[idx] += eG * weight;
        linB[idx] += eB * weight;
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String stage);
    }

    @SuppressWarnings("unchecked")
    public static GifGridData processGifGrid(URL url, int gridW, int gridH, int maxFrames, ProgressCallback progress) throws Exception {

        int targetAspectW = gridW * MAP_WIDTH;
        int targetAspectH = gridH * MAP_WIDTH;

        GifData gifData = readGif(url, maxFrames, targetAspectW, targetAspectH);
        if (gifData.frames.isEmpty()) {
            throw new Exception("No frames found in GIF");
        }

        int frameCount = gifData.frames.size();

        List<byte[]>[][] grid = new List[gridW][gridH];
        for (int gx = 0; gx < gridW; gx++) {
            for (int gy = 0; gy < gridH; gy++) {
                grid[gx][gy] = new ArrayList<>(frameCount);
            }
        }

        byte[][][][] gridArr = new byte[gridW][gridH][frameCount][];

        class DitherState {
            int[] prevPixels = null;
            byte[] prevDither = null;
        }
        final DitherState state = new DitherState();

        CompletableFuture<Void> pipeline = CompletableFuture.completedFuture(null);

        for (int f = 0; f < frameCount; f++) {
            final int frameIdx = f;
            BufferedImage frame = gifData.frames.get(f);

            pipeline = pipeline.thenRunAsync(() -> {
                int[] fullPixels = new int[targetAspectW * targetAspectH];
                frame.getRGB(0, 0, targetAspectW, targetAspectH, fullPixels, 0, targetAspectW);

                byte[] fullDither;
                if (frameIdx > 0 && state.prevPixels != null) {
                    fullDither = ditherPixelsStable(fullPixels, targetAspectW, targetAspectH, state.prevPixels, state.prevDither);
                } else {
                    fullDither = ditherPixels(fullPixels, targetAspectW, targetAspectH);
                }

                state.prevPixels = fullPixels;
                state.prevDither = fullDither;

                for (int gy = 0; gy < gridH; gy++) {
                    for (int gx = 0; gx < gridW; gx++) {
                        byte[] chunk = new byte[MAP_SIZE];
                        for (int cy = 0; cy < MAP_WIDTH; cy++) {
                            int srcY = gy * MAP_WIDTH + cy;
                            int srcX = gx * MAP_WIDTH;
                            System.arraycopy(fullDither, srcY * targetAspectW + srcX, chunk, cy * MAP_WIDTH, MAP_WIDTH);
                        }
                        gridArr[gx][gy][frameIdx] = chunk;
                    }
                }

                gifData.frames.set(frameIdx, null);
                if (progress != null && (frameIdx % 10 == 0 || frameIdx == frameCount - 1)) {
                    int pct = (int) ((frameIdx + 1) * 100.0 / frameCount);
                    progress.onProgress(frameIdx + 1, frameCount, "Dithering frame " + (frameIdx + 1) + "/" + frameCount + " (" + pct + "%)");
                }
            }, getExecutor());
        }

        pipeline.join();

        for (int gx = 0; gx < gridW; gx++) {
            for (int gy = 0; gy < gridH; gy++) {
                for (int f = 0; f < frameCount; f++) {
                    grid[gx][gy].add(gridArr[gx][gy][f]);
                }
            }
        }

        int avgDelay = gifData.delays.isEmpty() ? 100 :
                (int) gifData.delays.stream().mapToInt(Integer::intValue).average().orElse(100);

        return new GifGridData(grid, gifData.delays, avgDelay, gridW, gridH);
    }

    private static InputStream openLimitedStream(URL url) throws IOException {
        resolveAndValidate(url.getHost());
        return openLimitedStream(url, getMaxRedirects());
    }

    private static InputStream openLimitedStream(URL url, int remainingRedirects) throws IOException {
        if (!ALLOWED_SCHEMES.contains(url.getProtocol().toLowerCase())) {
            throw new IOException("Only HTTP/HTTPS URLs are allowed");
        }

        if (remainingRedirects <= 0) {
            throw new IOException("Too many redirects");
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(getConnectTimeout());
        conn.setReadTimeout(getReadTimeout());
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Emage-Plugin");
        conn.setRequestProperty("Host", url.getHost());

        try {
            conn.connect();

            int status = conn.getResponseCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String redirect = conn.getHeaderField("Location");
                conn.disconnect();
                if (redirect != null) {
                    URL redirectUrl = new URL(url, redirect);
                    resolveAndValidate(redirectUrl.getHost());
                    return openLimitedStream(redirectUrl, remainingRedirects - 1);
                }
                throw new IOException("Redirect without Location header");
            }

            if (status != 200) {
                conn.disconnect();
                throw new IOException("HTTP error: " + status);
            }

            long maxBytes = getMaxDownloadBytes();
            long contentLength = conn.getContentLengthLong();
            if (contentLength > maxBytes) {
                conn.disconnect();
                throw new IOException("File too large: " + contentLength + " bytes (max " + maxBytes + ")");
            }

            return new LimitedInputStream(conn.getInputStream(), maxBytes, conn);

        } catch (IOException ex) {
            conn.disconnect();
            throw ex;
        }
    }

    private static java.net.InetAddress resolveAndValidate(String host) throws IOException {
        java.net.InetAddress address;
        try {
            Future<java.net.InetAddress> dnsFuture = getExecutor().submit(
                    () -> java.net.InetAddress.getByName(host));
            address = dnsFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("DNS resolution timed out for host: " + host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("DNS resolution interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IOException("DNS resolution failed: " +
                    (cause != null ? cause.getMessage() : e.getMessage()));
        }

        validateAddress(address);
        return address;
    }

    private static void validateAddress(java.net.InetAddress address) throws IOException {
        if (!shouldBlockInternalUrls()) return;

        if (address.isLoopbackAddress() ||
                address.isLinkLocalAddress() ||
                address.isSiteLocalAddress() ||
                address.isAnyLocalAddress()) {
            throw new IOException("URLs pointing to internal/local networks are not allowed");
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            boolean isMapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) { isMapped = false; break; }
            }
            if (isMapped && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
                byte[] v4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                java.net.InetAddress v4Addr = java.net.InetAddress.getByAddress(v4);
                if (v4Addr.isLoopbackAddress() ||
                        v4Addr.isLinkLocalAddress() ||
                        v4Addr.isSiteLocalAddress() ||
                        v4Addr.isAnyLocalAddress()) {
                    throw new IOException("URLs pointing to internal/local networks are not allowed");
                }
            }
        }
    }

    private static class LimitedInputStream extends FilterInputStream {
        private long remaining;
        private final long limit;
        private final HttpURLConnection connection;

        LimitedInputStream(InputStream in, long limit, HttpURLConnection connection) {
            super(in);
            this.remaining = limit;
            this.limit = limit;
            this.connection = connection;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) throw new IOException("Download size limit exceeded (" + limit + " bytes)");
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) throw new IOException("Download size limit exceeded (" + limit + " bytes)");
            int toRead = (int) Math.min(len, remaining);
            int read = super.read(b, off, toRead);
            if (read > 0) remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            super.close();
            if (connection != null) connection.disconnect();
        }
    }

    public static BufferedImage padAndScaleToExact(BufferedImage src, int targetW, int targetH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();

        double targetRatio = (double) targetW / targetH;
        double srcRatio = (double) srcW / srcH;

        int drawW = targetW;
        int drawH = targetH;
        int offsetX = 0;
        int offsetY = 0;

        if (Math.abs(targetRatio - srcRatio) > 0.001) {
            if (srcRatio > targetRatio) {
                drawH = (int) Math.round(targetW / srcRatio);
                offsetY = (targetH - drawH) / 2;
            } else {
                drawW = (int) Math.round(targetH * srcRatio);
                offsetX = (targetW - drawW) / 2;
            }
        }

        BufferedImage padded = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = padded.createGraphics();

        g.setColor(new java.awt.Color(0, 0, 0, 255));
        g.fillRect(0, 0, targetW, targetH);

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        g.drawImage(src, offsetX, offsetY, drawW, drawH, null);
        g.dispose();

        return padded;
    }

    private static GifData readGif(URL url, int maxFrames, int targetAspectW, int targetAspectH) throws Exception {
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();

        long maxDecodedBytes = activeConfig != null
                ? activeConfig.getMaxMemoryMB() * 1024 * 1024
                : 256L * 1024 * 1024;
        long totalDecodedBytes = 0;

        try (InputStream is = new BufferedInputStream(openLimitedStream(url), 65536);
             ImageInputStream iis = ImageIO.createImageInputStream(is)) {

            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(iis, false, false);

            int numFrames;
            try {
                numFrames = reader.getNumImages(true);
            } catch (Exception e) {
                numFrames = maxFrames;
            }
            numFrames = Math.min(numFrames, maxFrames);

            int canvasWidth = 0;
            int canvasHeight = 0;

            Color gifBackgroundColor = null;
            try {
                IIOMetadata streamMeta = reader.getStreamMetadata();
                if (streamMeta != null) {
                    IIOMetadataNode root = (IIOMetadataNode) streamMeta.getAsTree(
                            streamMeta.getNativeMetadataFormatName());

                    NodeList lsdNodes = root.getElementsByTagName("LogicalScreenDescriptor");
                    int bgColorIndex = 0;
                    if (lsdNodes.getLength() > 0) {
                        IIOMetadataNode lsd = (IIOMetadataNode) lsdNodes.item(0);

                        String w = lsd.getAttribute("logicalScreenWidth");
                        String h = lsd.getAttribute("logicalScreenHeight");
                        if (w != null && !w.isEmpty()) canvasWidth = Integer.parseInt(w);
                        if (h != null && !h.isEmpty()) canvasHeight = Integer.parseInt(h);

                        String bgIdx = lsd.getAttribute("backgroundColorIndex");
                        if (bgIdx != null && !bgIdx.isEmpty()) {
                            bgColorIndex = Integer.parseInt(bgIdx);
                        }
                    }

                    NodeList gctNodes = root.getElementsByTagName("GlobalColorTable");
                    if (gctNodes.getLength() > 0) {
                        IIOMetadataNode gct = (IIOMetadataNode) gctNodes.item(0);
                        NodeList entries = gct.getElementsByTagName("ColorTableEntry");
                        for (int ei = 0; ei < entries.getLength(); ei++) {
                            IIOMetadataNode entry = (IIOMetadataNode) entries.item(ei);
                            String idxStr = entry.getAttribute("index");
                            if (idxStr != null && Integer.parseInt(idxStr) == bgColorIndex) {
                                int red = Integer.parseInt(entry.getAttribute("red"));
                                int green = Integer.parseInt(entry.getAttribute("green"));
                                int blue = Integer.parseInt(entry.getAttribute("blue"));
                                gifBackgroundColor = new Color(red, green, blue, 255);
                                break;
                            }
                        }
                    }
                }
            } catch (NumberFormatException e) {
                logger.log(Level.FINE, "Could not parse GIF metadata values: " + e.getMessage());
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not read GIF stream metadata. The animation may render incorrectly.", e);
            }

            BufferedImage firstFrame = reader.read(0);
            if (canvasWidth <= 0 || canvasHeight <= 0) {
                canvasWidth = firstFrame.getWidth();
                canvasHeight = firstFrame.getHeight();
            }

            if (canvasWidth > 4096 || canvasHeight > 4096) {
                reader.dispose();
                throw new Exception("GIF dimensions too large: " + canvasWidth + "x" + canvasHeight +
                        " (max 4096x4096)");
            }

            long canvasBytes = (long) canvasWidth * canvasHeight * 4;
            if (canvasBytes > maxDecodedBytes / 4) {
                reader.dispose();
                throw new Exception("GIF canvas too large: would require " +
                        (canvasBytes / 1024 / 1024) + "MB per frame");
            }

            BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D canvasG = canvas.createGraphics();
            canvasG.setBackground(new Color(0, 0, 0, 0));
            canvasG.clearRect(0, 0, canvasWidth, canvasHeight);

            BufferedImage restoreCanvas = null;

            final Color finalBgColor = gifBackgroundColor;

            long scaledFrameBytes = (long) targetAspectW * targetAspectH * 4;

            for (int i = 0; i < numFrames; i++) {
                BufferedImage rawFrame;
                try {
                    rawFrame = (i == 0) ? firstFrame : reader.read(i);
                } catch (IndexOutOfBoundsException | IIOException e) {
                    logger.warning("Could not read GIF frame " + i + ". Skipping frame. Cause: " + e.getMessage());
                    continue;
                } catch (Exception e) {
                    logger.warning("Unexpected error decoding frame " + i + ". Skipping frame. Cause: " + e.getMessage());
                    continue;
                }
                if (rawFrame == null) continue;

                totalDecodedBytes += scaledFrameBytes;
                if (totalDecodedBytes > maxDecodedBytes) {
                    logger.warning("GIF exceeded the decoded memory limit at frame " + i + ". The animation was truncated to prevent out-of-memory errors.");
                    break;
                }

                int delay = 50;
                String disposal = "none";
                int frameX = 0, frameY = 0;
                int frameW = rawFrame.getWidth();
                int frameH = rawFrame.getHeight();

                try {
                    IIOMetadata meta = reader.getImageMetadata(i);
                    if (meta != null) {
                        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(
                                meta.getNativeMetadataFormatName());

                        NodeList gceNodes = root.getElementsByTagName("GraphicControlExtension");
                        if (gceNodes.getLength() > 0) {
                            IIOMetadataNode gce = (IIOMetadataNode) gceNodes.item(0);
                            String delayStr = gce.getAttribute("delayTime");
                            if (delayStr != null && !delayStr.isEmpty()) {
                                int d = Integer.parseInt(delayStr);
                                delay = d <= 1 ? 50 : d * 10;
                            }
                            String disp = gce.getAttribute("disposalMethod");
                            if (disp != null) disposal = disp;
                        }

                        NodeList descNodes = root.getElementsByTagName("ImageDescriptor");
                        if (descNodes.getLength() > 0) {
                            IIOMetadataNode desc = (IIOMetadataNode) descNodes.item(0);
                            String x = desc.getAttribute("imageLeftPosition");
                            String y = desc.getAttribute("imageTopPosition");
                            if (x != null && !x.isEmpty()) frameX = Integer.parseInt(x);
                            if (y != null && !y.isEmpty()) frameY = Integer.parseInt(y);
                        }
                    }
                } catch (Exception e) {
                    logger.warning("Could not read metadata for GIF frame " + i + ". Delay and disposal methods will fall back to defaults. Cause: " + e.getMessage());
                }

                if ("restoreToPrevious".equalsIgnoreCase(disposal)) {
                    restoreCanvas = copyImage(canvas);
                }

                canvasG.drawImage(rawFrame, frameX, frameY, null);

                frames.add(padAndScaleToExact(canvas, targetAspectW, targetAspectH));
                delays.add(Math.max(20, delay));

                if ("restoreToBackgroundColor".equalsIgnoreCase(disposal)) {
                    if (finalBgColor != null) {
                        canvasG.setComposite(AlphaComposite.Src);
                        canvasG.setColor(finalBgColor);
                        canvasG.fillRect(frameX, frameY, frameW, frameH);
                        canvasG.setComposite(AlphaComposite.SrcOver);
                    } else {
                        canvasG.setComposite(AlphaComposite.Clear);
                        canvasG.fillRect(frameX, frameY, frameW, frameH);
                        canvasG.setComposite(AlphaComposite.SrcOver);
                    }
                } else if ("restoreToPrevious".equalsIgnoreCase(disposal) && restoreCanvas != null) {
                    canvasG.setComposite(AlphaComposite.Src);
                    canvasG.drawImage(restoreCanvas, 0, 0, null);
                    canvasG.setComposite(AlphaComposite.SrcOver);
                }
            }

            canvasG.dispose();
            reader.dispose();
        }

        if (frames.isEmpty()) {
            throw new Exception("No frames could be decoded from GIF");
        }

        return new GifData(frames, delays);
    }

    private static BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    public static void shutdown() {
        if (EXECUTOR == null || EXECUTOR.isShutdown()) return;

        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }

        EXECUTOR = null;
    }

    public static BufferedImage downloadImage(URL url) throws Exception {
        try (InputStream is = new BufferedInputStream(openLimitedStream(url), 65536)) {
            BufferedImage img = ImageIO.read(is);
            if (img == null) {
                throw new IOException("Failed to decode image");
            }
            return img;
        }
    }

    private static class GifData {
        final List<BufferedImage> frames;
        final List<Integer> delays;

        GifData(List<BufferedImage> frames, List<Integer> delays) {
            this.frames = frames;
            this.delays = delays;
        }
    }

    public static class GifGridData {
        public final List<byte[]>[][] grid;
        public final List<Integer> delays;
        public final int avgDelay;
        public final int gridWidth;
        public final int gridHeight;

        public GifGridData(List<byte[]>[][] grid, List<Integer> delays, int avgDelay, int gridWidth, int gridHeight) {
            this.grid = grid;
            this.delays = delays;
            this.avgDelay = avgDelay;
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
        }
    }
}