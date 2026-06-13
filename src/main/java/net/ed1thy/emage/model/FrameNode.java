package net.ed1thy.emage.model;

import org.jetbrains.annotations.NotNull;
import java.util.UUID;

public class FrameNode {

    private int entityID;
    private final UUID frameUUID;
    private final UUID worldUUID;
    private final int chunkX;
    private final int chunkZ;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final int mapId;
    private final com.github.retrooper.packetevents.protocol.item.ItemStack cachedItem;

    public FrameNode(int entityID, @NotNull UUID frameUUID, @NotNull UUID worldUUID,
                     int chunkX, int chunkZ, int blockX, int blockY, int blockZ, int mapId,
                     @NotNull com.github.retrooper.packetevents.protocol.item.ItemStack cachedItem) {
        this.entityID = entityID;
        this.frameUUID = frameUUID;
        this.worldUUID = worldUUID;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.mapId = mapId;
        this.cachedItem = cachedItem;
    }

    public long getChunkKey() {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    public int getEntityID() { return entityID; }

    @NotNull public UUID getFrameUUID() { return frameUUID; }
    @NotNull public UUID getWorldUUID() { return worldUUID; }
    public int getBlockX() { return blockX; }
    public int getBlockY() { return blockY; }
    public int getBlockZ() { return blockZ; }
    public int getMapID() { return mapId; }
    @NotNull public com.github.retrooper.packetevents.protocol.item.ItemStack getCachedItem() { return cachedItem; }
}