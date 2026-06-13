package net.ed1thy.emage.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkViewerTracker {

    private final ConcurrentHashMap<Long, Set<UUID>> chunkViewers = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, User> playerUsers = new ConcurrentHashMap<>();

    public void addViewer(long chunkKey, UUID playerUUID) {
        chunkViewers.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(playerUUID);
    }

    public void removeViewer(long chunkKey, UUID playerUUID) {
        Set<UUID> viewers = chunkViewers.get(chunkKey);
        if (viewers != null) {
            viewers.remove(playerUUID);
            if (viewers.isEmpty()) {
                chunkViewers.remove(chunkKey);
            }
        }
    }

    public void removeViewerFromAll(UUID playerUUID) {
        chunkViewers.values().forEach(viewers -> viewers.remove(playerUUID));
        chunkViewers.values().removeIf(Set::isEmpty);
        playerUsers.remove(playerUUID);
    }

    public void registerPlayer(@NotNull Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user != null) {
            playerUsers.put(player.getUniqueId(), user);
        }
    }

    @Nullable
    public User getUser(@NotNull UUID uuid) {
        return playerUsers.get(uuid);
    }
}