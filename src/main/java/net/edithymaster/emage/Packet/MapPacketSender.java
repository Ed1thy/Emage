package net.edithymaster.emage.Packet;

import org.bukkit.entity.Player;

public interface MapPacketSender {

    Object createPacket(int mapId, byte[] data);

    Object createDeltaPacket(int mapId, int startX, int startZ, int columns, int rows, byte[] data);

    void sendPacket(Player player, Object packet);
}