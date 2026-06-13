package net.ed1thy.emage.processing;

import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

public class ColorPalette {

    private final byte[] colorMap = new byte[262144];
    private final int[] mcColorsRGB = new int[256];
    private final double[][] mcColorsOklab = new double[256][3];
    private volatile boolean isReady = false;

    private static final int[] BASE_COLORS = {
            0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00,
            0xFFFFFF, 0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33,
            0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99,
            0x8133B2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5,
            0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
            0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C,
            0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86,
            0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FAEA6
    };

    public CompletableFuture<Void> generateLUT() {
        return CompletableFuture.runAsync(() -> {
            try {
                int maxId = 0;
                for (int i = 0; i < BASE_COLORS.length; i++) {
                    int baseRGB = BASE_COLORS[i];
                    int r = (baseRGB >> 16) & 0xFF;
                    int g = (baseRGB >> 8) & 0xFF;
                    int b = baseRGB & 0xFF;

                    int[] shades = {180, 220, 255, 135};

                    for (int shade = 0; shade < 4; shade++) {
                        int mapId = (i * 4) + shade;
                        if (mapId >= mcColorsRGB.length) continue;

                        int shadedR = (r * shades[shade]) / 255;
                        int shadedG = (g * shades[shade]) / 255;
                        int shadedB = (b * shades[shade]) / 255;

                        if (mapId >= 4) {
                            mcColorsRGB[mapId] = (shadedR << 16) | (shadedG << 8) | shadedB;
                            mcColorsOklab[mapId] = rgbToOklab(shadedR, shadedG, shadedB);
                            maxId = Math.max(maxId, mapId);
                        }
                    }
                }

                final int finalMaxId = maxId;

                IntStream.range(0, 64).parallel().forEach(rIdx -> {
                    int r = (rIdx << 2) + 2;
                    for (int gIdx = 0; gIdx < 64; gIdx++) {
                        int g = (gIdx << 2) + 2;
                        for (int bIdx = 0; bIdx < 64; bIdx++) {
                            int b = (bIdx << 2) + 2;

                            double[] targetLab = rgbToOklab(r, g, b);

                            byte bestColor = 4;
                            double bestDist = Double.MAX_VALUE;

                            for (int id = 4; id <= finalMaxId; id++) {
                                if (mcColorsRGB[id] == 0) continue;

                                double[] cand = mcColorsOklab[id];

                                double dL = targetLab[0] - cand[0];
                                double da = targetLab[1] - cand[1];
                                double db = targetLab[2] - cand[2];

                                double dist = (dL * dL) + (da * da) + (db * db);

                                if (dist < bestDist) {
                                    bestDist = dist;
                                    bestColor = (byte) id;
                                }
                            }

                            int index = (rIdx << 12) | (gIdx << 6) | bIdx;
                            colorMap[index] = bestColor;
                        }
                    }
                });

                isReady = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public byte getMappedColorFast(int r, int g, int b) {
        return colorMap[((r >> 2) << 12) | ((g >> 2) << 6) | (b >> 2)];
    }

    private static double[] rgbToOklab(int r, int g, int b) {
        double rL = srgbToLinear(r / 255.0);
        double gL = srgbToLinear(g / 255.0);
        double bL = srgbToLinear(b / 255.0);

        double l = 0.4122214708 * rL + 0.5363325363 * gL + 0.0514459929 * bL;
        double m = 0.2119034982 * rL + 0.6806995451 * gL + 0.1073969566 * bL;
        double s = 0.0883024619 * rL + 0.2817188376 * gL + 0.6299787005 * bL;

        l = Math.cbrt(l);
        m = Math.cbrt(m);
        s = Math.cbrt(s);

        return new double[]{
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        };
    }

    private static double srgbToLinear(double x) {
        return x >= 0.04045 ? Math.pow((x + 0.055) / 1.055, 2.4) : x / 12.92;
    }

    public boolean isReady() {
        return isReady;
    }
}