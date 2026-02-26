package net.edithymaster.emage.Config;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

public final class ConfigManager {

    private static final int CURRENT_CONFIG_VERSION = 1;

    public static void initialize(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        if (!configFile.exists()) {
            plugin.saveDefaultConfig();
            return;
        }

        FileConfiguration existingConfig = YamlConfiguration.loadConfiguration(configFile);
        int version = existingConfig.getInt("config-version", 0);

        if (version == CURRENT_CONFIG_VERSION) {
            backfillMissingKeys(plugin, existingConfig, configFile);
            return;
        }

        plugin.getLogger().info("Migrating configuration from version " + version + " to " + CURRENT_CONFIG_VERSION + ".");

        try {
            createBackupAsync(plugin, configFile, version);

            String defaultContent = readResourceToString(plugin, "config.yml");
            if (defaultContent == null) {
                plugin.getLogger().warning("Could not read default config.yml from JAR. Using existing config.");
                return;
            }

            YamlConfiguration defaultConfig = new YamlConfiguration();
            defaultConfig.loadFromString(defaultContent);

            mergeConfigs(existingConfig, defaultConfig);

            defaultConfig.set("config-version", CURRENT_CONFIG_VERSION);

            String mergedContent = defaultConfig.saveToString();

            writeConfigSafely(configFile, mergedContent);

            plugin.getLogger().info("Configuration migrated successfully.");

            plugin.reloadConfig();

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not migrate the configuration file. The existing configuration will be used.", e);
        }
    }

    private static void writeConfigSafely(File configFile, String content) throws IOException {
        Path configPath = configFile.toPath();
        Path tempPath = configPath.resolveSibling(configFile.getName() + ".tmp");

        Files.writeString(tempPath, content, StandardCharsets.UTF_8);

        try {
            Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void createBackupAsync(JavaPlugin plugin, File configFile, int oldVersion) {
        byte[] content;
        try {
            content = Files.readAllBytes(configFile.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read config for backup: " + e.getMessage());
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File backupDir = new File(plugin.getDataFolder(), "backups");
                if (!backupDir.exists()) {
                    backupDir.mkdirs();
                }

                String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                File backupFile = new File(backupDir, "config_v" + oldVersion + "_" + timestamp + ".yml");

                Files.write(backupFile.toPath(), content);
                plugin.getLogger().info("Backed up previous configuration to " + backupFile.getName());
            } catch (IOException e) {
                plugin.getLogger().warning("Could not create config backup: " + e.getMessage());
            }
        });
    }

    private static void backfillMissingKeys(JavaPlugin plugin, FileConfiguration config, File configFile) {
        boolean changed = false;

        try {
            String defaultContent = readResourceToString(plugin, "config.yml");
            if (defaultContent == null) return;

            YamlConfiguration defaultConfig = new YamlConfiguration();
            defaultConfig.loadFromString(defaultContent);

            for (String key : defaultConfig.getKeys(true)) {
                if (defaultConfig.isConfigurationSection(key)) continue;

                if (!config.isSet(key)) {
                    config.set(key, defaultConfig.get(key));
                    changed = true;
                }
            }

            if (changed) {
                String content = ((YamlConfiguration) config).saveToString();
                try {
                    writeConfigSafely(configFile, content);
                    plugin.getLogger().info("Added missing configuration keys.");
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not save backfilled config: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not backfill missing config keys: " + e.getMessage());
        }
    }

    private static void mergeConfigs(FileConfiguration source, YamlConfiguration target) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) continue;

            if (target.contains(key)) {
                target.set(key, source.get(key));
            }
        }
    }

    private static String readResourceToString(JavaPlugin plugin, String resourceName) {
        try (InputStream is = plugin.getResource(resourceName)) {
            if (is == null) {
                plugin.getLogger().warning("Resource not found in JAR: " + resourceName);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read resource " + resourceName + ": " + e.getMessage());
            return null;
        }
    }
}