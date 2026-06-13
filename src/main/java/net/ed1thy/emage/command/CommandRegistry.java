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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

        ItemFrame topLeftFrame = GridUtil.findTopLeftFrame(clickedFrame);

        if (topLeftFrame.getPersistentDataContainer().has(interactListener.getEmageKey(), PersistentDataType.INTEGER)) {
            int oldMapId = topLeftFrame.getPersistentDataContainer().get(interactListener.getEmageKey(), PersistentDataType.INTEGER);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Optional<MapMetadata> metaOpt = repository.getMetadataByMapId(oldMapId);
                    if (metaOpt.isPresent()) {
                        MapMetadata meta = metaOpt.get();
                        SyncGroup group = renderManager.getSyncGroup(meta.syncGroupID());

                        Bukkit.getScheduler().runTask(plugin, () -> {

                            renderManager.unregisterSyncGroup(meta.syncGroupID());

                            if (group != null) {
                                for (FrameNode node : group.getNodes()) {
                                    Entity ent = Bukkit.getEntity(node.getFrameUUID());
                                    if (ent instanceof ItemFrame f) {
                                        f.getPersistentDataContainer().remove(interactListener.getEmageKey());
                                        f.setItem(new ItemStack(Material.AIR));
                                        f.setVisible(true);
                                    }
                                    chunkTrackerListener.removeNodeFromCache(node);
                                }
                            } else {
                                topLeftFrame.getPersistentDataContainer().remove(interactListener.getEmageKey());
                                topLeftFrame.setItem(new ItemStack(Material.AIR));
                                topLeftFrame.setVisible(true);
                            }

                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                try {
                                    flatFileStorage.deleteSyncGroup(meta.syncGroupID());
                                    repository.deleteSyncGroup(meta.syncGroupID());
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Failed to clean up old grid files: " + e.getMessage());
                                }
                            });

                            startImagePipeline(player, url, inputColumns, inputRows, topLeftFrame);
                        });
                    } else {
                        Bukkit.getScheduler().runTask(plugin, () -> startImagePipeline(player, url, inputColumns, inputRows, topLeftFrame));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to clean up old grid during overwrite: " + e.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> startImagePipeline(player, url, inputColumns, inputRows, topLeftFrame));
                }
            });
        } else {
            startImagePipeline(player, url, inputColumns, inputRows, topLeftFrame);
        }
    }

    private void startImagePipeline(Player player, String url, int inputColumns, int inputRows, ItemFrame topLeftFrame) {
        int finalColumns;
        int finalRows;
        List<ItemFrame> gridFrames;

        if (inputColumns == -1) {
            GridUtil.GridData gridData = GridUtil.autoDetectGrid(topLeftFrame, configManager.maxImageGridSize);
            if (gridData == null) {
                messageManager.sendAutoDetectFailed(player);
                return;
            }
            finalColumns = gridData.columns();
            finalRows = gridData.rows();
            gridFrames = gridData.frames();
        } else {
            finalColumns = inputColumns;
            finalRows = inputRows;
            gridFrames = GridUtil.findGrid(topLeftFrame, finalColumns, finalRows);
            if (gridFrames == null || gridFrames.size() != (finalColumns * finalRows)) {
                messageManager.sendNotEnoughFrames(player, finalColumns, finalRows);
                return;
            }
        }

        playerCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        activeProcessingTasks.incrementAndGet();
        messageManager.sendProcessing(player, finalColumns, finalRows);

        imageDownloader.downloadImageStream(url).whenComplete((inputStream, throwable) -> {
            if (throwable != null) {
                messageManager.sendError(player, throwable.getMessage());
                activeProcessingTasks.decrementAndGet();
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
                            try {
                                List<FrameNode> nodes = new ArrayList<>();
                                com.github.retrooper.packetevents.protocol.player.User user = com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getUser(player);

                                for (int i = 0; i < gridFrames.size(); i++) {
                                    ItemFrame currentFrame = gridFrames.get(i);
                                    int mapId = mapIds.get(i);

                                    currentFrame.setRotation(org.bukkit.Rotation.NONE);
                                    currentFrame.setVisible(false);
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

                                SyncGroup group = renderManager.getSyncGroup(meta.syncGroupID());
                                if (group == null) {
                                    group = new SyncGroup(meta, new CopyOnWriteArrayList<>(nodes), flatFileStorage, configManager, null);
                                    final SyncGroup finalGroup = group;
                                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        renderManager.registerSyncGroup(meta.syncGroupID(), finalGroup);
                                    }, 1L);
                                } else {
                                    group.addNewWall(nodes);
                                }

                                messageManager.sendSuccess(player, finalColumns * finalRows);
                            } finally {
                                activeProcessingTasks.decrementAndGet();
                            }
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
                            activeProcessingTasks.decrementAndGet();
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
                        activeProcessingTasks.decrementAndGet();
                        return;
                    }
                    if (!isAnimated && (finalColumns > configManager.maxImageGridSize || finalRows > configManager.maxImageGridSize)) {
                        messageManager.sendImageSizeLimit(player, configManager.maxImageGridSize, configManager.maxImageGridSize);
                        activeProcessingTasks.decrementAndGet();
                        return;
                    }
                    if (isAnimated && totalFrames > configManager.maxGifFrames) {
                        messageManager.sendGifFrameLimit(player, configManager.maxGifFrames, totalFrames);
                        activeProcessingTasks.decrementAndGet();
                        return;
                    }

                    MapMetadata meta = repository.createSyncGroup(player.getUniqueId(), url, fileHash, finalColumns, finalRows, totalFrames, delayMs);
                    List<Integer> mapIds = repository.allocateVirtualMapIds(meta.syncGroupID(), finalColumns * finalRows);

                    pipeline.processStreamAsync(provider, meta.syncGroupID(), mapIds, finalColumns, finalRows).whenComplete((processedFrames, err) -> {
                        try {
                            if (err != null) {
                                err.printStackTrace();
                                messageManager.sendProcessError(player, err.getMessage());
                                return;
                            }

                            Bukkit.getScheduler().runTask(plugin, () -> {
                                List<FrameNode> nodes = new ArrayList<>();
                                com.github.retrooper.packetevents.protocol.player.User user = com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getUser(player);

                                for (int i = 0; i < gridFrames.size(); i++) {
                                    ItemFrame currentFrame = gridFrames.get(i);
                                    int mapId = mapIds.get(i);

                                    currentFrame.setRotation(org.bukkit.Rotation.NONE);
                                    currentFrame.setVisible(false);

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

                                SyncGroup group = new SyncGroup(meta, new CopyOnWriteArrayList<>(nodes), flatFileStorage, configManager, processedFrames);

                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    renderManager.registerSyncGroup(meta.syncGroupID(), group);
                                    messageManager.sendSuccess(player, finalColumns * finalRows);
                                }, 1L);
                            });
                        } finally {
                            activeProcessingTasks.decrementAndGet();
                        }
                    });

                } catch (Exception e) {
                    messageManager.sendReadError(player, e.getMessage());
                    closeStreamQuietly(inputStream);
                    activeProcessingTasks.decrementAndGet();
                }
            }, vtExecutor);
        });
    }

    private void handleRemoveSync(Player player) {
        Entity target = player.getTargetEntity(10);
        if (!(target instanceof ItemFrame clickedFrame)) {
            messageManager.sendNoFrame(player);
            return;
        }

        ItemFrame frame = GridUtil.findTopLeftFrame(clickedFrame);

        if (!frame.getPersistentDataContainer().has(interactListener.getEmageKey(), PersistentDataType.INTEGER)) {
            messageManager.sendNotEmageFrame(player);
            return;
        }

        int mapId = frame.getPersistentDataContainer().get(interactListener.getEmageKey(), PersistentDataType.INTEGER);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<MapMetadata> metaOpt = repository.getMetadataByMapId(mapId);
                if (metaOpt.isEmpty()) {
                    messageManager.sendMetadataNotFound(player);
                    return;
                }

                MapMetadata meta = metaOpt.get();
                SyncGroup group = renderManager.getSyncGroup(meta.syncGroupID());

                Bukkit.getScheduler().runTask(plugin, () -> {
                    renderManager.unregisterSyncGroup(meta.syncGroupID());

                    if (group != null) {
                        for (FrameNode node : group.getNodes()) {
                            Entity ent = Bukkit.getEntity(node.getFrameUUID());
                            if (ent instanceof ItemFrame f) {
                                f.getPersistentDataContainer().remove(interactListener.getEmageKey());
                                f.setItem(new ItemStack(Material.AIR));
                                f.setVisible(true);
                            }
                            chunkTrackerListener.removeNodeFromCache(node);
                        }
                    } else {
                        frame.getPersistentDataContainer().remove(interactListener.getEmageKey());
                        frame.setItem(new ItemStack(Material.AIR));
                        frame.setVisible(true);
                    }

                    messageManager.sendGridRemoved(player);

                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            flatFileStorage.deleteSyncGroup(meta.syncGroupID());
                            repository.deleteSyncGroup(meta.syncGroupID());
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to delete grid files: " + e.getMessage());
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