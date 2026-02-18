package net.edithymaster.emage.Processing;

import java.awt.Color;

public final class EmageColors {

    private EmageColors() {}

    private static final int MAX_VALID_INDEX = 220;

    private static final int[][] PALETTE = {
            {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
            {89, 125, 39}, {109, 153, 48}, {127, 178, 56}, {67, 94, 29},
            {174, 164, 115}, {213, 201, 140}, {247, 233, 163}, {130, 123, 86},
            {140, 140, 140}, {171, 171, 171}, {199, 199, 199}, {105, 105, 105},
            {180, 0, 0}, {220, 0, 0}, {255, 0, 0}, {135, 0, 0},
            {112, 112, 180}, {138, 138, 220}, {160, 160, 255}, {84, 84, 135},
            {117, 117, 117}, {144, 144, 144}, {167, 167, 167}, {88, 88, 88},
            {0, 87, 0}, {0, 106, 0}, {0, 124, 0}, {0, 65, 0},
            {180, 180, 180}, {220, 220, 220}, {255, 255, 255}, {135, 135, 135},
            {115, 118, 129}, {141, 144, 158}, {164, 168, 184}, {86, 88, 97},
            {106, 76, 54}, {130, 94, 66}, {151, 109, 77}, {79, 57, 40},
            {79, 79, 79}, {96, 96, 96}, {112, 112, 112}, {59, 59, 59},
            {45, 45, 180}, {55, 55, 220}, {64, 64, 255}, {33, 33, 135},
            {100, 84, 50}, {123, 102, 62}, {143, 119, 72}, {75, 63, 38},
            {180, 177, 172}, {220, 217, 211}, {255, 252, 245}, {135, 133, 129},
            {152, 89, 36}, {186, 109, 44}, {216, 127, 51}, {114, 67, 27},
            {125, 53, 152}, {153, 65, 186}, {178, 76, 216}, {94, 40, 114},
            {72, 108, 152}, {88, 132, 186}, {102, 153, 216}, {54, 81, 114},
            {161, 161, 36}, {197, 197, 44}, {229, 229, 51}, {121, 121, 27},
            {89, 144, 17}, {109, 176, 21}, {127, 204, 25}, {67, 108, 13},
            {170, 89, 116}, {208, 109, 142}, {242, 127, 165}, {127, 67, 87},
            {53, 53, 53}, {65, 65, 65}, {76, 76, 76}, {40, 40, 40},
            {108, 108, 108}, {132, 132, 132}, {153, 153, 153}, {81, 81, 81},
            {53, 89, 108}, {65, 109, 132}, {76, 127, 153}, {40, 67, 81},
            {89, 44, 125}, {109, 54, 153}, {127, 63, 178}, {67, 33, 94},
            {36, 53, 125}, {44, 65, 153}, {51, 76, 178}, {27, 40, 94},
            {72, 53, 36}, {88, 65, 44}, {102, 76, 51}, {54, 40, 27},
            {72, 89, 36}, {88, 109, 44}, {102, 127, 51}, {54, 67, 27},
            {108, 36, 36}, {132, 44, 44}, {153, 51, 51}, {81, 27, 27},
            {17, 17, 17}, {21, 21, 21}, {25, 25, 25}, {13, 13, 13},
            {176, 168, 54}, {215, 205, 66}, {250, 238, 77}, {132, 126, 40},
            {64, 154, 150}, {79, 188, 183}, {92, 219, 213}, {48, 115, 112},
            {52, 90, 180}, {63, 110, 220}, {74, 128, 255}, {39, 67, 135},
            {0, 153, 40}, {0, 187, 50}, {0, 217, 58}, {0, 114, 30},
            {91, 60, 34}, {111, 74, 42}, {129, 86, 49}, {68, 45, 25},
            {79, 1, 0}, {96, 1, 0}, {112, 2, 0}, {59, 1, 0},
            {147, 124, 113}, {180, 152, 138}, {209, 177, 161}, {110, 93, 85},
            {112, 57, 25}, {137, 70, 31}, {159, 82, 36}, {84, 43, 19},
            {105, 61, 76}, {128, 75, 93}, {149, 87, 108}, {78, 46, 57},
            {79, 76, 97}, {96, 93, 119}, {112, 108, 138}, {59, 57, 73},
            {131, 93, 25}, {160, 114, 31}, {186, 133, 36}, {98, 70, 19},
            {72, 82, 37}, {88, 100, 45}, {103, 117, 53}, {54, 61, 28},
            {112, 54, 55}, {138, 66, 67}, {160, 77, 78}, {84, 40, 41},
            {40, 28, 24}, {49, 35, 30}, {57, 41, 35}, {30, 21, 18},
            {95, 75, 69}, {116, 92, 84}, {135, 107, 98}, {71, 56, 51},
            {61, 64, 64}, {75, 79, 79}, {87, 92, 92}, {46, 48, 48},
            {86, 51, 62}, {105, 62, 75}, {122, 73, 88}, {64, 38, 46},
            {53, 43, 64}, {65, 53, 79}, {76, 62, 92}, {40, 32, 48},
            {53, 35, 24}, {65, 43, 30}, {76, 50, 35}, {40, 26, 18},
            {53, 57, 29}, {65, 70, 36}, {76, 82, 42}, {40, 43, 22},
            {100, 42, 32}, {122, 51, 39}, {142, 60, 46}, {75, 31, 24},
            {26, 15, 11}, {31, 18, 13}, {37, 22, 16}, {19, 11, 8},
            {133, 33, 34}, {163, 41, 42}, {189, 48, 49}, {100, 25, 25},
            {104, 44, 68}, {127, 54, 83}, {148, 63, 97}, {78, 33, 51},
            {64, 17, 20}, {79, 21, 25}, {92, 25, 29}, {48, 13, 15},
            {15, 88, 94}, {18, 108, 115}, {22, 126, 134}, {11, 66, 70},
            {40, 100, 98}, {50, 122, 120}, {58, 142, 140}, {30, 75, 74},
            {60, 31, 43}, {74, 37, 53}, {86, 44, 62}, {45, 23, 32},
            {14, 127, 93}, {17, 155, 114}, {20, 180, 133}, {10, 95, 70},
            {70, 70, 70}, {86, 86, 86}, {100, 100, 100}, {52, 52, 52},
            {152, 123, 103}, {186, 150, 126}, {216, 175, 147}, {114, 92, 77},
            {89, 117, 105}, {109, 144, 129}, {127, 167, 150}, {67, 88, 79},
            {105, 85, 98}, {128, 103, 120}, {148, 120, 138}, {78, 63, 73},
            {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
    };

    private static final Color[] COLORS = new Color[256];

    private static final int CACHE_BITS = 7;
    private static final int CACHE_SIZE = 1 << (CACHE_BITS * 3);
    private static final int CACHE_SHIFT = 8 - CACHE_BITS;
    private static final byte[] CACHE = new byte[CACHE_SIZE];
    private static volatile boolean initialized = false;

    private static final double[][] PALETTE_LAB = new double[256][3];
    private static final double[] LINEAR_TABLE = new double[256];

    private static final double[][] PALETTE_LINEAR_RGB = new double[256][3];

    private static final int DELIN_TABLE_SIZE = 16384;
    private static final int[] DELIN_TABLE = new int[DELIN_TABLE_SIZE + 1];

    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double RAD_30 = Math.toRadians(30);
    private static final double RAD_6 = Math.toRadians(6);
    private static final double RAD_63 = Math.toRadians(63);
    private static final double RAD_275 = Math.toRadians(275);
    private static final double RAD_25 = Math.toRadians(25);
    private static final double POW_25_7 = 6103515625.0;

    static {
        for (int i = 0; i < 256; i++) {
            int[] rgb = PALETTE[i];
            COLORS[i] = new Color(rgb[0], rgb[1], rgb[2]);

            double c = i / 255.0;
            LINEAR_TABLE[i] = (c <= 0.04045) ? (c / 12.92) : Math.pow((c + 0.055) / 1.055, 2.4);
        }

        for (int i = 0; i < 256; i++) {
            int[] rgb = PALETTE[i];
            PALETTE_LAB[i] = rgbToLab(rgb[0], rgb[1], rgb[2]);
            PALETTE_LINEAR_RGB[i][0] = LINEAR_TABLE[rgb[0]];
            PALETTE_LINEAR_RGB[i][1] = LINEAR_TABLE[rgb[1]];
            PALETTE_LINEAR_RGB[i][2] = LINEAR_TABLE[rgb[2]];
        }

        for (int i = 0; i <= DELIN_TABLE_SIZE; i++) {
            double linear = (double) i / DELIN_TABLE_SIZE;
            double srgb;
            if (linear <= 0.0031308) {
                srgb = linear * 12.92;
            } else {
                srgb = 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
            }
            DELIN_TABLE[i] = Math.max(0, Math.min(255, (int) Math.round(srgb * 255.0)));
        }
    }

    public static void initCache() {
        if (initialized) return;

        synchronized (CACHE) {
            if (initialized) return;

            int bits = CACHE_BITS;
            int total = 1 << bits;
            int shift = CACHE_SHIFT;

            java.util.stream.IntStream.range(0, total).parallel().forEach(r -> {
                for (int g = 0; g < total; g++) {
                    for (int b = 0; b < total; b++) {
                        int r8 = (r << shift) | (r >> (bits - shift));
                        int g8 = (g << shift) | (g >> (bits - shift));
                        int b8 = (b << shift) | (b >> (bits - shift));

                        int index = (r << (bits * 2)) | (g << bits) | b;
                        CACHE[index] = findClosestColorLab(r8, g8, b8);
                    }
                }
            });

            initialized = true;
        }
    }

    public static byte matchColor(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        if (initialized) {
            int index = ((r >> CACHE_SHIFT) << (CACHE_BITS * 2))
                    | ((g >> CACHE_SHIFT) << CACHE_BITS)
                    | (b >> CACHE_SHIFT);
            return CACHE[index];
        }

        return findClosestColorLab(r, g, b);
    }

    private static byte findClosestColorLab(int r, int g, int b) {
        double[] lab = rgbToLab(r, g, b);
        double L = lab[0], a = lab[1], bv = lab[2];

        double bestLabEuc = Double.MAX_VALUE;

        for (int i = 4; i < MAX_VALID_INDEX; i++) {
            double dL = L - PALETTE_LAB[i][0];
            double da = a - PALETTE_LAB[i][1];
            double db = bv - PALETTE_LAB[i][2];
            double d = dL * dL + da * da + db * db;
            if (d < bestLabEuc) bestLabEuc = d;
        }

        double threshold = bestLabEuc * 3.0 + 100.0;

        int bestIndex = 4;
        double bestDistance = Double.MAX_VALUE;

        for (int i = 4; i < MAX_VALID_INDEX; i++) {
            double dL = L - PALETTE_LAB[i][0];
            double da = a - PALETTE_LAB[i][1];
            double db = bv - PALETTE_LAB[i][2];
            double labEuc = dL * dL + da * da + db * db;

            if (labEuc > threshold) continue;

            double distance = ciede2000(lab, PALETTE_LAB[i]);

            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
                if (distance < 0.001) break;
            }
        }

        return (byte) bestIndex;
    }

    private static double pow7(double x) {
        double x2 = x * x;
        double x3 = x2 * x;
        return x3 * x3 * x;
    }

    private static double ciede2000(double[] lab1, double[] lab2) {
        double L1 = lab1[0], a1 = lab1[1], b1_val = lab1[2];
        double L2 = lab2[0], a2 = lab2[1], b2_val = lab2[2];

        double C1 = Math.sqrt(a1 * a1 + b1_val * b1_val);
        double C2 = Math.sqrt(a2 * a2 + b2_val * b2_val);
        double Cb = (C1 + C2) / 2.0;

        double Cb7 = pow7(Cb);
        double G = 0.5 * (1.0 - Math.sqrt(Cb7 / (Cb7 + POW_25_7)));

        double a1p = a1 * (1.0 + G);
        double a2p = a2 * (1.0 + G);

        double C1p = Math.sqrt(a1p * a1p + b1_val * b1_val);
        double C2p = Math.sqrt(a2p * a2p + b2_val * b2_val);

        double h1p = Math.atan2(b1_val, a1p);
        double h2p = Math.atan2(b2_val, a2p);
        if (h1p < 0) h1p += TWO_PI;
        if (h2p < 0) h2p += TWO_PI;

        double dLp = L2 - L1;
        double dCp = C2p - C1p;

        double dhp;
        if (C1p * C2p == 0) {
            dhp = 0;
        } else if (Math.abs(h2p - h1p) <= Math.PI) {
            dhp = h2p - h1p;
        } else if (h2p - h1p > Math.PI) {
            dhp = h2p - h1p - TWO_PI;
        } else {
            dhp = h2p - h1p + TWO_PI;
        }
        double dHp = 2.0 * Math.sqrt(C1p * C2p) * Math.sin(dhp / 2.0);

        double Lbp = (L1 + L2) / 2.0;
        double Cbp = (C1p + C2p) / 2.0;

        double hbp;
        if (C1p * C2p == 0) {
            hbp = h1p + h2p;
        } else if (Math.abs(h1p - h2p) <= Math.PI) {
            hbp = (h1p + h2p) / 2.0;
        } else if (h1p + h2p < TWO_PI) {
            hbp = (h1p + h2p + TWO_PI) / 2.0;
        } else {
            hbp = (h1p + h2p - TWO_PI) / 2.0;
        }

        double T = 1.0
                - 0.17 * Math.cos(hbp - RAD_30)
                + 0.24 * Math.cos(2.0 * hbp)
                + 0.32 * Math.cos(3.0 * hbp + RAD_6)
                - 0.20 * Math.cos(4.0 * hbp - RAD_63);

        double Lbp50sq = (Lbp - 50.0) * (Lbp - 50.0);
        double SL = 1.0 + 0.015 * Lbp50sq / Math.sqrt(20.0 + Lbp50sq);
        double SC = 1.0 + 0.045 * Cbp;
        double SH = 1.0 + 0.015 * Cbp * T;

        double Cbp7 = pow7(Cbp);
        double RC = 2.0 * Math.sqrt(Cbp7 / (Cbp7 + POW_25_7));
        double dTheta = RAD_30
                * Math.exp(-((hbp - RAD_275) / RAD_25) * ((hbp - RAD_275) / RAD_25));
        double RT = -Math.sin(2.0 * dTheta) * RC;

        double ratioL = dLp / SL;
        double ratioC = dCp / SC;
        double ratioH = dHp / SH;

        return ratioL * ratioL + ratioC * ratioC + ratioH * ratioH + RT * ratioC * ratioH;
    }

    private static double[] rgbToLab(int r, int g, int b) {
        double lr = LINEAR_TABLE[r];
        double lg = LINEAR_TABLE[g];
        double lb = LINEAR_TABLE[b];

        double x = lr * 0.4124564 + lg * 0.3575761 + lb * 0.1804375;
        double y = lr * 0.2126729 + lg * 0.7151522 + lb * 0.0721750;
        double z = lr * 0.0193339 + lg * 0.1191920 + lb * 0.9503041;

        x /= 0.95047;
        y /= 1.00000;
        z /= 1.08883;

        x = labF(x);
        y = labF(y);
        z = labF(z);

        return new double[]{116.0 * y - 16.0, 500.0 * (x - y), 200.0 * (y - z)};
    }

    private static double labF(double t) {
        if (t > 0.008856) {
            return Math.cbrt(t);
        } else {
            return (903.3 * t + 16.0) / 116.0;
        }
    }

    public static double linearize(int srgb) {
        return LINEAR_TABLE[Math.max(0, Math.min(255, srgb))];
    }

    public static int delinearize(double linear) {
        if (linear <= 0.0) return 0;
        if (linear >= 1.0) return 255;
        return DELIN_TABLE[(int) (linear * DELIN_TABLE_SIZE + 0.5)];
    }

    public static double[] getLinearRGB(byte index) {
        return PALETTE_LINEAR_RGB[index & 0xFF];
    }

    public static Color getColor(byte index) {
        return COLORS[index & 0xFF];
    }

    public static int[] getRGB(byte index) {
        int[] rgb = PALETTE[index & 0xFF];
        return new int[]{rgb[0], rgb[1], rgb[2]};
    }

    public static boolean isCacheReady() {
        return initialized;
    }
}