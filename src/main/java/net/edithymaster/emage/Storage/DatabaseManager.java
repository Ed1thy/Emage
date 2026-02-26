package net.edithymaster.emage.Storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.edithymaster.emage.Processing.EmageCompression;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class DatabaseManager {

    private static final int SCHEMA_VERSION = 1;
    private final JavaPlugin plugin;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean initializing = new AtomicBoolean(false);

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Emage-Database");
        t.setDaemon(true);
        return t;
    });

    private HikariDataSource dataSource;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Boolean> initAsync() {
        if (initialized.get()) {
            return CompletableFuture.completedFuture(true);
        }

        if (!initializing.compareAndSet(false, true)) {
            return CompletableFuture.supplyAsync(() -> {
                long deadline = System.currentTimeMillis() + 30_000;
                while (!initialized.get() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return initialized.get();
            }, dbExecutor);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Class.forName("org.sqlite.JDBC");

                File dataFolder = plugin.getDataFolder();
                if (!dataFolder.exists()) {
                    dataFolder.mkdirs();
                }

                File dbFile = new File(dataFolder, "data.db");
                boolean isNewDb = !dbFile.exists();

                String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(url);
                config.setPoolName("Emage-SQLite-Pool");
                config.setMaximumPoolSize(1);
                config.setMaxLifetime(0);
                config.setIdleTimeout(0);
                config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;");

                dataSource = new HikariDataSource(config);

                if (isNewDb) {
                    createTables();
                } else {
                    migrate();
                }

                migrateFromLegacyFiles();

                initialized.set(true);
                plugin.getLogger().info("Database initialized successfully via HikariCP.");

                return true;

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not initialize the SQLite database. Map data will not be saved or loaded.", e);
                return false;
            } finally {
                initializing.set(false);
            }
        }, dbExecutor);
    }

    public boolean isReady() {
        return initialized.get() && dataSource != null && !dataSource.isClosed();
    }

    private void createTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY);");
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO schema_version (version) VALUES (?)")) {
            ps.setInt(1, SCHEMA_VERSION);
            ps.executeUpdate();
        }

        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS emage_maps (" +
                    "map_id INTEGER PRIMARY KEY, " +
                    "grid_id INTEGER NOT NULL, " +
                    "sync_id INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "data_blob BLOB NOT NULL, " +
                    "meta_json TEXT);");

            stmt.execute("CREATE TABLE IF NOT EXISTS recycled_ids (" +
                    "map_id INTEGER PRIMARY KEY);");
        }
    }

    private void migrate() throws SQLException {
        int currentVersion = 0;
        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version")) {
            if (rs.next()) currentVersion = rs.getInt(1);
        }

        if (currentVersion < SCHEMA_VERSION) {
            plugin.getLogger().info("Upgrading database schema to version " + SCHEMA_VERSION + ".");
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ps = connection.prepareStatement(
                         "UPDATE schema_version SET version = ?")) {
                ps.setInt(1, SCHEMA_VERSION);
                ps.execute();
            }
        }
    }

    private void migrateFromLegacyFiles() {
        File mapsFolder = new File(plugin.getDataFolder(), "maps");
        if (!mapsFolder.exists() || !mapsFolder.isDirectory()) return;

        plugin.getLogger().info("Migrating legacy file storage to SQLite.");

        File[] files = mapsFolder.listFiles();
        if (files == null) return;

        int count = 0;

        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                String sql = "INSERT OR REPLACE INTO emage_maps (map_id, grid_id, sync_id, type, data_blob) VALUES (?, ?, 0, 'STATIC', ?)";

                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    for (File file : files) {
                        try {
                            if (file.getName().endsWith(".emap")) {
                                int mapId = Integer.parseInt(file.getName().replace(".emap", ""));
                                byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                                byte[] decompressed = EmageCompression.decompressLegacy(data);

                                ps.setInt(1, mapId);
                                ps.setLong(2, 0);
                                ps.setBytes(3, decompressed);
                                ps.addBatch();
                                count++;

                                if (count % 100 == 0) {
                                    ps.executeBatch();
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not migrate legacy map file: " + file.getName());
                        }
                    }
                    ps.executeBatch();
                }
                connection.commit();

                if (count > 0) {
                    plugin.getLogger().info("Migrated " + count + " legacy maps to SQLite.");
                    File backupTarget = new File(plugin.getDataFolder(), "maps_backup");
                    if (!mapsFolder.renameTo(backupTarget)) {
                        plugin.getLogger().warning("Could not rename legacy maps folder to maps_backup.");
                    }
                }

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Legacy map migration threw an unexpected error.", e);
                connection.rollback();
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get DB connection for legacy migration.", e);
        }
    }

    public CompletableFuture<Void> saveMapAsync(int mapId, long gridId, byte[] data) {
        if (!isReady()) {
            plugin.getLogger().warning("Cannot save map " + mapId + ": database not initialized.");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO emage_maps (map_id, grid_id, sync_id, type, data_blob) VALUES (?, ?, 0, 'STATIC', ?)";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, mapId);
                ps.setLong(2, gridId);
                ps.setBytes(3, data);
                ps.execute();

                removeFromPoolInternal(connection, mapId);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not save static map " + mapId + " to the database.", e);
            }
        }, dbExecutor);
    }

    public CompletableFuture<Void> saveAnimMapAsync(int mapId, long syncId, byte[] framesData, String metaJson) {
        if (!isReady()) {
            plugin.getLogger().warning("Cannot save animation " + mapId + ": database not initialized.");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO emage_maps (map_id, grid_id, sync_id, type, data_blob, meta_json) VALUES (?, 0, ?, 'ANIM', ?, ?)";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, mapId);
                ps.setLong(2, syncId);
                ps.setBytes(3, framesData);
                ps.setString(4, metaJson);
                ps.execute();

                removeFromPoolInternal(connection, mapId);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not save GIF " + mapId + " to the database.", e);
            }
        }, dbExecutor);
    }

    public CompletableFuture<Map<Integer, MapData>> loadAllMapsAsync() {
        if (!isReady()) {
            plugin.getLogger().warning("Cannot load maps: database not initialized.");
            return CompletableFuture.completedFuture(new HashMap<>());
        }

        return CompletableFuture.supplyAsync(() -> {
            Map<Integer, MapData> r = new HashMap<>();
            String sql = "SELECT map_id, grid_id, sync_id, type, data_blob, meta_json FROM emage_maps";
            try (Connection connection = dataSource.getConnection();
                 Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    int id = rs.getInt("map_id");
                    long gridId = rs.getLong("grid_id");
                    long syncId = rs.getLong("sync_id");
                    String type = rs.getString("type");
                    byte[] blob = rs.getBytes("data_blob");
                    String meta = rs.getString("meta_json");
                    r.put(id, new MapData(id, gridId, syncId, type, blob, meta));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not load maps from the database.", e);
            }
            return r;
        }, dbExecutor);
    }

    public CompletableFuture<Queue<Integer>> loadAllRecycledIdsAsync() {
        if (!isReady()) {
            return CompletableFuture.completedFuture(new ConcurrentLinkedQueue<>());
        }

        return CompletableFuture.supplyAsync(() -> {
            Queue<Integer> q = new ConcurrentLinkedQueue<>();
            String sql = "SELECT map_id FROM recycled_ids";
            try (Connection connection = dataSource.getConnection();
                 Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    q.add(rs.getInt("map_id"));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not load the pool of recycled map IDs.", e);
            }
            return q;
        }, dbExecutor);
    }

    private void removeFromPoolInternal(Connection connection, int mapId) {
        String sql = "DELETE FROM recycled_ids WHERE map_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, mapId);
            ps.execute();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not remove map ID " + mapId + " from the recycled pool.", e);
        }
    }

    public CompletableFuture<Void> removeFromPoolAsync(int mapId) {
        if (!isReady()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                removeFromPoolInternal(connection, mapId);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not acquire connection for map pool removal.", e);
            }
        }, dbExecutor);
    }

    public CompletableFuture<Void> addToPoolAsync(int mapId) {
        if (!isReady()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR IGNORE INTO recycled_ids (map_id) VALUES (?)";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, mapId);
                ps.execute();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not add map ID " + mapId + " to the recycled pool.", e);
            }
        }, dbExecutor);
    }

    public CompletableFuture<Void> deleteMapsAsync(Set<Integer> mapIds) {
        if (mapIds.isEmpty() || !isReady()) {
            return CompletableFuture.completedFuture(null);
        }

        Set<Integer> idsCopy = new HashSet<>(mapIds);

        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean originalAutoCommit = connection.getAutoCommit();
                try {
                    connection.setAutoCommit(false);
                    String sql = "DELETE FROM emage_maps WHERE map_id = ?";
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        for (int id : idsCopy) {
                            ps.setInt(1, id);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    connection.commit();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not delete maps from the database.", e);
                    connection.rollback();
                } finally {
                    connection.setAutoCommit(originalAutoCommit);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to acquire connection or manage transaction.", e);
            }
        }, dbExecutor);
    }

    public void shutdown() {
        initialized.set(false);

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void saveMap(int mapId, long gridId, byte[] data) {
        saveMapAsync(mapId, gridId, data);
    }

    public void saveAnimMap(int mapId, long syncId, byte[] framesData, String metaJson) {
        saveAnimMapAsync(mapId, syncId, framesData, metaJson);
    }

    public void removeFromPool(int mapId) {
        removeFromPoolAsync(mapId);
    }

    public void addToPool(int mapId) {
        addToPoolAsync(mapId);
    }

    public void deleteMaps(Set<Integer> mapIds) {
        deleteMapsAsync(mapIds);
    }

    public static class MapData {
        public final int id;
        public final long gridId;
        public final long syncId;
        public final boolean isAnim;
        public final byte[] data;
        public final String metaJson;

        public MapData(int id, long gridId, long syncId, String type, byte[] data, String metaJson) {
            this.id = id;
            this.gridId = gridId;
            this.syncId = syncId;
            this.isAnim = "ANIM".equalsIgnoreCase(type);
            this.data = data;
            this.metaJson = metaJson;
        }
    }
}