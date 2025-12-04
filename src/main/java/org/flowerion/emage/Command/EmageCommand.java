package org.flowerion.emage.Command;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.util.RayTraceResult;
import org.flowerion.emage.*;
import org.flowerion.emage.Config.EmageConfig;
import org.flowerion.emage.Manager.EmageManager;
import org.flowerion.emage.Processing.EmageCore;
import org.flowerion.emage.Render.EmageRenderer;
import org.flowerion.emage.Render.GifRenderer;
import org.flowerion.emage.Util.GifCache;
import org.flowerion.emage.Util.UpdateChecker;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.*;
import java.util.*;

public final class EmageCommand implements CommandExecutor, TabCompleter {

    private final EmagePlugin plugin;
    private final EmageManager manager;

    // Grid limits
    private static final int MAX_GIF_GRID = 4;      // Max 4x4 for GIFs
    private static final int MAX_IMAGE_GRID = 10;   // Max 10x10 for static images
    private static final int WARN_GIF_CELLS = 9;    // Warn at 3x3 or more

    public EmageCommand(EmagePlugin p, EmageManager m) {
        plugin = p;
        manager = m;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player pl)) {
            sender.sendMessage("Players only!");
            return true;
        }

        if (args.length < 1) {
            pl.sendMessage(plugin.hardcodedMsg("usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help" -> {
                sendHelp(pl);
                return true;
            }
            case "reload" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(plugin.hardcodedMsg("no-perm"));
                    return true;
                }
                plugin.reloadConfig();
                plugin.getEmageConfig().reload();
                pl.sendMessage(plugin.msg("reloaded"));
                return true;
            }
            case "clearcache" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(plugin.hardcodedMsg("no-perm"));
                    return true;
                }
                int cleared = GifCache.clearCache();
                pl.sendMessage(plugin.colorize(plugin.getPrefix() + "&aCleared &e" + cleared + " &acached GIFs."));
                return true;
            }
            case "cache" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(plugin.hardcodedMsg("no-perm"));
                    return true;
                }
                GifCache.CacheStats stats = GifCache.getStats();
                pl.sendMessage(plugin.colorize(plugin.getPrefix() + "&7GIF Cache:"));
                pl.sendMessage(plugin.colorize("&8 • &7Cached GIFs: &b" + stats.count));
                pl.sendMessage(plugin.colorize("&8 • &7Memory: &b" + stats.formattedSize));
                pl.sendMessage(plugin.colorize("&8 • &7Hit rate: &b" + String.format("%.1f%%", stats.hitRate * 100)));
                return true;
            }
            case "migrate" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(plugin.hardcodedMsg("no-perm"));
                    return true;
                }
                pl.sendMessage(plugin.msg("migrate-start"));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    int count = manager.migrateOldFormats();
                    Bukkit.getScheduler().runTask(plugin, () ->
                            pl.sendMessage(plugin.msg("migrated", "<count>", String.valueOf(count))));
                });
                return true;
            }
            case "update", "version" -> {
                UpdateChecker checker = plugin.getUpdateChecker();
                if (checker == null) {
                    pl.sendMessage(plugin.hardcodedMsg("update-disabled"));
                    return true;
                }

                pl.sendMessage(plugin.hardcodedMsg("update-checking"));

                checker.checkForUpdates().thenAccept(hasUpdate -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (hasUpdate) {
                            checker.sendUpdateNotification(pl);
                        } else {
                            pl.sendMessage(plugin.hardcodedMsg("update-latest",
                                    "<version>", checker.getCurrentVersion()));
                        }
                    });
                });
                return true;
            }

            case "cleanup" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(plugin.hardcodedMsg("no-perm"));
                    return true;
                }

                pl.sendMessage(plugin.msg("cleanup-start"));

                Set<Integer> mapsInUse = collectMapsInUse();

                pl.sendMessage(plugin.msg("cleanup-scanning", "<count>", String.valueOf(mapsInUse.size())));

                File mapsFolder = manager.getMapsFolder();
                int filesBefore = mapsFolder.listFiles() != null ? mapsFolder.listFiles().length : 0;

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    int deleted = manager.cleanupUnusedFiles(mapsInUse);

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (deleted > 0) {
                            pl.sendMessage(plugin.msg("cleanup-done", "<count>", String.valueOf(deleted)));
                        } else {
                            pl.sendMessage(plugin.colorize(plugin.getPrefix() + "&7No unused files found. All " + filesBefore + " files are in use."));
                        }
                    });
                });
                return true;
            }
            case "stats" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(plugin.hardcodedMsg("no-perm"));
                    return true;
                }
                EmageManager.MapStats stats = manager.getStats();
                pl.sendMessage(plugin.msg("stats",
                        "<static>", String.valueOf(stats.staticMaps),
                        "<anim>", String.valueOf(stats.animations),
                        "<size>", stats.getTotalSizeFormatted(),
                        "<active>", String.valueOf(stats.activeMaps)));
                return true;
            }
            case "perf", "performance" -> {
                if (!pl.hasPermission("emage.admin")) {
                    pl.sendMessage(plugin.hardcodedMsg("no-perm"));
                    return true;
                }
                EmageConfig cfg = plugin.getEmageConfig();
                EmageManager.MapStats stats = manager.getStats();
                GifCache.CacheStats cacheStats = GifCache.getStats();

                pl.sendMessage(plugin.getPrefix() + "&7Performance Status:");
                pl.sendMessage(plugin.colorize("&8 • &7" + stats.performanceStatus));
                pl.sendMessage(plugin.colorize("&8 • &7Active animations: &b" + GifRenderer.getActiveCount()));
                pl.sendMessage(plugin.colorize("&8 • &7GIF cache: &b" + cacheStats.count + " &7(&b" + cacheStats.formattedSize + "&7)"));
                pl.sendMessage(plugin.colorize("&8 • &7Cache hit rate: &b" + String.format("%.1f%%", cacheStats.hitRate * 100)));
                pl.sendMessage(plugin.colorize("&8 • &7Render distance: &b" + cfg.getRenderDistance() + " blocks"));
                return true;
            }
        }

        // Parse URL and options
        String urlStr = null;
        Integer reqWidth = null;
        Integer reqHeight = null;
        EmageCore.Quality quality = EmageCore.Quality.BALANCED;
        boolean noCache = false;

        for (String arg : args) {
            if (arg.startsWith("http://") || arg.startsWith("https://")) {
                urlStr = arg;
            } else if (arg.startsWith("--") || arg.startsWith("-")) {
                String flag = arg.replaceFirst("^-+", "").toLowerCase();
                switch (flag) {
                    case "high", "hq", "quality", "h" -> quality = EmageCore.Quality.HIGH;
                    case "balanced", "bal", "b", "normal" -> quality = EmageCore.Quality.BALANCED;
                    case "fast", "low", "f", "l", "speed" -> quality = EmageCore.Quality.FAST;
                    case "nocache", "nc", "fresh" -> noCache = true;
                }
            } else if (arg.contains("x") || arg.matches("\\d+")) {
                try {
                    if (arg.contains("x")) {
                        String[] parts = arg.toLowerCase().split("x");
                        reqWidth = Integer.parseInt(parts[0]);
                        reqHeight = Integer.parseInt(parts[1]);
                    } else {
                        reqWidth = Integer.parseInt(arg);
                        reqHeight = reqWidth;
                    }

                    if (reqWidth < 1 || reqHeight < 1) {
                        pl.sendMessage(plugin.hardcodedMsg("invalid-size"));
                        return true;
                    }
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }

        if (urlStr == null) {
            pl.sendMessage(plugin.hardcodedMsg("invalid-url"));
            return true;
        }

        ItemFrame targetFrame = getTargetFrame(pl);
        if (targetFrame == null) {
            pl.sendMessage(plugin.hardcodedMsg("no-frame"));
            return true;
        }

        FrameGrid grid = new FrameGrid(targetFrame, reqWidth, reqHeight);

        String qualityName = switch (quality) {
            case HIGH -> "High";
            case BALANCED -> "Balanced";
            case FAST -> "Fast";
        };

        pl.sendMessage(plugin.msg("detected",
                "<width>", String.valueOf(grid.width),
                "<height>", String.valueOf(grid.height),
                "<facing>", targetFrame.getFacing().toString(),
                "<quality>", qualityName));

        final int gridWidth = grid.width;
        final int gridHeight = grid.height;
        final List<FrameNode> frameNodes = new ArrayList<>(grid.nodes);
        final EmageCore.Quality finalQuality = quality;
        final String finalUrl = urlStr;
        final boolean finalNoCache = noCache;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URL url = new URI(finalUrl).toURL();

                // Check if GIF
                boolean isGif = finalUrl.toLowerCase().contains(".gif");
                if (!isGif) {
                    try {
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("HEAD");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                        String contentType = conn.getContentType();
                        conn.disconnect();
                        if (contentType != null && contentType.toLowerCase().contains("gif")) {
                            isGif = true;
                        }
                    } catch (Exception ignored) {}
                }

                if (isGif) {
                    // Check GIF grid limits
                    if (gridWidth > MAX_GIF_GRID || gridHeight > MAX_GIF_GRID) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                pl.sendMessage(plugin.colorize(plugin.getPrefix() +
                                        "&cGIF grid too large! Maximum is &e" + MAX_GIF_GRID + "x" + MAX_GIF_GRID + "&c.")));
                        return;
                    }

                    int totalCells = gridWidth * gridHeight;

                    // Warn about large grids
                    if (totalCells >= WARN_GIF_CELLS) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                pl.sendMessage(plugin.colorize(plugin.getPrefix() +
                                        "&eWarning: &7Large GIF grid (&e" + gridWidth + "x" + gridHeight +
                                        "&7) may cause lag. Processing...")));
                    }

                    processGif(pl, url, frameNodes, gridWidth, gridHeight, finalQuality, finalNoCache);
                } else {
                    // Check image grid limits
                    if (gridWidth > MAX_IMAGE_GRID || gridHeight > MAX_IMAGE_GRID) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                pl.sendMessage(plugin.colorize(plugin.getPrefix() +
                                        "&cImage grid too large! Maximum is &e" + MAX_IMAGE_GRID + "x" + MAX_IMAGE_GRID + "&c.")));
                        return;
                    }

                    processStaticImage(pl, url, frameNodes, gridWidth, gridHeight, finalQuality);
                }

            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                Bukkit.getScheduler().runTask(plugin, () ->
                        pl.sendMessage(plugin.hardcodedMsg("error", "<error>", errorMsg)));
                e.printStackTrace();
            }
        });

        return true;
    }

    private Set<Integer> collectMapsInUse() {
        Set<Integer> mapsInUse = new HashSet<>();

        for (World world : Bukkit.getWorlds()) {
            try {
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof ItemFrame itemFrame) {
                        ItemStack item = itemFrame.getItem();
                        if (item != null && item.getType() == Material.FILLED_MAP) {
                            try {
                                MapMeta meta = (MapMeta) item.getItemMeta();
                                if (meta != null && meta.hasMapView()) {
                                    MapView view = meta.getMapView();
                                    if (view != null) {
                                        @SuppressWarnings("deprecation")
                                        int id = view.getId();
                                        mapsInUse.add(id);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && item.getType() == Material.FILLED_MAP) {
                        try {
                            MapMeta meta = (MapMeta) item.getItemMeta();
                            if (meta != null && meta.hasMapView()) {
                                MapView view = meta.getMapView();
                                if (view != null) {
                                    @SuppressWarnings("deprecation")
                                    int id = view.getId();
                                    mapsInUse.add(id);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }

        return mapsInUse;
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.hardcodedMsg("help-header"));
        player.sendMessage(plugin.hardcodedMsg("help-url"));
        player.sendMessage(plugin.colorize("&7  Limits: GIF &e" + MAX_GIF_GRID + "x" + MAX_GIF_GRID +
                "&7, Image &e" + MAX_IMAGE_GRID + "x" + MAX_IMAGE_GRID));
        player.sendMessage(plugin.hardcodedMsg("help-quality"));
        player.sendMessage(plugin.hardcodedMsg("help-aliases"));
        player.sendMessage(plugin.colorize("&6/emage clearcache &7- &fClear GIF processing cache"));
        player.sendMessage(plugin.colorize("&6/emage cache &7- &fView cache statistics"));
        player.sendMessage(plugin.hardcodedMsg("help-cleanup"));
        player.sendMessage(plugin.hardcodedMsg("help-stats"));
        player.sendMessage(plugin.hardcodedMsg("help-perf"));
        player.sendMessage(plugin.hardcodedMsg("help-migrate"));
        player.sendMessage(plugin.hardcodedMsg("help-reload"));
        player.sendMessage(plugin.hardcodedMsg("help-update"));
        player.sendMessage(plugin.hardcodedMsg("help-footer"));
    }

    private void processStaticImage(Player player, URL url, List<FrameNode> nodes,
                                    int gridWidth, int gridHeight, EmageCore.Quality quality) throws Exception {
        BufferedImage original = ImageIO.read(url);
        if (original == null) {
            throw new Exception("Failed to read image");
        }

        int totalWidth = gridWidth * 128;
        int totalHeight = gridHeight * 128;

        BufferedImage resized = EmageCore.resize(original, totalWidth, totalHeight);

        long gridId = System.currentTimeMillis();

        List<ProcessedChunk> chunks = new ArrayList<>();

        for (FrameNode node : nodes) {
            if (node.gridX < 0 || node.gridX >= gridWidth ||
                    node.gridY < 0 || node.gridY >= gridHeight) {
                continue;
            }

            int px = node.gridX * 128;
            int py = node.gridY * 128;

            BufferedImage chunk = resized.getSubimage(px, py, 128, 128);
            byte[] mapData = EmageCore.dither(chunk, quality);

            chunks.add(new ProcessedChunk(node.frame, mapData));
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            int applied = 0;
            for (ProcessedChunk chunk : chunks) {
                applyMapToFrame(chunk.frame, chunk.data, gridId);
                applied++;
            }
            player.sendMessage(plugin.msg("success", "<total>", String.valueOf(applied)));
        });
    }

    private void processGif(Player player, URL url, List<FrameNode> nodes,
                            int gridWidth, int gridHeight, EmageCore.Quality quality,
                            boolean noCache) throws Exception {

        String cacheKey = GifCache.createKey(url.toString(), gridWidth, gridHeight, quality);
        int maxFrames = plugin.getEmageConfig().getMaxGifFrames();

        // Check cache first
        EmageCore.GifGridData cachedData = noCache ? null : GifCache.get(cacheKey);

        if (cachedData != null) {
            // Use cached data
            Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage(plugin.colorize(plugin.getPrefix() +
                            "&aUsing cached GIF data! &7(Use &e--nocache &7to reprocess)")));

            applyGifData(player, cachedData, nodes, gridWidth, gridHeight, 0);
            return;
        }

        // Process GIF
        Bukkit.getScheduler().runTask(plugin, () ->
                player.sendMessage(plugin.msg("processing-gif",
                        "<width>", String.valueOf(gridWidth),
                        "<height>", String.valueOf(gridHeight))));

        long startTime = System.currentTimeMillis();

        EmageCore.GifGridData gifData = EmageCore.processGifGrid(url, gridWidth, gridHeight, maxFrames, quality);

        long processTime = System.currentTimeMillis() - startTime;

        // Cache the processed data
        GifCache.put(cacheKey, gifData);

        // Apply to frames
        applyGifData(player, gifData, nodes, gridWidth, gridHeight, processTime);
    }

    private void applyGifData(Player player, EmageCore.GifGridData gifData, List<FrameNode> nodes,
                              int gridWidth, int gridHeight, long processTime) {

        long syncId = System.currentTimeMillis();

        Bukkit.getScheduler().runTask(plugin, () -> {
            int applied = 0;

            for (FrameNode node : nodes) {
                if (node.gridX < 0 || node.gridX >= gridWidth ||
                        node.gridY < 0 || node.gridY >= gridHeight) {
                    continue;
                }

                List<byte[]> frames = gifData.grid[node.gridX][node.gridY];
                if (frames != null && !frames.isEmpty()) {
                    applyGifToFrame(node.frame, frames, gifData.delays, gifData.avgDelay, syncId);
                    applied++;
                }
            }

            // Start animation after all maps are applied
            GifRenderer.startSyncGroup(syncId);

            int frameCount = gifData.grid[0][0] != null ? gifData.grid[0][0].size() : 0;

            if (processTime > 0) {
                player.sendMessage(plugin.msg("success-gif",
                        "<total>", String.valueOf(applied),
                        "<frames>", String.valueOf(frameCount),
                        "<time>", String.valueOf(processTime)));
            } else {
                player.sendMessage(plugin.colorize(plugin.getPrefix() +
                        "&aApplied &e" + applied + " &amaps with &e" + frameCount + " &aframes. &7(from cache)"));
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void applyMapToFrame(ItemFrame frame, byte[] mapData, long gridId) {
        MapView mapView = Bukkit.createMap(frame.getWorld());
        mapView.getRenderers().forEach(mapView::removeRenderer);
        mapView.setTrackingPosition(false);
        mapView.setUnlimitedTracking(false);
        mapView.addRenderer(new EmageRenderer(mapData));

        manager.saveMap(mapView.getId(), mapData, gridId);

        frame.setRotation(Rotation.NONE);

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setMapView(mapView);
            mapItem.setItemMeta(meta);
        }
        frame.setItem(mapItem);
    }

    @SuppressWarnings("deprecation")
    private void applyGifToFrame(ItemFrame frame, List<byte[]> frames,
                                 List<Integer> delays, int avgDelay, long syncId) {
        MapView mapView = Bukkit.createMap(frame.getWorld());
        mapView.getRenderers().forEach(mapView::removeRenderer);
        mapView.setTrackingPosition(false);
        mapView.setUnlimitedTracking(false);

        GifRenderer renderer = new GifRenderer(frames, delays, syncId);
        renderer.setMapView(mapView);
        mapView.addRenderer(renderer);

        manager.saveGif(mapView.getId(), frames, delays, avgDelay, syncId);
        GifRenderer.registerMapLocation(mapView.getId(), frame.getLocation());

        frame.setRotation(Rotation.NONE);

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setMapView(mapView);
            mapItem.setItemMeta(meta);
        }
        frame.setItem(mapItem);
    }

    private ItemFrame getTargetFrame(Player player) {
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        String lastArg = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

        boolean hasUrl = false;
        boolean hasSize = false;
        boolean hasQuality = false;

        for (String arg : args) {
            if (arg.startsWith("http")) hasUrl = true;
            if (arg.matches("\\d+x\\d+") || arg.matches("\\d+")) hasSize = true;
            if (arg.startsWith("-")) hasQuality = true;
        }

        if (args.length == 1) {
            if ("https://".startsWith(lastArg) || lastArg.isEmpty()) suggestions.add("https://");
            if ("help".startsWith(lastArg)) suggestions.add("help");
            if (sender.hasPermission("emage.admin")) {
                if ("cleanup".startsWith(lastArg)) suggestions.add("cleanup");
                if ("clearcache".startsWith(lastArg)) suggestions.add("clearcache");
                if ("cache".startsWith(lastArg)) suggestions.add("cache");
                if ("stats".startsWith(lastArg)) suggestions.add("stats");
                if ("perf".startsWith(lastArg)) suggestions.add("perf");
                if ("migrate".startsWith(lastArg)) suggestions.add("migrate");
                if ("reload".startsWith(lastArg)) suggestions.add("reload");
                if ("update".startsWith(lastArg)) suggestions.add("update");
            }
            return suggestions;
        }

        if (hasUrl && !hasSize) {
            suggestions.addAll(Arrays.asList("1x1", "2x2", "3x3", "4x4", "2x1", "1x2", "3x2", "2x3"));
        }

        if (hasUrl && !hasQuality) {
            if ("--high".startsWith(lastArg)) suggestions.add("--high");
            if ("--balanced".startsWith(lastArg)) suggestions.add("--balanced");
            if ("--fast".startsWith(lastArg)) suggestions.add("--fast");
            if ("--nocache".startsWith(lastArg)) suggestions.add("--nocache");
        }

        return suggestions;
    }

    private record ProcessedChunk(ItemFrame frame, byte[] data) {}
    private record FrameNode(ItemFrame frame, int gridX, int gridY) {}

    private static class FrameGrid {
        final List<FrameNode> nodes = new ArrayList<>();
        final int width;
        final int height;

        FrameGrid(ItemFrame startFrame, Integer reqWidth, Integer reqHeight) {
            BlockFace facing = startFrame.getFacing();

            Set<ItemFrame> connected = new HashSet<>();
            Queue<ItemFrame> queue = new LinkedList<>();
            Set<UUID> visited = new HashSet<>();

            queue.add(startFrame);
            visited.add(startFrame.getUniqueId());
            connected.add(startFrame);

            while (!queue.isEmpty()) {
                ItemFrame current = queue.poll();

                for (BlockFace dir : getSearchDirections(facing)) {
                    var neighborLoc = current.getLocation().add(dir.getDirection());

                    for (Entity entity : current.getWorld().getNearbyEntities(neighborLoc, 0.5, 0.5, 0.5)) {
                        if (entity instanceof ItemFrame neighbor &&
                                !visited.contains(neighbor.getUniqueId()) &&
                                neighbor.getFacing() == facing) {

                            visited.add(neighbor.getUniqueId());
                            connected.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
            }

            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

            for (ItemFrame f : connected) {
                int x = f.getLocation().getBlockX();
                int y = f.getLocation().getBlockY();
                int z = f.getLocation().getBlockZ();

                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }

            List<FrameNode> allNodes = new ArrayList<>();

            for (ItemFrame f : connected) {
                int wx = f.getLocation().getBlockX();
                int wy = f.getLocation().getBlockY();
                int wz = f.getLocation().getBlockZ();

                int gx, gy;

                switch (facing) {
                    case NORTH -> { gx = maxX - wx; gy = maxY - wy; }
                    case SOUTH -> { gx = wx - minX; gy = maxY - wy; }
                    case WEST -> { gx = wz - minZ; gy = maxY - wy; }
                    case EAST -> { gx = maxZ - wz; gy = maxY - wy; }
                    case UP -> { gx = wx - minX; gy = maxZ - wz; }
                    case DOWN -> { gx = wx - minX; gy = wz - minZ; }
                    default -> { gx = 0; gy = 0; }
                }

                allNodes.add(new FrameNode(f, gx, gy));
            }

            if (reqWidth != null && reqHeight != null) {
                FrameNode anchor = null;
                for (FrameNode node : allNodes) {
                    if (node.frame.getUniqueId().equals(startFrame.getUniqueId())) {
                        anchor = node;
                        break;
                    }
                }

                if (anchor != null) {
                    int ax = anchor.gridX;
                    int ay = anchor.gridY;

                    for (FrameNode node : allNodes) {
                        int rx = node.gridX - ax;
                        int ry = node.gridY - ay;

                        if (rx >= 0 && rx < reqWidth && ry >= 0 && ry < reqHeight) {
                            nodes.add(new FrameNode(node.frame, rx, ry));
                        }
                    }

                    this.width = reqWidth;
                    this.height = reqHeight;
                } else {
                    nodes.addAll(allNodes);
                    this.width = calcWidth(facing, minX, maxX, minZ, maxZ);
                    this.height = calcHeight(facing, minY, maxY, minZ, maxZ);
                }
            } else {
                nodes.addAll(allNodes);
                this.width = calcWidth(facing, minX, maxX, minZ, maxZ);
                this.height = calcHeight(facing, minY, maxY, minZ, maxZ);
            }
        }

        private static BlockFace[] getSearchDirections(BlockFace facing) {
            return switch (facing) {
                case UP, DOWN -> new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
                case NORTH, SOUTH -> new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.EAST, BlockFace.WEST};
                case EAST, WEST -> new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH};
                default -> new BlockFace[0];
            };
        }

        private static int calcWidth(BlockFace f, int minX, int maxX, int minZ, int maxZ) {
            return switch (f) {
                case NORTH, SOUTH, UP, DOWN -> maxX - minX + 1;
                case EAST, WEST -> maxZ - minZ + 1;
                default -> 1;
            };
        }

        private static int calcHeight(BlockFace f, int minY, int maxY, int minZ, int maxZ) {
            return switch (f) {
                case NORTH, SOUTH, EAST, WEST -> maxY - minY + 1;
                case UP, DOWN -> maxZ - minZ + 1;
                default -> 1;
            };
        }
    }
}