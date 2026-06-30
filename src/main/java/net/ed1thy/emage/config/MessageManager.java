package net.ed1thy.emage.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

public class MessageManager {

    private final ConfigManager configManager;
    private final MiniMessage miniMessage;

    private Component prefix;
    private Component noFrame;
    private Component onlyPlayers;
    private Component maxTasksReached;
    private Component cooldownActive;
    private Component autoDetectFailed;
    private Component notEmageFrame;
    private Component metadataNotFound;
    private Component gridRemoved;
    private Component cleanupFailed;
    private Component configReloaded;
    private Component smartUrlHint;

    private String updateAvailableRaw;
    private String processingStartedRaw;
    private String successRaw;
    private String errorRaw;
    private String processErrorRaw;
    private String readErrorRaw;
    private String notEnoughFramesRaw;
    private String gifSizeLimitRaw;
    private String imageSizeLimitRaw;
    private String gifFrameLimitRaw;
    private String protectedFrameRaw;

    private Sound successSound;
    private Sound errorSound;
    private Sound startSound;

    public MessageManager(@NotNull ConfigManager configManager) {
        this.configManager = configManager;
        this.miniMessage = MiniMessage.miniMessage();
    }

    public void load() {
        FileConfiguration config = configManager.getRawConfig();

        String rawPrefix = config.getString("messages.prefix", "<b><gradient:#F57E3A:#CB6E38:#C36220>Emage</gradient></b> <dark_gray>•</dark_gray> ");
        this.prefix = miniMessage.deserialize(rawPrefix);

        this.updateAvailableRaw = config.getString("messages.update-available", "<yellow>A new version of <gradient:#F57E3A:#CB6E38:#C36220>Emage</gradient> (<green><version></green>) is available! You are running <red><current_version></red>. Download it here: <click:open_url:'<url>'><aqua><u><url></u></aqua></click>");
        this.noFrame = prefix.append(miniMessage.deserialize(config.getString("messages.no-frame", "<red>You must be looking at an item frame.")));
        this.onlyPlayers = prefix.append(miniMessage.deserialize(config.getString("messages.only-players", "<red>Only players can use this command.")));
        this.maxTasksReached = prefix.append(miniMessage.deserialize(config.getString("messages.max-tasks-reached", "<red>The server is currently processing the maximum number of images allowed. Please wait a moment.")));
        this.cooldownActive = prefix.append(miniMessage.deserialize(config.getString("messages.cooldown-active", "<red>You are doing this too fast. Please wait before rendering another image.")));
        this.autoDetectFailed = prefix.append(miniMessage.deserialize(config.getString("messages.auto-detect-failed", "<red>Could not automatically detect the grid. A missing frame has been highlighted.")));
        this.notEmageFrame = prefix.append(miniMessage.deserialize(config.getString("messages.not-emage-frame", "<red>This ItemFrame does not contain an Emage render.")));
        this.metadataNotFound = prefix.append(miniMessage.deserialize(config.getString("messages.metadata-not-found", "<red>Failed to find grid metadata in database.")));
        this.gridRemoved = prefix.append(miniMessage.deserialize(config.getString("messages.grid-removed", "<green>Grid removed successfully.")));
        this.cleanupFailed = prefix.append(miniMessage.deserialize(config.getString("messages.cleanup-failed", "<red>Failed to cleanup files.")));
        this.configReloaded = prefix.append(miniMessage.deserialize(config.getString("messages.config-reloaded", "<green>Configuration reloaded successfully.")));
        this.smartUrlHint = prefix.append(miniMessage.deserialize(config.getString("messages.smart-url-hint", "<gray><i>Hint: Ensure you used a direct image link ending in .png, .jpg, or .gif (Not an imgur/discord viewer link!)</i>")));

        this.processingStartedRaw = config.getString("messages.processing-started", "<aqua>Processing image for <color:#4CABBB><width>x<height></color> grid. Please wait...");
        this.successRaw = config.getString("messages.success", "<green>Successfully applied image to <color:#4CABBB><total></color> frame(s)! <gray>[<red><click:run_command:'/emage remove'><hover:show_text:'Click to remove this render'>Undo</hover></click></red>]</gray>");
        this.errorRaw = config.getString("messages.error", "<red>An error occurred: <gray><error>");
        this.processErrorRaw = config.getString("messages.process-error", "<red>Failed to process image: <gray><error>");
        this.readErrorRaw = config.getString("messages.read-error", "<red>Error reading image: <gray><error>");
        this.notEnoughFramesRaw = config.getString("messages.not-enough-frames", "<red>Missing frames detected on this wall. The missing spot has been highlighted.");
        this.gifSizeLimitRaw = config.getString("messages.gif-size-limit", "<red>GIFs are limited to a max grid size of <max_width>x<max_height>.");
        this.imageSizeLimitRaw = config.getString("messages.image-size-limit", "<red>Images are limited to a max grid size of <max_width>x<max_height>.");
        this.gifFrameLimitRaw = config.getString("messages.gif-frame-limit", "<red>GIFs are limited to a maximum of <max_frames> frames. (Provided GIF has <frames>)");
        this.protectedFrameRaw = config.getString("messages.protected-frame", "<red>This Emage is protected. Use <white>/emage remove</white> to delete it.");

        if (configManager.enableSounds) {
            try { this.successSound = Sound.sound(Key.key(configManager.soundSuccess), Sound.Source.MASTER, 0.6f, 1.0f); } catch (Exception ignored) {}
            try { this.errorSound = Sound.sound(Key.key(configManager.soundError), Sound.Source.MASTER, 0.6f, 1.0f); } catch (Exception ignored) {}
            try { this.startSound = Sound.sound(Key.key(configManager.soundStart), Sound.Source.MASTER, 0.6f, 1.5f); } catch (Exception ignored) {}
        } else {
            this.successSound = null;
            this.errorSound = null;
            this.startSound = null;
        }
    }

    private void playSafely(@NotNull Audience audience, Sound sound) {
        if (sound != null && configManager.enableSounds) {
            audience.playSound(sound);
        }
    }

    public void sendActionBar(@NotNull Audience audience, @NotNull String rawMessage) {
        if (configManager.useActionBar) {
            audience.sendActionBar(miniMessage.deserialize(rawMessage));
        }
    }

    public void sendProtectedFrame(@NotNull Audience audience) { sendActionBar(audience, protectedFrameRaw); }
    public void sendSmartUrlHint(@NotNull Audience audience) { audience.sendMessage(this.smartUrlHint); }

    public void sendNoFrame(@NotNull Audience audience) { audience.sendMessage(this.noFrame); playSafely(audience, errorSound); }
    public void sendOnlyPlayers(@NotNull Audience audience) { audience.sendMessage(this.onlyPlayers); playSafely(audience, errorSound); }
    public void sendMaxTasksReached(@NotNull Audience audience) { audience.sendMessage(this.maxTasksReached); playSafely(audience, errorSound); }
    public void sendCooldownActive(@NotNull Audience audience) { audience.sendMessage(this.cooldownActive); playSafely(audience, errorSound); }
    public void sendAutoDetectFailed(@NotNull Audience audience) { audience.sendMessage(this.autoDetectFailed); playSafely(audience, errorSound); }
    public void sendNotEmageFrame(@NotNull Audience audience) { audience.sendMessage(this.notEmageFrame); playSafely(audience, errorSound); }
    public void sendMetadataNotFound(@NotNull Audience audience) { audience.sendMessage(this.metadataNotFound); playSafely(audience, errorSound); }
    public void sendGridRemoved(@NotNull Audience audience) { audience.sendMessage(this.gridRemoved); playSafely(audience, successSound); }
    public void sendCleanupFailed(@NotNull Audience audience) { audience.sendMessage(this.cleanupFailed); playSafely(audience, errorSound); }

    public void sendConfigReloaded(@NotNull Audience audience) {
        audience.sendMessage(this.configReloaded);
        playSafely(audience, successSound);
    }

    public void sendProcessing(@NotNull Audience audience, int width, int height) {
        audience.sendMessage(prefix.append(miniMessage.deserialize(
                processingStartedRaw,
                Placeholder.unparsed("width", String.valueOf(width)),
                Placeholder.unparsed("height", String.valueOf(height))
        )));
        playSafely(audience, startSound);
    }

    public void sendNotEnoughFrames(@NotNull Audience audience, int width, int height) {
        audience.sendMessage(prefix.append(miniMessage.deserialize(
                notEnoughFramesRaw,
                Placeholder.unparsed("width", String.valueOf(width)),
                Placeholder.unparsed("height", String.valueOf(height))
        )));
        playSafely(audience, errorSound);
    }

    public void sendGifSizeLimit(@NotNull Audience audience, int maxWidth, int maxHeight) {
        audience.sendMessage(prefix.append(miniMessage.deserialize(
                gifSizeLimitRaw,
                Placeholder.unparsed("max_width", String.valueOf(maxWidth)),
                Placeholder.unparsed("max_height", String.valueOf(maxHeight))
        )));
        playSafely(audience, errorSound);
    }

    public void sendImageSizeLimit(@NotNull Audience audience, int maxWidth, int maxHeight) {
        audience.sendMessage(prefix.append(miniMessage.deserialize(
                imageSizeLimitRaw,
                Placeholder.unparsed("max_width", String.valueOf(maxWidth)),
                Placeholder.unparsed("max_height", String.valueOf(maxHeight))
        )));
        playSafely(audience, errorSound);
    }

    public void sendGifFrameLimit(@NotNull Audience audience, int maxFrames, int frames) {
        audience.sendMessage(prefix.append(miniMessage.deserialize(
                gifFrameLimitRaw,
                Placeholder.unparsed("max_frames", String.valueOf(maxFrames)),
                Placeholder.unparsed("frames", String.valueOf(frames))
        )));
        playSafely(audience, errorSound);
    }

    public void sendUpdateAvailable(@NotNull Audience audience, @NotNull String currentVersion, @NotNull String newVersion, @NotNull String url) {
        audience.sendMessage(miniMessage.deserialize(
                updateAvailableRaw,
                Placeholder.unparsed("current_version", currentVersion),
                Placeholder.unparsed("version", newVersion),
                Placeholder.unparsed("url", url)
        ));
    }

    public void sendSuccess(@NotNull Audience audience, int totalFrames) {
        audience.sendMessage(prefix.append(miniMessage.deserialize(
                successRaw,
                Placeholder.unparsed("total", String.valueOf(totalFrames))
        )));
        playSafely(audience, successSound);
    }

    public void sendError(@NotNull Audience audience, @NotNull String errorMessage) {
        audience.sendMessage(prefix.append(miniMessage.deserialize(
                errorRaw,
                Placeholder.unparsed("error", errorMessage)
        )));
        playSafely(audience, errorSound);
    }

    public void sendProcessError(@NotNull Audience audience, @NotNull String errorMessage) {
        audience.sendMessage(prefix.append(miniMessage.deserialize(
                processErrorRaw,
                Placeholder.unparsed("error", errorMessage)
        )));
        playSafely(audience, errorSound);
    }

    public void sendReadError(@NotNull Audience audience, @NotNull String errorMessage) {
        audience.sendMessage(prefix.append(miniMessage.deserialize(
                readErrorRaw,
                Placeholder.unparsed("error", errorMessage)
        )));
        playSafely(audience, errorSound);
    }
}