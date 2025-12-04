package org.flowerion.emage.Config;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.flowerion.emage.Processing.EmageCore;

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

    private volatile int effectiveFps = 60;
    private volatile int effectiveRenderDistance = 64;
    private volatile int effectiveUpdateInterval = 1;
    private volatile boolean effectiveDistanceCulling = true;

    private int maxRenderDistance;
    private int maxFps;
    private int minFps;
    private int maxGifFrames;
    private int maxGridSize;
    private boolean useMemoryPool;
    private int poolSize;
    private long maxMemoryMB;
    private boolean adaptivePerformance;

    public EmageConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::adaptPerformance, 100L, 100L);
    }

    public void reload() {
        plugin.reloadConfig();
        var config = plugin.getConfig();

        maxRenderDistance = config.getInt("performance.max-render-distance", 64);
        maxFps = config.getInt("performance.max-fps", 60);
        minFps = config.getInt("performance.min-fps", 20);

        maxGifFrames = config.getInt("quality.max-gif-frames", 200);
        maxGridSize = config.getInt("quality.max-grid-size", 15);

        useMemoryPool = config.getBoolean("memory.use-pool", true);
        poolSize = config.getInt("memory.pool-size", 100);
        maxMemoryMB = config.getLong("memory.max-usage-mb", 256);

        adaptivePerformance = config.getBoolean("performance.adaptive", true);

        effectiveFps = maxFps;
        effectiveRenderDistance = maxRenderDistance;
        effectiveUpdateInterval = 1;
        effectiveDistanceCulling = true;

        EmageCore.setUsePool(useMemoryPool);
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
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        double memoryPressure = (double) usedMemory / maxMemoryMB;

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

    private final AtomicLong totalFramesRendered = new AtomicLong(0);
    private final AtomicLong totalFrameTime = new AtomicLong(0);
    private volatile double averageFrameTimeMs = 0;

    public void recordFrameRender(long nanos) {
        totalFramesRendered.incrementAndGet();
        totalFrameTime.addAndGet(nanos);
    }

    public void updateAverageFrameTime() {
        long frames = totalFramesRendered.getAndSet(0);
        long time = totalFrameTime.getAndSet(0);

        if (frames > 0) {
            averageFrameTimeMs = (time / frames) / 1_000_000.0;
        }
    }

    public double getAverageFrameTimeMs() {
        return averageFrameTimeMs;
    }

    public EmageCore.Quality getRecommendedQuality() {
        if (averageFrameTimeMs > 10) {
            return EmageCore.Quality.FAST;
        } else if (averageFrameTimeMs > 5) {
            return EmageCore.Quality.BALANCED;
        }
        return EmageCore.Quality.HIGH;
    }

    public boolean shouldSkipFrame(int consecutiveFrames) {
        if (!adaptivePerformance) return false;

        if (averageFrameTimeMs > 15 && consecutiveFrames % 2 == 0) {
            return true;
        }
        if (averageFrameTimeMs > 25 && consecutiveFrames % 3 != 0) {
            return true;
        }

        return false;
    }

    public void incrementMapCount() {
        activeMapCount.incrementAndGet();
    }

    public void decrementMapCount() {
        activeMapCount.decrementAndGet();
    }

    public void incrementAnimationCount() {
        activeAnimationCount.incrementAndGet();
    }

    public void decrementAnimationCount() {
        activeAnimationCount.decrementAndGet();
    }

    public void setMapCount(int count) {
        activeMapCount.set(count);
    }

    public void setAnimationCount(int count) {
        activeAnimationCount.set(count);
    }

    public void addMemoryUsage(long bytes) {
        totalMemoryUsed.addAndGet(bytes);
    }

    public void removeMemoryUsage(long bytes) {
        totalMemoryUsed.addAndGet(-bytes);
    }

    public int getRenderDistance() {
        return effectiveRenderDistance;
    }

    public int getRenderDistanceSquared() {
        return effectiveRenderDistance * effectiveRenderDistance;
    }

    public int getAnimationFps() {
        return effectiveFps;
    }

    public int getMapUpdateInterval() {
        return effectiveUpdateInterval;
    }

    public boolean useDistanceCulling() {
        return effectiveDistanceCulling;
    }

    public long getFrameTimeNanos() {
        return 1_000_000_000L / effectiveFps;
    }

    public int getMaxGifFrames() {
        return maxGifFrames;
    }

    public int getMaxGridSize() {
        return maxGridSize;
    }

    public boolean useMemoryPool() {
        return useMemoryPool;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public long getMaxMemoryMB() {
        return maxMemoryMB;
    }

    public boolean isAdaptivePerformance() {
        return adaptivePerformance;
    }

    public int getMaxFps() {
        return maxFps;
    }

    public int getMinFps() {
        return minFps;
    }

    public int getMaxRenderDistance() {
        return maxRenderDistance;
    }

    public String getPerformanceStatus() {
        return String.format("FPS: %d, Distance: %d, Interval: %d, Culling: %s",
                effectiveFps, effectiveRenderDistance, effectiveUpdateInterval,
                effectiveDistanceCulling ? "ON" : "OFF");
    }

    public int getActiveMapCount() {
        return activeMapCount.get();
    }

    public int getActiveAnimationCount() {
        return activeAnimationCount.get();
    }
}