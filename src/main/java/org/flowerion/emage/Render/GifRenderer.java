package org.flowerion.emage.Render;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.flowerion.emage.Config.EmageConfig;
import org.flowerion.emage.Processing.EmageCore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GIF renderer following ImageFrame's approach:
 * - Sync groups for coordinated playback
 * - Efficient map updates
 * - Proper timing control
 */
public final class GifRenderer extends MapRenderer {

    // All sync groups (animations sharing same timing)
    private static final Map<Long, SyncGroup> SYNC_GROUPS = new ConcurrentHashMap<>();

    // Map ID to renderer for quick lookup
    private static final Map<Integer, GifRenderer> RENDERERS = new ConcurrentHashMap<>();

    private static volatile boolean running = false;
    private static JavaPlugin plugin;
    private static EmageConfig config;

    private static final AtomicInteger ID_COUNTER = new AtomicInteger(0);

    // Instance fields
    private final int id;
    private final long syncId;
    private final byte[][] frames;
    private final int frameCount;

    private volatile MapView mapView;
    private volatile int lastRenderedFrame = -1;
    private volatile boolean needsRender = true;

    /**
     * Sync group manages timing for multiple renderers
     */
    private static class SyncGroup {
        final long syncId;
        final Set<GifRenderer> renderers = ConcurrentHashMap.newKeySet();
        final int[] delays;
        final long totalDuration;
        final int frameCount;

        volatile long startTime = 0;
        volatile int currentFrame = 0;
        volatile boolean active = false;
        volatile long lastFrameChangeTime = 0;

        SyncGroup(long syncId, List<Integer> delayList) {
            this.syncId = syncId;
            this.frameCount = delayList != null ? delayList.size() : 0;

            if (frameCount == 0) {
                this.delays = new int[0];
                this.totalDuration = 1;
                return;
            }

            this.delays = new int[frameCount];
            long total = 0;
            for (int i = 0; i < frameCount; i++) {
                // Minimum 50ms per frame for stability
                int delay = delayList.get(i);
                delay = Math.max(50, delay);
                this.delays[i] = delay;
                total += delay;
            }
            this.totalDuration = Math.max(1, total);
        }

        void start() {
            this.startTime = System.currentTimeMillis();
            this.currentFrame = 0;
            this.active = true;
            this.lastFrameChangeTime = startTime;
            markAllDirty();
        }

        void stop() {
            this.active = false;
        }

        /**
         * Update current frame based on elapsed time
         * @return true if frame changed
         */
        boolean tick(long now) {
            if (!active || frameCount <= 1) return false;

            long elapsed = now - startTime;
            long cyclePosition = elapsed % totalDuration;

            // Find which frame we should be on
            int targetFrame = 0;
            long accumulated = 0;
            for (int i = 0; i < frameCount; i++) {
                accumulated += delays[i];
                if (cyclePosition < accumulated) {
                    targetFrame = i;
                    break;
                }
            }

            if (targetFrame != currentFrame) {
                currentFrame = targetFrame;
                lastFrameChangeTime = now;
                markAllDirty();
                return true;
            }

            return false;
        }

        void markAllDirty() {
            for (GifRenderer renderer : renderers) {
                renderer.needsRender = true;
            }
        }

        int getCurrentFrame() {
            return active ? currentFrame : 0;
        }
    }

    // ==================== Static Methods ====================

    public static void init(JavaPlugin pl, EmageConfig cfg) {
        plugin = pl;
        config = cfg;

        if (running) return;
        running = true;

        // Main animation tick - every 2 ticks (100ms)
        // ImageFrame uses similar approach
        Bukkit.getScheduler().runTaskTimer(plugin, GifRenderer::tick, 2L, 2L);
    }

    public static void stop() {
        running = false;
        SYNC_GROUPS.clear();
        RENDERERS.clear();
    }

    /**
     * Main tick - updates all sync groups
     */
    private static void tick() {
        if (!running || SYNC_GROUPS.isEmpty()) return;

        long now = System.currentTimeMillis();

        // Update all sync groups
        for (SyncGroup group : SYNC_GROUPS.values()) {
            group.tick(now);
        }

        // Send map updates to players
        sendMapUpdates();
    }

    /**
     * Send map updates to nearby players
     * ImageFrame uses player.sendMap() for each dirty map
     */
    private static void sendMapUpdates() {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) return;

        // Collect all dirty renderers
        List<GifRenderer> dirtyRenderers = new ArrayList<>();
        for (GifRenderer renderer : RENDERERS.values()) {
            if (renderer.needsRender && renderer.mapView != null) {
                dirtyRenderers.add(renderer);
            }
        }

        if (dirtyRenderers.isEmpty()) return;

        // Send to each player
        for (Player player : players) {
            if (!player.isOnline()) continue;

            for (GifRenderer renderer : dirtyRenderers) {
                try {
                    player.sendMap(renderer.mapView);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Start a sync group (call after all maps are applied)
     */
    public static void startSyncGroup(long syncId) {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null) {
            group.start();
        }
    }

    /**
     * Reset sync group timing
     */
    public static void resetSyncTime(long syncId) {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null && group.active) {
            group.start();
        }
    }

    /**
     * Register map location (for distance-based culling if needed)
     */
    public static void registerMapLocation(int mapId, Location location) {
        // Can be used for distance culling in future
    }

    /**
     * Get count of active animated maps
     */
    public static int getActiveCount() {
        return RENDERERS.size();
    }

    // ==================== Instance Methods ====================

    public GifRenderer(List<byte[]> frameList, List<Integer> delays, long syncId) {
        super(false); // Not contextual

        this.id = ID_COUNTER.incrementAndGet();
        this.syncId = syncId;
        this.frameCount = frameList.size();

        // Copy frame data
        this.frames = new byte[frameCount][];
        for (int i = 0; i < frameCount; i++) {
            byte[] src = frameList.get(i);
            this.frames[i] = new byte[src.length];
            System.arraycopy(src, 0, this.frames[i], 0, src.length);
        }

        // Register with sync group
        SyncGroup group = SYNC_GROUPS.computeIfAbsent(syncId, k -> new SyncGroup(syncId, delays));
        group.renderers.add(this);

        if (config != null) {
            config.incrementAnimationCount();
        }
    }

    public GifRenderer(List<byte[]> frames, List<Integer> delays) {
        this(frames, delays, System.currentTimeMillis());
    }

    public GifRenderer(List<byte[]> frames, int delayMs) {
        this(frames, createDelayList(frames.size(), delayMs), System.currentTimeMillis());
    }

    public GifRenderer(List<byte[]> frames, int delayMs, long syncId) {
        this(frames, createDelayList(frames.size(), delayMs), syncId);
    }

    private static List<Integer> createDelayList(int size, int delay) {
        List<Integer> delays = new ArrayList<>(size);
        int clamped = Math.max(50, delay);
        for (int i = 0; i < size; i++) {
            delays.add(clamped);
        }
        return delays;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        // Register map view and renderer
        if (this.mapView == null) {
            this.mapView = map;
            @SuppressWarnings("deprecation")
            int mapId = map.getId();
            RENDERERS.put(mapId, this);
        }

        // Get current frame from sync group
        SyncGroup group = SYNC_GROUPS.get(syncId);
        int frameIndex = (group != null) ? group.getCurrentFrame() : 0;

        // Bounds check
        if (frameIndex < 0 || frameIndex >= frameCount) {
            frameIndex = 0;
        }

        // Skip if already rendered this frame
        if (frameIndex == lastRenderedFrame && !needsRender) {
            return;
        }

        // Get frame data
        byte[] data = frames[frameIndex];
        if (data == null || data.length < EmageCore.MAP_SIZE) {
            return;
        }

        // Render to canvas
        for (int y = 0; y < 128; y++) {
            int offset = y << 7; // y * 128
            for (int x = 0; x < 128; x++) {
                canvas.setPixel(x, y, data[offset + x]);
            }
        }

        lastRenderedFrame = frameIndex;
        needsRender = false;
    }

    /**
     * Remove this renderer and clean up
     */
    public void remove() {
        // Remove from sync group
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null) {
            group.renderers.remove(this);
            if (group.renderers.isEmpty()) {
                SYNC_GROUPS.remove(syncId);
            }
        }

        // Remove from renderer map
        if (mapView != null) {
            @SuppressWarnings("deprecation")
            int mapId = mapView.getId();
            RENDERERS.remove(mapId);
        }

        if (config != null) {
            config.decrementAnimationCount();
        }
    }

    public void setMapView(MapView view) {
        this.mapView = view;
        if (view != null) {
            @SuppressWarnings("deprecation")
            int mapId = view.getId();
            RENDERERS.put(mapId, this);
        }
    }

    public MapView getMapView() {
        return this.mapView;
    }

    public long getSyncId() {
        return syncId;
    }

    public int getId() {
        return id;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public List<byte[]> getFrames() {
        List<byte[]> list = new ArrayList<>(frameCount);
        for (byte[] frame : frames) {
            byte[] copy = new byte[frame.length];
            System.arraycopy(frame, 0, copy, 0, frame.length);
            list.add(copy);
        }
        return list;
    }

    public List<Integer> getDelays() {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null && group.delays != null) {
            List<Integer> delayList = new ArrayList<>(group.frameCount);
            for (int delay : group.delays) {
                delayList.add(delay);
            }
            return delayList;
        }
        return Collections.emptyList();
    }

    public int getAverageDelay() {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null && group.frameCount > 0) {
            return (int) (group.totalDuration / group.frameCount);
        }
        return 100;
    }
}