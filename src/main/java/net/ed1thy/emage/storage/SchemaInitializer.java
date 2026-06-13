package net.ed1thy.emage.storage;

import org.jetbrains.annotations.NotNull;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class SchemaInitializer {

    private final DatabaseManager dbManager;

    public SchemaInitializer(@NotNull DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void initialize() {
        String createMetadataTable = """
                CREATE TABLE IF NOT EXISTS emage_metadata (
                    sync_group_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    creator_uuid VARCHAR(36) NOT NULL,
                    source_url TEXT NOT NULL,
                    file_hash VARCHAR(64) NOT NULL,
                    columns INTEGER NOT NULL,
                    rows INTEGER NOT NULL,
                    total_frames INTEGER NOT NULL,
                    delay_ms INTEGER NOT NULL
                );
                """;

        String createMapIdsTable = """
                CREATE TABLE IF NOT EXISTS emage_maps (
                    map_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sync_group_id INTEGER NOT NULL,
                    FOREIGN KEY (sync_group_id) REFERENCES emage_metadata(sync_group_id) ON DELETE CASCADE
                );
                """;

        String createIndex = "CREATE INDEX IF NOT EXISTS idx_emage_maps_group ON emage_maps(sync_group_id);";

        try (Connection connection = dbManager.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute(createMetadataTable);
            statement.execute(createMapIdsTable);
            statement.execute(createIndex);

            try {
                statement.execute("ALTER TABLE emage_metadata ADD COLUMN file_hash VARCHAR(64) DEFAULT '';");
            } catch (SQLException ignored) {}

            statement.execute("INSERT OR IGNORE INTO sqlite_sequence (name, seq) VALUES ('emage_maps', 1000000);");
            statement.execute("UPDATE sqlite_sequence SET seq = 1000000 WHERE name = 'emage_maps' AND seq > 1000000000;");

        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize Emage SQLite schema", e);
        }
    }
}