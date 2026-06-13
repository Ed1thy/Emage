package net.ed1thy.emage.render;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.model.MapFrameUpdate;
import net.ed1thy.emage.model.MapMetadata;
import net.ed1thy.emage.storage.FlatFileStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SyncGroup {

    private final MapMetadata metadata;
    private final List<FrameNode> nodes;
    private final FlatFileStorage flatFileStorage;

    private int currentFrameIndex = 0;
    private long lastTickTime = 0;

    private final Cache<Long, MapFrameUpdate> frameCache;
    private final Map<Integer, MapFrameUpdate> baseFrames = new ConcurrentHashMap<>();

    private final Set<UUID> initializedUsers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> visiblePlayers = ConcurrentHashMap.newKeySet();

    private final List<org.bukkit.util.Vector> checkPoints = new CopyOnWriteArrayList<>();
    private final List<org.bukkit.Location> centerLocs = new CopyOnWriteArrayList<>();
    private org.bukkit.World world;

    private static final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SyncGroup(@NotNull MapMetadata metadata, @NotNull List<FrameNode> nodes,
                     @NotNull FlatFileStorage flatFileStorage, @NotNull ConfigManager configManager,
                     @Nullable Map<Long, MapFrameUpdate> preloadedFrames) {
        this.metadata = metadata;
        this.nodes = nodes;
        this.flatFileStorage = flatFileStorage;

        long maxBytes = (long) configManager.cacheMaxMemoryMb * 1024L * 1024L;

        this.frameCache = Caffeine.newBuilder()
                .maximumWeight(maxBytes)
                .weigher((Long key, MapFrameUpdate value) -> {
                    int totalBytes = 0;
                    for (DeltaFrame df : value.parts()) totalBytes += df.packetBuf().capacity();
                    return totalBytes;
                })
                .expireAfterAccess(configManager.cacheExpireMinutes, TimeUnit.MINUTES)
                .removalListener((Long key, MapFrameUpdate value, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (value != null) {
                        value.freeMemory();
                    }
                })
                .build();

        if (preloadedFrames != null) {
            for (FrameNode node : nodes) {
                int mapId = node.getMapID();
                long key = (0L << 32) | (mapId & 0xFFFFFFFFL);
                MapFrameUpdate f0 = preloadedFrames.get(key);
                if (f0 != null) baseFrames.put(mapId, f0);
            }
        } else {
            CompletableFuture.runAsync(() -> {
                try {
                    Map<Integer, MapFrameUpdate> f0Bundle = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), 0);
                    baseFrames.putAll(f0Bundle);
                } catch (Exception ignored) {}
            }, vtExecutor);
        }

        if (!nodes.isEmpty()) {
            this.world = org.bukkit.Bukkit.getWorld(nodes.get(0).getWorldUUID());
            addNewWallBounds(nodes);
        }
    }

    public void addNewWall(List<FrameNode> newNodes) {
        this.nodes.addAll(newNodes);
        if (this.world == null && !newNodes.isEmpty()) {
            this.world = org.bukkit.Bukkit.getWorld(newNodes.get(0).getWorldUUID());
        }
        addNewWallBounds(newNodes);
    }

    private void addNewWallBounds(List<FrameNode> wallNodes) {
        if (wallNodes.isEmpty() || this.world == null) return;

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (FrameNode n : wallNodes) {
            minX = Math.min(minX, n.getBlockX()); minY = Math.min(minY, n.getBlockY()); minZ = Math.min(minZ, n.getBlockZ());
            maxX = Math.max(maxX, n.getBlockX() + 1.0); maxY = Math.max(maxY, n.getBlockY() + 1.0); maxZ = Math.max(maxZ, n.getBlockZ() + 1.0);
        }

        org.bukkit.Location center = new org.bukkit.Location(world, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        this.centerLocs.add(center);

        this.checkPoints.add(center.toVector());

        this.checkPoints.add(new org.bukkit.util.Vector(minX, minY, minZ));
        this.checkPoints.add(new org.bukkit.util.Vector(maxX, minY, minZ));
        this.checkPoints.add(new org.bukkit.util.Vector(minX, maxY, minZ));
        this.checkPoints.add(new org.bukkit.util.Vector(maxX, maxY, minZ));
        this.checkPoints.add(new org.bukkit.util.Vector(minX, minY, maxZ));
        this.checkPoints.add(new org.bukkit.util.Vector(maxX, minY, maxZ));
        this.checkPoints.add(new org.bukkit.util.Vector(minX, maxY, maxZ));
        this.checkPoints.add(new org.bukkit.util.Vector(maxX, maxY, maxZ));
    }

    public void updateVisibility() {
        if (world == null || centerLocs.isEmpty()) return;
        visiblePlayers.clear();

        for (org.bukkit.Location center : centerLocs) {
            for (org.bukkit.entity.Player player : center.getNearbyPlayers(32.0)) {
                if (isVisible(player)) {
                    visiblePlayers.add(player.getUniqueId());
                }
            }
        }

        initializedUsers.removeIf(uuid -> !visiblePlayers.contains(uuid));
    }

    private boolean isVisible(org.bukkit.entity.Player player) {
        org.bukkit.Location eyeLoc = player.getEyeLocation();
        org.bukkit.util.Vector eye = eyeLoc.toVector();
        org.bukkit.util.Vector lookDir = eyeLoc.getDirection();

        for (org.bukkit.util.Vector pt : checkPoints) {
            double distSq = eye.distanceSquared(pt);
            if (distSq > 1024) continue;

            org.bukkit.util.Vector dirToPt = pt.clone().subtract(eye);
            double dist = Math.sqrt(distSq);
            dirToPt.normalize();

            if (dirToPt.dot(lookDir) > 0.0) {
                org.bukkit.util.RayTraceResult hit = world.rayTraceBlocks(eyeLoc, dirToPt, dist, org.bukkit.FluidCollisionMode.NEVER, true);
                if (hit == null || hit.getHitBlock() == null) {
                    return true;
                } else {
                    double hitDist = hit.getHitPosition().distance(eye);
                    if (hitDist >= dist - 0.8) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean shouldTick(long currentTimeMillis) {
        if (!metadata.isAnimated()) {
            return (currentTimeMillis - lastTickTime) >= 1000;
        }
        return (currentTimeMillis - lastTickTime) >= metadata.delayMs();
    }

    public void tick(long currentTimeMillis, @NotNull ChunkViewerTracker tracker, @NotNull RenderManager renderManager, @NotNull PacketSender sender) {
        lastTickTime = currentTimeMillis;

        if (metadata.isAnimated()) {
            currentFrameIndex++;
            if (currentFrameIndex >= metadata.totalFrames()) {
                currentFrameIndex = 0;
            }
        } else {
            currentFrameIndex = 0;
        }

        if (visiblePlayers.isEmpty()) return;

        if (metadata.isAnimated()) {
            int nextFrame = (currentFrameIndex + 1) % metadata.totalFrames();
            boolean missingNext = false;
            for (FrameNode node : nodes) {
                if (frameCache.getIfPresent(((long) nextFrame << 32) | (node.getMapID() & 0xFFFFFFFFL)) == null) {
                    missingNext = true; break;
                }
            }
            if (missingNext) {
                CompletableFuture.runAsync(() -> {
                    try {
                        Map<Integer, MapFrameUpdate> bundled = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), nextFrame);
                        for (FrameNode node : nodes) {
                            long key = ((long) nextFrame << 32) | (node.getMapID() & 0xFFFFFFFFL);
                            MapFrameUpdate update = bundled.get(node.getMapID());
                            frameCache.put(key, update != null ? update : new MapFrameUpdate(new DeltaFrame[0]));
                        }
                    } catch (Exception ignored) {}
                }, vtExecutor);
            }
        }

        boolean missingCurrent = false;
        for (FrameNode node : nodes) {
            if (frameCache.getIfPresent(((long) currentFrameIndex << 32) | (node.getMapID() & 0xFFFFFFFFL)) == null) {
                missingCurrent = true; break;
            }
        }
        if (missingCurrent) {
            try {
                Map<Integer, MapFrameUpdate> bundled = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), currentFrameIndex);
                for (FrameNode node : nodes) {
                    long key = ((long) currentFrameIndex << 32) | (node.getMapID() & 0xFFFFFFFFL);
                    MapFrameUpdate update = bundled.get(node.getMapID());
                    frameCache.put(key, update != null ? update : new MapFrameUpdate(new DeltaFrame[0]));
                }
            } catch (Exception ignored) {}
        }

        Set<UUID> newlyInitialized = new HashSet<>();

        for (FrameNode node : nodes) {
            long cacheKey = ((long) currentFrameIndex << 32) | (node.getMapID() & 0xFFFFFFFFL);
            MapFrameUpdate update = frameCache.getIfPresent(cacheKey);

            if (update != null) {
                for (UUID uuid : visiblePlayers) {
                    boolean isNewUser = !initializedUsers.contains(uuid);

                    if (isNewUser && currentFrameIndex != 0) {
                        MapFrameUpdate base = baseFrames.get(node.getMapID());
                        if (base != null) {
                            for (DeltaFrame subChunk : base.parts()) {
                                renderManager.enqueuePacket(uuid, sender.createMapPacket(subChunk));
                            }
                        }
                    }

                    for (DeltaFrame subChunk : update.parts()) {
                        renderManager.enqueuePacket(uuid, sender.createMapPacket(subChunk));
                    }
                    newlyInitialized.add(uuid);
                }
            }
        }

        initializedUsers.addAll(newlyInitialized);
    }

    public List<FrameNode> getNodes() {
        return nodes;
    }

    public void cleanup() {
        for (MapFrameUpdate update : baseFrames.values()) {
            update.freeMemory();
        }
        baseFrames.clear();

        frameCache.invalidateAll();
        frameCache.cleanUp();
    }
}