package net.ed1thy.emage.processing.dither;

import net.ed1thy.emage.processing.ColorPalette;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

public class BlueNoiseDither {

    private static final int MATRIX_SIZE = 128;
    private static final int MATRIX_MASK = MATRIX_SIZE - 1;
    private static final int[][] PRECALC_SPREAD = new int[MATRIX_SIZE][MATRIX_SIZE];

    private final ForkJoinPool customPool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

    static {
        for (int y = 0; y < MATRIX_SIZE; y++) {
            for (int x = 0; x < MATRIX_SIZE; x++) {
                double noise = ign(x, y);
                float offset = (float) (noise - 0.5);

                PRECALC_SPREAD[y][x] = Math.round(offset * 12);
            }
        }
    }

    private static double fract(double val) { return val - Math.floor(val); }
    private static double ign(double x, double y) { return fract(52.9829189 * fract(0.06711056 * x + 0.00583715 * y)); }

    public byte[] applyDither(int @NotNull [] pixels, int width, int height, @NotNull ColorPalette lut) {
        byte[] outColors = new byte[pixels.length];

        try {
            customPool.submit(() -> IntStream.range(0, height).parallel().forEach(y -> {
                int noiseY = y & MATRIX_MASK;
                int[] noiseRow = PRECALC_SPREAD[noiseY];

                for (int x = 0; x < width; x++) {
                    int idx = y * width + x;
                    int argb = pixels[idx];

                    if (((argb >> 24) & 0xFF) < 128) {
                        outColors[idx] = 0;
                        continue;
                    }

                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;

                    int noiseX = x & MATRIX_MASK;
                    int offset = noiseRow[noiseX];

                    int adjR = r + offset;
                    int adjG = g + offset;
                    int adjB = b + offset;

                    adjR = adjR < 0 ? 0 : (adjR > 255 ? 255 : adjR);
                    adjG = adjG < 0 ? 0 : (adjG > 255 ? 255 : adjG);
                    adjB = adjB < 0 ? 0 : (adjB > 255 ? 255 : adjB);

                    outColors[idx] = lut.getMappedColorFast(adjR, adjG, adjB);
                }
            })).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }

        return outColors;
    }

    public void shutdown() {
        customPool.shutdown();
    }
}