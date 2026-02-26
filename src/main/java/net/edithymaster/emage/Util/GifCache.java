package net.edithymaster.emage.Util;

import net.edithymaster.emage.Processing.EmageCore;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.lang.ref.SoftReference;

public final class GifCache {

    private GifCache() {}

    private static volatile int maxEntries = 20;
    private static volatile long maxMemoryBytes = 100L * 1024 * 1024;
    private static volatile long expireTimeMs = 30L * 60 * 1000;

    private static final Map<String, CacheEntry> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(maxEntries + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return false;
                }
            }
    );

    private static final AtomicLong hits = new AtomicLong(0);
    private static final AtomicLong misses = new AtomicLong(0);
    private static final AtomicLong totalSizeBytes = new AtomicLong(0);

    private static volatile Logger logger;

    public static void init(Logger log) {
        logger = log;
    }

    public static void configure(int entries, long memoryBytes, long expireMs) {
        maxEntries = Math.max(1, entries);
        maxMemoryBytes = Math.max(1024 * 1024, memoryBytes);
        expireTimeMs = Math.max(60000, expireMs);
    }

    private static class CacheEntry {
        final SoftReference<EmageCore.GifGridData> dataRef;
        final long sizeBytes;
        volatile long lastAccessed;

        CacheEntry(EmageCore.GifGridData data) {
            this.dataRef = new SoftReference<>(data);
            this.lastAccessed = System.currentTimeMillis();
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
            return System.currentTimeMillis() - lastAccessed > expireTimeMs;
        }
    }

    public static String createKey(String url, int gridWidth, int gridHeight) {
        String input = url + "|" + gridWidth + "x" + gridHeight;

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }

    public static EmageCore.GifGridData get(String key) {
        CacheEntry entry;
        synchronized (CACHE) {
            entry = CACHE.get(key);
        }

        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }

        EmageCore.GifGridData data = entry.dataRef.get();
        if (data == null || entry.isExpired()) {
            synchronized (CACHE) {
                CACHE.remove(key);
            }
            totalSizeBytes.addAndGet(-entry.sizeBytes);
            misses.incrementAndGet();
            return null;
        }

        entry.lastAccessed = System.currentTimeMillis();
        hits.incrementAndGet();
        return data;
    }

    public static void put(String key, EmageCore.GifGridData data) {
        CacheEntry newEntry = new CacheEntry(data);

        synchronized (CACHE) {
            cleanupExpired();
            evictIfNeeded(newEntry.sizeBytes);

            CacheEntry old = CACHE.put(key, newEntry);
            if (old != null) {
                totalSizeBytes.addAndGet(-old.sizeBytes);
            }
        }

        totalSizeBytes.addAndGet(newEntry.sizeBytes);
    }

    private static void cleanupExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CacheEntry>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> e = it.next();
            CacheEntry entry = e.getValue();
            if (now - entry.lastAccessed > expireTimeMs || entry.dataRef.get() == null) {
                totalSizeBytes.addAndGet(-entry.sizeBytes);
                it.remove();
            }
        }
    }

    private static void evictIfNeeded(long incomingSize) {
        while (CACHE.size() >= maxEntries) {
            evictOldest();
        }

        while (totalSizeBytes.get() + incomingSize > maxMemoryBytes && !CACHE.isEmpty()) {
            evictOldest();
        }
    }

    private static void evictOldest() {
        Iterator<Map.Entry<String, CacheEntry>> it = CACHE.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<String, CacheEntry> eldest = it.next();
            it.remove();
            totalSizeBytes.addAndGet(-eldest.getValue().sizeBytes);
        }
    }

    public static int clearCache() {
        int count;
        synchronized (CACHE) {
            count = CACHE.size();
            CACHE.clear();
        }
        hits.set(0);
        misses.set(0);
        totalSizeBytes.set(0);
        return count;
    }

    public static CacheStats getStats() {
        long size = totalSizeBytes.get();
        long h = hits.get();
        long m = misses.get();
        double hitRate = (h + m) > 0 ? (double) h / (h + m) : 0;

        int count;
        synchronized (CACHE) {
            count = CACHE.size();
        }

        return new CacheStats(count, size, formatSize(size), h, m, hitRate);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

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