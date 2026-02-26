package net.edithymaster.emage.Command;

import net.edithymaster.emage.Config.EmageConfig;
import net.edithymaster.emage.EmagePlugin;
import net.edithymaster.emage.Manager.EmageManager;
import net.edithymaster.emage.Processing.EmageColors;
import net.edithymaster.emage.Processing.EmageCore;
import net.edithymaster.emage.Render.GifRenderer;
import net.edithymaster.emage.Util.FrameGrid;
import net.edithymaster.emage.Util.GifCache;
import net.edithymaster.emage.Util.MessageUtil;
import net.edithymaster.emage.Util.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class SubCommandExecutor {

    public static boolean execute(Player pl, String[] args, EmagePlugin plugin, EmageManager manager) {
        switch (args[0].toLowerCase()) {
            case "help" -> {
                sendHelp(pl, plugin);
                return true;
            }
            case "remove" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("all")) {
                    if (!pl.hasPermission("emage.admin")) {
                        pl.sendMessage(MessageUtil.msg("no-perm"));
                        return true;
                    }

                    pl.sendMessage(MessageUtil.colorize(plugin.getConfig().getString("messages.prefix", "&#4CABBB&lE&#3DA3B8&lm&#2E9BB4&la&#1E92B1&lg&#0F8AAD&le &8• ") + "&#8B9DA0Removing all Emage maps globally..."));

                    Set<Integer> allManaged = new HashSet<>(manager.getManagedMaps());
                    int count = allManaged.size();

                    if (count == 0) {
                        pl.sendMessage(MessageUtil.colorize(plugin.getConfig().getString("messages.prefix", "&#4CABBB&lE&#3DA3B8&lm&#2E9BB4&la&#1E92B1&lg&#0F8AAD&le &8• ") + "&#C75050No Emage maps found on the server."));
                        return true;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        int framesCleared = 0;
                        for (World world : Bukkit.getWorlds()) {
                            for (Chunk chunk : world.getLoadedChunks()) {
                                for (Entity entity : chunk.getEntities()) {
                                    if (entity instanceof ItemFrame frame) {
                                        ItemStack item = frame.getItem();
                                        if (item != null && item.getType() == Material.FILLED_MAP) {
                                            MapMeta meta = (MapMeta) item.getItemMeta();
                                            if (meta != null && meta.hasMapId()) {
                                                if (allManaged.contains(meta.getMapId())) {
                                                    frame.setItem(null);
                                                    framesCleared++;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        for (int mapId : allManaged) {
                            manager.destroyMapDataPublic(mapId);
                        }

                        pl.sendMessage(MessageUtil.colorize(plugin.getConfig().getString("messages.prefix", "&#4CABBB&lE&#3DA3B8&lm&#2E9BB4&la&#1E92B1&lg&#0F8AAD&le &8• ") + "&#50C78ASuccessfully removed &#4CABBB" + count + " &#50C78Amaps and cleared &#4CABBB" + framesCleared + " &#50C78Aitem frames."));
                    });
                    return true;
                } else {
                    ItemFrame targetFrame = getTargetFrame(pl);
                    if (targetFrame == null) {
                        pl.sendMessage(MessageUtil.msg("no-frame"));
                        return true;
                    }

                    ItemStack item = targetFrame.getItem();
                    if (item == null || item.getType() != Material.FILLED_MAP) {
                        pl.sendMessage(MessageUtil.colorize(plugin.getConfig().getString("messages.prefix", "&#4CABBB&lE&#3DA3B8&lm&#2E9BB4&la&#1E92B1&lg&#0F8AAD&le &8• ") + "&#C75050This frame does not contain a map."));
                        return true;
                    }

                    MapMeta meta = (MapMeta) item.getItemMeta();
                    if (meta == null || !meta.hasMapView()) {
                        pl.sendMessage(MessageUtil.colorize(plugin.getConfig().getString("messages.prefix", "&#4CABBB&lE&#3DA3B8&lm&#2E9BB4&la&#1E92B1&lg&#0F8AAD&le &8• ") + "&#C75050This frame does not contain a valid map."));
                        return true;
                    }

                    int clickedMapId = meta.getMapView().getId();
                    EmageManager.CachedMapData cachedData = manager.getMapCache().get(clickedMapId);

                    if (cachedData == null) {
                        pl.sendMessage(MessageUtil.colorize(plugin.getConfig().getString("messages.prefix", "&#4CABBB&lE&#3DA3B8&lm&#2E9BB4&la&#1E92B1&lg&#0F8AAD&le &8• ") + "&#C75050This map is not managed by Emage."));
                        return true;
                    }

                    long targetSyncId = cachedData.syncId;
                    Set<Integer> mapsToRemove = new HashSet<>();

                    for (Map.Entry<Integer, EmageManager.CachedMapData> entry : manager.getMapCache().entrySet()) {
                        if (entry.getValue().syncId == targetSyncId) {
                            mapsToRemove.add(entry.getKey());
                        }
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        FrameGrid grid = new FrameGrid(targetFrame, null, null, 50);
                        int framesCleared = 0;
                        for (FrameGrid.FrameNode node : grid.nodes) {
                            ItemStack nodeItem = node.frame.getItem();
                            if (nodeItem != null && nodeItem.getType() == Material.FILLED_MAP) {
                                MapMeta nodeMeta = (MapMeta) nodeItem.getItemMeta();
                                if (nodeMeta != null && nodeMeta.hasMapView()) {
                                    if (mapsToRemove.contains(nodeMeta.getMapView().getId())) {
                                        node.frame.setItem(null);
                                        framesCleared++;
                                    }
                                }
                            }
                        }

                        for (int mapId : mapsToRemove) {
                            manager.destroyMapDataPublic(mapId);
                        }

                        pl.sendMessage(MessageUtil.colorize(plugin.getConfig().getString("messages.prefix", "&#4CABBB&lE&#3DA3B8&lm&#2E9BB4&la&#1E92B1&lg&#0F8AAD&le &8• ") + "&#50C78ASuccessfully removed image from &#4CABBB" + framesCleared + " &#50C78Aframe(s)."));
                    });

                    return true;
                }
            }
            case "reload" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(MessageUtil.msg("no-perm"));
                    return true;
                }
                plugin.reloadConfig();
                plugin.getEmageConfig().reload();
                EmageCore.setConfig(plugin.getEmageConfig());
                GifCache.configure(
                        plugin.getEmageConfig().getCacheMaxEntries(),
                        plugin.getEmageConfig().getCacheMaxMemoryBytes(),
                        plugin.getEmageConfig().getCacheExpireMs()
                );
                pl.sendMessage(MessageUtil.msg("reloaded"));
                return true;
            }
            case "clearcache" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(MessageUtil.msg("no-perm"));
                    return true;
                }
                int cleared = GifCache.clearCache();
                pl.sendMessage(MessageUtil.msg("cache-cleared", "<count>", String.valueOf(cleared)));
                return true;
            }
            case "cache" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(MessageUtil.msg("no-perm"));
                    return true;
                }
                GifCache.CacheStats stats = GifCache.getStats();
                pl.sendMessage(MessageUtil.msg("cache-header"));
                pl.sendMessage(MessageUtil.msgNoPrefix("cache-count", "<count>", String.valueOf(stats.count)));
                pl.sendMessage(MessageUtil.msgNoPrefix("cache-memory", "<size>", stats.formattedSize));
                pl.sendMessage(MessageUtil.msgNoPrefix("cache-hitrate", "<rate>", String.format("%.1f%%", stats.hitRate * 100)));
                return true;
            }
            case "update", "version" -> {
                UpdateChecker checker = plugin.getUpdateChecker();
                if (checker == null) {
                    pl.sendMessage(MessageUtil.msg("update-disabled"));
                    return true;
                }
                pl.sendMessage(MessageUtil.msg("update-checking"));
                checker.checkForUpdates().thenAccept(hasUpdate -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!pl.isOnline()) return;
                        if (hasUpdate) {
                            checker.sendUpdateNotification(pl);
                        } else {
                            pl.sendMessage(MessageUtil.msg("update-latest", "<version>", checker.getCurrentVersion()));
                        }
                    });
                }).exceptionally(throwable -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (pl.isOnline()) {
                            pl.sendMessage(MessageUtil.msg("error", "<error>", "Update check failed. Check console for details."));
                        }
                    });
                    plugin.getLogger().log(Level.WARNING, "An error occurred while checking for updates.", throwable);
                    return null;
                });
                return true;
            }
            case "cleanup" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(MessageUtil.msg("no-perm"));
                    return true;
                }
                pl.sendMessage(MessageUtil.msg("cleanup-start"));
                List<File> worldFolders = new ArrayList<>();
                for (World world : Bukkit.getWorlds()) {
                    worldFolders.add(world.getWorldFolder());
                }
                File mapsFolder = manager.getMapsFolder();
                File[] existingFiles = mapsFolder.listFiles();
                int filesBefore = existingFiles != null ? existingFiles.length : 0;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    Set<Integer> mapsInUse = collectValidMapIDs(worldFolders, plugin);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (pl.isOnline()) pl.sendMessage(MessageUtil.msg("cleanup-scanning", "<count>", String.valueOf(mapsInUse.size())));
                    });
                    manager.cleanupUnusedFiles(mapsInUse, deleted -> {
                        if (pl.isOnline()) {
                            if (deleted > 0) pl.sendMessage(MessageUtil.msg("cleanup-done", "<count>", String.valueOf(deleted)));
                            else pl.sendMessage(MessageUtil.msg("cleanup-none", "<count>", String.valueOf(filesBefore)));
                        }
                    });
                });
                return true;
            }
            case "stats" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(MessageUtil.msg("no-perm"));
                    return true;
                }
                EmageManager.MapStats stats = manager.getStats();
                pl.sendMessage(MessageUtil.msg("stats",
                        "<static>", String.valueOf(stats.staticMaps),
                        "<anim>", String.valueOf(stats.animations),
                        "<size>", stats.getTotalSizeFormatted(),
                        "<active>", String.valueOf(stats.activeMaps)));
                return true;
            }
            case "synccolors" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(MessageUtil.msg("no-perm"));
                    return true;
                }
                pl.sendMessage(MessageUtil.colorize("&7Syncing color palette with server..."));
                EmageCore.initColorSystem();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    EmageColors.forceRebuildCache();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (pl.isOnline()) pl.sendMessage(MessageUtil.colorize("&aColor palette successfully re-synced and cached!"));
                    });
                });
                return true;
            }
            case "perf", "performance" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(MessageUtil.msg("no-perm"));
                    return true;
                }
                EmageConfig cfg = plugin.getEmageConfig();
                EmageManager.MapStats stats = manager.getStats();
                GifCache.CacheStats cacheStats = GifCache.getStats();
                pl.sendMessage(MessageUtil.msg("perf-header"));
                pl.sendMessage(MessageUtil.msgNoPrefix("perf-status", "<status>", stats.performanceStatus));
                pl.sendMessage(MessageUtil.msgNoPrefix("perf-packet-sender", "<sender>", "PacketEvents"));
                pl.sendMessage(MessageUtil.msgNoPrefix("perf-animations", "<count>", String.valueOf(GifRenderer.getActiveCount())));
                pl.sendMessage(MessageUtil.msgNoPrefix("perf-cache", "<count>", String.valueOf(cacheStats.count), "<size>", cacheStats.formattedSize));
                pl.sendMessage(MessageUtil.msgNoPrefix("perf-hitrate", "<rate>", String.format("%.1f%%", cacheStats.hitRate * 100)));
                pl.sendMessage(MessageUtil.msgNoPrefix("perf-distance", "<distance>", String.valueOf(cfg.getRenderDistance())));
                return true;
            }
        }
        return false;
    }

    private static ItemFrame getTargetFrame(Player player) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                6.0,
                entity -> entity instanceof ItemFrame
        );

        if (result != null && result.getHitEntity() instanceof ItemFrame frame) {
            return frame;
        }
        return null;
    }

    private static void sendHelp(Player player, EmagePlugin plugin) {
        EmageConfig cfg = plugin.getEmageConfig();
        player.sendMessage(MessageUtil.msgNoPrefix("help-header"));
        player.sendMessage(MessageUtil.msgNoPrefix("help-url"));
        player.sendMessage(MessageUtil.msgNoPrefix("help-limits", "<gif-max>", String.valueOf(cfg.getMaxGifGridSize()), "<image-max>", String.valueOf(cfg.getMaxImageGridSize())));
        player.sendMessage(MessageUtil.msgNoPrefix("help-aliases"));

        player.sendMessage(MessageUtil.msgNoPrefix("help-remove"));
        if (player.hasPermission("emage.admin")) {
            player.sendMessage(MessageUtil.msgNoPrefix("help-removeall"));
        }

        player.sendMessage(MessageUtil.msgNoPrefix("help-clearcache"));
        player.sendMessage(MessageUtil.msgNoPrefix("help-cache"));
        player.sendMessage(MessageUtil.msgNoPrefix("help-cleanup"));
        player.sendMessage(MessageUtil.msgNoPrefix("help-stats"));
        player.sendMessage(MessageUtil.msgNoPrefix("help-perf"));
        player.sendMessage(MessageUtil.msgNoPrefix("help-reload"));
        player.sendMessage(MessageUtil.msgNoPrefix("help-update"));
        player.sendMessage(MessageUtil.msgNoPrefix("help-footer"));
    }

    private static Set<Integer> collectValidMapIDs(List<File> worldFolders, EmagePlugin plugin) {
        Set<Integer> validMapIds = new HashSet<>();
        for (File worldFolder : worldFolders) {
            File dataFolder = new File(worldFolder, "data");
            if (dataFolder.exists() && dataFolder.isDirectory()) {
                File[] mapFiles = dataFolder.listFiles((dir, name) -> name.startsWith("map_") && name.endsWith(".dat"));
                if (mapFiles != null) {
                    for (File mapFile : mapFiles) {
                        try {
                            String idStr = mapFile.getName().substring(4, mapFile.getName().length() - 4);
                            validMapIds.add(Integer.parseInt(idStr));
                        } catch (NumberFormatException e) {
                            plugin.getLogger().fine("Skipped non-numeric map file: " + mapFile.getName());
                        }
                    }
                }
            }
        }
        return validMapIds;
    }
}