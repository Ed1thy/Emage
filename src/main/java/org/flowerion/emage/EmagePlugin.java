package org.flowerion.emage;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.flowerion.emage.Command.EmageCommand;
import org.flowerion.emage.Config.EmageConfig;
import org.flowerion.emage.Manager.EmageManager;
import org.flowerion.emage.Processing.EmageCore;
import org.flowerion.emage.Render.GifRenderer;
import org.flowerion.emage.Util.GifCache;
import org.flowerion.emage.Util.UpdateChecker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmagePlugin extends JavaPlugin {

    private EmageManager manager;
    private UpdateChecker updateChecker;
    private EmageConfig emageConfig;
    private final Pattern hexPattern = Pattern.compile("&#([a-fA-F0-9]{6})");

    private static final String PREFIX = "&#321212&lE&#3E1111&lm&#4A0F0F&la&#560E0E&lg&#620C0C&le &8&l• ";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Initialize color system
        getLogger().info("Initializing color system...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            EmageCore.initColorSystem();
            getLogger().info("Color system initialized.");
        });

        emageConfig = new EmageConfig(this);

        GifRenderer.init(this, emageConfig);

        manager = new EmageManager(this, emageConfig);
        Bukkit.getPluginManager().registerEvents(manager, this);

        var cmd = getCommand("emage");
        if (cmd != null) {
            EmageCommand exec = new EmageCommand(this, manager);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }

        manager.loadAllMaps();

        if (getConfig().getBoolean("check-updates", true)) {
            updateChecker = new UpdateChecker(this, "EdithyLikesToCode", "Emage");
        }

        getLogger().info("Emage v" + getDescription().getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
        // Stop renderer
        GifRenderer.stop();

        // Shutdown core
        EmageCore.shutdown();

        // Clear GIF cache
        int cached = GifCache.clearCache();
        if (cached > 0) {
            getLogger().info("Cleared " + cached + " cached GIFs.");
        }

        // Shutdown manager
        if (manager != null) {
            manager.shutdown();
        }

        getLogger().info("Emage disabled!");
    }

    public String msg(String key, String... placeholders) {
        String message = getConfig().getString("messages." + key);
        if (message == null) return ChatColor.RED + "Missing: " + key;

        message = PREFIX + message;

        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1] != null ? placeholders[i + 1] : "";
            message = message.replace(placeholders[i], value);
        }

        return colorize(message);
    }

    public String hardcodedMsg(String key, String... placeholders) {
        String message = getHardcodedMessage(key);
        if (message == null) return ChatColor.RED + "Missing: " + key;

        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1] != null ? placeholders[i + 1] : "";
            message = message.replace(placeholders[i], value);
        }

        return colorize(message);
    }

    public String getPrefix() {
        return colorize(PREFIX);
    }

    private String getHardcodedMessage(String key) {
        return switch (key) {
            case "usage" -> PREFIX + "&cUsage: /emage <url> [size] [--quality]";
            case "no-frame" -> PREFIX + "&cPlease look at an Item Frame!";
            case "invalid-size" -> PREFIX + "&cInvalid size! Use format: 3x3 or just 3";
            case "invalid-url" -> PREFIX + "&cPlease provide a valid URL";
            case "no-perm" -> PREFIX + "&cYou don't have permission to do that!";
            case "error" -> PREFIX + "&cFailed to load image: &7<error>";

            case "help-header" -> "&7&m----------&r &6Emage Help &7&m----------";
            case "help-url" -> "&6/emage <url> [size] &7- &fApply image to item frames";
            case "help-quality" -> "&7  Quality: &e--fast&7, &e--balanced&7, &e--high";
            case "help-aliases" -> "&7  Flags: &e-f&7, &e-b&7, &e-h&7, &e--nocache";
            case "help-migrate" -> "&6/emage migrate &7- &fConvert old format files";
            case "help-reload" -> "&6/emage reload &7- &fReload configuration";
            case "help-cleanup" -> "&6/emage cleanup &7- &fDelete unused map files";
            case "help-stats" -> "&6/emage stats &7- &fShow storage statistics";
            case "help-update" -> "&6/emage update &7- &fCheck for updates";
            case "help-perf" -> "&6/emage perf &7- &fShow performance status";
            case "help-footer" -> "&7&m---------------------------------";

            case "update-checking" -> PREFIX + "&7Checking for updates...";
            case "update-latest" -> PREFIX + "&aYou are running the latest version! &7(v<version>)";
            case "update-disabled" -> PREFIX + "&cUpdate checking is disabled in config.";
            case "update-available" -> PREFIX + "&aA new update is available! &7(v<version>)";

            default -> null;
        };
    }

    public String colorize(String message) {
        Matcher matcher = hexPattern.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            try {
                String hex = matcher.group(1);
                matcher.appendReplacement(buffer, net.md_5.bungee.api.ChatColor.of("#" + hex).toString());
            } catch (Exception ignored) {}
        }
        matcher.appendTail(buffer);

        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public EmageManager getManager() {
        return manager;
    }

    public EmageConfig getEmageConfig() {
        return emageConfig;
    }
}