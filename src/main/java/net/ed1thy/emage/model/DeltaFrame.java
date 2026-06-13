package net.ed1thy.emage.model;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

public record DeltaFrame(
        int frameIndex,
        int mapId,
        @NotNull ByteBuf packetBuf
) {
    public void freeMemory() {
        if (packetBuf != null && packetBuf.refCnt() > 0) {
            packetBuf.release(packetBuf.refCnt());
        }
    }
}