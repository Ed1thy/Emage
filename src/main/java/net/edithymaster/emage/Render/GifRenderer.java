package net.edithymaster.emage.Render;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import net.edithymaster.emage.Config.EmageConfig;
import net.edithymaster.emage.Processing.EmageCore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class GifRenderer extends MapRenderer {

    private static final Map<Long, SyncGroup> SYNC_GROUPS = new ConcurrentHashMap<>();
    private static final Map<Integer, GifRenderer> RENDERERS = new ConcurrentHashMap<>();
    private static final Map<Integer, Location> MAP_LOCATIONS = new ConcurrentHashMap<>();

    private static volatile boolean running = false;
    private static JavaPlugin plugin;
    private static EmageConfig config;

    private static final AtomicInteger ID_COUNTER = new AtomicInteger(0);

    private static final int DEFAULT_RENDER_DISTANCE_SQ = 64 * 64;

    private final int id;
    private final long syncId;
    private final byte[][] frames;
    private final int frameCount;

    private volatile MapView mapView;
    private volatile int lastRenderedFrame = -1;
    private volatile boolean needsRender = true;

    private static class SyncGroup {
        final long syncID;
        final Set<GifRenderer> renderers = ConcurrentHashMap.newKeySet();
        final int[] delays;
        final long totalDuration;
        final int frameCount;

        final long[] cumulativeDelays;

        volatile long startTime = 0;
        volatile int currentFrame = 0;
        volatile boolean active = false;

        SyncGroup(long syncID, List<Integer> delayList) {
            this.syncID = syncID;
            this.frameCount = delayList != null ? delayList.size() : 0;

            if (frameCount == 0) {
                this.delays = new int[0];
                this.cumulativeDelays = new long[0];
                this.totalDuration = 1;
                return;
            }

            this.delays = new int[frameCount];
            this.cumulativeDelays = new long[frameCount];
            long total = 0;
            for (int i = 0; i < frameCount; i++) {
                int delay = delayList.get(i);
                delay = Math.max(20, delay);
                this.delays[i] = delay;
                total += delay;
                this.cumulativeDelays[i] = total;
            }
            this.totalDuration = Math.max(1, total);
        }

        void start() {
            this.startTime = System.currentTimeMillis();
            this.currentFrame = 0;
            this.active = true;
            markAllDirty();
        }

        void stop() {
            this.active = false;
        }

        boolean tick(long now) {
            if (!active || frameCount <= 1) return false;

            long elapsed = now - startTime;
            long cyclePosition = elapsed % totalDuration;

            int targetFrame = findFrame(cyclePosition);

            if (targetFrame != currentFrame) {
                currentFrame = targetFrame;
                markAllDirty();
                return true;
            }

            return false;
        }

        private int findFrame(long cyclePosition) {
            int lo = 0, hi = frameCount - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cumulativeDelays[mid] <= cyclePosition) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }

            return lo;
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

        Bukkit.getScheduler().runTaskTimer(plugin, GifRenderer::tick, 1L, 1L);
    }

    public static void stop() {
        running = false;
        SYNC_GROUPS.clear();
        RENDERERS.clear();
        MAP_LOCATIONS.clear();
    }

    private static void tick() {
        if (!running || SYNC_GROUPS.isEmpty()) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        long now = System.currentTimeMillis();

        boolean anyChanged = false;
        for (SyncGroup group : SYNC_GROUPS.values()) {
            if (group.tick(now)) {
                anyChanged = true;
            }
        }

        if (anyChanged) {
            sendMapUpdates();
        }
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

        int renderDistSq = config != null ? config.getRenderDistanceSquared() : DEFAULT_RENDER_DISTANCE_SQ;
        int perPlayerBudget = config != null ? config.getMaxPacketsPerTick() : 80;

        for (Player player : players) {
            if (!player.isOnline()) continue;

            Location playerLoc = player.getLocation();
            World playerWorld = player.getWorld();
            int sent = 0;

            for (int i = 0, size = dirtyRenderers.size(); i < size; i++) {
                if (sent >= perPlayerBudget) break;

                GifRenderer renderer = dirtyRenderers.get(i);

                @SuppressWarnings("deprecation")
                int mapID = renderer.mapView.getId();
                Location mapLoc = MAP_LOCATIONS.get(mapID);

                if (mapLoc != null) {
                    if (!playerWorld.equals(mapLoc.getWorld())) continue;
                    if (playerLoc.distanceSquared(mapLoc) > renderDistSq) continue;
                }

                try {
                    player.sendMap(renderer.mapView);
                    sent++;
                } catch (Exception ignored) {}
            }
        }

        for (int i = 0, size = dirtyRenderers.size(); i < size; i++) {
            dirtyRenderers.get(i).needsRender = false;
        }
    }

    public static void startSyncGroup(long syncID) {
        SyncGroup group = SYNC_GROUPS.get(syncID);
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

    public static void registerMapLocation(int mapId, Location location) {
        if (location != null) {
            MAP_LOCATIONS.put(mapId, location.clone());
        }
    }

    public static int getActiveCount() {
        return RENDERERS.size();
    }

    public GifRenderer(List<byte[]> frameList, List<Integer> delays, long syncID) {
        super(false);

        this.id = ID_COUNTER.incrementAndGet();
        this.syncId = syncID;
        this.frameCount = frameList.size();

        this.frames = new byte[frameCount][];
        for (int i = 0; i < frameCount; i++) {
            this.frames[i] = frameList.get(i);
        }

        SyncGroup group = SYNC_GROUPS.computeIfAbsent(syncID, k -> new SyncGroup(syncID, delays));
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
        int clamped = Math.max(50, delay);
        List<Integer> delays = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            delays.add(clamped);
        }
        return delays;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (this.mapView == null) {
            setMapView(map);
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
            int mapID = mapView.getId();
            RENDERERS.remove(mapID);
            MAP_LOCATIONS.remove(mapID);
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
        return Collections.unmodifiableList(Arrays.asList(frames));
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