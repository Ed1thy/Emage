package net.edithymaster.emage.Util;

import net.edithymaster.emage.Processing.EmageCore;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GifCache {

    private GifCache() {}

    private static final int MAX_ENTRIES = 20;
    private static final long MAX_MEMORY_BYTES = 100L * 1024 * 1024;
    private static final long EXPIRE_TIME_MS = 30L * 60 * 1000;

    private static final Map<String, CacheEntry> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75f, true) {
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

    private static class CacheEntry {
        final EmageCore.GifGridData data;
        final long sizeBytes;
        volatile long lastAccessed;

        CacheEntry(EmageCore.GifGridData data) {
            this.data = data;
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
            return System.currentTimeMillis() - lastAccessed > EXPIRE_TIME_MS;
        }
    }

    public static String createKey(String url, int gridWidth, int gridHeight, EmageCore.Quality quality) {
        String input = url + "|" + gridWidth + "x" + gridHeight + "|" + quality.name();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder(32);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            if (logger != null) logger.log(Level.WARNING, "MD5 not available, using hashCode", e);
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

        if (entry.isExpired()) {
            synchronized (CACHE) {
                CACHE.remove(key);
            }
            totalSizeBytes.addAndGet(-entry.sizeBytes);
            misses.incrementAndGet();
            return null;
        }

        entry.lastAccessed = System.currentTimeMillis();
        hits.incrementAndGet();
        return entry.data;
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
            if (now - e.getValue().lastAccessed > EXPIRE_TIME_MS) {
                totalSizeBytes.addAndGet(-e.getValue().sizeBytes);
                it.remove();
            }
        }
    }

    private static void evictIfNeeded(long incomingSize) {
        while (CACHE.size() >= MAX_ENTRIES) {
            evictOldest();
        }

        while (totalSizeBytes.get() + incomingSize > MAX_MEMORY_BYTES && !CACHE.isEmpty()) {
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