package net.ed1thy.emage.render;

import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.FrameNode;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

public class PacketSender {

    @NotNull
    public PacketWrapper<?> createMapPacket(@NotNull DeltaFrame delta) {
        return new ZeroCopyMapWrapper(delta);
    }

    public void spoofItemFrameMap(@NotNull User user, @NotNull FrameNode node) {
        Equipment equipment = new Equipment(EquipmentSlot.MAIN_HAND, node.getCachedItem());
        WrapperPlayServerEntityEquipment equipmentPacket = new WrapperPlayServerEntityEquipment(
                node.getEntityID(),
                Collections.singletonList(equipment)
        );
        user.sendPacket(equipmentPacket);
    }
}