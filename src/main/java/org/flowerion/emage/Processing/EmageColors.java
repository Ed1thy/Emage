package org.flowerion.emage.Processing;

import java.awt.Color;

/**
 * Minecraft map color palette and matching.
 * Optimized for speed with pre-computed cache.
 */
public final class EmageColors {

    private EmageColors() {}

    // Full Minecraft map palette (1.17+)
    private static final int[][] PALETTE = {
            // 0-3: Transparent
            {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
            // 4-7: GRASS
            {89, 125, 39}, {109, 153, 48}, {127, 178, 56}, {67, 94, 29},
            // 8-11: SAND
            {174, 164, 115}, {213, 201, 140}, {247, 233, 163}, {130, 123, 86},
            // 12-15: WOOL
            {140, 140, 140}, {171, 171, 171}, {199, 199, 199}, {105, 105, 105},
            // 16-19: FIRE
            {180, 0, 0}, {220, 0, 0}, {255, 0, 0}, {135, 0, 0},
            // 20-23: ICE
            {112, 112, 180}, {138, 138, 220}, {160, 160, 255}, {84, 84, 135},
            // 24-27: METAL
            {117, 117, 117}, {144, 144, 144}, {167, 167, 167}, {88, 88, 88},
            // 28-31: PLANT
            {0, 87, 0}, {0, 106, 0}, {0, 124, 0}, {0, 65, 0},
            // 32-35: SNOW
            {180, 180, 180}, {220, 220, 220}, {255, 255, 255}, {135, 135, 135},
            // 36-39: CLAY
            {115, 118, 129}, {141, 144, 158}, {164, 168, 184}, {86, 88, 97},
            // 40-43: DIRT
            {106, 76, 54}, {130, 94, 66}, {151, 109, 77}, {79, 57, 40},
            // 44-47: STONE
            {79, 79, 79}, {96, 96, 96}, {112, 112, 112}, {59, 59, 59},
            // 48-51: WATER
            {45, 45, 180}, {55, 55, 220}, {64, 64, 255}, {33, 33, 135},
            // 52-55: WOOD
            {100, 84, 50}, {123, 102, 62}, {143, 119, 72}, {75, 63, 38},
            // 56-59: QUARTZ
            {180, 177, 172}, {220, 217, 211}, {255, 252, 245}, {135, 133, 129},
            // 60-63: COLOR_ORANGE
            {152, 89, 36}, {186, 109, 44}, {216, 127, 51}, {114, 67, 27},
            // 64-67: COLOR_MAGENTA
            {125, 53, 152}, {153, 65, 186}, {178, 76, 216}, {94, 40, 114},
            // 68-71: COLOR_LIGHT_BLUE
            {72, 108, 152}, {88, 132, 186}, {102, 153, 216}, {54, 81, 114},
            // 72-75: COLOR_YELLOW
            {161, 161, 36}, {197, 197, 44}, {229, 229, 51}, {121, 121, 27},
            // 76-79: COLOR_LIGHT_GREEN
            {89, 144, 17}, {109, 176, 21}, {127, 204, 25}, {67, 108, 13},
            // 80-83: COLOR_PINK
            {170, 89, 116}, {208, 109, 142}, {242, 127, 165}, {127, 67, 87},
            // 84-87: COLOR_GRAY
            {53, 53, 53}, {65, 65, 65}, {76, 76, 76}, {40, 40, 40},
            // 88-91: COLOR_LIGHT_GRAY
            {108, 108, 108}, {132, 132, 132}, {153, 153, 153}, {81, 81, 81},
            // 92-95: COLOR_CYAN
            {53, 89, 108}, {65, 109, 132}, {76, 127, 153}, {40, 67, 81},
            // 96-99: COLOR_PURPLE
            {89, 44, 125}, {109, 54, 153}, {127, 63, 178}, {67, 33, 94},
            // 100-103: COLOR_BLUE
            {36, 53, 125}, {44, 65, 153}, {51, 76, 178}, {27, 40, 94},
            // 104-107: COLOR_BROWN
            {72, 53, 36}, {88, 65, 44}, {102, 76, 51}, {54, 40, 27},
            // 108-111: COLOR_GREEN
            {72, 89, 36}, {88, 109, 44}, {102, 127, 51}, {54, 67, 27},
            // 112-115: COLOR_RED
            {108, 36, 36}, {132, 44, 44}, {153, 51, 51}, {81, 27, 27},
            // 116-119: COLOR_BLACK
            {17, 17, 17}, {21, 21, 21}, {25, 25, 25}, {13, 13, 13},
            // 120-123: GOLD
            {176, 168, 54}, {215, 205, 66}, {250, 238, 77}, {132, 126, 40},
            // 124-127: DIAMOND
            {64, 154, 150}, {79, 188, 183}, {92, 219, 213}, {48, 115, 112},
            // 128-131: LAPIS
            {52, 90, 180}, {63, 110, 220}, {74, 128, 255}, {39, 67, 135},
            // 132-135: EMERALD
            {0, 153, 40}, {0, 187, 50}, {0, 217, 58}, {0, 114, 30},
            // 136-139: PODZOL
            {91, 60, 34}, {111, 74, 42}, {129, 86, 49}, {68, 45, 25},
            // 140-143: NETHER
            {79, 1, 0}, {96, 1, 0}, {112, 2, 0}, {59, 1, 0},
            // 144-147: TERRACOTTA_WHITE
            {147, 124, 113}, {180, 152, 138}, {209, 177, 161}, {110, 93, 85},
            // 148-151: TERRACOTTA_ORANGE
            {112, 57, 25}, {137, 70, 31}, {159, 82, 36}, {84, 43, 19},
            // 152-155: TERRACOTTA_MAGENTA
            {105, 61, 76}, {128, 75, 93}, {149, 87, 108}, {78, 46, 57},
            // 156-159: TERRACOTTA_LIGHT_BLUE
            {79, 76, 97}, {96, 93, 119}, {112, 108, 138}, {59, 57, 73},
            // 160-163: TERRACOTTA_YELLOW
            {131, 93, 25}, {160, 114, 31}, {186, 133, 36}, {98, 70, 19},
            // 164-167: TERRACOTTA_LIGHT_GREEN
            {72, 82, 37}, {88, 100, 45}, {103, 117, 53}, {54, 61, 28},
            // 168-171: TERRACOTTA_PINK
            {112, 54, 55}, {138, 66, 67}, {160, 77, 78}, {84, 40, 41},
            // 172-175: TERRACOTTA_GRAY
            {40, 28, 24}, {49, 35, 30}, {57, 41, 35}, {30, 21, 18},
            // 176-179: TERRACOTTA_LIGHT_GRAY
            {95, 75, 69}, {116, 92, 84}, {135, 107, 98}, {71, 56, 51},
            // 180-183: TERRACOTTA_CYAN
            {61, 64, 64}, {75, 79, 79}, {87, 92, 92}, {46, 48, 48},
            // 184-187: TERRACOTTA_PURPLE
            {86, 51, 62}, {105, 62, 75}, {122, 73, 88}, {64, 38, 46},
            // 188-191: TERRACOTTA_BLUE
            {53, 43, 64}, {65, 53, 79}, {76, 62, 92}, {40, 32, 48},
            // 192-195: TERRACOTTA_BROWN
            {53, 35, 24}, {65, 43, 30}, {76, 50, 35}, {40, 26, 18},
            // 196-199: TERRACOTTA_GREEN
            {53, 57, 29}, {65, 70, 36}, {76, 82, 42}, {40, 43, 22},
            // 200-203: TERRACOTTA_RED
            {100, 42, 32}, {122, 51, 39}, {142, 60, 46}, {75, 31, 24},
            // 204-207: TERRACOTTA_BLACK
            {26, 15, 11}, {31, 18, 13}, {37, 22, 16}, {19, 11, 8},
            // 208-211: CRIMSON_NYLIUM
            {133, 33, 34}, {163, 41, 42}, {189, 48, 49}, {100, 25, 25},
            // 212-215: CRIMSON_STEM
            {104, 44, 68}, {127, 54, 83}, {148, 63, 97}, {78, 33, 51},
            // 216-219: CRIMSON_HYPHAE
            {64, 17, 20}, {79, 21, 25}, {92, 25, 29}, {48, 13, 15},
            // 220-223: WARPED_NYLIUM
            {15, 88, 94}, {18, 108, 115}, {22, 126, 134}, {11, 66, 70},
            // 224-227: WARPED_STEM
            {40, 100, 98}, {50, 122, 120}, {58, 142, 140}, {30, 75, 74},
            // 228-231: WARPED_HYPHAE
            {60, 31, 43}, {74, 37, 53}, {86, 44, 62}, {45, 23, 32},
            // 232-235: WARPED_WART_BLOCK
            {14, 127, 93}, {17, 155, 114}, {20, 180, 133}, {10, 95, 70},
            // 236-239: DEEPSLATE
            {70, 70, 70}, {86, 86, 86}, {100, 100, 100}, {52, 52, 52},
            // 240-243: RAW_IRON
            {152, 123, 103}, {186, 150, 126}, {216, 175, 147}, {114, 92, 77},
            // 244-247: GLOW_LICHEN
            {89, 117, 105}, {109, 144, 129}, {127, 167, 150}, {67, 88, 79},
            // 248-255: Unused/reserved
            {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
            {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
    };

    // Pre-computed Color objects
    private static final Color[] COLORS = new Color[256];

    // Color cache for fast lookup (6-bit per channel = 262144 entries)
    private static final byte[] CACHE = new byte[64 * 64 * 64];
    private static volatile boolean initialized = false;

    static {
        // Initialize Color objects
        for (int i = 0; i < 256; i++) {
            int[] rgb = PALETTE[i];
            COLORS[i] = new Color(rgb[0], rgb[1], rgb[2]);
        }
    }

    /**
     * Initialize color cache (call once at startup)
     */
    public static void initCache() {
        if (initialized) return;

        synchronized (CACHE) {
            if (initialized) return;

            // Build cache for all 6-bit color combinations
            for (int r = 0; r < 64; r++) {
                for (int g = 0; g < 64; g++) {
                    for (int b = 0; b < 64; b++) {
                        // Expand 6-bit to 8-bit
                        int r8 = (r << 2) | (r >> 4);
                        int g8 = (g << 2) | (g >> 4);
                        int b8 = (b << 2) | (b >> 4);

                        int index = (r << 12) | (g << 6) | b;
                        CACHE[index] = findClosestColor(r8, g8, b8);
                    }
                }
            }

            initialized = true;
        }
    }

    /**
     * Match RGB to palette color (uses cache if available)
     */
    public static byte matchColor(int r, int g, int b) {
        // Clamp values
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        if (initialized) {
            // Use cache (6-bit precision)
            int index = ((r >> 2) << 12) | ((g >> 2) << 6) | (b >> 2);
            return CACHE[index];
        }

        return findClosestColor(r, g, b);
    }

    /**
     * Find closest palette color using weighted RGB distance
     */
    private static byte findClosestColor(int r, int g, int b) {
        int bestIndex = 4; // Start after transparent colors
        int bestDistance = Integer.MAX_VALUE;

        // Skip indices 0-3 (transparent)
        for (int i = 4; i < 248; i++) {
            int[] pal = PALETTE[i];

            // Skip unused palette entries
            if (pal[0] == 0 && pal[1] == 0 && pal[2] == 0 && i > 119) {
                continue;
            }

            // Weighted RGB distance (human eye is more sensitive to green)
            int dr = r - pal[0];
            int dg = g - pal[1];
            int db = b - pal[2];

            // Weight: R=2, G=4, B=1 (approximates human perception)
            int distance = (dr * dr * 2) + (dg * dg * 4) + (db * db);

            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;

                // Early exit for exact match
                if (distance == 0) break;
            }
        }

        return (byte) bestIndex;
    }

    /**
     * Get Color for palette index
     */
    public static Color getColor(byte index) {
        return COLORS[index & 0xFF];
    }

    /**
     * Get RGB array for palette index
     */
    public static int[] getRGB(byte index) {
        int[] rgb = PALETTE[index & 0xFF];
        return new int[]{rgb[0], rgb[1], rgb[2]};
    }

    /**
     * Check if cache is ready
     */
    public static boolean isCacheReady() {
        return initialized;
    }
}