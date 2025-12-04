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

public final class GifRenderer extends MapRenderer {

    private static final Map<Long, SyncGroup> SYNC_GROUPS = new ConcurrentHashMap<>();

    private static final Map<Integer, GifRenderer> RENDERERS = new ConcurrentHashMap<>();

    private static volatile boolean running = false;
    private static JavaPlugin plugin;
    private static EmageConfig config;

    private static final AtomicInteger ID_COUNTER = new AtomicInteger(0);

    private final int id;
    private final long syncId;
    private final byte[][] frames;
    private final int frameCount;

    private volatile MapView mapView;
    private volatile int lastRenderedFrame = -1;
    private volatile boolean needsRender = true;

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

        boolean tick(long now) {
            if (!active || frameCount <= 1) return false;

            long elapsed = now - startTime;
            long cyclePosition = elapsed % totalDuration;

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

    public static void init(JavaPlugin pl, EmageConfig cfg) {
        plugin = pl;
        config = cfg;

        if (running) return;
        running = true;

        Bukkit.getScheduler().runTaskTimer(plugin, GifRenderer::tick, 2L, 2L);
    }

    public static void stop() {
        running = false;
        SYNC_GROUPS.clear();
        RENDERERS.clear();
    }

    private static void tick() {
        if (!running || SYNC_GROUPS.isEmpty()) return;

        long now = System.currentTimeMillis();

        for (SyncGroup group : SYNC_GROUPS.values()) {
            group.tick(now);
        }

        sendMapUpdates();
    }

    private static void sendMapUpdates() {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) return;

        List<GifRenderer> dirtyRenderers = new ArrayList<>();
        for (GifRenderer renderer : RENDERERS.values()) {
            if (renderer.needsRender && renderer.mapView != null) {
                dirtyRenderers.add(renderer);
            }
        }

        if (dirtyRenderers.isEmpty()) return;

        for (Player player : players) {
            if (!player.isOnline()) continue;

            for (GifRenderer renderer : dirtyRenderers) {
                try {
                    player.sendMap(renderer.mapView);
                } catch (Exception ignored) {}
            }
        }
    }

    public static void startSyncGroup(long syncId) {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null) {
            group.start();
        }
    }

    public static void resetSyncTime(long syncId) {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null && group.active) {
            group.start();
        }
    }

    public static void registerMapLocation(int mapId, Location location) {}

    public static int getActiveCount() {
        return RENDERERS.size();
    }

    public GifRenderer(List<byte[]> frameList, List<Integer> delays, long syncId) {
        super(false);

        this.id = ID_COUNTER.incrementAndGet();
        this.syncId = syncId;
        this.frameCount = frameList.size();

        this.frames = new byte[frameCount][];
        for (int i = 0; i < frameCount; i++) {
            byte[] src = frameList.get(i);
            this.frames[i] = new byte[src.length];
            System.arraycopy(src, 0, this.frames[i], 0, src.length);
        }

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
        if (this.mapView == null) {
            this.mapView = map;
            @SuppressWarnings("deprecation")
            int mapId = map.getId();
            RENDERERS.put(mapId, this);
        }

        SyncGroup group = SYNC_GROUPS.get(syncId);
        int frameIndex = (group != null) ? group.getCurrentFrame() : 0;

        if (frameIndex < 0 || frameIndex >= frameCount) {
            frameIndex = 0;
        }

        if (frameIndex == lastRenderedFrame && !needsRender) {
            return;
        }

        byte[] data = frames[frameIndex];
        if (data == null || data.length < EmageCore.MAP_SIZE) {
            return;
        }

        for (int y = 0; y < 128; y++) {
            int offset = y << 7;
            for (int x = 0; x < 128; x++) {
                canvas.setPixel(x, y, data[offset + x]);
            }
        }

        lastRenderedFrame = frameIndex;
        needsRender = false;
    }

    public void remove() {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null) {
            group.renderers.remove(this);
            if (group.renderers.isEmpty()) {
                SYNC_GROUPS.remove(syncId);
            }
        }

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