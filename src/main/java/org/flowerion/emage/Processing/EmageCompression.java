package org.flowerion.emage.Processing;

import java.io.*;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class EmageCompression {

    private EmageCompression() {}

    private static final int MAP_SIZE = 16384;

    public static byte[] compressSingleStatic(byte[] data) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeByte('E');
            dos.writeByte('M');
            dos.writeByte('1');

            int[] colorCount = new int[256];
            for (byte b : data) {
                colorCount[b & 0xFF]++;
            }

            int uniqueColors = 0;
            byte[] colorToIndex = new byte[256];
            byte[] indexToColor = new byte[256];

            for (int i = 0; i < 256; i++) {
                if (colorCount[i] > 0) {
                    colorToIndex[i] = (byte) uniqueColors;
                    indexToColor[uniqueColors] = (byte) i;
                    uniqueColors++;
                }
            }

            if (uniqueColors == 0) uniqueColors = 1;

            dos.writeByte(uniqueColors & 0xFF);
            for (int i = 0; i < uniqueColors; i++) {
                dos.writeByte(indexToColor[i]);
            }

            int bpp = getBitsPerPixel(uniqueColors);
            dos.writeByte(bpp);

            byte[] remapped = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                remapped[i] = colorToIndex[data[i] & 0xFF];
            }

            byte[] packed;
            if (bpp < 8) {
                packed = packBits(remapped, bpp);
            } else {
                packed = remapped;
            }

            byte[] compressed = deflate(packed);

            dos.writeInt(packed.length);
            dos.writeInt(compressed.length);
            dos.write(compressed);

            dos.close();

            byte[] result = baos.toByteArray();

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return deflateFallback(data);
        }
    }

    private static long frameHash(byte[] data) {
        long hash = 0;
        int step = Math.max(1, data.length / 64);
        for (int i = 0; i < data.length; i += step) {
            hash = hash * 31 + (data[i] & 0xFF);
        }
        return hash;
    }

    private static boolean framesEqual(byte[] a, byte[] b) {
        if (a.length != b.length) return false;

        int[] checkPoints = {0, 127, 8128, 16256, 16383};
        for (int i : checkPoints) {
            if (i < a.length && a[i] != b[i]) return false;
        }

        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static int countDifferences(byte[] a, byte[] b, int maxDiff) {
        int count = 0;
        for (int i = 0; i < a.length && count <= maxDiff; i++) {
            if (a[i] != b[i]) count++;
        }
        return count;
    }

    private static byte[] encodeSparse(byte[] current, byte[] reference, int diffCount) {
        int size = 2 + (diffCount * 3);
        byte[] result = new byte[size];

        result[0] = (byte) ((diffCount >> 8) & 0xFF);
        result[1] = (byte) (diffCount & 0xFF);

        int pos = 2;
        for (int i = 0; i < current.length && pos < size; i++) {
            if (current[i] != reference[i]) {
                result[pos++] = (byte) ((i >> 8) & 0xFF);
                result[pos++] = (byte) (i & 0xFF);
                result[pos++] = current[i];
            }
        }

        return result;
    }

    public static byte[] compressAnimGridParallel(Map<Integer, List<byte[]>> cells,
                                                  List<Integer> delays, long syncId) {
        if (cells.size() <= 4) {
            return compressAnimGrid(cells, delays, syncId);
        }

        return compressAnimGrid(cells, delays, syncId);
    }

    public static byte[] decompressSingleStatic(byte[] compressed) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
            DataInputStream dis = new DataInputStream(bais);

            int m1 = dis.readByte() & 0xFF;
            int m2 = dis.readByte() & 0xFF;
            int version = dis.readByte() & 0xFF;

            if (m1 != 'E' || m2 != 'M') {
                dis.close();
                return decompressLegacy(compressed);
            }

            int uniqueColors = dis.readByte() & 0xFF;
            if (uniqueColors == 0) uniqueColors = 256;

            byte[] indexToColor = new byte[256];
            for (int i = 0; i < uniqueColors; i++) {
                indexToColor[i] = dis.readByte();
            }

            int bpp = dis.readByte() & 0xFF;
            int packedSize = dis.readInt();
            int compressedSize = dis.readInt();

            byte[] compressedData = new byte[compressedSize];
            dis.readFully(compressedData);
            dis.close();

            byte[] packed = inflate(compressedData, packedSize);

            byte[] remapped;
            if (bpp < 8) {
                remapped = unpackBits(packed, bpp, MAP_SIZE);
            } else {
                remapped = packed;
            }

            byte[] result = new byte[MAP_SIZE];
            for (int i = 0; i < MAP_SIZE; i++) {
                int idx = remapped[i] & 0xFF;
                result[i] = indexToColor[idx];
            }

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return decompressLegacy(compressed);
        }
    }

    public static Set<Integer> getMapIdsFromFile(File file) {
        Set<Integer> mapIds = new HashSet<>();

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            byte[] header = new byte[3];
            dis.readFully(header);

            if (header[0] == 'E' && header[1] == 'G' && (header[2] == 'S' || header[2] == 'A')) {
                dis.readLong();
                int cellCount = dis.readShort() & 0xFFFF;

                if (header[2] == 'A') {
                    dis.readShort();
                }

                for (int i = 0; i < cellCount; i++) {
                    mapIds.add(dis.readInt());
                }
                return mapIds;
            }

            if (header[0] == 'E' && header[1] == 'G') {
                dis.readLong();
                int cellCount = dis.readInt();
                int frameCount = dis.readInt();

                dis.skipBytes(frameCount * 2);

                for (int i = 0; i < cellCount; i++) {
                    mapIds.add(dis.readInt());
                }
                return mapIds;
            }

            if (header[0] == 'E' && header[1] == 'M') {
                return mapIds;
            }

        } catch (Exception e) {
            // Ignore errors, return empty set
        }

        return mapIds;
    }

    public static byte[] compressStaticGrid(Map<Integer, byte[]> cells, long gridId) {
        try {
            if (cells.isEmpty()) {
                return new byte[0];
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeByte('E');
            dos.writeByte('G');
            dos.writeByte('S');

            dos.writeLong(gridId);

            List<Integer> mapIds = new ArrayList<>(cells.keySet());
            Collections.sort(mapIds);

            dos.writeShort(mapIds.size());

            for (int mapId : mapIds) {
                dos.writeInt(mapId);
            }

            int[] colorCount = new int[256];
            for (byte[] data : cells.values()) {
                for (byte b : data) {
                    colorCount[b & 0xFF]++;
                }
            }

            int uniqueColors = 0;
            byte[] colorToIndex = new byte[256];
            byte[] indexToColor = new byte[256];

            for (int i = 0; i < 256; i++) {
                if (colorCount[i] > 0) {
                    colorToIndex[i] = (byte) uniqueColors;
                    indexToColor[uniqueColors] = (byte) i;
                    uniqueColors++;
                }
            }

            if (uniqueColors == 0) uniqueColors = 1;

            dos.writeByte(uniqueColors & 0xFF);
            for (int i = 0; i < uniqueColors; i++) {
                dos.writeByte(indexToColor[i]);
            }

            int bpp = getBitsPerPixel(uniqueColors);
            dos.writeByte(bpp);

            ByteArrayOutputStream cellData = new ByteArrayOutputStream();
            byte[] prevRemapped = null;

            for (int mapId : mapIds) {
                byte[] data = cells.get(mapId);

                byte[] remapped = new byte[MAP_SIZE];
                for (int i = 0; i < MAP_SIZE; i++) {
                    remapped[i] = colorToIndex[data[i] & 0xFF];
                }

                if (prevRemapped == null) {
                    byte[] packed = packBits(remapped, bpp);
                    cellData.write(0);
                    writeShort(cellData, packed.length);
                    cellData.write(packed);
                } else {
                    int diffCount = 0;
                    for (int i = 0; i < MAP_SIZE; i++) {
                        if (remapped[i] != prevRemapped[i]) diffCount++;
                    }

                    if (diffCount == 0) {
                        cellData.write(3);
                    } else if (diffCount < MAP_SIZE / 8) {
                        ByteArrayOutputStream sparse = new ByteArrayOutputStream();
                        writeShort(sparse, diffCount);
                        for (int i = 0; i < MAP_SIZE; i++) {
                            if (remapped[i] != prevRemapped[i]) {
                                writeShort(sparse, i);
                                sparse.write(remapped[i] & 0xFF);
                            }
                        }
                        byte[] sparseData = sparse.toByteArray();
                        cellData.write(1);
                        writeShort(cellData, sparseData.length);
                        cellData.write(sparseData);
                    } else {
                        byte[] xorData = new byte[MAP_SIZE];
                        for (int i = 0; i < MAP_SIZE; i++) {
                            xorData[i] = (byte) (remapped[i] ^ prevRemapped[i]);
                        }
                        byte[] packed = packBits(xorData, bpp);

                        byte[] fullPacked = packBits(remapped, bpp);

                        if (packed.length < fullPacked.length) {
                            cellData.write(2);
                            writeShort(cellData, packed.length);
                            cellData.write(packed);
                        } else {
                            cellData.write(0);
                            writeShort(cellData, fullPacked.length);
                            cellData.write(fullPacked);
                        }
                    }
                }

                prevRemapped = remapped;
            }

            byte[] rawCellData = cellData.toByteArray();
            byte[] compressedCellData = deflate(rawCellData);

            dos.writeInt(rawCellData.length);
            dos.writeInt(compressedCellData.length);
            dos.write(compressedCellData);

            dos.close();

            byte[] result = baos.toByteArray();

            int rawSize = cells.size() * MAP_SIZE;

            System.out.println("[Emage Debug] Static grid compression: " + rawSize + " -> " + result.length +
                    " bytes (" + String.format("%.1f%%", result.length * 100.0 / rawSize) +
                    "), cells: " + cells.size() + ", colors: " + uniqueColors + ", bpp: " + bpp);

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return new byte[0];
        }
    }

    public static StaticGridData decompressStaticGrid(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);

            int m1 = dis.readByte() & 0xFF;
            int m2 = dis.readByte() & 0xFF;
            int m3 = dis.readByte() & 0xFF;

            if (m1 != 'E' || m2 != 'G' || m3 != 'S') {
                dis.close();
                return null;
            }

            long gridId = dis.readLong();
            int cellCount = dis.readShort() & 0xFFFF;

            List<Integer> mapIds = new ArrayList<>(cellCount);
            for (int i = 0; i < cellCount; i++) {
                mapIds.add(dis.readInt());
            }

            int uniqueColors = dis.readByte() & 0xFF;
            if (uniqueColors == 0) uniqueColors = 256;

            byte[] indexToColor = new byte[256];
            for (int i = 0; i < uniqueColors; i++) {
                indexToColor[i] = dis.readByte();
            }

            int bpp = dis.readByte() & 0xFF;

            int rawSize = dis.readInt();
            int compressedSize = dis.readInt();

            byte[] compressedCellData = new byte[compressedSize];
            dis.readFully(compressedCellData);
            dis.close();

            byte[] rawCellData = inflate(compressedCellData, rawSize);

            Map<Integer, byte[]> cells = new HashMap<>();
            ByteArrayInputStream cellIn = new ByteArrayInputStream(rawCellData);
            byte[] prevRemapped = null;

            for (int mapId : mapIds) {
                int marker = cellIn.read() & 0xFF;
                byte[] remapped;

                if (marker == 3) {
                    remapped = prevRemapped != null ? prevRemapped.clone() : new byte[MAP_SIZE];
                } else {
                    int dataLen = readShort(cellIn);
                    byte[] frameData = new byte[dataLen];
                    cellIn.read(frameData);

                    if (marker == 0) {
                        remapped = unpackBits(frameData, bpp, MAP_SIZE);
                    } else if (marker == 1) {
                        remapped = prevRemapped != null ? prevRemapped.clone() : new byte[MAP_SIZE];
                        ByteArrayInputStream sparseIn = new ByteArrayInputStream(frameData);
                        int count = readShort(sparseIn);
                        for (int i = 0; i < count; i++) {
                            int pos = readShort(sparseIn);
                            int val = sparseIn.read() & 0xFF;
                            if (pos < MAP_SIZE) remapped[pos] = (byte) val;
                        }
                    } else {
                        byte[] xorData = unpackBits(frameData, bpp, MAP_SIZE);
                        byte[] prev = prevRemapped != null ? prevRemapped : new byte[MAP_SIZE];
                        remapped = new byte[MAP_SIZE];
                        for (int i = 0; i < MAP_SIZE; i++) {
                            remapped[i] = (byte) (xorData[i] ^ prev[i]);
                        }
                    }
                }

                byte[] result = new byte[MAP_SIZE];
                for (int i = 0; i < MAP_SIZE; i++) {
                    result[i] = indexToColor[remapped[i] & 0xFF];
                }

                cells.put(mapId, result);
                prevRemapped = remapped;
            }

            return new StaticGridData(gridId, cells);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] compressAnimGrid(Map<Integer, List<byte[]>> cells, List<Integer> delays, long syncId) {
        try {
            if (cells.isEmpty()) {
                return new byte[0];
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeByte('E');
            dos.writeByte('G');
            dos.writeByte('A');

            dos.writeLong(syncId);

            List<Integer> mapIds = new ArrayList<>(cells.keySet());
            Collections.sort(mapIds);

            int frameCount = cells.get(mapIds.get(0)).size();

            dos.writeShort(mapIds.size());
            dos.writeShort(frameCount);

            for (int mapId : mapIds) {
                dos.writeInt(mapId);
            }

            int avgDelay = 100;
            if (delays != null && !delays.isEmpty()) {
                avgDelay = (int) delays.stream().mapToInt(Integer::intValue).average().orElse(100);
            }
            dos.writeShort(avgDelay);

            for (int i = 0; i < frameCount; i++) {
                int delay = (delays != null && i < delays.size()) ? delays.get(i) : avgDelay;
                int diff = delay - avgDelay;
                dos.writeShort(diff);
            }

            int[] colorCount = new int[256];
            for (List<byte[]> frames : cells.values()) {
                for (byte[] frame : frames) {
                    for (byte b : frame) {
                        colorCount[b & 0xFF]++;
                    }
                }
            }

            int uniqueColors = 0;
            byte[] colorToIndex = new byte[256];
            byte[] indexToColor = new byte[256];

            for (int i = 0; i < 256; i++) {
                if (colorCount[i] > 0) {
                    colorToIndex[i] = (byte) uniqueColors;
                    indexToColor[uniqueColors] = (byte) i;
                    uniqueColors++;
                }
            }

            if (uniqueColors == 0) uniqueColors = 1;

            dos.writeByte(uniqueColors & 0xFF);
            for (int i = 0; i < uniqueColors; i++) {
                dos.writeByte(indexToColor[i]);
            }

            int bpp = getBitsPerPixel(uniqueColors);
            dos.writeByte(bpp);

            ByteArrayOutputStream frameData = new ByteArrayOutputStream();

            Map<Integer, byte[]> prevTemporal = new HashMap<>();

            for (int f = 0; f < frameCount; f++) {
                byte[] prevSpatial = null;

                for (int mapId : mapIds) {
                    byte[] frame = cells.get(mapId).get(f);

                    byte[] remapped = new byte[MAP_SIZE];
                    for (int i = 0; i < MAP_SIZE; i++) {
                        remapped[i] = colorToIndex[frame[i] & 0xFF];
                    }

                    byte[] temporalRef = prevTemporal.get(mapId);

                    byte[] bestData = null;
                    int bestMarker = 0;

                    byte[] fullPacked = packBits(remapped, bpp);
                    bestData = fullPacked;
                    bestMarker = 0;

                    if (temporalRef != null) {
                        EncodingResult temporal = tryDelta(remapped, temporalRef, bpp);
                        if (temporal.size < bestData.length) {
                            bestData = temporal.data;
                            bestMarker = temporal.marker | 0x10;
                        }
                    }

                    if (prevSpatial != null) {
                        EncodingResult spatial = tryDelta(remapped, prevSpatial, bpp);
                        if (spatial.size < bestData.length) {
                            bestData = spatial.data;
                            bestMarker = spatial.marker | 0x20;
                        }
                    }

                    frameData.write(bestMarker);
                    if ((bestMarker & 0x0F) != 3) {
                        writeShort(frameData, bestData.length);
                        frameData.write(bestData);
                    }

                    prevTemporal.put(mapId, remapped);
                    prevSpatial = remapped;
                }
            }

            byte[] rawFrameData = frameData.toByteArray();
            byte[] compressedFrameData = deflate(rawFrameData);

            dos.writeInt(rawFrameData.length);
            dos.writeInt(compressedFrameData.length);
            dos.write(compressedFrameData);

            dos.close();

            byte[] result = baos.toByteArray();

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return new byte[0];
        }
    }

    private static class EncodingResult {
        byte[] data;
        int marker;
        int size;

        EncodingResult(byte[] data, int marker) {
            this.data = data;
            this.marker = marker;
            this.size = (marker == 3) ? 1 : data.length + 3;
        }
    }

    private static EncodingResult tryDelta(byte[] current, byte[] reference, int bpp) {
        int diffCount = 0;
        for (int i = 0; i < MAP_SIZE; i++) {
            if (current[i] != reference[i]) diffCount++;
        }

        if (diffCount == 0) {
            return new EncodingResult(new byte[0], 3);
        }

        if (diffCount < MAP_SIZE / 8) {
            try {
                ByteArrayOutputStream sparse = new ByteArrayOutputStream();
                writeShort(sparse, diffCount);
                for (int i = 0; i < MAP_SIZE; i++) {
                    if (current[i] != reference[i]) {
                        writeShort(sparse, i);
                        sparse.write(current[i] & 0xFF);
                    }
                }
                return new EncodingResult(sparse.toByteArray(), 1);
            } catch (Exception e) {
            }
        }

        byte[] xorData = new byte[MAP_SIZE];
        for (int i = 0; i < MAP_SIZE; i++) {
            xorData[i] = (byte) (current[i] ^ reference[i]);
        }
        byte[] packed = packBits(xorData, bpp);
        return new EncodingResult(packed, 2);
    }

    public static AnimGridData decompressAnimGrid(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);

            int m1 = dis.readByte() & 0xFF;
            int m2 = dis.readByte() & 0xFF;
            int m3 = dis.readByte() & 0xFF;

            if (m1 != 'E' || m2 != 'G' || m3 != 'A') {
                dis.close();
                return decompressLegacyAnimGrid(data);
            }

            long syncId = dis.readLong();
            int cellCount = dis.readShort() & 0xFFFF;
            int frameCount = dis.readShort() & 0xFFFF;

            List<Integer> mapIds = new ArrayList<>(cellCount);
            for (int i = 0; i < cellCount; i++) {
                mapIds.add(dis.readInt());
            }

            int avgDelay = dis.readShort() & 0xFFFF;
            List<Integer> delays = new ArrayList<>(frameCount);
            for (int i = 0; i < frameCount; i++) {
                int diff = dis.readShort();
                delays.add(Math.max(20, avgDelay + diff));
            }

            int uniqueColors = dis.readByte() & 0xFF;
            if (uniqueColors == 0) uniqueColors = 256;

            byte[] indexToColor = new byte[256];
            for (int i = 0; i < uniqueColors; i++) {
                indexToColor[i] = dis.readByte();
            }

            int bpp = dis.readByte() & 0xFF;

            int rawSize = dis.readInt();
            int compressedSize = dis.readInt();

            byte[] compressedFrameData = new byte[compressedSize];
            dis.readFully(compressedFrameData);
            dis.close();

            byte[] rawFrameData = inflate(compressedFrameData, rawSize);

            Map<Integer, List<byte[]>> cells = new HashMap<>();
            for (int mapId : mapIds) {
                cells.put(mapId, new ArrayList<>(frameCount));
            }

            ByteArrayInputStream frameIn = new ByteArrayInputStream(rawFrameData);
            Map<Integer, byte[]> prevTemporal = new HashMap<>();

            for (int f = 0; f < frameCount; f++) {
                byte[] prevSpatial = null;

                for (int mapId : mapIds) {
                    int header = frameIn.read() & 0xFF;
                    int refType = (header >> 4) & 0x0F;
                    int marker = header & 0x0F;

                    byte[] reference;
                    if (refType == 1) {
                        reference = prevTemporal.get(mapId);
                    } else if (refType == 2) {
                        reference = prevSpatial;
                    } else {
                        reference = null;
                    }

                    byte[] remapped;

                    if (marker == 3) {
                        remapped = reference != null ? reference.clone() : new byte[MAP_SIZE];
                    } else {
                        int dataLen = readShort(frameIn);
                        byte[] frameData = new byte[dataLen];
                        frameIn.read(frameData);

                        if (marker == 0) {
                            remapped = unpackBits(frameData, bpp, MAP_SIZE);
                        } else if (marker == 1) {
                            remapped = reference != null ? reference.clone() : new byte[MAP_SIZE];
                            ByteArrayInputStream sparseIn = new ByteArrayInputStream(frameData);
                            int count = readShort(sparseIn);
                            for (int i = 0; i < count; i++) {
                                int pos = readShort(sparseIn);
                                int val = sparseIn.read() & 0xFF;
                                if (pos < MAP_SIZE) remapped[pos] = (byte) val;
                            }
                        } else {
                            byte[] xorData = unpackBits(frameData, bpp, MAP_SIZE);
                            byte[] ref = reference != null ? reference : new byte[MAP_SIZE];
                            remapped = new byte[MAP_SIZE];
                            for (int i = 0; i < MAP_SIZE; i++) {
                                remapped[i] = (byte) (xorData[i] ^ ref[i]);
                            }
                        }
                    }

                    byte[] result = new byte[MAP_SIZE];
                    for (int i = 0; i < MAP_SIZE; i++) {
                        result[i] = indexToColor[remapped[i] & 0xFF];
                    }

                    cells.get(mapId).add(result);
                    prevTemporal.put(mapId, remapped);
                    prevSpatial = remapped;
                }
            }

            return new AnimGridData(syncId, cells, delays);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static AnimGridData decompressLegacyAnimGrid(byte[] data) {
        try {
            EmageCore.AnimData animData = EmageCore.decompressAnim(data);
            if (animData != null && !animData.frames.isEmpty()) {
                Map<Integer, List<byte[]>> cells = new HashMap<>();
                cells.put(0, animData.frames);
                return new AnimGridData(animData.syncId, cells, animData.delays);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static byte[] decompressLegacy(byte[] data) {
        try {
            return EmageCore.decompressMap(data);
        } catch (Exception e) {
            return new byte[MAP_SIZE];
        }
    }

    private static byte[] deflateFallback(byte[] data) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write('E');
            baos.write('M');
            baos.write('0');

            byte[] compressed = deflate(data);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(data.length);
            dos.writeInt(compressed.length);
            dos.write(compressed);
            dos.close();

            return baos.toByteArray();
        } catch (Exception e) {
            return data;
        }
    }

    private static int getBitsPerPixel(int colorCount) {
        if (colorCount <= 2) return 1;
        if (colorCount <= 4) return 2;
        if (colorCount <= 16) return 4;
        return 8;
    }

    private static byte[] packBits(byte[] data, int bpp) {
        if (bpp >= 8) return data.clone();

        int pixelsPerByte = 8 / bpp;
        int packedSize = (data.length + pixelsPerByte - 1) / pixelsPerByte;
        byte[] packed = new byte[packedSize];
        int mask = (1 << bpp) - 1;

        for (int i = 0; i < data.length; i++) {
            int byteIdx = i / pixelsPerByte;
            int bitOffset = (pixelsPerByte - 1 - (i % pixelsPerByte)) * bpp;
            packed[byteIdx] |= (byte) ((data[i] & mask) << bitOffset);
        }

        return packed;
    }

    private static byte[] unpackBits(byte[] packed, int bpp, int size) {
        if (bpp >= 8) return Arrays.copyOf(packed, size);

        byte[] unpacked = new byte[size];
        int pixelsPerByte = 8 / bpp;
        int mask = (1 << bpp) - 1;

        for (int i = 0; i < size; i++) {
            int byteIdx = i / pixelsPerByte;
            if (byteIdx >= packed.length) break;
            int bitOffset = (pixelsPerByte - 1 - (i % pixelsPerByte)) * bpp;
            unpacked[i] = (byte) ((packed[byteIdx] >> bitOffset) & mask);
        }

        return unpacked;
    }

    private static byte[] deflate(byte[] data) {
        try {
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            deflater.setInput(data);
            deflater.finish();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];

            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                baos.write(buffer, 0, count);
            }

            deflater.end();
            return baos.toByteArray();
        } catch (Exception e) {
            return data;
        }
    }

    private static byte[] inflate(byte[] data, int expectedSize) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(data);

            byte[] result = new byte[expectedSize];
            int offset = 0;

            while (!inflater.finished() && offset < expectedSize) {
                int count = inflater.inflate(result, offset, expectedSize - offset);
                if (count == 0 && inflater.needsInput()) break;
                offset += count;
            }

            inflater.end();
            return result;
        } catch (Exception e) {
            return new byte[expectedSize];
        }
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readShort(InputStream in) throws IOException {
        int b1 = in.read() & 0xFF;
        int b2 = in.read() & 0xFF;
        return (b1 << 8) | b2;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    public static class StaticGridData {
        public final long gridId;
        public final Map<Integer, byte[]> cells;

        public StaticGridData(long gridId, Map<Integer, byte[]> cells) {
            this.gridId = gridId;
            this.cells = cells;
        }
    }

    public static class AnimGridData {
        public final long syncId;
        public final Map<Integer, List<byte[]>> cells;
        public final List<Integer> delays;

        public AnimGridData(long syncId, Map<Integer, List<byte[]>> cells, List<Integer> delays) {
            this.syncId = syncId;
            this.cells = cells;
            this.delays = delays;
        }
    }
}