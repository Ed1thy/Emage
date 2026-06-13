package net.ed1thy.emage.network;

import org.jetbrains.annotations.NotNull;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

public class PreFlightImageValidator {

    private static final int MAX_DIMENSION = 4096;

    public static void validate(@NotNull BufferedInputStream stream) throws IOException {
        stream.mark(128 * 1024);

        NoCloseInputStream noCloseStream = new NoCloseInputStream(stream);

        try (ImageInputStream iis = ImageIO.createImageInputStream(noCloseStream)) {
            if (iis == null) {
                throw new IOException("Failed to read image stream headers.");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format or invalid image header.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new IOException("Security Exception: Image dimensions (" + width + "x" + height + ") exceed the safe limit of " + MAX_DIMENSION + "x" + MAX_DIMENSION + " pixels.");
                }
            } finally {
                reader.dispose();
            }
        } finally {
            try {
                stream.reset();
            } catch (IOException e) {
                throw new IOException("Failed to reset image stream after validation.", e);
            }
        }
    }

    private static class NoCloseInputStream extends FilterInputStream {
        protected NoCloseInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {}
    }
}