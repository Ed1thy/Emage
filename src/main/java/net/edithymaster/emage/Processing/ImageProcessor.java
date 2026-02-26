package net.edithymaster.emage.Processing;

import net.edithymaster.emage.Config.EmageConfig;
import net.edithymaster.emage.EmagePlugin;
import net.edithymaster.emage.Manager.EmageManager;
import net.edithymaster.emage.Packet.MapPacketSender;
import net.edithymaster.emage.Render.GifRenderer;
import net.edithymaster.emage.Util.FrameGrid.FrameNode;
import net.edithymaster.emage.Util.GifCache;
import net.edithymaster.emage.Util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ImageProcessor {

    public static void processUrl(Player player, String finalUrl, List<FrameNode> frameNodes, int gridWidth, int gridHeight, boolean finalNoCache, EmagePlugin plugin, EmageManager manager, AtomicInteger activeTasks, long uniqueId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URL url = new URI(finalUrl).toURL();
                boolean isGif = finalUrl.toLowerCase().contains(".gif");

                if (!isGif) {
                    try {
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        try {
                            conn.setRequestMethod("HEAD");
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(5000);
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                            conn.connect();
                            String contentType = conn.getContentType();
                            if (contentType != null && contentType.toLowerCase().contains("gif")) {
                                isGif = true;
                            }
                        } finally {
                            conn.disconnect();
                        }
                    } catch (IOException e) {
                        plugin.getLogger().fine("Could not detect content type via HEAD request for " + url.getHost() + ": " + e.getMessage() + ". URL will be processed as a static image unless the URL contains '.gif'.");
                    }
                }

                EmageConfig cfg = plugin.getEmageConfig();

                if (isGif) {
                    int maxGifGrid = cfg.getMaxGifGridSize();
                    if (gridWidth > maxGifGrid || gridHeight > maxGifGrid) {
                        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(MessageUtil.msg("gif-too-large", "<max>", String.valueOf(maxGifGrid))));
                        return;
                    }

                    int totalCells = gridWidth * gridHeight;
                    if (totalCells >= 9) {
                        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(MessageUtil.msg("gif-large-warning", "<width>", String.valueOf(gridWidth), "<height>", String.valueOf(gridHeight))));
                    }

                    processGif(player, url, frameNodes, gridWidth, gridHeight, finalNoCache, plugin, manager, uniqueId);
                } else {
                    int maxImgGrid = cfg.getMaxImageGridSize();
                    if (gridWidth > maxImgGrid || gridHeight > maxImgGrid) {
                        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(MessageUtil.msg("image-too-large", "<max>", String.valueOf(maxImgGrid))));
                        return;
                    }

                    processStaticImage(player, url, frameNodes, gridWidth, gridHeight, plugin, manager, uniqueId);
                }

            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                plugin.getLogger().warning("Could not process the requested image URL. Cause: " + errorMsg);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(MessageUtil.msg("error", "<error>", errorMsg)));
            } finally {
                activeTasks.decrementAndGet();
            }
        });
    }

    private static void processStaticImage(Player player, URL url, List<FrameNode> nodes, int gridWidth, int gridHeight, EmagePlugin plugin, EmageManager manager, long gridId) throws Exception {
        BufferedImage original = EmageCore.downloadImage(url);
        int totalWidth = gridWidth * 128;
        int totalHeight = gridHeight * 128;
        BufferedImage resized = EmageCore.padAndScaleToExact(original, totalWidth, totalHeight);
        List<ProcessedChunk> chunks = new ArrayList<>();

        for (FrameNode node : nodes) {
            if (node.gridX < 0 || node.gridX >= gridWidth || node.gridY < 0 || node.gridY >= gridHeight) continue;
            int px = node.gridX * 128;
            int py = node.gridY * 128;
            BufferedImage chunk = resized.getSubimage(px, py, 128, 128);
            byte[] mapData = EmageCore.dither(chunk);
            chunks.add(new ProcessedChunk(node.frame, mapData));
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            int applied = 0;
            for (ProcessedChunk chunk : chunks) {
                applyMapToFrame(chunk.frame, chunk.data, gridId, manager);
                applied++;
            }

            MapPacketSender pktSender = plugin.getMapPacketSender();
            for (ProcessedChunk chunk : chunks) {
                ItemStack item = chunk.frame.getItem();
                if (item != null && item.getType() == Material.FILLED_MAP) {
                    MapMeta meta = (MapMeta) item.getItemMeta();
                    if (meta != null && meta.hasMapView()) {
                        Object packet = pktSender.createPacket(meta.getMapView().getId(), chunk.data);
                        pktSender.sendPacket(player, packet);
                    }
                }
            }
            player.sendMessage(MessageUtil.msg("success", "<total>", String.valueOf(applied)));
        });
    }

    private static void processGif(Player player, URL url, List<FrameNode> nodes, int gridWidth, int gridHeight, boolean noCache, EmagePlugin plugin, EmageManager manager, long syncId) throws Exception {
        String cacheKey = GifCache.createKey(url.toString(), gridWidth, gridHeight);
        int maxFrames = plugin.getEmageConfig().getMaxGifFrames();
        EmageCore.GifGridData cachedData = noCache ? null : GifCache.get(cacheKey);

        if (cachedData != null) {
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(MessageUtil.msg("using-cache")));
            applyGifData(player, cachedData, nodes, gridWidth, gridHeight, 0, plugin, manager, syncId);
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(MessageUtil.msg("processing-gif", "<width>", String.valueOf(gridWidth), "<height>", String.valueOf(gridHeight))));
        long startTime = System.currentTimeMillis();

        EmageCore.GifGridData gifData = EmageCore.processGifGrid(url, gridWidth, gridHeight, maxFrames, (current, total, stage) -> {
            Bukkit.getScheduler().runTask(plugin, () -> player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(MessageUtil.colorize("&7" + stage))));
        });

        long processTime = System.currentTimeMillis() - startTime;
        GifCache.put(cacheKey, gifData);
        applyGifData(player, gifData, nodes, gridWidth, gridHeight, processTime, plugin, manager, syncId);
    }

    private static void applyGifData(Player player, EmageCore.GifGridData gifData, List<FrameNode> nodes, int gridWidth, int gridHeight, long processTime, EmagePlugin plugin, EmageManager manager, long syncId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            int applied = 0;
            for (FrameNode node : nodes) {
                if (node.gridX < 0 || node.gridX >= gridWidth || node.gridY < 0 || node.gridY >= gridHeight) continue;
                List<byte[]> frames = gifData.grid[node.gridX][node.gridY];
                if (frames != null && !frames.isEmpty()) {
                    applyGifToFrame(node.frame, frames, gifData.delays, gifData.avgDelay, syncId, manager);
                    applied++;
                }
            }

            GifRenderer.startSyncGroup(syncId);
            int frameCount = gifData.grid[0][0] != null ? gifData.grid[0][0].size() : 0;

            if (processTime > 0) {
                player.sendMessage(MessageUtil.msg("success-gif", "<total>", String.valueOf(applied), "<frames>", String.valueOf(frameCount), "<time>", String.valueOf(processTime)));
            } else {
                player.sendMessage(MessageUtil.msg("success-gif-cached", "<total>", String.valueOf(applied), "<frames>", String.valueOf(frameCount)));
            }
        });
    }

    private static void applyMapToFrame(ItemFrame frame, byte[] mapData, long gridId, EmageManager manager) {
        MapView mapView = getOrCreateMapView(frame, manager);

        mapView.setTrackingPosition(false);
        mapView.setUnlimitedTracking(false);

        manager.saveMap(mapView.getId(), mapData, gridId);

        frame.setRotation(Rotation.NONE);
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setMapView(mapView);
            mapItem.setItemMeta(meta);
        }
        frame.setItem(mapItem);
    }

    private static void applyGifToFrame(ItemFrame frame, List<byte[]> frames, List<Integer> delays, int avgDelay, long syncId, EmageManager manager) {
        MapView mapView = getOrCreateMapView(frame, manager);

        mapView.setTrackingPosition(false);
        mapView.setUnlimitedTracking(false);

        new GifRenderer(mapView.getId(), frames, delays, syncId);
        manager.saveGif(mapView.getId(), frames, delays, avgDelay, syncId);
        GifRenderer.registerMapLocation(mapView.getId(), frame.getLocation());

        frame.setRotation(Rotation.NONE);
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setMapView(mapView);
            mapItem.setItemMeta(meta);
        }
        frame.setItem(mapItem);
    }

    private static MapView getOrCreateMapView(ItemFrame frame, EmageManager manager) {
        ItemStack existing = frame.getItem();
        if (existing != null && existing.getType() == Material.FILLED_MAP) {
            try {
                MapMeta meta = (MapMeta) existing.getItemMeta();
                if (meta != null && meta.hasMapView()) {
                    MapView view = meta.getMapView();
                    if (view != null) {
                        new ArrayList<>(view.getRenderers()).forEach(view::removeRenderer);
                        GifRenderer.removeByMapId(view.getId());

                        return view;
                    }
                }
            } catch (Exception ignored) {}
        }
        return manager.allocateMap(frame.getWorld());
    }

    private record ProcessedChunk(ItemFrame frame, byte[] data) {}
}