package net.ed1thy.emage.config;

import net.ed1thy.emage.Emage;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public class ConfigManager {

    private final Emage plugin;
    private FileConfiguration config;
    public boolean checkForUpdates;

    public int maxPacketsPerTick;

    public String dbFileName;
    public int dbPoolSize;
    public String mapDataDirectory;
    public File mapDataFolder;

    public int maxGifFrames;
    public int maxGifGridSize;
    public int maxImageGridSize;

    public int maxFileSizeMb;
    public int connectTimeoutSeconds;
    public int readTimeoutSeconds;
    public int maxRedirects;
    public boolean blockInternalUrls;

    public int cacheMaxMemoryMb;
    public int cacheExpireMinutes;

    public int cooldownSeconds;
    public int maxConcurrentTasks;

    public ConfigManager(@NotNull Emage plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.checkForUpdates = config.getBoolean("updates.check-for-updates", true);

        this.maxPacketsPerTick = config.getInt("performance.max-packets-per-tick", 64);

        this.dbFileName = config.getString("storage.database.file-name", "emage_meta.db");
        this.dbPoolSize = config.getInt("storage.database.pool-size", 2);
        this.mapDataDirectory = config.getString("storage.map-data-directory", "map_data");
        this.mapDataFolder = new File(plugin.getDataFolder(), mapDataDirectory);
        if (!mapDataFolder.exists()) {
            mapDataFolder.mkdirs();
        }

        this.maxGifFrames = config.getInt("quality.max-gif-frames", 240);
        this.maxGifGridSize = config.getInt("quality.max-gif-grid-size", 5);
        this.maxImageGridSize = config.getInt("quality.max-image-grid-size", 16);

        this.maxFileSizeMb = config.getInt("downloads.max-file-size-mb", 50);
        this.connectTimeoutSeconds = config.getInt("downloads.connect-timeout-seconds", 10);
        this.readTimeoutSeconds = config.getInt("downloads.read-timeout-seconds", 30);
        this.maxRedirects = config.getInt("downloads.max-redirects", 5);
        this.blockInternalUrls = config.getBoolean("downloads.block-internal-urls", true);

        this.cacheMaxMemoryMb = config.getInt("cache.max-memory-mb", 100);
        this.cacheExpireMinutes = config.getInt("cache.expire-minutes", 30);

        this.cooldownSeconds = config.getInt("rate-limits.cooldown-seconds", 5);
        this.maxConcurrentTasks = config.getInt("rate-limits.max-concurrent-tasks", 3);
    }

    @NotNull
    public FileConfiguration getRawConfig() {
        return config;
    }
}