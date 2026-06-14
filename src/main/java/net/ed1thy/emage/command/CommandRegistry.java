package net.ed1thy.emage.command;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.config.MessageManager;
import net.ed1thy.emage.listener.ChunkTrackerListener;
import net.ed1thy.emage.listener.FrameInteractListener;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.model.MapMetadata;
import net.ed1thy.emage.network.ImageDownloader;
import net.ed1thy.emage.processing.ImageFrameProvider;
import net.ed1thy.emage.processing.ImagePipeline;
import net.ed1thy.emage.render.PacketSender;
import net.ed1thy.emage.render.RenderManager;
import net.ed1thy.emage.render.SyncGroup;
import net.ed1thy.emage.storage.FlatFileStorage;
import net.ed1thy.emage.storage.MapMetadataRepository;
import net.ed1thy.emage.util.GridUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataType;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CommandRegistry {

    private final Emage plugin;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final ImageDownloader imageDownloader;
    private final MapMetadataRepository repository;
    private final ImagePipeline pipeline;
    private final FlatFileStorage flatFileStorage;
    private final RenderManager renderManager;
    private final PacketSender packetSender;
    private final ChunkTrackerListener chunkTrackerListener;
    private final FrameInteractListener interactListener;

    private final Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();
    private final AtomicInteger activeProcessingTasks = new AtomicInteger(0);

    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CommandRegistry(@NotNull Emage plugin, @NotNull ConfigManager configManager, @NotNull MessageManager messageManager,
                           @NotNull ImageDownloader imageDownloader, @NotNull MapMetadataRepository repository,
                           @NotNull ImagePipeline pipeline, @NotNull FlatFileStorage flatFileStorage,
                           @NotNull RenderManager renderManager, @NotNull PacketSender packetSender,
                           @NotNull ChunkTrackerListener chunkTrackerListener, @NotNull FrameInteractListener interactListener) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.imageDownloader = imageDownloader;
        this.repository = repository;
        this.pipeline = pipeline;
        this.flatFileStorage = flatFileStorage;
        this.renderManager = renderManager;
        this.packetSender = packetSender;
        this.chunkTrackerListener = chunkTrackerListener;
        this.interactListener = interactListener;
    }

    public void registerCommands() {
        PaperCommandManager<CommandSourceStack> commandManager = PaperCommandManager.builder()
                .executionCoordinator(ExecutionCoordinator.asyncCoordinator())
                .buildOnEnable(plugin);

        var builder = commandManager.commandBuilder("emage", "em");

        commandManager.command(builder.literal("apply")
                .required("url", StringParser.stringParser(StringParser.StringMode.GREEDY))
                .handler(ctx -> {
                    if (!(ctx.sender().getSender() instanceof Player player)) {
                        messageManager.sendOnlyPlayers(ctx.sender().getSender());
                        return;
                    }
                    String url = ctx.get("url");
                    Bukkit.getScheduler().runTask(plugin, () -> handleRenderSync(player, url, -1, -1));
                })
        );

        commandManager.command(builder.literal("apply-grid")
                .required("columns", IntegerParser.integerParser(1, configManager.maxImageGridSize))
                .required("rows", IntegerParser.integerParser(1, configManager.maxImageGridSize))
                .required("url", StringParser.stringParser(StringParser.StringMode.GREEDY))
                .handler(ctx -> {
                    if (!(ctx.sender().getSender() instanceof Player player)) {
                        messageManager.sendOnlyPlayers(ctx.sender().getSender());
                        return;
                    }
                    int columns = ctx.get("columns");
                    int rows = ctx.get("rows");
                    String url = ctx.get("url");
                    Bukkit.getScheduler().runTask(plugin, () -> handleRenderSync(player, url, columns, rows));
                })
        );

        commandManager.command(builder.literal("remove")
                .handler(ctx -> {
                    if (!(ctx.sender().getSender() instanceof Player player)) {
                        messageManager.sendOnlyPlayers(ctx.sender().getSender());
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> handleRemoveSync(player));
                })
        );
    }

    private List<ItemFrame> findContiguousEmageFrames(ItemFrame start, List<Integer> validMapIds) {
        List<ItemFrame> result = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        Queue<ItemFrame> queue = new LinkedList<>();

        queue.add(start);
        visited.add(start.getUniqueId());

        while (!queue.isEmpty()) {
            ItemFrame current = queue.poll();
            result.add(current);

            for (Entity entity : current.getNearbyEntities(2.0, 2.0, 2.0)) {
                if (entity instanceof ItemFrame neighbor && !visited.contains(neighbor.getUniqueId())) {
                    if (neighbor.getFacing() == current.getFacing()) {
                        if (neighbor.getPersistentDataContainer().has(interactListener.getEmageKey(), PersistentDataType.INTEGER)) {
                            int nMapId = neighbor.getPersistentDataContainer().get(interactListener.getEmageKey(), PersistentDataType.INTEGER);
                            if (validMapIds.contains(nMapId)) {
                                visited.add(neighbor.getUniqueId());
                                queue.add(neighbor);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private void handleRenderSync(Player player, String url, int inputColumns, int inputRows) {
        if (activeProcessingTasks.get() >= configManager.maxConcurrentTasks) {
            messageManager.sendMaxTasksReached(player);
            return;
        }

        long lastRenderTime = playerCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (System.currentTimeMillis() - lastRenderTime < (configManager.cooldownSeconds * 1000L)) {
            messageManager.sendCooldownActive(player);
            return;
        }

        Entity target = player.getTargetEntity(10);
        if (!(target instanceof ItemFrame clickedFrame)) {
            messageManager.sendNoFrame(player);
            return;
        }

        if (clickedFrame.getPersistentDataContainer().has(interactListener.getEmageKey(), PersistentDataType.INTEGER)) {
            int oldMapId = clickedFrame.getPersistentDataContainer().get(interactListener.getEmageKey(), PersistentDataType.INTEGER);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Optional<MapMetadata> metaOpt = repository.getMetadataByMapId(oldMapId);
                    if (metaOpt.isPresent()) {
                        MapMetadata meta = metaOpt.get();
                        List<Integer> groupMapIds = repository.getMapIdsForGroup(meta.syncGroupID());

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            SyncGroup group = renderManager.getSyncGroup(meta.syncGroupID());
                            List<ItemFrame> wallFrames = findContiguousEmageFrames(clickedFrame, groupMapIds);

                            for (ItemFrame f : wallFrames) {
                                f.getPersistentDataContainer().remove(interactListener.getEmageKey());
                                f.setItem(new ItemStack(Material.AIR));
                                f.setVisible(true);

                                if (group != null) {
                                    group.getNodes().removeIf(node -> {
                                        boolean matches = node.getFrameUUID().equals(f.getUniqueId());
                                        if (matches) chunkTrackerListener.removeNodeFromCache(node);
                                        return matches;
                                    });
                                }
                            }

                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                try {
                                    repository.removePlacedFrames(wallFrames);
                                    if (repository.countPlacedFrames(meta.syncGroupID()) == 0) {
                                        flatFileStorage.deleteSyncGroup(meta.syncGroupID());
                                        repository.deleteSyncGroup(meta.syncGroupID());
                                        Bukkit.getScheduler().runTask(plugin, () -> renderManager.unregisterSyncGroup(meta.syncGroupID()));
                                    }
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Failed GC cleanup on overwrite: " + e.getMessage());
                                }
                            });

                            startImagePipeline(player, url, inputColumns, inputRows, clickedFrame);
                        });
                    } else {
                        Bukkit.getScheduler().runTask(plugin, () -> startImagePipeline(player, url, inputColumns, inputRows, clickedFrame));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to clean up old grid during overwrite: " + e.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> startImagePipeline(player, url, inputColumns, inputRows, clickedFrame));
                }
            });
        } else {
            startImagePipeline(player, url, inputColumns, inputRows, clickedFrame);
        }
    }

    private void spawnMissingParticle(Player player, ItemFrame frame, GridUtil.MissingFrameException e) {
        org.bukkit.Location loc = new org.bukkit.Location(frame.getWorld(), e.x + 0.5, e.y + 0.5, e.z + 0.5);
        player.spawnParticle(org.bukkit.Particle.DUST, loc, 40, 0.2, 0.2, 0.2, new org.bukkit.Particle.DustOptions(org.bukkit.Color.RED, 1.5f));
    }

    private void revertLoadingSpinner(List<ItemFrame> frames) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (ItemFrame frame : frames) {
                frame.setGlowing(false);
                if (frame.getItem().getType() == Material.CLOCK) {
                    frame.setItem(new ItemStack(Material.AIR));
                }
            }
        });
    }

    private void finalizeFrameApplication(Player player, List<ItemFrame> gridFrames, MapMetadata meta, List<Integer> mapIds, int columns, int rows, net.ed1thy.emage.model.MapFrameUpdate dummy, Map<Long, net.ed1thy.emage.model.MapFrameUpdate> processedFrames) {
        try {
            List<FrameNode> nodes = new ArrayList<>();
            com.github.retrooper.packetevents.protocol.player.User user = com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getUser(player);

            for (int i = 0; i < gridFrames.size(); i++) {
                ItemFrame currentFrame = gridFrames.get(i);
                int mapId = mapIds.get(i);

                currentFrame.setRotation(org.bukkit.Rotation.NONE);
                currentFrame.setVisible(false);
                currentFrame.setGlowing(false);
                currentFrame.getPersistentDataContainer().set(interactListener.getEmageKey(), PersistentDataType.INTEGER, mapId);

                ItemStack bukkitMap = new ItemStack(Material.FILLED_MAP);
                if (bukkitMap.getItemMeta() instanceof MapMeta mapMeta) {
                    mapMeta.setMapId(mapId);
                    bukkitMap.setItemMeta(mapMeta);
                }
                currentFrame.setItem(bukkitMap);

                com.github.retrooper.packetevents.protocol.item.ItemStack peItem = SpigotConversionUtil.fromBukkitItemStack(bukkitMap);

                FrameNode node = new FrameNode(currentFrame.getEntityId(), currentFrame.getUniqueId(), currentFrame.getWorld().getUID(),
                        currentFrame.getLocation().getChunk().getX(), currentFrame.getLocation().getChunk().getZ(),
                        currentFrame.getLocation().getBlockX(), currentFrame.getLocation().getBlockY(), currentFrame.getLocation().getBlockZ(), mapId, peItem);

                chunkTrackerListener.addNodeToCache(node);
                nodes.add(node);

                if (user != null) {
                    packetSender.spoofItemFrameMap(user, node);
                }
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    repository.addPlacedFrames(meta.syncGroupID(), gridFrames);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to register placed frames in DB: " + e.getMessage());
                }
            });

            SyncGroup group = renderManager.getSyncGroup(meta.syncGroupID());
            if (group == null) {
                group = new SyncGroup(meta, new CopyOnWriteArrayList<>(nodes), flatFileStorage, configManager, processedFrames);
                final SyncGroup finalGroup = group;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    renderManager.registerSyncGroup(meta.syncGroupID(), finalGroup);
                }, 1L);
            } else {
                group.addNewWall(nodes);
            }

            messageManager.sendActionBar(player, "");
            messageManager.sendSuccess(player, columns * rows);
        } finally {
            activeProcessingTasks.decrementAndGet();
        }
    }

    private void startImagePipeline(Player player, String url, int inputColumns, int inputRows, ItemFrame clickedFrame) {
        GridUtil.GridData gridData;
        try {
            gridData = GridUtil.detectGrid(clickedFrame, inputColumns, inputRows, configManager.maxImageGridSize);
            if (gridData == null) {
                if (inputColumns == -1) messageManager.sendAutoDetectFailed(player);
                else messageManager.sendNotEnoughFrames(player, inputColumns, inputRows);
                return;
            }
        } catch (GridUtil.MissingFrameException e) {
            spawnMissingParticle(player, clickedFrame, e);
            if (inputColumns == -1) messageManager.sendAutoDetectFailed(player);
            else messageManager.sendNotEnoughFrames(player, inputColumns, inputRows);
            return;
        }

        int finalColumns = gridData.columns();
        int finalRows = gridData.rows();
        List<ItemFrame> gridFrames = gridData.frames();

        playerCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        activeProcessingTasks.incrementAndGet();

        for (ItemFrame frame : gridFrames) {
            frame.setItem(new ItemStack(Material.CLOCK));
            frame.setGlowing(true);
        }

        messageManager.sendProcessing(player, finalColumns, finalRows);
        messageManager.sendActionBar(player, "<color:#4CABBB>Checking Cache & Downloading...</color>");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<MapMetadata> urlMeta = repository.getMetadataByUrl(url, finalColumns, finalRows);
                if (urlMeta.isPresent() && flatFileStorage.groupExists(urlMeta.get().syncGroupID())) {
                    MapMetadata meta = urlMeta.get();
                    List<Integer> mapIds = repository.getMapIdsForGroup(meta.syncGroupID());

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        finalizeFrameApplication(player, gridFrames, meta, mapIds, finalColumns, finalRows, null, null);
                    });
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to check URL cache: " + e.getMessage());
            }

            imageDownloader.downloadImageStream(url).whenComplete((inputStream, throwable) -> {
                if (throwable != null) {
                    messageManager.sendError(player, throwable.getMessage());
                    messageManager.sendActionBar(player, "");
                    revertLoadingSpinner(gridFrames);
                    activeProcessingTasks.decrementAndGet();

                    if (!url.matches("(?i).*\\.(png|jpg|jpeg|gif|webp)(\\?.*)?$")) {
                        messageManager.sendSmartUrlHint(player);
                    }
                    return;
                }

                CompletableFuture.runAsync(() -> {
                    try {
                        byte[] imageBytes = inputStream.readAllBytes();
                        MessageDigest md = MessageDigest.getInstance("MD5");
                        byte[] hashBytes = md.digest(imageBytes);
                        StringBuilder sb = new StringBuilder();
                        for (byte b : hashBytes) sb.append(String.format("%02x", b));
                        String fileHash = sb.toString();

                        Optional<MapMetadata> existingMeta = repository.getMetadataByHash(fileHash, finalColumns, finalRows);

                        if (existingMeta.isPresent() && flatFileStorage.groupExists(existingMeta.get().syncGroupID())) {
                            MapMetadata meta = existingMeta.get();
                            List<Integer> mapIds = repository.getMapIdsForGroup(meta.syncGroupID());

                            Bukkit.getScheduler().runTask(plugin, () -> {
                                finalizeFrameApplication(player, gridFrames, meta, mapIds, finalColumns, finalRows, null, null);
                            });
                            return;
                        }

                        javax.imageio.ImageIO.setUseCache(false);
                        boolean isGif = imageBytes.length > 3 && imageBytes[0] == 'G' && imageBytes[1] == 'I' && imageBytes[2] == 'F';
                        ImageFrameProvider provider;

                        if (isGif) {
                            net.ed1thy.emage.processing.GifDecoder gifDecoder = new net.ed1thy.emage.processing.GifDecoder();
                            gifDecoder.read(imageBytes);
                            provider = gifDecoder;
                        } else {
                            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(imageBytes));
                            if (img == null) {
                                messageManager.sendReadError(player, "Unsupported image format or corrupt file.");
                                messageManager.sendActionBar(player, "");
                                revertLoadingSpinner(gridFrames);
                                activeProcessingTasks.decrementAndGet();

                                if (!url.matches("(?i).*\\.(png|jpg|jpeg|gif|webp)(\\?.*)?$")) {
                                    messageManager.sendSmartUrlHint(player);
                                }
                                return;
                            }
                            provider = new net.ed1thy.emage.processing.StaticImageProvider(img);
                        }

                        int totalFrames = provider.getFrameCount();
                        int delayMs = provider.getDelayMs();
                        if (delayMs <= 0) delayMs = 100;

                        boolean isAnimated = totalFrames > 1;

                        if (isAnimated && (finalColumns > configManager.maxGifGridSize || finalRows > configManager.maxGifGridSize)) {
                            messageManager.sendGifSizeLimit(player, configManager.maxGifGridSize, configManager.maxGifGridSize);
                            messageManager.sendActionBar(player, "");
                            revertLoadingSpinner(gridFrames);
                            activeProcessingTasks.decrementAndGet();
                            return;
                        }
                        if (!isAnimated && (finalColumns > configManager.maxImageGridSize || finalRows > configManager.maxImageGridSize)) {
                            messageManager.sendImageSizeLimit(player, configManager.maxImageGridSize, configManager.maxImageGridSize);
                            messageManager.sendActionBar(player, "");
                            revertLoadingSpinner(gridFrames);
                            activeProcessingTasks.decrementAndGet();
                            return;
                        }
                        if (isAnimated && totalFrames > configManager.maxGifFrames) {
                            messageManager.sendGifFrameLimit(player, configManager.maxGifFrames, totalFrames);
                            messageManager.sendActionBar(player, "");
                            revertLoadingSpinner(gridFrames);
                            activeProcessingTasks.decrementAndGet();
                            return;
                        }

                        MapMetadata meta = repository.createSyncGroup(player.getUniqueId(), url, fileHash, finalColumns, finalRows, totalFrames, delayMs);
                        List<Integer> mapIds = repository.allocateVirtualMapIds(meta.syncGroupID(), finalColumns * finalRows);

                        pipeline.processStreamAsync(provider, meta.syncGroupID(), mapIds, finalColumns, finalRows, progress -> {
                            int percent = (int) Math.round(progress * 100);
                            messageManager.sendActionBar(player, "<color:#4CABBB>Processing Frames: <white>" + percent + "%</white></color>");
                        }).whenComplete((processedFrames, err) -> {
                            try {
                                if (err != null) {
                                    err.printStackTrace();
                                    messageManager.sendProcessError(player, err.getMessage());
                                    messageManager.sendActionBar(player, "");
                                    revertLoadingSpinner(gridFrames);
                                    return;
                                }

                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    finalizeFrameApplication(player, gridFrames, meta, mapIds, finalColumns, finalRows, null, processedFrames);
                                });
                            } finally {}
                        });

                    } catch (Exception e) {
                        messageManager.sendReadError(player, e.getMessage());
                        messageManager.sendActionBar(player, "");
                        revertLoadingSpinner(gridFrames);
                        closeStreamQuietly(inputStream);
                        activeProcessingTasks.decrementAndGet();
                    }
                }, vtExecutor);
            });
        });
    }

    private void handleRemoveSync(Player player) {
        Entity target = player.getTargetEntity(10);
        if (!(target instanceof ItemFrame clickedFrame)) {
            messageManager.sendNoFrame(player);
            return;
        }

        if (!clickedFrame.getPersistentDataContainer().has(interactListener.getEmageKey(), PersistentDataType.INTEGER)) {
            messageManager.sendNotEmageFrame(player);
            return;
        }

        int mapId = clickedFrame.getPersistentDataContainer().get(interactListener.getEmageKey(), PersistentDataType.INTEGER);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<MapMetadata> metaOpt = repository.getMetadataByMapId(mapId);
                if (metaOpt.isEmpty()) {
                    messageManager.sendMetadataNotFound(player);
                    return;
                }

                MapMetadata meta = metaOpt.get();
                List<Integer> groupMapIds = repository.getMapIdsForGroup(meta.syncGroupID());

                Bukkit.getScheduler().runTask(plugin, () -> {
                    SyncGroup group = renderManager.getSyncGroup(meta.syncGroupID());
                    List<ItemFrame> wallFrames = findContiguousEmageFrames(clickedFrame, groupMapIds);

                    for (ItemFrame f : wallFrames) {
                        f.getPersistentDataContainer().remove(interactListener.getEmageKey());
                        f.setItem(new ItemStack(Material.AIR));
                        f.setVisible(true);

                        if (group != null) {
                            group.getNodes().removeIf(node -> {
                                boolean matches = node.getFrameUUID().equals(f.getUniqueId());
                                if (matches) chunkTrackerListener.removeNodeFromCache(node);
                                return matches;
                            });
                        }
                    }

                    messageManager.sendGridRemoved(player);

                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            repository.removePlacedFrames(wallFrames);

                            if (repository.countPlacedFrames(meta.syncGroupID()) == 0) {
                                flatFileStorage.deleteSyncGroup(meta.syncGroupID());
                                repository.deleteSyncGroup(meta.syncGroupID());
                                Bukkit.getScheduler().runTask(plugin, () -> renderManager.unregisterSyncGroup(meta.syncGroupID()));
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to run GC check after remove: " + e.getMessage());
                        }
                    });
                });

            } catch (Exception e) {
                messageManager.sendCleanupFailed(player);
            }
        });
    }

    private void closeStreamQuietly(InputStream stream) {
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
    }
}