package net.ed1thy.emage.processing;

import java.awt.image.BufferedImage;

public interface ImageFrameProvider extends AutoCloseable {
    int getFrameCount();
    int getDelayMs();
    BufferedImage getFrame(int index);

    @Override
    void close() throws Exception;
}