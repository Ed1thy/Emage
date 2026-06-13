package net.ed1thy.emage.processing;

import java.awt.image.BufferedImage;
import java.util.ArrayList;

public class GifDecoder implements ImageFrameProvider {
    private byte[] in;
    private int p;
    private int width;
    private int height;
    private boolean gctFlag;
    private int gctSize;
    private int[] gct;
    private int[] act;
    private int bgIndex;
    private int bgColor;
    private int lastBgColor;
    private int transIndex;
    private boolean interlace;
    private int ix, iy, iw, ih;
    private int lrx, lry, lrw, lrh;
    private byte[] block = new byte[256];
    private int blockSize = 0;
    private int dispose = 0;
    private int lastDispose = 0;
    private boolean transparency = false;
    private int delay = 0;

    private byte[] pixels;
    private int[] mainPixels;
    private int[] lastPixels;

    private short[] prefix;
    private byte[] suffix;
    private byte[] pixelStack;

    private final ArrayList<GifFrame> frames = new ArrayList<>();

    private static class GifFrame {
        public BufferedImage image;
        public int delay;
        public GifFrame(BufferedImage im, int del) {
            image = im;
            delay = del;
        }
    }

    public void read(byte[] data) {
        if (data == null) return;
        this.in = data;
        this.p = 0;
        readHeader();
        if (!err()) {
            readContents();
        }
    }

    @Override public int getFrameCount() { return frames.size(); }
    @Override public int getDelayMs() { return frames.isEmpty() ? 100 : frames.get(0).delay; }
    @Override public BufferedImage getFrame(int n) { return frames.get(n).image; }
    @Override public void close() throws Exception {}

    private boolean err() { return p >= in.length; }
    private int read() { return err() ? 0 : in[p++] & 0xFF; }
    private int readShort() { return read() | (read() << 8); }

    private void readBlock() {
        blockSize = read();
        int n = 0;
        if (blockSize > 0) {
            try {
                int count;
                while (n < blockSize) {
                    count = blockSize - n;
                    System.arraycopy(in, p, block, n, count);
                    p += count;
                    n += count;
                }
            } catch (Exception ignored) {}
        }
    }

    private void readHeader() {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 6; i++) id.append((char) read());
        if (!id.toString().startsWith("GIF")) return;
        width = readShort();
        height = readShort();
        int packed = read();
        gctFlag = (packed & 0x80) != 0;
        gctSize = 2 << (packed & 7);
        bgIndex = read();
        read();
        if (gctFlag) {
            gct = readColorTable(gctSize);
            bgColor = gct[bgIndex];
        }
    }

    private int[] readColorTable(int ncolors) {
        int[] tab = new int[256];
        for (int i = 0; i < ncolors; i++) {
            int r = read(); int g = read(); int b = read();
            tab[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        return tab;
    }

    private void readContents() {
        boolean done = false;
        while (!done && !err()) {
            int code = read();
            switch (code) {
                case 0x2C: readImage(); break;
                case 0x21:
                    code = read();
                    switch (code) {
                        case 0xF9:
                            read();
                            int packed = read();
                            dispose = (packed & 0x1c) >> 2;
                            if (dispose == 0) dispose = 1;
                            transparency = (packed & 1) != 0;
                            delay = readShort() * 10;
                            transIndex = read();
                            read();
                            break;
                        default: skip();
                    }
                    break;
                case 0x3B: done = true; break;
                case 0x00: break;
                default: skip();
            }
        }
    }

    private void skip() {
        do { readBlock(); } while (blockSize > 0 && !err());
    }

    private void readImage() {
        ix = readShort(); iy = readShort(); iw = readShort(); ih = readShort();
        int packed = read();
        boolean lctFlag = (packed & 0x80) != 0;
        interlace = (packed & 0x40) != 0;
        int lctSize = 2 << (packed & 7);
        if (lctFlag) act = readColorTable(lctSize);
        else {
            act = gct;
            if (bgIndex == transIndex) bgColor = 0;
        }
        int save = 0;
        if (transparency) {
            save = act[transIndex];
            act[transIndex] = 0;
        }
        if (act == null) return;
        decodeImageData();
        skip();
        setPixels();

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, mainPixels, 0, width);
        frames.add(new GifFrame(image, delay));

        if (transparency) act[transIndex] = save;

        lastDispose = dispose;
        lrx = ix; lry = iy; lrw = iw; lrh = ih;
        lastBgColor = bgColor;
    }

    private void setPixels() {
        if (mainPixels == null) {
            mainPixels = new int[width * height];
            lastPixels = new int[width * height];
        }

        if (lastDispose > 0) {
            if (lastDispose == 3) {
                System.arraycopy(lastPixels, 0, mainPixels, 0, width * height);
            } else if (lastDispose == 2) {
                int c = transparency ? 0 : lastBgColor;
                for (int i = 0; i < lrh; i++) {
                    int line = (lry + i) * width + lrx;
                    for (int j = 0; j < lrw; j++) {
                        mainPixels[line + j] = c;
                    }
                }
            }
        }

        if (dispose == 3) {
            System.arraycopy(mainPixels, 0, lastPixels, 0, width * height);
        }

        int pass = 1, inc = 8, iline = 0;
        for (int i = 0; i < ih; i++) {
            int line = i;
            if (interlace) {
                if (iline >= ih) {
                    pass++;
                    switch (pass) {
                        case 2: iline = 4; break;
                        case 3: iline = 2; inc = 4; break;
                        case 4: iline = 1; inc = 2; break;
                    }
                }
                line = iline;
                iline += inc;
            }
            line += iy;
            if (line < height) {
                int k = line * width;
                int dx = k + ix;
                int dmax = dx + iw;
                if (k + width < dmax) dmax = k + width;
                int sx = i * iw;
                while (dx < dmax) {
                    int index = ((int) pixels[sx++]) & 0xff;
                    int c = act[index];
                    if (c != 0) mainPixels[dx] = c;
                    dx++;
                }
            }
        }
    }

    private void decodeImageData() {
        int npix = iw * ih;
        if (pixels == null || pixels.length < npix) pixels = new byte[npix];
        if (prefix == null) prefix = new short[4096];
        if (suffix == null) suffix = new byte[4096];
        if (pixelStack == null) pixelStack = new byte[4097];
        int data_size = read();
        int clear = 1 << data_size;
        int end_of_information = clear + 1;
        int available = clear + 2;
        int old_code = -1;
        int code_size = data_size + 1;
        int code_mask = (1 << code_size) - 1;
        for (int code = 0; code < clear; code++) { prefix[code] = 0; suffix[code] = (byte) code; }
        int datum = 0, bits = 0, count = 0, first = 0, top = 0, pi = 0, bi = 0;
        for (int i = 0; i < npix; ) {
            if (top == 0) {
                if (bits < code_size) {
                    if (count == 0) {
                        readBlock();
                        if (blockSize <= 0) break;
                        count = blockSize;
                        bi = 0;
                    }
                    datum += (((int) block[bi]) & 0xff) << bits;
                    bits += 8; bi++; count--; continue;
                }
                int code = datum & code_mask;
                datum >>= code_size; bits -= code_size;
                if (code > available || code == end_of_information) break;
                if (code == clear) {
                    code_size = data_size + 1;
                    code_mask = (1 << code_size) - 1;
                    available = clear + 2;
                    old_code = -1;
                    continue;
                }
                if (old_code == -1) {
                    pixelStack[top++] = suffix[code];
                    old_code = code; first = code;
                    continue;
                }
                int in_code = code;
                if (code == available) {
                    pixelStack[top++] = (byte) first;
                    code = old_code;
                }
                while (code > clear) {
                    pixelStack[top++] = suffix[code];
                    code = prefix[code];
                }
                first = ((int) suffix[code]) & 0xff;
                if (available >= 4096) break;
                pixelStack[top++] = (byte) first;
                prefix[available] = (short) old_code;
                suffix[available] = (byte) first;
                available++;
                if (((available & code_mask) == 0) && (available < 4096)) {
                    code_size++;
                    code_mask += available;
                }
                old_code = in_code;
            }
            top--;
            pixels[pi++] = pixelStack[top];
            i++;
        }
        for (int i = pi; i < npix; i++) pixels[i] = 0;
    }
}