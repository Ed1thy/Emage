package net.edithymaster.emage.Command;

import net.edithymaster.emage.Processing.EmageColors;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.util.RayTraceResult;
import net.edithymaster.emage.*;
import net.edithymaster.emage.Manager.EmageManager;
import net.edithymaster.emage.Util.MessageUtil;
import net.edithymaster.emage.Util.FrameGrid;
import net.edithymaster.emage.Util.FrameGrid.FrameNode;
import net.edithymaster.emage.Processing.ImageProcessor;

import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class EmageCommand implements CommandExecutor, TabCompleter {

    private final EmagePlugin plugin;
    private final EmageManager manager;

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicLong idGenerator;

    private int cleanupTaskId = -1;

    public EmageCommand(EmagePlugin p, EmageManager m) {
        plugin = p;
        manager = m;

        this.idGenerator = new AtomicLong(
                System.currentTimeMillis() * 1000 + (System.nanoTime() % 1000)
        );

        cleanupTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            long cooldownMs = plugin.getEmageConfig().getCooldownMs();
            cooldowns.entrySet().removeIf(e -> now - e.getValue() >= cooldownMs);
        }, 100L, 100L).getTaskId();
    }

    public void shutdown() {
        if (cleanupTaskId != -1) {
            Bukkit.getScheduler().cancelTask(cleanupTaskId);
            cleanupTaskId = -1;
        }
        cooldowns.clear();
    }

    private long generateUniqueId() {
        return idGenerator.incrementAndGet();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player pl)) {
            sender.sendMessage(MessageUtil.msg("players-only"));
            return true;
        }

        long now = System.currentTimeMillis();
        long cooldownMs = plugin.getEmageConfig().getCooldownMs();

        if (args.length >= 1 && args[0].startsWith("http")) {
            Long lastUse = cooldowns.get(pl.getUniqueId());
            if (lastUse != null && now - lastUse < cooldownMs) {
                long remaining = (cooldownMs - (now - lastUse)) / 1000 + 1;
                pl.sendMessage(MessageUtil.msg("cooldown", "<remaining>", String.valueOf(remaining)));
                return true;
            }
            cooldowns.put(pl.getUniqueId(), now);
        }

        if (!pl.hasPermission("emage.use")) {
            pl.sendMessage(MessageUtil.msg("no-perm"));
            return true;
        }

        if (args.length < 1) {
            pl.sendMessage(MessageUtil.msg("usage"));
            return true;
        }

        if (SubCommandExecutor.execute(pl, args, plugin, manager)) {
            return true;
        }

        String urlStr = null;
        Integer reqWidth = null;
        Integer reqHeight = null;
        boolean noCache = false;

        for (String arg : args) {
            if (arg.startsWith("http://") || arg.startsWith("https://")) {
                urlStr = arg;
            } else if (arg.startsWith("--") || arg.startsWith("-")) {
                String flag = arg.replaceFirst("^-+", "").toLowerCase();
                if (flag.equals("nocache") || flag.equals("nc") || flag.equals("fresh")) {
                    noCache = true;
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
                        pl.sendMessage(MessageUtil.msg("invalid-size"));
                        return true;
                    }
                } catch (NumberFormatException e) {
                    pl.sendMessage(MessageUtil.msg("invalid-size"));
                    return true;
                }
            }
        }

        if (urlStr == null) {
            pl.sendMessage(MessageUtil.msg("invalid-url"));
            return true;
        }

        try {
            URI uri = new URI(urlStr);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                pl.sendMessage(MessageUtil.msg("invalid-url"));
                return true;
            }
            uri.toURL();
        } catch (URISyntaxException | MalformedURLException e) {
            pl.sendMessage(MessageUtil.msg("invalid-url"));
            return true;
        }

        ItemFrame targetFrame = getTargetFrame(pl);
        if (targetFrame == null) {
            pl.sendMessage(MessageUtil.msg("no-frame"));
            return true;
        }

        int maxConfigGridSize = Math.max(
                plugin.getEmageConfig().getMaxImageGridSize(),
                plugin.getEmageConfig().getMaxGifGridSize()
        );
        FrameGrid grid = new FrameGrid(targetFrame, reqWidth, reqHeight, maxConfigGridSize);

        pl.sendMessage(MessageUtil.msg("detected",
                "<width>", String.valueOf(grid.width),
                "<height>", String.valueOf(grid.height),
                "<facing>", targetFrame.getFacing().toString()));

        final int gridWidth = grid.width;
        final int gridHeight = grid.height;
        final List<FrameNode> frameNodes = new ArrayList<>(grid.nodes);
        final String finalUrl = urlStr;
        final boolean finalNoCache = noCache;

        int maxTasks = plugin.getEmageConfig().getMaxConcurrentTasks();
        if (activeTasks.get() >= maxTasks) {
            pl.sendMessage(MessageUtil.msg("server-busy"));
            return true;
        }

        activeTasks.incrementAndGet();

        if (!EmageColors.isCacheReady()) {
            activeTasks.decrementAndGet();
            pl.sendMessage(MessageUtil.msg("color-init"));
            return true;
        }

        ImageProcessor.processUrl(pl, finalUrl, frameNodes, gridWidth, gridHeight, finalNoCache, plugin, manager, activeTasks, generateUniqueId());

        return true;
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
        boolean hasNoCache = false;

        for (String arg : args) {
            if (arg.startsWith("http")) hasUrl = true;
            if (arg.matches("\\d+x\\d+") || arg.matches("\\d+")) hasSize = true;
            if (arg.equalsIgnoreCase("--nocache") || arg.equalsIgnoreCase("-nc")) hasNoCache = true;
        }

        if (args.length == 1) {
            if ("https://".startsWith(lastArg) || lastArg.isEmpty()) suggestions.add("https://");
            if ("help".startsWith(lastArg)) suggestions.add("help");
            if ("remove".startsWith(lastArg)) suggestions.add("remove");
            if (sender.hasPermission("emage.admin")) {
                if ("cleanup".startsWith(lastArg)) suggestions.add("cleanup");
                if ("clearcache".startsWith(lastArg)) suggestions.add("clearcache");
                if ("cache".startsWith(lastArg)) suggestions.add("cache");
                if ("stats".startsWith(lastArg)) suggestions.add("stats");
                if ("perf".startsWith(lastArg)) suggestions.add("perf");
                if ("reload".startsWith(lastArg)) suggestions.add("reload");
                if ("update".startsWith(lastArg)) suggestions.add("update");
                if ("synccolors".startsWith(lastArg)) suggestions.add("synccolors");
            }
            return suggestions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("remove") && sender.hasPermission("emage.admin")) {
            if ("all".startsWith(lastArg)) suggestions.add("all");
            return suggestions;
        }

        if (hasUrl && !hasSize) {
            suggestions.addAll(Arrays.asList("1x1", "2x2", "3x3", "4x4", "2x1", "1x2", "3x2", "2x3"));
        }

        if (hasUrl && !hasNoCache && !lastArg.matches("\\d+x\\d+") && !lastArg.matches("\\d+")) {
            if ("--nocache".startsWith(lastArg)) suggestions.add("--nocache");
        }

        return suggestions;
    }
}