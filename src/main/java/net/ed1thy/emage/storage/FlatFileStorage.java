package net.ed1thy.emage.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.MapFrameUpdate;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class FlatFileStorage {

    private final DatabaseManager dbManager;

    public FlatFileStorage(@NotNull DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void saveBundledFrame(int syncGroupId, int frameIndex, Map<Integer, MapFrameUpdate> updates) throws IOException {
        if (updates.isEmpty()) return;

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
                    buf.getBytes(buf.readerIndex(), bytes);
                    dos.write(bytes);
                }
            }
        }

        byte[] uncompressed = baos.toByteArray();
        byte[] compressed = new byte[(int) com.github.luben.zstd.Zstd.compressBound(uncompressed.length)];
        long compressedSize = com.github.luben.zstd.Zstd.compress(compressed, uncompressed, 3);
        byte[] finalCompressed = java.util.Arrays.copyOf(compressed, (int) compressedSize);

        String sql = "INSERT OR REPLACE INTO emage_frame_data (sync_group_id, frame_index, uncompressed_size, data) VALUES (?, ?, ?, ?)";

        executeWithRetry(() -> {
            try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, syncGroupId);
                ps.setInt(2, frameIndex);
                ps.setInt(3, uncompressed.length);
                ps.setBytes(4, finalCompressed);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IOException("Failed to save frame data to SQLite", e);
            }
        });
    }

    @NotNull
    public Map<Integer, MapFrameUpdate> loadBundledFrame(int syncGroupId, int frameIndex) throws IOException {
        String sql = "SELECT uncompressed_size, data FROM emage_frame_data WHERE sync_group_id = ? AND frame_index = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, syncGroupId);
            ps.setInt(2, frameIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int uncompressedSize = rs.getInt("uncompressed_size");
                    byte[] compressed = rs.getBytes("data");

                    if (uncompressedSize <= 0) return java.util.Collections.emptyMap();

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
            }
        } catch (SQLException e) {
            throw new IOException("Failed to load frame data from SQLite", e);
        }
        return java.util.Collections.emptyMap();
    }

    public boolean groupExists(int syncGroupId) {
        String sql = "SELECT 1 FROM emage_frame_data WHERE sync_group_id = ? LIMIT 1";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, syncGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void deleteSyncGroup(int syncGroupId) throws IOException {
        String sql = "DELETE FROM emage_frame_data WHERE sync_group_id = ?";
        executeWithRetry(() -> {
            try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, syncGroupId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IOException("Failed to delete frame data from SQLite", e);
            }
        });
    }

    public int cleanupOrphanedFrameData() throws IOException {
        String sql = "DELETE FROM emage_frame_data WHERE sync_group_id NOT IN (SELECT sync_group_id FROM emage_metadata)";
        try (Connection conn = dbManager.getConnection(); Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IOException("Failed to cleanup orphaned frame data", e);
        }
    }

    private void executeWithRetry(IoAction action) throws IOException {
        int maxRetries = 3;
        int attempt = 0;
        while (true) {
            try {
                action.run();
                return;
            } catch (IOException e) {
                if (e.getCause() instanceof SQLException sqlEx) {
                    int errCode = sqlEx.getErrorCode();
                    boolean isTransient = (errCode == 5 || errCode == 6) || "database is locked".equalsIgnoreCase(sqlEx.getMessage());

                    if (isTransient && attempt < maxRetries) {
                        attempt++;
                        try {
                            TimeUnit.MILLISECONDS.sleep(50L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                        continue;
                    }
                }
                throw e;
            }
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}