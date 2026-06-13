package net.ed1thy.emage.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.MapFrameUpdate;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class FlatFileStorage {

    private static final int MAGIC_V3 = -998;
    private final Path dataFolder;

    public FlatFileStorage(@NotNull ConfigManager configManager) {
        this.dataFolder = configManager.mapDataFolder.toPath();
    }

    public void saveBundledFrame(int syncGroupId, int frameIndex, Map<Integer, MapFrameUpdate> updates) throws IOException {
        if (updates.isEmpty()) return;

        Path groupDir = dataFolder.resolve(String.valueOf(syncGroupId));
        if (!Files.exists(groupDir)) Files.createDirectories(groupDir);

        Path file = groupDir.resolve("f" + frameIndex + ".emz");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(updates.size());
            for (Map.Entry<Integer, MapFrameUpdate> entry : updates.entrySet()) {
                dos.writeInt(entry.getKey());
                MapFrameUpdate update = entry.getValue();
                dos.writeInt(update.parts().length);
                for (DeltaFrame df : update.parts()) {
                    ByteBuf buf = df.packetBuf();
                    int len = buf.readableBytes();
                    dos.writeInt(len);
                    byte[] bytes = new byte[len];
                    buf.getBytes(0, bytes);
                    dos.write(bytes);
                }
            }
        }

        byte[] uncompressed = baos.toByteArray();
        byte[] compressed = new byte[(int) com.github.luben.zstd.Zstd.compressBound(uncompressed.length)];
        long compressedSize = com.github.luben.zstd.Zstd.compress(compressed, uncompressed, 7);

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(MAGIC_V3);
            out.writeInt(uncompressed.length);
            out.write(compressed, 0, (int) compressedSize);
        }
    }

    @NotNull
    public Map<Integer, MapFrameUpdate> loadBundledFrame(int syncGroupId, int frameIndex) throws IOException {
        Path file = dataFolder.resolve(String.valueOf(syncGroupId)).resolve("f" + frameIndex + ".emz");
        if (!Files.exists(file)) return java.util.Collections.emptyMap();

        byte[] fileBytes = Files.readAllBytes(file);
        ByteBuffer bb = ByteBuffer.wrap(fileBytes);
        int magic = bb.getInt();

        if (magic == MAGIC_V3) {
            int uncompressedSize = bb.getInt();
            byte[] compressed = new byte[bb.remaining()];
            bb.get(compressed);

            byte[] uncompressed = new byte[uncompressedSize];
            com.github.luben.zstd.Zstd.decompress(uncompressed, compressed);

            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(uncompressed));
            int numUpdates = dis.readInt();
            Map<Integer, MapFrameUpdate> resultMap = new java.util.HashMap<>();

            for (int i = 0; i < numUpdates; i++) {
                int mapId = dis.readInt();
                int numParts = dis.readInt();
                DeltaFrame[] parts = new DeltaFrame[numParts];
                for (int p = 0; p < numParts; p++) {
                    int len = dis.readInt();
                    byte[] bytes = new byte[len];
                    dis.readFully(bytes);

                    ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(len);
                    buf.writeBytes(bytes);
                    parts[p] = new DeltaFrame(frameIndex, mapId, buf);
                }
                resultMap.put(mapId, new MapFrameUpdate(parts));
            }
            return resultMap;
        }
        return java.util.Collections.emptyMap();
    }

    public boolean groupExists(int syncGroupId) { return Files.exists(dataFolder.resolve(String.valueOf(syncGroupId))); }

    public void deleteSyncGroup(int syncGroupId) throws IOException {
        Path groupDir = dataFolder.resolve(String.valueOf(syncGroupId));
        if (Files.exists(groupDir)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(groupDir)) {
                paths.map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    public int cleanupOrphanedDirectories(@NotNull java.util.Set<Integer> activeGroupIDs) {
        if (!Files.exists(dataFolder)) return 0;
        int deletedCount = 0;
        try (java.util.stream.Stream<Path> stream = Files.list(dataFolder)) {
            for (Path dir : stream.toList()) {
                if (Files.isDirectory(dir)) {
                    try {
                        int groupId = Integer.parseInt(dir.getFileName().toString());
                        if (!activeGroupIDs.contains(groupId)) {
                            deleteSyncGroup(groupId);
                            deletedCount++;
                        }
                    } catch (Exception ignored) { }
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
        return deletedCount;
    }
}