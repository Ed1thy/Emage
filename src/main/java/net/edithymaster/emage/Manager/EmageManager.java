package net.edithymaster.emage.Manager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.edithymaster.emage.Processing.EmageCompression;
import net.edithymaster.emage.Storage.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import net.edithymaster.emage.Config.EmageConfig;
import net.edithymaster.emage.Render.GifRenderer;

import java.io.File;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class EmageManager {

    private final JavaPlugin plugin;
    private final EmageConfig config;
    private final DatabaseManager db;
    private final Gson gson = new Gson();

    private final Set<Integer> managedMaps = ConcurrentHashMap.newKeySet();
    private final Map<Integer, CachedMapData> mapCache = new ConcurrentHashMap<>();
    private final Set<Integer> appliedMaps = ConcurrentHashMap.newKeySet();

    private final Map<Long, PendingGrid> pendingGrids = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    private final Set<Integer> pendingMapInits = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean mapInitTaskScheduled = new AtomicBoolean(false);
    private final Queue<Integer> recycledMapIds = new ConcurrentLinkedQueue<>();

    private final AtomicBoolean databaseReady = new AtomicBoolean(false);
    private final AtomicBoolean mapsLoaded = new AtomicBoolean(false);

    private final AtomicLong lastWorldSaveTime = new AtomicLong(0);

    public EmageManager(JavaPlugin plugin, EmageConfig config) {
        this.plugin = plugin;
        this.config = config;

        this.db = new DatabaseManager(plugin);

        db.initAsync().thenCompose(success -> {
            if (success) {
                databaseReady.set(true);
                plugin.getLogger().info("Database ready, loading maps...");
                return db.loadAllRecycledIdsAsync();
            } else {
                plugin.getLogger().severe("Database initialization failed. Map persistence is disabled.");
                return CompletableFuture.completedFuture((Queue<Integer>) null);
            }
        }).thenAccept(ids -> {
            try {
                if (ids != null) {
                    recycledMapIds.addAll(ids);
                }
                if (databaseReady.get()) {
                    loadAllMapsInternal();
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Unexpected error in Database async callback", e);
            }
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Database initialization threw an exception.", throwable);
            return null;
        });

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Emage-Scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isReady() {
        return databaseReady.get() && mapsLoaded.get();
    }

    public boolean isDatabaseReady() {
        return databaseReady.get() && db.isReady();
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        for (PendingGrid grid : pendingGrids.values()) {
            grid.flushSync();
        }
        pendingGrids.clear();

        db.shutdown();
        databaseReady.set(false);
        mapsLoaded.set(false);
    }

    public MapView allocateMap(World world) {
        int attempts = recycledMapIds.size();
        for (int i = 0; i < attempts; i++) {
            Integer recycledId = recycledMapIds.poll();
            if (recycledId == null) break;

            MapView view = Bukkit.getMap(recycledId);

            if (view != null) {
                if (isDatabaseReady()) {
                    db.removeFromPool(recycledId);
                }
                List<org.bukkit.map.MapRenderer> renderers = new ArrayList<>(view.getRenderers());
                for (org.bukkit.map.MapRenderer r : renderers) {
                    view.removeRenderer(r);
                }
                view.setTrackingPosition(false);
                view.setUnlimitedTracking(false);

                plugin.getLogger().fine("Reused recycled Map ID: " + recycledId);
                return view;
            } else {
                recycledMapIds.add(recycledId);
            }
        }

        return Bukkit.createMap(world);
    }

    public void saveMap(int mapId, byte[] data, long gridId) {
        managedMaps.add(mapId);
        mapCache.put(mapId, new CachedMapData(data, null, null, 0, gridId, false));
        config.incrementMapCount();

        if (isDatabaseReady()) {
            pendingGrids.computeIfAbsent(gridId, PendingGrid::new)
                    .addStatic(mapId, data)
                    .scheduleSave();
        } else {
            plugin.getLogger().warning("Cannot save map " + mapId + ": database not ready. Data cached in memory only.");
        }
    }

    public void saveGif(int mapId, List<byte[]> frames, List<Integer> delays, int avgDelay, long syncId) {
        managedMaps.add(mapId);
        mapCache.put(mapId, new CachedMapData(null, frames, new ArrayList<>(delays), avgDelay, syncId, true));
        config.incrementMapCount();
        config.incrementAnimationCount();

        if (isDatabaseReady()) {
            pendingGrids.computeIfAbsent(syncId, PendingGrid::new)
                    .addAnim(mapId, frames, delays)
                    .scheduleSave();
        } else {
            plugin.getLogger().warning("Cannot save GIF " + mapId + ": database not ready. Data cached in memory only.");
        }
    }

    private class PendingGrid {
        final long id;
        final Map<Integer, byte[]> statics = new HashMap<>();
        final Map<Integer, AnimSaveData> anims = new HashMap<>();
        private ScheduledFuture<?> task;
        volatile boolean saving = false;

        PendingGrid(long id) {
            this.id = id;
        }

        synchronized PendingGrid addStatic(int mapId, byte[] data) {
            statics.put(mapId, data);
            return this;
        }

        synchronized PendingGrid addAnim(int mapId, List<byte[]> frames, List<Integer> delays) {
            anims.put(mapId, new AnimSaveData(frames, delays));
            return this;
        }

        synchronized void scheduleSave() {
            if (saving) return;
            if (task != null) task.cancel(false);
            task = scheduler.schedule(this::saveNowAsync, 500, TimeUnit.MILLISECONDS);
        }

        void saveNowAsync() {
            if (!isDatabaseReady()) {
                plugin.getLogger().warning("Skipping save for grid " + id + ": database not ready.");
                return;
            }

            synchronized (this) {
                if (saving) return;
                saving = true;
            }

            Map<Integer, byte[]> sCopy;
            Map<Integer, AnimSaveData> aCopy;

            synchronized (this) {
                sCopy = new HashMap<>(statics);
                aCopy = new HashMap<>(anims);
                statics.clear();
                anims.clear();
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    for (Map.Entry<Integer, byte[]> entry : sCopy.entrySet()) {
                        byte[] compressed = EmageCompression.compressSingleStatic(entry.getValue());
                        db.saveMap(entry.getKey(), id, compressed);
                    }
                    for (Map.Entry<Integer, AnimSaveData> entry : aCopy.entrySet()) {
                        byte[] compressed = EmageCompression.compressAnimFrames(entry.getValue().frames);
                        String meta = gson.toJson(entry.getValue().delays);
                        db.saveAnimMap(entry.getKey(), id, compressed, meta);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Could not save grid " + id + " to the database.", e);
                    synchronized (PendingGrid.this) {
                        statics.putAll(sCopy);
                        anims.putAll(aCopy);
                    }
                } finally {
                    synchronized (PendingGrid.this) {
                        saving = false;
                        if (!statics.isEmpty() || !anims.isEmpty()) {
                            scheduleSave();
                        } else {
                            pendingGrids.remove(id);
                        }
                    }
                }
            });
        }

        void flushSync() {
            Map<Integer, byte[]> sCopy;
            Map<Integer, AnimSaveData> aCopy;

            synchronized (this) {
                if (task != null) task.cancel(false);
                sCopy = new HashMap<>(statics);
                aCopy = new HashMap<>(anims);
                statics.clear();
                anims.clear();
            }

            List<CompletableFuture<Void>> waitFutures = new ArrayList<>();

            for (Map.Entry<Integer, byte[]> entry : sCopy.entrySet()) {
                try {
                    byte[] compressed = EmageCompression.compressSingleStatic(entry.getValue());
                    waitFutures.add(db.saveMapAsync(entry.getKey(), id, compressed));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not flush map " + entry.getKey() + " during shutdown.", e);
                }
            }
            for (Map.Entry<Integer, AnimSaveData> entry : aCopy.entrySet()) {
                try {
                    byte[] compressed = EmageCompression.compressAnimFrames(entry.getValue().frames);
                    String meta = gson.toJson(entry.getValue().delays);
                    waitFutures.add(db.saveAnimMapAsync(entry.getKey(), id, compressed, meta));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not flush GIF " + entry.getKey() + " during shutdown.", e);
                }
            }

            try {
                CompletableFuture.allOf(waitFutures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Timeout or interruption while flushing maps", e);
            }
        }
    }

    private static class AnimSaveData {
        final List<byte[]> frames;
        final List<Integer> delays;

        AnimSaveData(List<byte[]> f, List<Integer> d) {
            this.frames = f;
            this.delays = d;
        }
    }

    public void loadAllMaps() {
        if (!databaseReady.get()) {
            plugin.getLogger().info("Database still initializing. Maps will load automatically when ready.");
            return;
        }

        if (mapsLoaded.get()) {
            plugin.getLogger().info("Maps already loaded.");
            return;
        }

        loadAllMapsInternal();
    }

    private void loadAllMapsInternal() {
        db.loadAllMapsAsync().thenAccept(rawData -> {
            Set<Integer> loadedIds = new HashSet<>();

            int staticCount = 0;
            int animCount = 0;

            for (Map.Entry<Integer, DatabaseManager.MapData> entry : rawData.entrySet()) {
                int mapId = entry.getKey();
                DatabaseManager.MapData data = entry.getValue();

                managedMaps.add(mapId);

                try {
                    if (data.isAnim) {
                        List<byte[]> frames = EmageCompression.decompressAnimFrames(data.data);
                        if (frames.isEmpty()) continue;

                        List<Integer> delays = parseDelays(data.metaJson);
                        int avgDelay = (int) delays.stream().mapToInt(i -> i).average().orElse(100);

                        mapCache.put(mapId, new CachedMapData(null, frames, delays, avgDelay, data.syncId, true));
                        loadedIds.add(mapId);
                        animCount++;
                    } else {
                        byte[] decompressed = EmageCompression.decompressStatic(data.data);
                        mapCache.put(mapId, new CachedMapData(decompressed, null, null, 0, data.gridId, false));
                        loadedIds.add(mapId);
                        staticCount++;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not decompress map " + mapId +
                            " from the database. It will be skipped. Cause: " + e.getMessage());
                }
            }

            final int finalStatic = staticCount;
            final int finalAnim = animCount;
            final Set<Integer> finalLoadedIds = loadedIds;

            Bukkit.getScheduler().runTask(plugin, () -> {
                int appliedCount = 0;
                for (int mapId : finalLoadedIds) {
                    if (applyRendererToMap(mapId)) appliedCount++;
                }
                config.setMapCount(finalStatic + finalAnim);
                config.setAnimationCount(finalAnim);
                mapsLoaded.set(true);
                plugin.getLogger().info("Loaded " + finalStatic + " static maps and " +
                        finalAnim + " GIFs. Applied " + appliedCount + " renderers.");
            });
        });
    }

    private List<Integer> parseDelays(String json) {
        if (json == null || json.isEmpty()) return Collections.singletonList(100);
        try {
            Type listType = new TypeToken<ArrayList<Integer>>() {}.getType();
            List<Integer> list = gson.fromJson(json, listType);
            return (list != null && !list.isEmpty()) ? list : Collections.singletonList(100);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not parse frame delays for a GIF. " +
                    "Defaulting to 100ms per frame. Payload: " + json);
            return Collections.singletonList(100);
        }
    }

    public boolean applyRendererToMap(int mapId) {
        if (appliedMaps.contains(mapId)) return false;

        CachedMapData cached = mapCache.get(mapId);
        if (cached == null) return false;

        MapView mapView = Bukkit.getMap(mapId);
        if (mapView == null) return false;

        appliedMaps.add(mapId);

        new ArrayList<>(mapView.getRenderers()).forEach(mapView::removeRenderer);
        mapView.setTrackingPosition(false);
        mapView.setUnlimitedTracking(false);

        if (cached.isAnimation) {
            new GifRenderer(mapId, cached.frames, cached.delays, cached.syncId);
        }

        return true;
    }

    public void flushAllPendingSavesAsync() {
        for (PendingGrid grid : pendingGrids.values()) {
            grid.saveNowAsync();
        }
    }

    public void destroyMapData(int mapId) {
        CachedMapData cached = mapCache.remove(mapId);
        boolean wasManaged = managedMaps.remove(mapId);
        appliedMaps.remove(mapId);

        if (wasManaged) {
            config.decrementMapCount();
            if (cached != null && cached.isAnimation) {
                config.decrementAnimationCount();
            }
        }

        removeRenderers(mapId);

        if (isDatabaseReady()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                db.deleteMaps(Collections.singleton(mapId));
                db.addToPool(mapId);
                recycledMapIds.add(mapId);
            });
        }
    }

    public void cleanupUnusedFiles(Set<Integer> mapsInUse, java.util.function.Consumer<Integer> callback) {
        if (!isDatabaseReady()) {
            callback.accept(0);
            return;
        }

        db.loadAllMapsAsync().thenAccept(dbMaps -> {
            Set<Integer> dbIds = dbMaps.keySet();
            Set<Integer> toDelete = new HashSet<>();

            for (int id : dbIds) {
                if (!mapsInUse.contains(id)) toDelete.add(id);
            }

            final int count = toDelete.size();

            if (!toDelete.isEmpty()) {
                db.deleteMaps(toDelete);

                for (int id : toDelete) {
                    db.addToPool(id);
                }
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                for (int id : toDelete) {
                    CachedMapData cached = mapCache.remove(id);
                    boolean wasManaged = managedMaps.remove(id);
                    appliedMaps.remove(id);

                    if (wasManaged) {
                        config.decrementMapCount();
                        if (cached != null && cached.isAnimation) config.decrementAnimationCount();
                    }

                    removeRenderers(id);
                }
                callback.accept(count);
            });
        });
    }

    private void removeRenderers(int mapId) {
        MapView mapView = Bukkit.getMap(mapId);
        if (mapView != null) {
            new ArrayList<>(mapView.getRenderers()).forEach(mapView::removeRenderer);
        }
        GifRenderer.removeByMapId(mapId);
    }

    public MapStats getStats() {
        int animCount = (int) mapCache.values().stream().filter(c -> c.isAnimation).count();
        int totalManaged = managedMaps.size();
        return new MapStats(
                Math.max(0, totalManaged - animCount),
                animCount,
                0,
                totalManaged,
                config.getPerformanceStatus()
        );
    }

    public File getMapsFolder() {
        return plugin.getDataFolder();
    }

    public static class CachedMapData {
        public final byte[] staticData;
        public final List<byte[]> frames;
        public final List<Integer> delays;
        public final int avgDelay;
        public final long syncId;
        public final boolean isAnimation;

        public CachedMapData(byte[] staticData, List<byte[]> frames, List<Integer> delays,
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

    public Set<Integer> getAppliedMaps() { return appliedMaps; }
    public Map<Integer, CachedMapData> getMapCache() { return mapCache; }
    public Set<Integer> getPendingMapInits() { return pendingMapInits; }
    public AtomicBoolean getMapInitTaskScheduled() { return mapInitTaskScheduled; }
    public boolean applyRendererToMapPublic(int mapId) { return applyRendererToMap(mapId); }
    public AtomicLong getLastWorldSaveTime() { return lastWorldSaveTime; }
    public Set<Integer> getManagedMaps() { return managedMaps; }
    public void destroyMapDataPublic(int mapId) { destroyMapData(mapId); }
}