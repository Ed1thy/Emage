package org.flowerion.emage.Util;

import org.flowerion.emage.Processing.EmageCore;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for processed GIF data to avoid reprocessing the same GIF.
 */
public final class GifCache {

    private GifCache() {}

    // Cache storage
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    // Cache limits
    private static final int MAX_ENTRIES = 20;
    private static final long MAX_MEMORY_BYTES = 100 * 1024 * 1024; // 100MB
    private static final long EXPIRE_TIME_MS = 30 * 60 * 1000; // 30 minutes

    // Stats
    private static long hits = 0;
    private static long misses = 0;

    private static class CacheEntry {
        final EmageCore.GifGridData data;
        final long createdAt;
        final long sizeBytes;
        long lastAccessed;

        CacheEntry(EmageCore.GifGridData data) {
            this.data = data;
            this.createdAt = System.currentTimeMillis();
            this.lastAccessed = this.createdAt;
            this.sizeBytes = calculateSize(data);
        }

        private static long calculateSize(EmageCore.GifGridData data) {
            long size = 0;
            for (int x = 0; x < data.gridWidth; x++) {
                for (int y = 0; y < data.gridHeight; y++) {
                    List<byte[]> frames = data.grid[x][y];
                    if (frames != null) {
                        for (byte[] frame : frames) {
                            size += frame.length;
                        }
                    }
                }
            }
            return size;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - lastAccessed > EXPIRE_TIME_MS;
        }
    }

    /**
     * Create a cache key from URL and parameters
     */
    public static String createKey(String url, int gridWidth, int gridHeight, EmageCore.Quality quality) {
        String input = url + "|" + gridWidth + "x" + gridHeight + "|" + quality.name();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fallback to simple hash
            return String.valueOf(input.hashCode());
        }
    }

    /**
     * Get cached GIF data
     */
    public static EmageCore.GifGridData get(String key) {
        CacheEntry entry = CACHE.get(key);

        if (entry == null) {
            misses++;
            return null;
        }

        if (entry.isExpired()) {
            CACHE.remove(key);
            misses++;
            return null;
        }

        entry.lastAccessed = System.currentTimeMillis();
        hits++;
        return entry.data;
    }

    /**
     * Store GIF data in cache
     */
    public static void put(String key, EmageCore.GifGridData data) {
        // Clean up expired entries first
        cleanupExpired();

        // Check if we need to evict entries
        evictIfNeeded();

        // Store new entry
        CACHE.put(key, new CacheEntry(data));
    }

    /**
     * Remove expired entries
     */
    private static void cleanupExpired() {
        CACHE.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * Evict oldest entries if cache is too large
     */
    private static void evictIfNeeded() {
        // Check entry count
        while (CACHE.size() >= MAX_ENTRIES) {
            evictOldest();
        }

        // Check memory usage
        long totalSize = CACHE.values().stream().mapToLong(e -> e.sizeBytes).sum();
        while (totalSize > MAX_MEMORY_BYTES && !CACHE.isEmpty()) {
            evictOldest();
            totalSize = CACHE.values().stream().mapToLong(e -> e.sizeBytes).sum();
        }
    }

    /**
     * Evict the least recently used entry
     */
    private static void evictOldest() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<String, CacheEntry> entry : CACHE.entrySet()) {
            if (entry.getValue().lastAccessed < oldestTime) {
                oldestTime = entry.getValue().lastAccessed;
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            CACHE.remove(oldestKey);
        }
    }

    /**
     * Clear entire cache
     */
    public static int clearCache() {
        int count = CACHE.size();
        CACHE.clear();
        hits = 0;
        misses = 0;
        return count;
    }

    /**
     * Get cache statistics
     */
    public static CacheStats getStats() {
        long totalSize = CACHE.values().stream().mapToLong(e -> e.sizeBytes).sum();
        double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) : 0;

        return new CacheStats(
                CACHE.size(),
                totalSize,
                formatSize(totalSize),
                hits,
                misses,
                hitRate
        );
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Cache statistics holder
     */
    public static class CacheStats {
        public final int count;
        public final long sizeBytes;
        public final String formattedSize;
        public final long hits;
        public final long misses;
        public final double hitRate;

        public CacheStats(int count, long sizeBytes, String formattedSize,
                          long hits, long misses, double hitRate) {
            this.count = count;
            this.sizeBytes = sizeBytes;
            this.formattedSize = formattedSize;
            this.hits = hits;
            this.misses = misses;
            this.hitRate = hitRate;
        }
    }
}