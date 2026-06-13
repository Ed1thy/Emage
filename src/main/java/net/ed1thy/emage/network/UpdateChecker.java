package net.ed1thy.emage.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.config.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class UpdateChecker {

    private final Emage plugin;
    private final MessageManager messageManager;
    private final String currentVersion;
    private final HttpClient httpClient;

    private String latestVersion = null;
    private String downloadUrl = null;
    private boolean updateAvailable = false;

    private static final String MODRINTH_SLUG = "emage";
    private static final String GITHUB_REPO = "Ed1thy/Emage";
    private static final String SPIGOT_ID = "00000";

    public UpdateChecker(@NotNull Emage plugin, @NotNull MessageManager messageManager) {
        this.plugin = plugin;
        this.messageManager = messageManager;
        this.currentVersion = plugin.getPluginMeta().getVersion();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public void checkForUpdates() {
        if (updateAvailable) return;

        try {
            UpdateResult result = checkModrinth();
            if (result == null) result = checkGitHub();
            if (result == null) result = checkSpigot();

            if (result != null && isNewer(currentVersion, result.version())) {
                this.latestVersion = result.version();
                this.downloadUrl = result.url();
                this.updateAvailable = true;

                notifyConsole();
                notifyAllOps();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to check for Emage updates: " + e.getMessage());
        }
    }

    private UpdateResult checkModrinth() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version")).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonArray jsonArray = JsonParser.parseString(response.body()).getAsJsonArray();
                if (!jsonArray.isEmpty()) {
                    String version = jsonArray.get(0).getAsJsonObject().get("version_number").getAsString();
                    return new UpdateResult(version, "https://modrinth.com/plugin/" + MODRINTH_SLUG);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private UpdateResult checkGitHub() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest")).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject jsonObject = JsonParser.parseString(response.body()).getAsJsonObject();
                String version = jsonObject.get("tag_name").getAsString();
                String url = jsonObject.get("html_url").getAsString();
                return new UpdateResult(version, url);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private UpdateResult checkSpigot() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.spigotmc.org/legacy/update.php?resource=" + SPIGOT_ID)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return new UpdateResult(response.body(), "https://www.spigotmc.org/resources/" + SPIGOT_ID);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isNewer(String current, String fetched) {
        current = current.replaceAll("[^0-9.]", "");
        fetched = fetched.replaceAll("[^0-9.]", "");

        String[] currentParts = current.split("\\.");
        String[] fetchedParts = fetched.split("\\.");
        int length = Math.max(currentParts.length, fetchedParts.length);

        for (int i = 0; i < length; i++) {
            int c = i < currentParts.length && !currentParts[i].isEmpty() ? Integer.parseInt(currentParts[i]) : 0;
            int f = i < fetchedParts.length && !fetchedParts[i].isEmpty() ? Integer.parseInt(fetchedParts[i]) : 0;
            if (f > c) return true;
            if (f < c) return false;
        }
        return false;
    }

    private void notifyConsole() {
        plugin.getLogger().warning("=====================================================");
        plugin.getLogger().warning(" A new version of Emage is available! (" + latestVersion + ")");
        plugin.getLogger().warning(" You are currently running version " + currentVersion);
        plugin.getLogger().warning(" Download it here: " + downloadUrl);
        plugin.getLogger().warning("=====================================================");
    }

    private void notifyAllOps() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("emage.admin")) {
                    notifyPlayer(player);
                }
            }
        });
    }

    public void notifyPlayer(Player player) {
        if (updateAvailable && latestVersion != null && downloadUrl != null) {
            messageManager.sendUpdateAvailable(player, currentVersion, latestVersion, downloadUrl);
        }
    }

    private record UpdateResult(String version, String url) {}
}