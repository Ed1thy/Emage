package net.ed1thy.emage.processing;

import java.awt.image.BufferedImage;

public class StaticImageProvider implements ImageFrameProvider {
    private final BufferedImage image;

    public StaticImageProvider(BufferedImage image) {
        this.image = image;
    }

    @Override public int getFrameCount() { return 1; }
    @Override public int getDelayMs() { return 100; }
    @Override public BufferedImage getFrame(int index) { return image; }

    @Override
    public void close() throws Exception {}
}