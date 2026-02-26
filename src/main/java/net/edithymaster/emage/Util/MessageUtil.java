package net.edithymaster.emage.Util;

import net.edithymaster.emage.EmagePlugin;
import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageUtil {

    private static EmagePlugin plugin;
    private static final Pattern hexPattern = Pattern.compile("&#([a-fA-F0-9]{6})");

    public static void init(EmagePlugin p) {
        plugin = p;
    }

    private static String getRawPrefix() {
        String prefix = plugin.getConfig().getString("messages.prefix");
        if (prefix == null) {
            prefix = "&#321212&lE&#3E1111&lm&#4A0F0F&la&#560E0E&lg&#620C0C&le &8&l• ";
        }
        return prefix;
    }

    public static String msg(String key, String... placeholders) {
        String message = plugin.getConfig().getString("messages." + key);
        if (message == null) return ChatColor.RED + "Missing message: " + key;

        message = getRawPrefix() + message;

        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1] != null ? placeholders[i + 1] : "";
            message = message.replace(placeholders[i], value);
        }

        return colorize(message);
    }

    public static String msgNoPrefix(String key, String... placeholders) {
        String message = plugin.getConfig().getString("messages." + key);
        if (message == null) return ChatColor.RED + "Missing message: " + key;

        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1] != null ? placeholders[i + 1] : "";
            message = message.replace(placeholders[i], value);
        }

        return colorize(message);
    }

    public static String colorize(String message) {
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
}