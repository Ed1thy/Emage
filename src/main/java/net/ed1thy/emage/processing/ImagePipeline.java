package net.ed1thy.emage.processing;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.MapFrameUpdate;
import net.ed1thy.emage.processing.dither.BlueNoiseDither;
import net.ed1thy.emage.storage.FlatFileStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class ImagePipeline {

    private final ColorPalette lut;
    private final FlatFileStorage storage;
    private final BlueNoiseDither dither = new BlueNoiseDither();
    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ImagePipeline(@NotNull ColorPalette lut, @NotNull FlatFileStorage storage) {
        this.lut = lut;
        this.storage = storage;
    }

    public void shutdown() {
        vtExecutor.shutdownNow();
        dither.shutdown();
    }

    private void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public CompletableFuture<ConcurrentHashMap<Long, MapFrameUpdate>> processStreamAsync(
            @NotNull ImageFrameProvider decoder, int syncGroupId, @NotNull List<Integer> virtualMapIds, int columns, int rows,
            @Nullable Consumer<Double> progressCallback) {

        return CompletableFuture.supplyAsync(() -> {
            ConcurrentHashMap<Long, MapFrameUpdate> preloadedMap = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> ioTasks = new ArrayList<>();

            try (decoder) {
                while (!lut.isReady()) {
                    Thread.sleep(50);
                }

                int totalWidth = columns * 128;
                int totalHeight = rows * 128;
                byte[][] previousMapFrames = new byte[virtualMapIds.size()][16384];

                int totalFramesCount = decoder.getFrameCount();

                for (int frameIndex = 0; frameIndex < totalFramesCount; frameIndex++) {
                    BufferedImage rawFrame = decoder.getFrame(frameIndex);

                    int drawW = totalWidth;
                    int drawH = totalHeight;
                    int offsetX = 0;
                    int offsetY = 0;

                    BufferedImage scaledImage = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D gFinal = scaledImage.createGraphics();

                    gFinal.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    gFinal.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    gFinal.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    gFinal.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);

                    gFinal.setComposite(AlphaComposite.Src);
                    gFinal.drawImage(rawFrame, offsetX, offsetY, drawW, drawH, null);
                    gFinal.dispose();

                    int[] argbPixels = scaledImage.getRGB(0, 0, totalWidth, totalHeight, null, 0, totalWidth);
                    byte[] ditheredColors = dither.applyDither(argbPixels, totalWidth, totalHeight, lut);

                    final boolean firstFrame = (frameIndex == 0);
                    final int fIndex = frameIndex;

                    Map<Integer, MapFrameUpdate> frameUpdates = new ConcurrentHashMap<>();

                    java.util.stream.IntStream.range(0, rows * columns).parallel().forEach(mapIndex -> {
                        int row = mapIndex / columns;
                        int col = mapIndex % columns;
                        int mapId = virtualMapIds.get(mapIndex);

                        byte[] currentMapColors = extractMapArray(ditheredColors, totalWidth, col * 128, row * 128);
                        byte[] prevMapColors = previousMapFrames[mapIndex];
                        long cacheKey = ((long) fIndex << 32) | (mapId & 0xFFFFFFFFL);

                        if (firstFrame) {
                            ByteBuf packetBuf = PooledByteBufAllocator.DEFAULT.directBuffer(16384 + 16);
                            writeVarInt(packetBuf, mapId);
                            packetBuf.writeByte(0);
                            packetBuf.writeBoolean(true);
                            packetBuf.writeBoolean(false);
                            packetBuf.writeByte(128);
                            packetBuf.writeByte(128);
                            packetBuf.writeByte(0);
                            packetBuf.writeByte(0);
                            writeVarInt(packetBuf, 16384);
                            packetBuf.writeBytes(currentMapColors);

                            DeltaFrame fullPart = new DeltaFrame(fIndex, mapId, packetBuf);
                            MapFrameUpdate update = new MapFrameUpdate(new DeltaFrame[]{fullPart});

                            preloadedMap.put(cacheKey, update);
                            frameUpdates.put(mapId, update);
                            previousMapFrames[mapIndex] = currentMapColors;
                        } else {
                            MapFrameUpdate update = calculateDelta(fIndex, mapId, prevMapColors, currentMapColors);
                            if (update != null) {
                                frameUpdates.put(mapId, update);
                                previousMapFrames[mapIndex] = currentMapColors;
                            }
                        }
                    });

                    if (firstFrame) {
                        ioTasks.add(CompletableFuture.runAsync(() -> {
                            try { storage.saveBundledFrame(syncGroupId, fIndex, frameUpdates); }
                            catch (Exception e) { e.printStackTrace(); }
                        }, vtExecutor));
                    } else {
                        ioTasks.add(CompletableFuture.runAsync(() -> {
                            try {
                                storage.saveBundledFrame(syncGroupId, fIndex, frameUpdates);
                                for (MapFrameUpdate update : frameUpdates.values()) update.freeMemory();
                            } catch (Exception e) { e.printStackTrace(); }
                        }, vtExecutor));
                    }

                    if (progressCallback != null) {
                        progressCallback.accept((double) (frameIndex + 1) / totalFramesCount);
                    }
                }

                CompletableFuture.allOf(ioTasks.toArray(new CompletableFuture[0])).join();

            } catch (Exception e) {
                throw new RuntimeException("Failed to process image stream: " + e.getMessage(), e);
            }

            return preloadedMap;
        }, vtExecutor);
    }

    private byte[] extractMapArray(byte[] fullGrid, int gridWidth, int startX, int startY) {
        byte[] mapTile = new byte[16384];
        for (int y = 0; y < 128; y++) {
            System.arraycopy(fullGrid, (startY + y) * gridWidth + startX, mapTile, y * 128, 128);
        }
        return mapTile;
    }

    private MapFrameUpdate calculateDelta(int frameIndex, int mapId, byte[] prev, byte[] curr) {
        int minX = 128, minY = 128, maxX = -1, maxY = -1;

        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int idx = y * 128 + x;
                if (prev[idx] != curr[idx]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX == -1) return null;

        int updateWidth = (maxX - minX) + 1;
        int updateHeight = (maxY - minY) + 1;

        ByteBuf packetBuf = PooledByteBufAllocator.DEFAULT.directBuffer(updateWidth * updateHeight + 18);
        writeVarInt(packetBuf, mapId);
        packetBuf.writeByte(0);
        packetBuf.writeBoolean(true);
        packetBuf.writeBoolean(false);
        packetBuf.writeByte(updateWidth);
        packetBuf.writeByte(updateHeight);
        packetBuf.writeByte(minX);
        packetBuf.writeByte(minY);
        writeVarInt(packetBuf, updateWidth * updateHeight);

        for (int y = 0; y < updateHeight; y++) {
            packetBuf.writeBytes(curr, (minY + y) * 128 + minX, updateWidth);
        }

        DeltaFrame part = new DeltaFrame(frameIndex, mapId, packetBuf);
        return new MapFrameUpdate(new DeltaFrame[]{part});
    }
}