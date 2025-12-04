package org.flowerion.emage.Processing;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NodeList;

public final class EmageCore {

    private EmageCore() {}

    public static final int MAP_SIZE = 16384;
    public static final int MAP_WIDTH = 128;

    // Single background thread for processing
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Emage-Processor");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // Simple pool tracking (not actually pooling, just for API compatibility)
    private static volatile boolean usePool = true;
    private static volatile int poolSize = 0;

    public enum Quality {
        FAST,
        BALANCED,
        HIGH
    }

    // ==================== Pool Methods (for API compatibility) ====================

    public static void setUsePool(boolean use) {
        usePool = use;
    }

    public static boolean isUsePool() {
        return usePool;
    }

    public static int getPoolSize() {
        return poolSize;
    }

    public static void setMaxPoolSize(int size) {
        // No-op for compatibility
    }

    public static byte[] acquireBuffer() {
        return new byte[MAP_SIZE];
    }

    public static void releaseBuffer(byte[] buffer) {
        // No-op
    }

    public static void clearAllPools() {
        poolSize = 0;
    }

    // ==================== Color Methods ====================

    public static void initColorSystem() {
        EXECUTOR.submit(EmageColors::initCache);
    }

    public static byte matchColor(int r, int g, int b) {
        return EmageColors.matchColor(r, g, b);
    }

    public static Color getColor(byte index) {
        return EmageColors.getColor(index);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // ==================== Image Resize ====================

    /**
     * Resize an image to the specified dimensions.
     */
    public static BufferedImage resize(BufferedImage src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height) {
            return src;
        }

        BufferedImage dest = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return dest;
    }

    // ==================== Image Processing ====================

    public static byte[] dither(BufferedImage img) {
        return dither(img, Quality.BALANCED);
    }

    public static byte[] dither(BufferedImage img, Quality quality) {
        BufferedImage prepared = prepareImage(img, MAP_WIDTH, MAP_WIDTH);
        int[] pixels = new int[MAP_SIZE];
        prepared.getRGB(0, 0, MAP_WIDTH, MAP_WIDTH, pixels, 0, MAP_WIDTH);
        return ditherPixels(pixels, quality);
    }

    public static byte[] ditherPixels(int[] pixels, Quality quality) {
        return switch (quality) {
            case FAST -> ditherOrdered(pixels);
            case BALANCED, HIGH -> ditherFloydSteinberg(pixels);
        };
    }

    private static BufferedImage prepareImage(BufferedImage src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height) {
            return src;
        }
        BufferedImage dest = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return dest;
    }

    // ==================== Ordered Dithering ====================

    private static final int[][] BAYER_4X4 = {
            { 0,  8,  2, 10},
            {12,  4, 14,  6},
            { 3, 11,  1,  9},
            {15,  7, 13,  5}
    };

    private static byte[] ditherOrdered(int[] pixels) {
        byte[] result = new byte[MAP_SIZE];

        for (int i = 0; i < MAP_SIZE; i++) {
            int rgb = pixels[i];
            if (((rgb >> 24) & 0xFF) < 128) {
                result[i] = 0;
                continue;
            }

            int x = i & 127;
            int y = i >> 7;
            float threshold = (BAYER_4X4[y & 3][x & 3] / 16.0f) - 0.5f;
            float strength = 24.0f;

            int r = clamp((int) (((rgb >> 16) & 0xFF) + threshold * strength));
            int g = clamp((int) (((rgb >> 8) & 0xFF) + threshold * strength));
            int b = clamp((int) ((rgb & 0xFF) + threshold * strength));

            result[i] = matchColor(r, g, b);
        }

        return result;
    }

    // ==================== Floyd-Steinberg Dithering ====================

    private static byte[] ditherFloydSteinberg(int[] pixels) {
        byte[] result = new byte[MAP_SIZE];
        short[] errR = new short[MAP_SIZE];
        short[] errG = new short[MAP_SIZE];
        short[] errB = new short[MAP_SIZE];

        for (int y = 0; y < MAP_WIDTH; y++) {
            int row = y * MAP_WIDTH;
            int nextRow = row + MAP_WIDTH;

            for (int x = 0; x < MAP_WIDTH; x++) {
                int idx = row + x;
                int rgb = pixels[idx];

                if (((rgb >> 24) & 0xFF) < 128) {
                    result[idx] = 0;
                    continue;
                }

                int r = clamp(((rgb >> 16) & 0xFF) + (errR[idx] >> 4));
                int g = clamp(((rgb >> 8) & 0xFF) + (errG[idx] >> 4));
                int b = clamp((rgb & 0xFF) + (errB[idx] >> 4));

                byte match = matchColor(r, g, b);
                result[idx] = match;

                Color pal = getColor(match);
                short eR = (short) (r - pal.getRed());
                short eG = (short) (g - pal.getGreen());
                short eB = (short) (b - pal.getBlue());

                if (x + 1 < MAP_WIDTH) {
                    errR[idx + 1] += (eR * 7);
                    errG[idx + 1] += (eG * 7);
                    errB[idx + 1] += (eB * 7);
                }
                if (y + 1 < MAP_WIDTH) {
                    if (x > 0) {
                        errR[nextRow + x - 1] += (eR * 3);
                        errG[nextRow + x - 1] += (eG * 3);
                        errB[nextRow + x - 1] += (eB * 3);
                    }
                    errR[nextRow + x] += (eR * 5);
                    errG[nextRow + x] += (eG * 5);
                    errB[nextRow + x] += (eB * 5);
                    if (x + 1 < MAP_WIDTH) {
                        errR[nextRow + x + 1] += eR;
                        errG[nextRow + x + 1] += eG;
                        errB[nextRow + x + 1] += eB;
                    }
                }
            }
        }

        return result;
    }

    // ==================== GIF Processing ====================

    public static GifGridData processGifGrid(URL url, int gridW, int gridH, int maxFrames) throws Exception {
        return processGifGrid(url, gridW, gridH, maxFrames, Quality.BALANCED);
    }

    /**
     * Process GIF sequentially but efficiently.
     */
    public static GifGridData processGifGrid(URL url, int gridW, int gridH, int maxFrames, Quality quality) throws Exception {
        int totalW = gridW * MAP_WIDTH;
        int totalH = gridH * MAP_WIDTH;

        // Read all frames
        GifData gifData = readGif(url, maxFrames);
        if (gifData.frames.isEmpty()) {
            throw new Exception("No frames found in GIF");
        }

        int frameCount = gifData.frames.size();

        // Initialize grid
        @SuppressWarnings("unchecked")
        List<byte[]>[][] grid = new List[gridW][gridH];
        for (int gx = 0; gx < gridW; gx++) {
            for (int gy = 0; gy < gridH; gy++) {
                grid[gx][gy] = new ArrayList<>(frameCount);
            }
        }

        // Reusable buffers
        int[] allPixels = new int[totalW * totalH];
        int[] chunkPixels = new int[MAP_SIZE];

        // Process frames one at a time
        for (int f = 0; f < frameCount; f++) {
            BufferedImage frame = gifData.frames.get(f);

            // Resize frame
            BufferedImage resized = resize(frame, totalW, totalH);

            // Get all pixels
            resized.getRGB(0, 0, totalW, totalH, allPixels, 0, totalW);

            // Process each grid cell
            for (int gy = 0; gy < gridH; gy++) {
                for (int gx = 0; gx < gridW; gx++) {
                    int startX = gx * MAP_WIDTH;
                    int startY = gy * MAP_WIDTH;

                    // Extract chunk
                    for (int cy = 0; cy < MAP_WIDTH; cy++) {
                        System.arraycopy(allPixels, (startY + cy) * totalW + startX,
                                chunkPixels, cy * MAP_WIDTH, MAP_WIDTH);
                    }

                    // Dither
                    byte[] dithered = ditherPixels(chunkPixels, quality);
                    grid[gx][gy].add(dithered);
                }
            }

            // Clear frame reference to help GC
            gifData.frames.set(f, null);

            // Yield occasionally to prevent blocking
            if ((f & 7) == 7) {
                Thread.yield();
            }
        }

        int avgDelay = gifData.delays.isEmpty() ? 100 :
                (int) gifData.delays.stream().mapToInt(Integer::intValue).average().orElse(100);

        return new GifGridData(grid, gifData.delays, avgDelay, gridW, gridH);
    }

    private static GifData readGif(URL url, int maxFrames) throws Exception {
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();

        try (InputStream is = new BufferedInputStream(url.openStream(), 65536);
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

            // Get canvas size from stream metadata
            try {
                IIOMetadata streamMeta = reader.getStreamMetadata();
                if (streamMeta != null) {
                    IIOMetadataNode root = (IIOMetadataNode) streamMeta.getAsTree(
                            streamMeta.getNativeMetadataFormatName());
                    NodeList lsdNodes = root.getElementsByTagName("LogicalScreenDescriptor");
                    if (lsdNodes.getLength() > 0) {
                        IIOMetadataNode lsd = (IIOMetadataNode) lsdNodes.item(0);
                        String w = lsd.getAttribute("logicalScreenWidth");
                        String h = lsd.getAttribute("logicalScreenHeight");
                        if (w != null && !w.isEmpty()) canvasWidth = Integer.parseInt(w);
                        if (h != null && !h.isEmpty()) canvasHeight = Integer.parseInt(h);
                    }
                }
            } catch (Exception ignored) {}

            if (canvasWidth <= 0 || canvasHeight <= 0) {
                BufferedImage first = reader.read(0);
                canvasWidth = first.getWidth();
                canvasHeight = first.getHeight();
            }

            BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D canvasG = canvas.createGraphics();
            canvasG.setBackground(new Color(0, 0, 0, 0));
            canvasG.clearRect(0, 0, canvasWidth, canvasHeight);

            BufferedImage restoreCanvas = null;

            for (int i = 0; i < numFrames; i++) {
                BufferedImage rawFrame;
                try {
                    rawFrame = reader.read(i);
                } catch (Exception e) {
                    break;
                }
                if (rawFrame == null) break;

                // Read metadata
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
                } catch (Exception ignored) {}

                // Save canvas if needed
                if ("restoreToPrevious".equalsIgnoreCase(disposal)) {
                    restoreCanvas = copyImage(canvas);
                }

                // Draw frame
                canvasG.drawImage(rawFrame, frameX, frameY, null);

                // Store frame
                frames.add(copyImage(canvas));
                delays.add(Math.max(20, delay));

                // Handle disposal
                if ("restoreToBackgroundColor".equalsIgnoreCase(disposal)) {
                    canvasG.setComposite(AlphaComposite.Clear);
                    canvasG.fillRect(frameX, frameY, frameW, frameH);
                    canvasG.setComposite(AlphaComposite.SrcOver);
                } else if ("restoreToPrevious".equalsIgnoreCase(disposal) && restoreCanvas != null) {
                    canvasG.setComposite(AlphaComposite.Src);
                    canvasG.drawImage(restoreCanvas, 0, 0, null);
                    canvasG.setComposite(AlphaComposite.SrcOver);
                }
            }

            canvasG.dispose();
            reader.dispose();
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

    // ==================== Compression ====================

    public static byte[] compressMap(byte[] data) {
        return EmageCompression.compressSingleStatic(data);
    }

    public static byte[] decompressMap(byte[] compressed) {
        return EmageCompression.decompressSingleStatic(compressed);
    }

    public static AnimData decompressAnim(byte[] data) {
        try {
            EmageCompression.AnimGridData grid = EmageCompression.decompressAnimGrid(data);
            if (grid != null && !grid.cells.isEmpty()) {
                List<byte[]> frames = grid.cells.values().iterator().next();
                int avg = grid.delays.isEmpty() ? 100 :
                        (int) grid.delays.stream().mapToInt(i -> i).average().orElse(100);
                return new AnimData(frames, grid.delays, avg, grid.syncId);
            }
        } catch (Exception ignored) {}

        List<byte[]> frames = new ArrayList<>();
        frames.add(new byte[MAP_SIZE]);
        List<Integer> delays = new ArrayList<>();
        delays.add(100);
        return new AnimData(frames, delays, 100, 0L);
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
        }
    }

    // ==================== Data Classes ====================

    private static class GifData {
        final List<BufferedImage> frames;
        final List<Integer> delays;

        GifData(List<BufferedImage> frames, List<Integer> delays) {
            this.frames = frames;
            this.delays = delays;
        }
    }

    public static class AnimData {
        public final List<byte[]> frames;
        public final List<Integer> delays;
        public final int avgDelay;
        public final long syncId;

        public AnimData(List<byte[]> frames, List<Integer> delays, int avgDelay, long syncId) {
            this.frames = frames;
            this.delays = delays;
            this.avgDelay = avgDelay;
            this.syncId = syncId;
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