package org.flowerion.emage.Util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateChecker implements Listener {

    private final JavaPlugin plugin;
    private final String currentVersion;
    private final String githubOwner;
    private final String githubRepo;

    private static final String GITHUB_URL = "https://github.com/EdithyLikesToCode/Emage";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/emage";
    private static final String SPIGOTMC_URL = "https://www.spigotmc.org/resources/emage.130410/";

    private String latestVersion = null;
    private String downloadUrl = null;
    private String releaseNotes = null;
    private boolean updateAvailable = false;

    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;
    private long lastCheck = 0;

    public UpdateChecker(JavaPlugin plugin, String githubOwner, String githubRepo) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;

        Bukkit.getPluginManager().registerEvents(this, plugin);

        checkForUpdates();

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkForUpdates,
                20 * 60 * 60,
                20 * 60 * 60 * 6
        );
    }

    public CompletableFuture<Boolean> checkForUpdates() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long now = System.currentTimeMillis();
                if (now - lastCheck < CHECK_INTERVAL_MS && latestVersion != null) {
                    return updateAvailable;
                }
                lastCheck = now;

                String apiUrl = String.format(
                        "https://api.github.com/repos/%s/%s/releases/latest",
                        githubOwner, githubRepo
                );

                HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                connection.setRequestProperty("User-Agent", "Emage-UpdateChecker");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    plugin.getLogger().fine("Update check failed: HTTP " + responseCode);
                    return false;
                }

                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                String json = response.toString();
                latestVersion = extractJsonValue(json, "tag_name");
                downloadUrl = extractJsonValue(json, "html_url");
                releaseNotes = extractJsonValue(json, "body");

                if (latestVersion != null) {
                    latestVersion = latestVersion.replaceFirst("^[vV]", "");
                }

                updateAvailable = isNewerVersion(latestVersion, currentVersion);

                if (updateAvailable) {
                    plugin.getLogger().info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    plugin.getLogger().info("A new update is available: v" + latestVersion);
                    plugin.getLogger().info("Current version: v" + currentVersion);
                    plugin.getLogger().info("GitHub: " + GITHUB_URL);
                    plugin.getLogger().info("Modrinth: " + MODRINTH_URL);
                    plugin.getLogger().info("SpigotMC: " + SPIGOTMC_URL);
                    plugin.getLogger().info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                } else {
                    plugin.getLogger().fine("Emage is up to date (v" + currentVersion + ")");
                }

                return updateAvailable;

            } catch (Exception e) {
                plugin.getLogger().fine("Update check failed: " + e.getMessage());
                return false;
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!player.hasPermission("emage.admin") && !player.isOp()) {
            return;
        }

        if (!updateAvailable || latestVersion == null) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sendUpdateNotification(player);
            }
        }, 60L);
    }

    public void sendUpdateNotification(Player player) {
        if (!updateAvailable || latestVersion == null) {
            return;
        }

        player.sendMessage("");
        player.sendMessage("§8§l┃ §b§lEmage Update Available!");
        player.sendMessage("§8§l┃");
        player.sendMessage("§8§l┃ §7Current: §cv" + currentVersion);
        player.sendMessage("§8§l┃ §7Latest: §av" + latestVersion);
        player.sendMessage("§8§l┃");
        player.sendMessage("§8§l┃ §7Download from:");

        sendClickableLink(player, "GitHub", GITHUB_URL, "§7Open GitHub releases page");
        sendClickableLink(player, "Modrinth", MODRINTH_URL, "§7Open Modrinth page");
        sendClickableLink(player, "SpigotMC", SPIGOTMC_URL, "§7Open SpigotMC page");

        player.sendMessage("");
    }

    private void sendClickableLink(Player player, String name, String url, String hoverText) {
        try {
            net.md_5.bungee.api.chat.TextComponent message = new net.md_5.bungee.api.chat.TextComponent("§8§l┃   §e§n" + name);
            message.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL, url));
            message.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                    new net.md_5.bungee.api.chat.ComponentBuilder(hoverText + "\n§8" + url).create()));

            player.spigot().sendMessage(message);
        } catch (Exception e) {
            player.sendMessage("§8§l┃   §e" + name + ": §7" + url);
        }
    }

    private String extractJsonValue(String json, String key) {
        try {
            Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
            Matcher matcher = pattern.matcher(json);

            if (matcher.find()) {
                return matcher.group(1)
                        .replace("\\n", "\n")
                        .replace("\\r", "")
                        .replace("\\\"", "\"");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isNewerVersion(String remoteVersion, String localVersion) {
        if (remoteVersion == null || localVersion == null) {
            return false;
        }

        try {
            String remote = remoteVersion.replaceAll("[^0-9.]", "");
            String local = localVersion.replaceAll("[^0-9.]", "");

            String[] remoteParts = remote.split("\\.");
            String[] localParts = local.split("\\.");

            int maxLength = Math.max(remoteParts.length, localParts.length);

            for (int i = 0; i < maxLength; i++) {
                int remotePart = i < remoteParts.length ? parseVersionPart(remoteParts[i]) : 0;
                int localPart = i < localParts.length ? parseVersionPart(localParts[i]) : 0;

                if (remotePart > localPart) {
                    return true;
                } else if (remotePart < localPart) {
                    return false;
                }
            }

            return false;

        } catch (Exception e) {
            return !remoteVersion.equals(localVersion);
        }
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getReleaseNotes() {
        return releaseNotes;
    }

    public static String getGithubUrl() {
        return GITHUB_URL;
    }

    public static String getModrinthUrl() {
        return MODRINTH_URL;
    }

    public static String getSpigotmcUrl() {
        return SPIGOTMC_URL;
    }
}