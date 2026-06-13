package net.ed1thy.emage.storage;

import net.ed1thy.emage.model.MapMetadata;
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
                if (rs.next()) {
                    int syncGroupId = rs.getInt(1);
                    return new MapMetadata(syncGroupId, creatorUuid, sourceUrl, columns, rows, totalFrames, delayMs);
                } else {
                    throw new SQLException("Failed to retrieve auto-generated sync_group_id from SQLite.");
                }
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
                        if (rs.next()) {
                            allocatedIds.add(rs.getInt(1));
                        }
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ex) { e.addSuppressed(ex); }
            }
            throw e;
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(initialAutoCommit); } catch (SQLException ignored) {}
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }

        if (allocatedIds.size() != amount) {
            throw new SQLException("Failed to retrieve all auto-generated map IDs.");
        }

        return allocatedIds;
    }

    @NotNull
    public Optional<MapMetadata> getMetadataByMapId(int mapId) throws SQLException {
        String sql = "SELECT m.* FROM emage_metadata m JOIN emage_maps ids ON m.sync_group_id = ids.sync_group_id WHERE ids.map_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, mapId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new MapMetadata(
                            rs.getInt("sync_group_id"),
                            UUID.fromString(rs.getString("creator_uuid")),
                            rs.getString("source_url"),
                            rs.getInt("columns"),
                            rs.getInt("rows"),
                            rs.getInt("total_frames"),
                            rs.getInt("delay_ms")
                    ));
                }
            }
        }
        return Optional.empty();
    }

    @NotNull
    public java.util.Set<Integer> getAllSyncGroupIDs() throws SQLException {
        java.util.Set<Integer> activeIds = new java.util.HashSet<>();
        String sql = "SELECT sync_group_id FROM emage_metadata";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                activeIds.add(rs.getInt(1));
            }
        }
        return activeIds;
    }

    @NotNull
    public MapMetadata createSyncGroup(@NotNull UUID creatorUuid, @NotNull String sourceUrl, @NotNull String fileHash,
                                       int columns, int rows, int totalFrames, int delayMs) throws SQLException {
        String sql = "INSERT INTO emage_metadata (creator_uuid, source_url, file_hash, columns, rows, total_frames, delay_ms) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, creatorUuid.toString());
            ps.setString(2, sourceUrl);
            ps.setString(3, fileHash);
            ps.setInt(4, columns);
            ps.setInt(5, rows);
            ps.setInt(6, totalFrames);
            ps.setInt(7, delayMs);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return new MapMetadata(rs.getInt(1), creatorUuid, sourceUrl, columns, rows, totalFrames, delayMs);
                } else {
                    throw new SQLException("Failed to retrieve auto-generated ID.");
                }
            }
        }
    }

    @NotNull
    public Optional<MapMetadata> getMetadataByHash(@NotNull String hash, int columns, int rows) throws SQLException {
        String sql = "SELECT * FROM emage_metadata WHERE file_hash = ? AND columns = ? AND rows = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setInt(2, columns);
            ps.setInt(3, rows);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new MapMetadata(
                            rs.getInt("sync_group_id"), UUID.fromString(rs.getString("creator_uuid")),
                            rs.getString("source_url"), rs.getInt("columns"), rs.getInt("rows"),
                            rs.getInt("total_frames"), rs.getInt("delay_ms")
                    ));
                }
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