package net.ed1thy.emage.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.config.ConfigManager;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final HikariDataSource dataSource;

    public DatabaseManager(@NotNull Emage plugin, @NotNull ConfigManager configManager) {
        File dbFile = new File(plugin.getDataFolder(), configManager.dbFileName);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        hikariConfig.setPoolName("Emage-SQLite-Pool");
        hikariConfig.setMaximumPoolSize(configManager.dbPoolSize);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setIdleTimeout(0);
        hikariConfig.setMaxLifetime(0);

        hikariConfig.addDataSourceProperty("journal_mode", "WAL");
        hikariConfig.addDataSourceProperty("synchronous", "NORMAL");
        hikariConfig.addDataSourceProperty("mmap_size", "33554432");
        hikariConfig.addDataSourceProperty("cache_size", "-2000");
        hikariConfig.addDataSourceProperty("temp_store", "MEMORY");
        hikariConfig.addDataSourceProperty("busy_timeout", "5000");
        hikariConfig.setConnectionInitSql("PRAGMA foreign_keys = ON;");

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    @NotNull
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void runWalCheckpoint() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
        } catch (SQLException ignored) {}
    }

    public void closePool() {
        if (dataSource != null && !dataSource.isClosed()) {
            runWalCheckpoint();
            dataSource.close();
        }
    }
}