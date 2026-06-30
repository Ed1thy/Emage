package net.ed1thy.emage.render;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.ByteBuf;
import net.ed1thy.emage.model.DeltaFrame;

public class ZeroCopyMapWrapper extends PacketWrapper<ZeroCopyMapWrapper> {

    private final DeltaFrame delta;

    public ZeroCopyMapWrapper(DeltaFrame delta) {
        super(PacketType.Play.Server.MAP_DATA);
        this.delta = delta;
    }

    public DeltaFrame getDelta() {
        return delta;
    }

    @Override
    public void write() {
        ByteBuf nettyBuf = (ByteBuf) getBuffer();
        ByteBuf deltaBuf = delta.packetBuf();
        nettyBuf.writeBytes(deltaBuf, deltaBuf.readerIndex(), deltaBuf.readableBytes());    }

    @Override
    public void read() {}
}