package net.edithymaster.emage.Processing;

import com.github.luben.zstd.Zstd;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

public final class EmageCompression {

    private EmageCompression() {}

    private static final Logger logger = Logger.getLogger(EmageCompression.class.getName());

    private static final byte[] MAGIC_HEADER = { 'E', 'M', 'Z' };

    public static byte[] compressSingleStatic(byte[] data) {
        try {
            ByteArrayOutputStream logicStream = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(logicStream);

            dos.write(MAGIC_HEADER);
            dos.writeByte(1);

            byte[] compressed = Zstd.compress(data, 1);

            dos.writeInt(data.length);
            dos.writeInt(compressed.length);
            dos.write(compressed);
            dos.close();

            return logicStream.toByteArray();

        } catch (IOException e) {
            logger.warning("Could not compress map data. Saving uncompressed. Cause: " + e.getMessage());
            return data;
        }
    }

    public static byte[] decompressStatic(byte[] compressedData) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(compressedData))) {
            byte[] magic = new byte[3];
            dis.readFully(magic);

            if (!Arrays.equals(magic, MAGIC_HEADER)) {
                return decompressLegacy(compressedData);
            }

            dis.readByte();
            int rawSize = dis.readInt();
            int compSize = dis.readInt();

            byte[] zstdData = new byte[compSize];
            dis.readFully(zstdData);

            return Zstd.decompress(zstdData, rawSize);

        } catch (IOException e) {
            logger.warning("Could not decompress map data. The map will render blank. Cause: " + e.getMessage());
            return new byte[EmageCore.MAP_SIZE];
        }
    }

    public static byte[] decompressLegacy(byte[] data) {
        if (data == null || data.length < 3) {
            return new byte[EmageCore.MAP_SIZE];
        }

        if (data[0] == 'E' && data[1] == 'M' && data[2] == '0') {
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
                dis.skipBytes(3);

                int size = dis.readInt();
                int compSize = dis.readInt();

                if (size < 0 || size > EmageCore.MAP_SIZE * 2 || compSize < 0 || compSize > data.length) {
                    logger.warning("Invalid legacy map data: size=" + size + ", compSize=" + compSize);
                    return new byte[EmageCore.MAP_SIZE];
                }

                byte[] comp = new byte[compSize];
                dis.readFully(comp);

                return inflate(comp, size);
            } catch (IOException e) {
                logger.warning("Could not decompress legacy EM0 map data. Cause: " + e.getMessage());
                return new byte[EmageCore.MAP_SIZE];
            }
        } else if (data[0] == 'E' && data[1] == 'M' && data[2] == '1') {
            return new byte[EmageCore.MAP_SIZE];
        }

        logger.fine("Unknown legacy map format: " + (char)data[0] + (char)data[1] + (char)data[2]);
        return new byte[EmageCore.MAP_SIZE];
    }

    public static byte[] compressAnimFrames(List<byte[]> frames) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.write(MAGIC_HEADER);
            dos.writeByte(2);

            dos.writeInt(frames.size());

            int totalSize = 0;
            for (byte[] f : frames) totalSize += f.length;

            byte[] concat = new byte[totalSize];
            int offset = 0;
            for (byte[] f : frames) {
                System.arraycopy(f, 0, concat, offset, f.length);
                offset += f.length;
            }

            byte[] compressed = Zstd.compress(concat, 1);

            dos.writeInt(totalSize);
            dos.writeInt(compressed.length);
            dos.write(compressed);
            dos.close();

            return baos.toByteArray();

        } catch (Exception e) {
            logger.warning("Could not compress GIF frames. Saving uncompressed. Cause: " + e.getMessage());
            return new byte[0];
        }
    }

    public static List<byte[]> decompressAnimFrames(byte[] data) {
        List<byte[]> list = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] magic = new byte[3];
            dis.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_HEADER)) return list;

            int version = dis.readByte() & 0xFF;
            if (version != 2) return list;

            int frameCount = dis.readInt();
            int rawSize = dis.readInt();
            int compSize = dis.readInt();

            byte[] zstdData = new byte[compSize];
            dis.readFully(zstdData);

            byte[] decompressed = Zstd.decompress(zstdData, rawSize);

            int frameSize = EmageCore.MAP_SIZE;
            for (int i = 0; i < frameCount; i++) {
                byte[] frame = new byte[frameSize];
                System.arraycopy(decompressed, i * frameSize, frame, 0, frameSize);
                list.add(frame);
            }

        } catch (Exception e) {
            logger.warning("Could not decompress GIF frames. The GIF will not play. Cause: " + e.getMessage());
        }
        return list;
    }

    private static byte[] inflate(byte[] data, int expectedSize) {
        if (expectedSize <= 0 || expectedSize > EmageCore.MAP_SIZE * 2) {
            logger.warning("Invalid inflate size: " + expectedSize);
            return new byte[Math.max(0, Math.min(expectedSize, EmageCore.MAP_SIZE))];
        }

        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(data);
            byte[] result = new byte[expectedSize];
            int offset = 0;

            while (!inflater.finished() && offset < expectedSize) {
                try {
                    int count = inflater.inflate(result, offset, expectedSize - offset);
                    if (count == 0 && inflater.needsInput()) {
                        break;
                    }
                    offset += count;
                } catch (java.util.zip.DataFormatException e) {
                    logger.warning("Inflate data format error: " + e.getMessage());
                    break;
                }
            }

            return result;
        } finally {
            inflater.end();
        }
    }
}