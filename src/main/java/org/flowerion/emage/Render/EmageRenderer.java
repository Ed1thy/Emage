package org.flowerion.emage.Render;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

public class EmageRenderer extends MapRenderer {

    private final byte[] data;
    private volatile boolean rendered = false;

    public EmageRenderer(byte[] data) {
        super(false);
        this.data = data;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (rendered) return;

        for (int y = 0; y < 128; y++) {
            int rowOffset = y << 7;
            for (int x = 0; x < 128; x++) {
                canvas.setPixel(x, y, data[rowOffset + x]);
            }
        }

        rendered = true;
    }

    public byte[] getData() {
        return data;
    }
}