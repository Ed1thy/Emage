package net.edithymaster.emage.Render;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.edithymaster.emage.Config.EmageConfig;
import net.edithymaster.emage.Packet.MapPacketSender;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class GifRenderer {

    public static final Map<Long, SyncGroup> SYNC_GROUPS = new ConcurrentHashMap<>();
    private static final Map<Integer, GifRenderer> RENDERERS = new ConcurrentHashMap<>();
    private static final Map<Integer, Location> MAP_LOCATIONS = new ConcurrentHashMap<>();

    private static final double FOV_COSINE_THRESHOLD = 0.64;

    private static volatile boolean running = false;
    private static JavaPlugin plugin;
    private static EmageConfig config;
    private static MapPacketSender packetSender;

    private static ScheduledExecutorService renderExecutor;
    private static int playerCacheTaskID = -1;

    private static volatile PlayerSnapshot[] playerSnapshots = new PlayerSnapshot[0];

    private static final AtomicInteger ID_COUNTER = new AtomicInteger(0);
    private static long lastTickTime = 0;
    private static int tickCounter = 0;

    private static final int DEFAULT_RENDER_DISTANCE_SQ = 64 * 64;

    private final int id;
    private final int cachedMapId;
    private final long syncId;
    private final byte[][] frames;
    private final int frameCount;

    private final FrameDelta[] computedDeltas;

    private boolean needsRender = true;

    private static final class PlayerSnapshot {
        final UUID playerId;
        final World world;
        final double x, y, z;
        final double eyeHeight;
        final double pitchRad, yawRad;
        final Set<Long> visibleSyncGroups;

        PlayerSnapshot(Player player, Set<Long> previousVisibleGroups) {
            this.playerId = player.getUniqueId();
            Location loc = player.getLocation();
            this.world = loc.getWorld();
            this.x = loc.getX();
            this.y = loc.getY();
            this.z = loc.getZ();
            this.eyeHeight = player.getEyeHeight();
            this.pitchRad = Math.toRadians(loc.getPitch());
            this.yawRad = Math.toRadians(loc.getYaw());
            this.visibleSyncGroups = ConcurrentHashMap.newKeySet(previousVisibleGroups != null ? previousVisibleGroups.size() + 2 : 16);
            if (previousVisibleGroups != null) this.visibleSyncGroups.addAll(previousVisibleGroups);
        }
    }

    private static final Map<UUID, Set<Long>> playerVisibleGroupsCarryOver = new HashMap<>();

    private static class FrameDelta {
        final int minX, minY, columns, rows;
        final byte[] data;

        FrameDelta(int minX, int minY, int columns, int rows, byte[] data) {
            this.minX = minX;
            this.minY = minY;
            this.columns = columns;
            this.rows = rows;
            this.data = data;
        }
    }

    private static final FrameDelta NO_CHANGE = new FrameDelta(0, 0, 0, 0, null);

    public static void init(JavaPlugin pl, EmageConfig cfg, MapPacketSender sender) {
        if (running) stop();
        plugin = pl;
        config = cfg;
        packetSender = sender;
        running = true;

        playerCacheTaskID = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Collection<? extends Player> online = Bukkit.getOnlinePlayers();

            if (++tickCounter % 100 == 0) {
                Set<UUID> onlineIds = new HashSet<>(online.size());
                for (Player p : online) {
                    onlineIds.add(p.getUniqueId());
                }
                playerVisibleGroupsCarryOver.keySet().retainAll(onlineIds);
            }

            PlayerSnapshot[] newSnapshots = new PlayerSnapshot[online.size()];
            int idx = 0;
            for (Player p : online) {
                UUID uuid = p.getUniqueId();
                Set<Long> carry = playerVisibleGroupsCarryOver.computeIfAbsent(uuid, k -> new HashSet<>());
                newSnapshots[idx++] = new PlayerSnapshot(p, carry);
            }
            playerSnapshots = newSnapshots;
        }, 1L, 1L).getTaskId();

        renderExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Emage-Render-Thread");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        renderExecutor.scheduleAtFixedRate(GifRenderer::tick, 0, 5, TimeUnit.MILLISECONDS);
    }

    public static void stop() {
        running = false;
        if (playerCacheTaskID != -1) {
            Bukkit.getScheduler().cancelTask(playerCacheTaskID);
            playerCacheTaskID = -1;
        }
        if (renderExecutor != null) {
            renderExecutor.shutdownNow();
            try {
                renderExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        SYNC_GROUPS.clear();
        RENDERERS.clear();
        MAP_LOCATIONS.clear();
        playerSnapshots = new PlayerSnapshot[0];
        playerVisibleGroupsCarryOver.clear();

        ID_COUNTER.set(0);
        lastTickTime = 0;
        tickCounter = 0;
        plugin = null;
    }

    private static void tick() {
        if (!running || SYNC_GROUPS.isEmpty()) return;
        PlayerSnapshot[] snaps = playerSnapshots;
        if (snaps.length == 0) return;

        long now = System.currentTimeMillis();
        int fps = Math.max(1, Math.min(60, config != null ? config.getAnimationFps() : 30));
        if (now - lastTickTime < 1000L / fps) return;
        lastTickTime = now;

        boolean triggerUpdate = false;
        for (SyncGroup group : SYNC_GROUPS.values()) {
            if (group.tick(now, snaps.length > 0)) triggerUpdate = true;
        }

        if (triggerUpdate) sendMapUpdates(snaps);
    }

    public static void removeByMapId(int mapId) {
        GifRenderer renderer = RENDERERS.remove(mapId);
        if (renderer != null) {
            SyncGroup group = SYNC_GROUPS.get(renderer.syncId);
            if (group != null) {
                group.renderers.remove(renderer);
                if (group.renderers.isEmpty()) SYNC_GROUPS.remove(renderer.syncId);
            }
        }
        MAP_LOCATIONS.remove(mapId);
    }

    private static void sendMapUpdates(PlayerSnapshot[] snaps) {
        if (!running || RENDERERS.isEmpty() || snaps.length == 0) return;

        Map<Long, List<GifRenderer>> dirtyGroups = new HashMap<>();

        int totalDirty = 0;
        for (GifRenderer renderer : RENDERERS.values()) {
            if (!renderer.needsRender) continue;
            dirtyGroups.computeIfAbsent(renderer.syncId, k -> new ArrayList<>()).add(renderer);
            totalDirty++;
        }

        if (totalDirty == 0) return;

        int renderDistSq = config != null ? config.getRenderDistanceSquared() : DEFAULT_RENDER_DISTANCE_SQ;
        int perPlayerBudget = config != null ? config.getMaxPacketsPerTick() : 32;

        MapPacketSender sender = packetSender;
        Map<UUID, List<Object>> packetsByPlayer = new HashMap<>();

        for (PlayerSnapshot pData : snaps) {
            World pWorld = pData.world;
            if (pWorld == null) continue;

            double pX = pData.x, pY = pData.y, pZ = pData.z;
            double eyeX = pX, eyeY = pY + pData.eyeHeight, eyeZ = pZ;

            double pitch = pData.pitchRad, yaw = pData.yawRad;
            double xz = Math.cos(pitch);
            double dirX = -xz * Math.sin(yaw), dirY = -Math.sin(pitch), dirZ = xz * Math.cos(yaw);

            int sentToThisPlayer = 0;
            List<Object> packetsToSend = new ArrayList<>();

            for (Map.Entry<Long, List<GifRenderer>> entry : dirtyGroups.entrySet()) {
                List<GifRenderer> groupList = entry.getValue();
                if (groupList.isEmpty()) continue;
                if (sentToThisPlayer >= perPlayerBudget) break;

                boolean groupVisible = false;
                long syncID = entry.getKey();

                for (GifRenderer renderer : groupList) {
                    Location loc = MAP_LOCATIONS.get(renderer.cachedMapId);
                    if (loc == null || loc.getWorld() != pWorld) continue;

                    double dx = pX - loc.getX(), dy = pY - loc.getY(), dz = pZ - loc.getZ();
                    if (dx * dx + dy * dy + dz * dz > renderDistSq) continue;

                    double toTargetX = loc.getX() - eyeX;
                    double toTargetY = loc.getY() - eyeY;
                    double toTargetZ = loc.getZ() - eyeZ;

                    double distSq = toTargetX * toTargetX + toTargetY * toTargetY + toTargetZ * toTargetZ;
                    if (distSq >= 1.0) {
                        double invDist = 1.0 / Math.sqrt(distSq);
                        double dot = dirX * (toTargetX * invDist) + dirY * (toTargetY * invDist) + dirZ * (toTargetZ * invDist);
                        if (dot > FOV_COSINE_THRESHOLD) { groupVisible = true; break; }
                    } else {
                        groupVisible = true; break;
                    }
                }

                if (groupVisible) {
                    boolean newlyVisible = pData.visibleSyncGroups.add(syncID);
                    SyncGroup syncGroup = SYNC_GROUPS.get(syncID);
                    int frameIndex = (syncGroup != null) ? syncGroup.getCurrentFrame() : 0;

                    for (GifRenderer renderer : groupList) {
                        if (sentToThisPlayer >= perPlayerBudget) break;

                        int safeIdx = frameIndex;
                        if (safeIdx < 0 || safeIdx >= renderer.frameCount) safeIdx = 0;

                        Object packet;
                        if (newlyVisible) {
                            packet = sender.createPacket(renderer.cachedMapId, renderer.frames[safeIdx]);
                        } else {
                            FrameDelta fd = renderer.computedDeltas[safeIdx];
                            if (fd == null) {
                                byte[] current = renderer.frames[safeIdx];
                                byte[] previous = renderer.frames[(safeIdx - 1 + renderer.frameCount) % renderer.frameCount];
                                fd = renderer.buildDeltaInfo(current, previous);
                                if (fd == null) fd = NO_CHANGE;
                                renderer.computedDeltas[safeIdx] = fd;
                            }

                            if (fd != NO_CHANGE) {
                                packet = sender.createDeltaPacket(renderer.cachedMapId, fd.minX, fd.minY, fd.columns, fd.rows, fd.data);
                            } else {
                                packet = null;
                            }
                        }

                        if (packet != null) {
                            packetsToSend.add(packet);
                            sentToThisPlayer++;
                        }
                    }
                } else {
                    pData.visibleSyncGroups.remove(syncID);
                }
            }

            if (!packetsToSend.isEmpty()) {
                packetsByPlayer.put(pData.playerId, packetsToSend);
            }
        }

        for (Map.Entry<Long, List<GifRenderer>> entry : dirtyGroups.entrySet()) {
            SyncGroup syncGroup = SYNC_GROUPS.get(entry.getKey());
            int frameIndex = (syncGroup != null) ? syncGroup.getCurrentFrame() : 0;
            for (GifRenderer renderer : entry.getValue()) {
                renderer.needsRender = false;
                int safeIdx = frameIndex;
                if (safeIdx < 0 || safeIdx >= renderer.frameCount) safeIdx = 0;
            }
        }

        if (!packetsByPlayer.isEmpty()) {
            JavaPlugin pl = plugin;
            if (pl != null && pl.isEnabled()) {
                Bukkit.getScheduler().runTask(pl, () -> {
                    for (Map.Entry<UUID, List<Object>> entry : packetsByPlayer.entrySet()) {
                        Player targetPlayer = Bukkit.getPlayer(entry.getKey());
                        if (targetPlayer == null || !targetPlayer.isOnline()) continue;

                        for (Object packet : entry.getValue()) {
                            try {
                                sender.sendPacket(targetPlayer, packet);
                            } catch (Exception e) {
                                if (pl.isEnabled()) {
                                    pl.getLogger().fine("Failed to send map packet to " +
                                            entry.getKey() + ": " + e.getMessage());
                                }
                                break;
                            }
                        }
                    }
                });
            }
        }
    }

    public static void startSyncGroup(long syncID) {
        SyncGroup group = SYNC_GROUPS.get(syncID);
        if (group != null) group.start();
    }

    public static void resetSyncTime(long syncId) {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null && group.active) group.start();
    }

    public static void registerMapLocation(int mapId, Location location) {
        if (location != null) MAP_LOCATIONS.put(mapId, location.clone());
    }

    public static int getCurrentFrameForSync(long syncId) {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        return group != null ? group.getCurrentFrame() : 0;
    }

    public static int getActiveCount() {
        return RENDERERS.size();
    }

    public GifRenderer(int mapId, List<byte[]> frameList, List<Integer> delays, long syncID) {
        this.id = ID_COUNTER.incrementAndGet();
        this.cachedMapId = mapId;
        this.syncId = syncID;
        this.frameCount = frameList.size();
        this.frames = new byte[frameCount][];
        for (int i = 0; i < frameCount; i++) {
            this.frames[i] = frameList.get(i);
        }

        this.computedDeltas = new FrameDelta[frameCount];

        SyncGroup group = SYNC_GROUPS.computeIfAbsent(syncID, k -> new SyncGroup(syncID, delays));
        group.renderers.add(this);

        RENDERERS.put(this.cachedMapId, this);
    }

    public void remove() {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null) {
            group.renderers.remove(this);
            if (group.renderers.isEmpty()) SYNC_GROUPS.remove(syncId);
        }
        RENDERERS.remove(cachedMapId);
        MAP_LOCATIONS.remove(cachedMapId);
    }

    private FrameDelta buildDeltaInfo(byte[] current, byte[] previous) {
        int minX = 128, maxX = -1, minY = 128, maxY = -1;
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int idx = y * 128 + x;
                if (current[idx] != previous[idx]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return null;

        int columns = maxX - minX + 1;
        int rows = maxY - minY + 1;
        byte[] deltaData = new byte[columns * rows];

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                deltaData[y * columns + x] = current[(minY + y) * 128 + (minX + x)];
            }
        }
        return new FrameDelta(minX, minY, columns, rows, deltaData);
    }

    public int getFrameCount() {
        return frameCount;
    }

    public void setNeedsRender(boolean needsRender) {
        this.needsRender = needsRender;
    }
}