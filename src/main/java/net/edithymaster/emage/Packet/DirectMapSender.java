package net.edithymaster.emage.Packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import org.bukkit.entity.Player;

import java.util.Collections;

public class DirectMapSender implements MapPacketSender {

    @Override
    public Object createPacket(int mapId, byte[] data) {
        return new WrapperPlayServerMapData(
                mapId,
                (byte) 0,
                false,
                false,
                Collections.emptyList(),
                128,
                128,
                0,
                0,
                data
        );
    }

    @Override
    public Object createDeltaPacket(int mapId, int startX, int startZ, int columns, int rows, byte[] data) {
        return new WrapperPlayServerMapData(
                mapId,
                (byte) 0,
                false,
                false,
                Collections.emptyList(),
                columns,
                rows,
                startX,
                startZ,
                data
        );
    }

    @Override
    public void sendPacket(Player player, Object packet) {
        if (player == null || !player.isOnline()) return;

        PacketEvents.getAPI().getPlayerManager().sendPacket(player, (WrapperPlayServerMapData) packet);
    }
}