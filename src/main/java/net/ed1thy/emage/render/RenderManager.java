package net.ed1thy.emage.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.model.FrameNode;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RenderManager {

    private final Emage plugin;
    private final ChunkViewerTracker chunkViewerTracker;
    private final PacketSender packetSender;

    private final int maxPacketsPerTick;
    private static final int MAX_PACKETS_PER_PLAYER_PER_TICK = 150;

    private final Map<Integer, SyncGroup> activeGroups = new ConcurrentHashMap<>();
    private final Queue<SpoofTask> pendingSpoofs = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Queue<PacketWrapper<?>>> userPacketQueues = new ConcurrentHashMap<>();

    private ScheduledExecutorService executorService;
    private BukkitTask visibilityTask;

    public record SpoofTask(@NotNull User user, @NotNull FrameNode node) {}

    public RenderManager(@NotNull Emage plugin, @NotNull ChunkViewerTracker chunkViewerTracker, @NotNull PacketSender packetSender, @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.chunkViewerTracker = chunkViewerTracker;
        this.packetSender = packetSender;
        this.maxPacketsPerTick = configManager.maxPacketsPerTick;
    }

    public void start() {
        if (executorService != null && !executorService.isShutdown()) return;

        executorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Emage-Render-Thread");
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });

        executorService.scheduleWithFixedDelay(this::tickAll, 20, 20, TimeUnit.MILLISECONDS);

        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (SyncGroup group : activeGroups.values()) {
                group.updateVisibility();
            }
        }, 20L, 20L);
    }

    private void tickAll() {
        try {
            long currentTime = System.currentTimeMillis();

            int spoofSent = 0;
            SpoofTask spoofTask;
            while (spoofSent < maxPacketsPerTick && (spoofTask = pendingSpoofs.poll()) != null) {
                packetSender.spoofItemFrameMap(spoofTask.user(), spoofTask.node());
                spoofSent++;
            }

            if (!activeGroups.isEmpty()) {
                for (SyncGroup group : activeGroups.values()) {
                    if (group.shouldTick(currentTime)) {
                        group.tick(currentTime, chunkViewerTracker, this, packetSender);
                    }
                }
            }

            Iterator<Map.Entry<UUID, Queue<PacketWrapper<?>>>> iterator = userPacketQueues.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Queue<PacketWrapper<?>>> entry = iterator.next();
                UUID uuid = entry.getKey();
                Queue<PacketWrapper<?>> queue = entry.getValue();

                org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    iterator.remove();
                    continue;
                }

                User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
                if (user == null) {
                    iterator.remove();
                    continue;
                }

                if (queue.size() > 1000) {
                    queue.clear();
                    continue;
                }

                int sent = 0;
                PacketWrapper<?> packet;
                while (sent < MAX_PACKETS_PER_PLAYER_PER_TICK && (packet = queue.poll()) != null) {
                    user.sendPacket(packet);
                    sent++;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Render Thread Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void enqueuePacket(@NotNull UUID uuid, @NotNull PacketWrapper<?> packet) {
        userPacketQueues.computeIfAbsent(uuid, k -> new ConcurrentLinkedQueue<>()).add(packet);
    }

    public void queueSpoof(@NotNull User user, @NotNull FrameNode node) {
        pendingSpoofs.add(new SpoofTask(user, node));
    }

    public void registerSyncGroup(int syncGroupID, @NotNull SyncGroup group) {
        group.updateVisibility();

        activeGroups.put(syncGroupID, group);
    }

    public void unregisterSyncGroup(int syncGroupID) {
        SyncGroup group = activeGroups.remove(syncGroupID);
        if (group != null) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, group::cleanup, 2L);
        }
    }

    public SyncGroup getSyncGroup(int syncGroupID) {
        return activeGroups.get(syncGroupID);
    }

    public void shutdown() {
        if (visibilityTask != null) visibilityTask.cancel();
        if (executorService != null) executorService.shutdownNow();

        for (SyncGroup group : activeGroups.values()) {
            group.cleanup();
        }

        activeGroups.clear();
        pendingSpoofs.clear();
        userPacketQueues.clear();
    }
}