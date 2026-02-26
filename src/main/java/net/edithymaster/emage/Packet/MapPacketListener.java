package net.edithymaster.emage.Packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import net.edithymaster.emage.Manager.EmageManager;
import net.edithymaster.emage.Render.GifRenderer;

public class MapPacketListener extends PacketListenerAbstract {

    private final EmageManager manager;

    public MapPacketListener(EmageManager manager) {
        this.manager = manager;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.MAP_DATA) {
            WrapperPlayServerMapData mapData = new WrapperPlayServerMapData(event);
            int mapId = mapData.getMapId();

            EmageManager.CachedMapData cached = manager.getMapCache().get(mapId);
            if (cached != null) {
                byte[] payload = null;

                if (cached.isAnimation) {
                    int currentFrame = GifRenderer.getCurrentFrameForSync(cached.syncId);
                    if (currentFrame >= 0 && currentFrame < cached.frames.size()) {
                        payload = cached.frames.get(currentFrame);
                    }
                } else if (cached.staticData != null) {
                    payload = cached.staticData;
                }

                if (payload != null) {
                    mapData.setColumns(128);
                    mapData.setRows(128);
                    mapData.setX(0);
                    mapData.setZ(0);
                    mapData.setData(payload);

                    event.markForReEncode(true);
                }
            }
        }
    }
}