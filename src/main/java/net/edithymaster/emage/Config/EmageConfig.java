package net.edithymaster.emage.Config;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import net.edithymaster.emage.Processing.EmageCore;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class EmageConfig {

    private final JavaPlugin plugin;

    private static final int HIGH_MAP_COUNT = 50;
    private static final int HIGH_PLAYER_COUNT = 20;
    private static final int HIGH_ANIMATION_COUNT = 20;

    private final AtomicInteger activeMapCount = new AtomicInteger(0);
    private final AtomicInteger activeAnimationCount = new AtomicInteger(0);
    private final AtomicLong totalMemoryUsed = new AtomicLong(0);
    private final AtomicLong lastAdaptCheck = new AtomicLong(0);

    private volatile int effectiveFps;
    private volatile int effectiveRenderDistance;
    private volatile int effectiveUpdateInterval;
    private volatile boolean effectiveDistanceCulling;

    // Performance
    private int maxRenderDistance;
    private int maxFps;
    private int minFps;
    private int maxPacketsPerTick;
    private boolean adaptivePerformance;

    // Quality
    private int maxGifFrames;
    private int maxGridSize;
    private int maxGifGridSize;
    private int maxImageGridSize;

    // Memory
    private long maxMemoryMB;

    // Downloads
    private long maxDownloadBytes;
    private int connectTimeout;
    private int readTimeout;
    private int maxRedirects;
    private boolean blockInternalUrls;

    // Cache
    private int cacheMaxEntries;
    private long cacheMaxMemoryBytes;
    private long cacheExpireMs;

    // Rate limits
    private long cooldownMs;
    private int maxConcurrentTasks;

    private int adaptiveTaskID = -1;

    public EmageConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();

        adaptiveTaskID = Bukkit.getScheduler().runTaskTimer(
                plugin, this::adaptPerformance, 100L, 100L
        ).getTaskId();
    }

    public void reload() {
        plugin.reloadConfig();
        var config = plugin.getConfig();

        // Performance
        maxRenderDistance = config.getInt("performance.max-render-distance", 64);
        maxFps = config.getInt("performance.max-fps", 60);
        minFps = config.getInt("performance.min-fps", 20);
        maxPacketsPerTick = config.getInt("performance.max-packets-per-tick", 80);
        adaptivePerformance = config.getBoolean("performance.adaptive", true);

        // Quality
        maxGifFrames = config.getInt("quality.max-gif-frames", 200);
        maxGridSize = config.getInt("quality.max-grid-size", 15);
        maxGifGridSize = config.getInt("quality.max-gif-grid-size", 4);
        maxImageGridSize = config.getInt("quality.max-image-grid-size", 10);

        // Memory
        maxMemoryMB = config.getLong("memory.max-usage-mb", 256);

        // Downloads
        maxDownloadBytes = config.getLong("downloads.max-file-size-mb", 50) * 1024 * 1024;
        connectTimeout = config.getInt("downloads.connect-timeout", 10) * 1000;
        readTimeout = config.getInt("downloads.read-timeout", 30) * 1000;
        maxRedirects = config.getInt("downloads.max-redirects", 5);
        blockInternalUrls = config.getBoolean("downloads.block-internal-urls", true);

        // Cache
        cacheMaxEntries = config.getInt("cache.max-entries", 20);
        cacheMaxMemoryBytes = config.getLong("cache.max-memory-mb", 100) * 1024 * 1024;
        cacheExpireMs = config.getLong("cache.expire-minutes", 30) * 60 * 1000;

        // Rate limits
        cooldownMs = config.getLong("rate-limits.cooldown-seconds", 5) * 1000;
        maxConcurrentTasks = config.getInt("rate-limits.max-concurrent-tasks", 3);

        maxFps = Math.max(1, maxFps);
        minFps = Math.max(1, minFps);
        if (minFps > maxFps) minFps = maxFps;
        maxPacketsPerTick = Math.max(1, maxPacketsPerTick);
        maxRenderDistance = Math.max(8, maxRenderDistance);
        maxGifFrames = Math.max(1, maxGifFrames);
        maxGridSize = Math.max(1, maxGridSize);
        maxGifGridSize = Math.max(1, maxGifGridSize);
        maxImageGridSize = Math.max(1, maxImageGridSize);
        maxMemoryMB = Math.max(32, maxMemoryMB);
        maxDownloadBytes = Math.max(1024 * 1024, maxDownloadBytes);
        connectTimeout = Math.max(1000, connectTimeout);
        readTimeout = Math.max(1000, readTimeout);
        maxRedirects = Math.max(0, maxRedirects);
        cacheMaxEntries = Math.max(0, cacheMaxEntries);
        cacheExpireMs = Math.max(60000, cacheExpireMs);
        cooldownMs = Math.max(0, cooldownMs);
        maxConcurrentTasks = Math.max(1, maxConcurrentTasks);

        effectiveFps = maxFps;
        effectiveRenderDistance = maxRenderDistance;
        effectiveUpdateInterval = 1;
        effectiveDistanceCulling = true;

        adaptPerformance();
    }

    private void adaptPerformance() {
        if (!adaptivePerformance) return;

        long now = System.currentTimeMillis();
        if (now - lastAdaptCheck.get() < 5000) return;
        lastAdaptCheck.set(now);

        int players = Bukkit.getOnlinePlayers().size();
        int maps = activeMapCount.get();
        int animations = activeAnimationCount.get();

        double loadFactor = 0.0;
        loadFactor += (double) players / HIGH_PLAYER_COUNT * 0.3;
        loadFactor += (double) maps / HIGH_MAP_COUNT * 0.3;
        loadFactor += (double) animations / HIGH_ANIMATION_COUNT * 0.4;

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        double memoryPressure = (double) usedMemory / Math.min(maxMemory, maxMemoryMB);

        if (memoryPressure > 0.8) {
            loadFactor += 0.5;
        } else if (memoryPressure > 0.6) {
            loadFactor += 0.2;
        }

        if (loadFactor > 1.5) {
            effectiveFps = minFps;
        } else if (loadFactor > 1.0) {
            effectiveFps = (int) lerp(maxFps, minFps, (loadFactor - 1.0) / 0.5);
        } else if (loadFactor > 0.5) {
            effectiveFps = (int) lerp(maxFps, maxFps * 0.75, (loadFactor - 0.5) / 0.5);
        } else {
            effectiveFps = maxFps;
        }

        if (loadFactor > 1.0) {
            effectiveRenderDistance = (int) (maxRenderDistance * 0.5);
        } else if (loadFactor > 0.5) {
            effectiveRenderDistance = (int) lerp(maxRenderDistance, maxRenderDistance * 0.7, (loadFactor - 0.5) / 0.5);
        } else {
            effectiveRenderDistance = maxRenderDistance;
        }

        if (loadFactor > 1.5) {
            effectiveUpdateInterval = 3;
        } else if (loadFactor > 1.0) {
            effectiveUpdateInterval = 2;
        } else {
            effectiveUpdateInterval = 1;
        }

        effectiveFps = Math.max(minFps, Math.min(maxFps, effectiveFps));
        effectiveRenderDistance = Math.max(16, Math.min(maxRenderDistance, effectiveRenderDistance));
    }

    private double lerp(double a, double b, double t) {
        t = Math.max(0, Math.min(1, t));
        return a + (b - a) * t;
    }

    // Counters

    public void incrementMapCount() { activeMapCount.incrementAndGet(); }
    public void decrementMapCount() { activeMapCount.decrementAndGet(); }
    public void incrementAnimationCount() { activeAnimationCount.incrementAndGet(); }
    public void decrementAnimationCount() { activeAnimationCount.decrementAndGet(); }
    public void setMapCount(int count) { activeMapCount.set(count); }
    public void setAnimationCount(int count) { activeAnimationCount.set(count); }

    // Performance getters

    public int getRenderDistance() { return effectiveRenderDistance; }
    public int getRenderDistanceSquared() { return effectiveRenderDistance * effectiveRenderDistance; }
    public int getAnimationFps() { return effectiveFps; }
    public int getMapUpdateInterval() { return effectiveUpdateInterval; }
    public int getMaxPacketsPerTick() { return maxPacketsPerTick; }
    public boolean isAdaptivePerformance() { return adaptivePerformance; }

    // Quality getters

    public int getMaxGifFrames() { return maxGifFrames; }
    public int getMaxGifGridSize() { return maxGifGridSize; }
    public int getMaxImageGridSize() { return maxImageGridSize; }

    // Memory getters

    public long getMaxMemoryMB() { return maxMemoryMB; }

    // Download getters

    public long getMaxDownloadBytes() { return maxDownloadBytes; }
    public int getConnectTimeout() { return connectTimeout; }
    public int getReadTimeout() { return readTimeout; }
    public int getMaxRedirects() { return maxRedirects; }
    public boolean blockInternalUrls() { return blockInternalUrls; }

    // Cache getters

    public int getCacheMaxEntries() { return cacheMaxEntries; }
    public long getCacheMaxMemoryBytes() { return cacheMaxMemoryBytes; }
    public long getCacheExpireMs() { return cacheExpireMs; }

    // Rate limit getters

    public long getCooldownMs() { return cooldownMs; }
    public int getMaxConcurrentTasks() { return maxConcurrentTasks; }

    // Status

    public String getPerformanceStatus() {
        return String.format("FPS: %d, Distance: %d, Interval: %d, Culling: %s",
                effectiveFps, effectiveRenderDistance, effectiveUpdateInterval,
                effectiveDistanceCulling ? "ON" : "OFF");
    }

    public void shutdown() {
        if (adaptiveTaskID != -1) {
            Bukkit.getScheduler().cancelTask(adaptiveTaskID);
            adaptiveTaskID = -1;
        }
    }
}