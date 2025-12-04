package org.flowerion.emage.Manager;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.flowerion.emage.Config.EmageConfig;
import org.flowerion.emage.Processing.EmageCompression;
import org.flowerion.emage.Render.EmageRenderer;
import org.flowerion.emage.Render.GifRenderer;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class EmageManager implements Listener {

    private final JavaPlugin plugin;
    private final EmageConfig config;
    private final File mapsFolder;

    private final Set<Integer> managedMaps = ConcurrentHashMap.newKeySet();
    private final Map<Integer, CachedMapData> mapCache = new ConcurrentHashMap<>();

    private final Map<Long, PendingStaticGrid> pendingStaticGrids = new ConcurrentHashMap<>();
    private final Map<Long, PendingAnimGrid> pendingAnimGrids = new ConcurrentHashMap<>();

    private final ExecutorService ioExecutor;
    private final ScheduledExecutorService scheduler;

    public EmageManager(JavaPlugin plugin, EmageConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.mapsFolder = new File(plugin.getDataFolder(), "maps");
        if (!mapsFolder.exists()) {
            mapsFolder.mkdirs();
        }

        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Emage-IO");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Emage-Scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void shutdown() {
        for (PendingStaticGrid grid : pendingStaticGrids.values()) {
            grid.saveNow();
        }
        for (PendingAnimGrid grid : pendingAnimGrids.values()) {
            grid.saveNow();
        }

        ioExecutor.shutdown();
        scheduler.shutdown();
        try {
            ioExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
        }
    }

    public void saveMap(int mapId, byte[] data) {
        saveMap(mapId, data, System.nanoTime());
    }

    public void saveMap(int mapId, byte[] data, long gridId) {
        managedMaps.add(mapId);
        mapCache.put(mapId, new CachedMapData(data, null, null, 0, gridId, false));
        config.incrementMapCount();

        PendingStaticGrid grid = pendingStaticGrids.computeIfAbsent(gridId,
                k -> new PendingStaticGrid(gridId));
        grid.addCell(mapId, data);
        grid.scheduleSave();
    }

    public void saveGif(int mapId, List<byte[]> frames, List<Integer> delays, int avgDelay, long syncId) {
        managedMaps.add(mapId);
        mapCache.put(mapId, new CachedMapData(null, new ArrayList<>(frames),
                new ArrayList<>(delays), avgDelay, syncId, true));
        config.incrementMapCount();
        config.incrementAnimationCount();

        PendingAnimGrid grid = pendingAnimGrids.computeIfAbsent(syncId,
                k -> new PendingAnimGrid(syncId, delays));
        grid.addCell(mapId, frames);
        grid.scheduleSave();
    }

    private class PendingStaticGrid {
        final long gridId;
        final Map<Integer, byte[]> cells = new ConcurrentHashMap<>();
        private ScheduledFuture<?> saveTask;
        private volatile boolean saving = false;

        PendingStaticGrid(long gridId) {
            this.gridId = gridId;
        }

        void addCell(int mapId, byte[] data) {
            cells.put(mapId, data.clone());
        }

        synchronized void scheduleSave() {
            if (saving) return;
            if (saveTask != null) saveTask.cancel(false);
            saveTask = scheduler.schedule(this::saveNow, 500, TimeUnit.MILLISECONDS);
        }

        synchronized void saveNow() {
            if (saving) return;
            saving = true;
            pendingStaticGrids.remove(gridId);

            ioExecutor.submit(() -> {
                try {
                    if (cells.size() == 1) {
                        Map.Entry<Integer, byte[]> entry = cells.entrySet().iterator().next();
                        int mapId = entry.getKey();
                        byte[] data = entry.getValue();

                        byte[] compressed = EmageCompression.compressSingleStatic(data);
                        File file = new File(mapsFolder, mapId + ".emap");
                        Files.write(file.toPath(), compressed);

                        plugin.getLogger().info("Saved static map " + mapId + ": " +
                                data.length + " -> " + compressed.length + " bytes");
                    } else {
                        byte[] compressed = EmageCompression.compressStaticGrid(cells, gridId);
                        File file = new File(mapsFolder, "static_" + gridId + ".esgrid");
                        Files.write(file.toPath(), compressed);

                        int rawSize = cells.size() * 16384;
                        plugin.getLogger().info("Saved static grid: " + cells.size() + " cells, " +
                                formatSize(rawSize) + " -> " + formatSize(compressed.length));
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to save static grid " + gridId, e);
                }
            });
        }
    }

    private class PendingAnimGrid {
        final long syncId;
        final List<Integer> delays;
        final Map<Integer, List<byte[]>> cells = new ConcurrentHashMap<>();
        private ScheduledFuture<?> saveTask;
        private volatile boolean saving = false;

        PendingAnimGrid(long syncId, List<Integer> delays) {
            this.syncId = syncId;
            this.delays = delays != null ? new ArrayList<>(delays) : new ArrayList<>();
        }

        void addCell(int mapId, List<byte[]> frames) {
            // Don't clone here - just reference (saves memory and time)
            cells.put(mapId, frames);
        }

        synchronized void scheduleSave() {
            if (saving) return;
            if (saveTask != null) saveTask.cancel(false);
            // Delay save by 2 seconds to batch multiple cells
            saveTask = scheduler.schedule(this::saveNow, 2000, TimeUnit.MILLISECONDS);
        }

        synchronized void saveNow() {
            if (saving) return;
            saving = true;
            pendingAnimGrids.remove(syncId);

            // Clone data for async saving
            final Map<Integer, List<byte[]>> cellsCopy = new HashMap<>();
            for (Map.Entry<Integer, List<byte[]>> entry : cells.entrySet()) {
                List<byte[]> framesCopy = new ArrayList<>(entry.getValue().size());
                for (byte[] frame : entry.getValue()) {
                    byte[] copy = new byte[frame.length];
                    System.arraycopy(frame, 0, copy, 0, frame.length);
                    framesCopy.add(copy);
                }
                cellsCopy.put(entry.getKey(), framesCopy);
            }
            final List<Integer> delaysCopy = new ArrayList<>(delays);

            ioExecutor.submit(() -> {
                try {
                    byte[] compressed = EmageCompression.compressAnimGrid(cellsCopy, delaysCopy, syncId);
                    File file = new File(mapsFolder, "anim_" + syncId + ".eagrid");
                    java.nio.file.Files.write(file.toPath(), compressed);

                    int frameCount = cellsCopy.isEmpty() ? 0 : cellsCopy.values().iterator().next().size();
                    plugin.getLogger().info("Saved animation: " + cellsCopy.size() + " cells, " + frameCount + " frames");
                } catch (Exception e) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to save animation " + syncId, e);
                }
            });
        }
    }

    public void loadAllMaps() {
        plugin.getLogger().info("Loading saved maps...");

        int staticLoaded = 0;
        int animLoaded = 0;

        File[] animGridFiles = mapsFolder.listFiles((dir, name) -> name.endsWith(".eagrid"));
        if (animGridFiles != null) {
            for (File file : animGridFiles) {
                try {
                    byte[] data = Files.readAllBytes(file.toPath());
                    EmageCompression.AnimGridData grid = EmageCompression.decompressAnimGrid(data);
                    if (grid != null) {
                        animLoaded += applyAnimGrid(grid);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load: " + file.getName());
                }
            }
        }

        File[] staticGridFiles = mapsFolder.listFiles((dir, name) -> name.endsWith(".esgrid"));
        if (staticGridFiles != null) {
            for (File file : staticGridFiles) {
                try {
                    byte[] data = Files.readAllBytes(file.toPath());
                    EmageCompression.StaticGridData grid = EmageCompression.decompressStaticGrid(data);
                    if (grid != null) {
                        staticLoaded += applyStaticGrid(grid);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load: " + file.getName());
                }
            }
        }

        File[] emapFiles = mapsFolder.listFiles((dir, name) -> name.endsWith(".emap"));
        if (emapFiles != null) {
            for (File file : emapFiles) {
                try {
                    int mapId = Integer.parseInt(file.getName().replace(".emap", ""));
                    if (!managedMaps.contains(mapId)) {
                        byte[] data = Files.readAllBytes(file.toPath());
                        byte[] mapData = EmageCompression.decompressSingleStatic(data);
                        if (applyStaticMap(mapId, mapData)) {
                            staticLoaded++;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        loadLegacyFiles();

        config.setMapCount(staticLoaded + animLoaded);
        config.setAnimationCount(animLoaded);

        plugin.getLogger().info("Loaded " + staticLoaded + " static, " + animLoaded + " animations");
    }

    private void loadLegacyFiles() {
        File[] oldGridFiles = mapsFolder.listFiles((dir, name) ->
                name.startsWith("grid_") && name.endsWith(".egrid"));
        if (oldGridFiles != null) {
            for (File file : oldGridFiles) {
                try {
                    byte[] data = Files.readAllBytes(file.toPath());
                    EmageCompression.AnimGridData grid = EmageCompression.decompressAnimGrid(data);
                    if (grid != null) {
                        applyAnimGrid(grid);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private int applyStaticGrid(EmageCompression.StaticGridData grid) {
        int count = 0;
        for (Map.Entry<Integer, byte[]> entry : grid.cells.entrySet()) {
            if (applyStaticMap(entry.getKey(), entry.getValue())) {
                count++;
            }
        }
        return count;
    }

    private int applyAnimGrid(EmageCompression.AnimGridData grid) {
        GifRenderer.resetSyncTime(grid.syncId);

        int count = 0;
        int avgDelay = grid.delays.isEmpty() ? 100 :
                (int) grid.delays.stream().mapToInt(Integer::intValue).average().orElse(100);

        for (Map.Entry<Integer, List<byte[]>> entry : grid.cells.entrySet()) {
            int mapId = entry.getKey();
            List<byte[]> frames = entry.getValue();

            managedMaps.add(mapId);
            mapCache.put(mapId, new CachedMapData(null, frames, grid.delays, avgDelay, grid.syncId, true));

            @SuppressWarnings("deprecation")
            MapView mapView = Bukkit.getMap(mapId);
            if (mapView != null) {
                applyAnimRenderer(mapView, frames, grid.delays, grid.syncId);
                count++;
            }
        }

        return count;
    }

    private boolean applyStaticMap(int mapId, byte[] data) {
        managedMaps.add(mapId);
        mapCache.put(mapId, new CachedMapData(data, null, null, 0, 0, false));

        @SuppressWarnings("deprecation")
        MapView mapView = Bukkit.getMap(mapId);
        if (mapView != null) {
            applyStaticRenderer(mapView, data);
            return true;
        }
        return false;
    }

    private void applyStaticRenderer(MapView mapView, byte[] data) {
        mapView.getRenderers().forEach(mapView::removeRenderer);
        mapView.setTrackingPosition(false);
        mapView.setUnlimitedTracking(false);
        mapView.addRenderer(new EmageRenderer(data));
    }

    private void applyAnimRenderer(MapView mapView, List<byte[]> frames, List<Integer> delays, long syncId) {
        mapView.getRenderers().forEach(mapView::removeRenderer);
        mapView.setTrackingPosition(false);
        mapView.setUnlimitedTracking(false);

        GifRenderer renderer = new GifRenderer(frames, delays, syncId);
        renderer.setMapView(mapView);
        mapView.addRenderer(renderer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMapInitialize(MapInitializeEvent event) {
        @SuppressWarnings("deprecation")
        int mapId = event.getMap().getId();

        CachedMapData cached = mapCache.get(mapId);
        if (cached != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (cached.isAnimation) {
                    applyAnimRenderer(event.getMap(), cached.frames, cached.delays, cached.syncId);
                } else if (cached.staticData != null) {
                    applyStaticRenderer(event.getMap(), cached.staticData);
                }
            }, 1L);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<Integer, CachedMapData> entry : mapCache.entrySet()) {
                @SuppressWarnings("deprecation")
                MapView mapView = Bukkit.getMap(entry.getKey());
                if (mapView != null) {
                    CachedMapData cached = entry.getValue();
                    if (cached.isAnimation) {
                        applyAnimRenderer(mapView, cached.frames, cached.delays, cached.syncId);
                    } else if (cached.staticData != null) {
                        applyStaticRenderer(mapView, cached.staticData);
                    }
                }
            }
        }, 40L);
    }

    public int cleanupUnusedFiles(Set<Integer> mapsInUse) {
        int deleted = 0;
        File[] files = mapsFolder.listFiles();
        if (files == null) return 0;

        plugin.getLogger().info("Cleanup: Found " + mapsInUse.size() + " maps in use, scanning " + files.length + " files...");

        for (File file : files) {
            String name = file.getName();

            try {
                boolean shouldDelete = false;
                Set<Integer> fileMapIds = new HashSet<>();

                if (name.endsWith(".emap")) {
                    int mapId = Integer.parseInt(name.replace(".emap", ""));
                    fileMapIds.add(mapId);
                    shouldDelete = !mapsInUse.contains(mapId);

                } else if (name.startsWith("static_") && name.endsWith(".esgrid")) {
                    fileMapIds = getMapIdsFromGridFile(file);
                    shouldDelete = !hasAnyMapInUse(fileMapIds, mapsInUse);

                } else if (name.startsWith("anim_") && name.endsWith(".eagrid")) {
                    fileMapIds = getMapIdsFromGridFile(file);
                    shouldDelete = !hasAnyMapInUse(fileMapIds, mapsInUse);

                } else if (name.startsWith("grid_") && name.endsWith(".egrid")) {
                    fileMapIds = getMapIdsFromLegacyGridFile(file);
                    shouldDelete = !hasAnyMapInUse(fileMapIds, mapsInUse);

                } else if (name.endsWith(".eanim")) {
                    int mapId = Integer.parseInt(name.replace(".eanim", ""));
                    fileMapIds.add(mapId);
                    shouldDelete = !mapsInUse.contains(mapId);
                }

                if (shouldDelete && !fileMapIds.isEmpty()) {
                    plugin.getLogger().info("Cleanup: Deleting " + name + " (maps: " + fileMapIds + ")");
                    if (file.delete()) {
                        deleted++;
                        for (int mapId : fileMapIds) {
                            managedMaps.remove(mapId);
                            mapCache.remove(mapId);
                        }
                    } else {
                        plugin.getLogger().warning("Cleanup: Failed to delete " + name);
                    }
                }
            } catch (NumberFormatException e) {
                plugin.getLogger().fine("Cleanup: Skipping non-map file " + name);
            } catch (Exception e) {
                plugin.getLogger().warning("Cleanup: Error processing " + name + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Cleanup: Deleted " + deleted + " unused files");
        return deleted;
    }

    private boolean hasAnyMapInUse(Set<Integer> fileMapIds, Set<Integer> mapsInUse) {
        for (int mapId : fileMapIds) {
            if (mapsInUse.contains(mapId)) {
                return true;
            }
        }
        return false;
    }

    private Set<Integer> getMapIdsFromGridFile(File file) {
        Set<Integer> mapIds = new HashSet<>();

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int m1 = dis.readByte() & 0xFF;
            int m2 = dis.readByte() & 0xFF;
            int m3 = dis.readByte() & 0xFF;

            if (m1 != 'E' || m2 != 'G') {
                return mapIds;
            }

            dis.readLong();

            int cellCount = dis.readShort() & 0xFFFF;

            if (m3 == 'A') {
                dis.readShort();
            }

            for (int i = 0; i < cellCount; i++) {
                mapIds.add(dis.readInt());
            }

        } catch (Exception e) {
            plugin.getLogger().fine("Could not read map IDs from " + file.getName() + ": " + e.getMessage());
        }

        return mapIds;
    }

    private Set<Integer> getMapIdsFromLegacyGridFile(File file) {
        Set<Integer> mapIds = new HashSet<>();

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int m1 = dis.readByte() & 0xFF;
            int m2 = dis.readByte() & 0xFF;
            int version = dis.readByte() & 0xFF;

            if (m1 != 'E' || m2 != 'G') {
                return mapIds;
            }

            dis.readLong();

            int cellCount = dis.readInt();

            dis.readInt();

            for (int i = 0; i < cellCount; i++) {
                dis.readShort();
            }

        } catch (Exception e) {
            plugin.getLogger().fine("Could not read map IDs from legacy " + file.getName());
        }

        try {
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
            Set<Integer> ids = EmageCompression.getMapIdsFromFile(file);
            if (!ids.isEmpty()) {
                return ids;
            }
        } catch (Exception ignored) {}

        return mapIds;
    }

    public int migrateOldFormats() {
        return 0;
    }

    public MapStats getStats() {
        int staticCount = 0;
        int animCount = 0;
        long totalSize = 0;

        File[] files = mapsFolder.listFiles();
        if (files != null) {
            for (File file : files) {
                totalSize += file.length();
                String name = file.getName();
                if (name.endsWith(".emap") || name.endsWith(".esgrid")) staticCount++;
                else if (name.endsWith(".eagrid")) animCount++;
            }
        }

        return new MapStats(staticCount, animCount, totalSize, managedMaps.size(),
                config.getPerformanceStatus());
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    public File getMapsFolder() {
        return mapsFolder;
    }

    private static class CachedMapData {
        final byte[] staticData;
        final List<byte[]> frames;
        final List<Integer> delays;
        final int avgDelay;
        final long syncId;
        final boolean isAnimation;

        CachedMapData(byte[] staticData, List<byte[]> frames, List<Integer> delays,
                      int avgDelay, long syncId, boolean isAnimation) {
            this.staticData = staticData;
            this.frames = frames;
            this.delays = delays;
            this.avgDelay = avgDelay;
            this.syncId = syncId;
            this.isAnimation = isAnimation;
        }
    }

    public static class MapStats {
        public final int staticMaps;
        public final int animations;
        public final long totalSizeBytes;
        public final int activeMaps;
        public final String performanceStatus;

        public MapStats(int staticMaps, int animations, long totalSizeBytes,
                        int activeMaps, String performanceStatus) {
            this.staticMaps = staticMaps;
            this.animations = animations;
            this.totalSizeBytes = totalSizeBytes;
            this.activeMaps = activeMaps;
            this.performanceStatus = performanceStatus;
        }

        public String getTotalSizeFormatted() {
            if (totalSizeBytes < 1024) return totalSizeBytes + " B";
            if (totalSizeBytes < 1024 * 1024) return String.format("%.1f KB", totalSizeBytes / 1024.0);
            return String.format("%.2f MB", totalSizeBytes / (1024.0 * 1024.0));
        }
    }
}