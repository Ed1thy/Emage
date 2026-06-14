package net.ed1thy.emage.storage;

import net.ed1thy.emage.model.MapMetadata;
import org.bukkit.entity.ItemFrame;
import org.jetbrains.annotations.NotNull;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MapMetadataRepository {

    private final DatabaseManager dbManager;

    public MapMetadataRepository(@NotNull DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void addPlacedFrames(int syncGroupId, List<ItemFrame> frames) throws SQLException {
        String sql = "INSERT OR IGNORE INTO emage_placed_frames (frame_uuid, sync_group_id, world_uuid, chunk_x, chunk_z) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ItemFrame frame : frames) {
                ps.setString(1, frame.getUniqueId().toString());
                ps.setInt(2, syncGroupId);
                ps.setString(3, frame.getWorld().getUID().toString());
                ps.setInt(4, frame.getLocation().getChunk().getX());
                ps.setInt(5, frame.getLocation().getChunk().getZ());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void addPlacedFrame(int syncGroupId, ItemFrame frame) throws SQLException {
        addPlacedFrames(syncGroupId, java.util.Collections.singletonList(frame));
    }

    public void removePlacedFrames(List<ItemFrame> frames) throws SQLException {
        String sql = "DELETE FROM emage_placed_frames WHERE frame_uuid = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ItemFrame frame : frames) {
                ps.setString(1, frame.getUniqueId().toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void removePlacedFrameByUUID(UUID frameUuid) throws SQLException {
        String sql = "DELETE FROM emage_placed_frames WHERE frame_uuid = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, frameUuid.toString());
            ps.executeUpdate();
        }
    }

    public int countPlacedFrames(int syncGroupId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM emage_placed_frames WHERE sync_group_id = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, syncGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    public List<UUID> getPlacedFramesInChunk(UUID worldUuid, int chunkX, int chunkZ) throws SQLException {
        List<UUID> uuids = new java.util.ArrayList<>();
        String sql = "SELECT frame_uuid FROM emage_placed_frames WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, worldUuid.toString());
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) uuids.add(UUID.fromString(rs.getString(1)));
            }
        }
        return uuids;
    }

    public int getGroupIdByFrameUUID(UUID frameUuid) throws SQLException {
        String sql = "SELECT sync_group_id FROM emage_placed_frames WHERE frame_uuid = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, frameUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
    }

    @NotNull
    public MapMetadata createSyncGroup(@NotNull UUID creatorUuid, @NotNull String sourceUrl,
                                       int columns, int rows, int totalFrames, int delayMs) throws SQLException {
        String sql = "INSERT INTO emage_metadata (creator_uuid, source_url, columns, rows, total_frames, delay_ms) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, creatorUuid.toString());
            ps.setString(2, sourceUrl);
            ps.setInt(3, columns);
            ps.setInt(4, rows);
            ps.setInt(5, totalFrames);
            ps.setInt(6, delayMs);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return new MapMetadata(rs.getInt(1), creatorUuid, sourceUrl, columns, rows, totalFrames, delayMs);
                else throw new SQLException("Failed to retrieve auto-generated sync_group_id from SQLite.");
            }
        }
    }

    @NotNull
    public List<Integer> allocateVirtualMapIds(int syncGroupId, int amount) throws SQLException {
        List<Integer> allocatedIds = new ArrayList<>(amount);
        String sql = "INSERT INTO emage_maps (sync_group_id) VALUES (?)";
        Connection conn = null;
        boolean initialAutoCommit = true;
        try {
            conn = dbManager.getConnection();
            initialAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < amount; i++) {
                    ps.setInt(1, syncGroupId);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) allocatedIds.add(rs.getInt(1));
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { e.addSuppressed(ex); }
            throw e;
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(initialAutoCommit); } catch (SQLException ignored) {}
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
        if (allocatedIds.size() != amount) throw new SQLException("Failed to retrieve all auto-generated map IDs.");
        return allocatedIds;
    }

    @NotNull
    public Optional<MapMetadata> getMetadataByMapId(int mapId) throws SQLException {
        String sql = "SELECT m.* FROM emage_metadata m JOIN emage_maps ids ON m.sync_group_id = ids.sync_group_id WHERE ids.map_id = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, mapId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(new MapMetadata(
                        rs.getInt("sync_group_id"), UUID.fromString(rs.getString("creator_uuid")),
                        rs.getString("source_url"), rs.getInt("columns"), rs.getInt("rows"),
                        rs.getInt("total_frames"), rs.getInt("delay_ms")
                ));
            }
        }
        return Optional.empty();
    }

    @NotNull
    public java.util.Set<Integer> getAllSyncGroupIDs() throws SQLException {
        java.util.Set<Integer> activeIds = new java.util.HashSet<>();
        String sql = "SELECT sync_group_id FROM emage_metadata";
        try (Connection conn = dbManager.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) activeIds.add(rs.getInt(1));
        }
        return activeIds;
    }

    @NotNull
    public MapMetadata createSyncGroup(@NotNull UUID creatorUuid, @NotNull String sourceUrl, @NotNull String fileHash,
                                       int columns, int rows, int totalFrames, int delayMs) throws SQLException {
        String sql = "INSERT INTO emage_metadata (creator_uuid, source_url, file_hash, columns, rows, total_frames, delay_ms) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, creatorUuid.toString());
            ps.setString(2, sourceUrl);
            ps.setString(3, fileHash);
            ps.setInt(4, columns);
            ps.setInt(5, rows);
            ps.setInt(6, totalFrames);
            ps.setInt(7, delayMs);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return new MapMetadata(rs.getInt(1), creatorUuid, sourceUrl, columns, rows, totalFrames, delayMs);
                else throw new SQLException("Failed to retrieve auto-generated ID.");
            }
        }
    }

    @NotNull
    public Optional<MapMetadata> getMetadataByHash(@NotNull String hash, int columns, int rows) throws SQLException {
        String sql = "SELECT * FROM emage_metadata WHERE file_hash = ? AND columns = ? AND rows = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setInt(2, columns);
            ps.setInt(3, rows);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(new MapMetadata(
                        rs.getInt("sync_group_id"), UUID.fromString(rs.getString("creator_uuid")),
                        rs.getString("source_url"), rs.getInt("columns"), rs.getInt("rows"),
                        rs.getInt("total_frames"), rs.getInt("delay_ms")
                ));
            }
        }
        return Optional.empty();
    }

    @NotNull
    public Optional<MapMetadata> getMetadataByUrl(@NotNull String url, int columns, int rows) throws SQLException {
        String sql = "SELECT * FROM emage_metadata WHERE source_url = ? AND columns = ? AND rows = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, url);
            ps.setInt(2, columns);
            ps.setInt(3, rows);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(new MapMetadata(
                        rs.getInt("sync_group_id"), UUID.fromString(rs.getString("creator_uuid")),
                        rs.getString("source_url"), rs.getInt("columns"), rs.getInt("rows"),
                        rs.getInt("total_frames"), rs.getInt("delay_ms")
                ));
            }
        }
        return Optional.empty();
    }

    @NotNull
    public List<Integer> getMapIdsForGroup(int syncGroupId) throws SQLException {
        List<Integer> ids = new java.util.ArrayList<>();
        String sql = "SELECT map_id FROM emage_maps WHERE sync_group_id = ? ORDER BY map_id ASC";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, syncGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt("map_id"));
            }
        }
        return ids;
    }

    public void deleteSyncGroup(int syncGroupId) throws SQLException {
        String sql = "DELETE FROM emage_metadata WHERE sync_group_id = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, syncGroupId);
            ps.executeUpdate();
        }
    }
}