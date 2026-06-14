package net.ed1thy.emage.listener;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.model.MapMetadata;
import net.ed1thy.emage.render.RenderManager;
import net.ed1thy.emage.render.SyncGroup;
import net.ed1thy.emage.storage.FlatFileStorage;
import net.ed1thy.emage.storage.MapMetadataRepository;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class PersistenceListener implements Listener {

    private final Emage plugin;
    private final FrameInteractListener interactListener;
    private final MapMetadataRepository repository;
    private final RenderManager renderManager;
    private final ChunkTrackerListener chunkTrackerListener;
    private final ConfigManager configManager;
    private final FlatFileStorage flatFileStorage;

    public PersistenceListener(@NotNull Emage plugin, @NotNull FrameInteractListener interactListener,
                               @NotNull MapMetadataRepository repository, @NotNull RenderManager renderManager,
                               @NotNull ChunkTrackerListener chunkTrackerListener, @NotNull ConfigManager configManager,
                               @NotNull FlatFileStorage flatFileStorage) {
        this.plugin = plugin;
        this.interactListener = interactListener;
        this.repository = repository;
        this.renderManager = renderManager;
        this.chunkTrackerListener = chunkTrackerListener;
        this.configManager = configManager;
        this.flatFileStorage = flatFileStorage;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<ItemFrame> loadedFrames = new ArrayList<>();
        List<FrameNode> createdNodes = new ArrayList<>();

        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemFrame frame) {
                if (frame.getPersistentDataContainer().has(interactListener.getEmageKey(), PersistentDataType.INTEGER)) {
                    loadedFrames.add(frame);
                    int mapId = frame.getPersistentDataContainer().get(interactListener.getEmageKey(), PersistentDataType.INTEGER);

                    ItemStack bukkitMap = new ItemStack(Material.FILLED_MAP);
                    if (bukkitMap.getItemMeta() instanceof MapMeta mapMeta) {
                        mapMeta.setMapId(mapId);
                        bukkitMap.setItemMeta(mapMeta);
                    }
                    com.github.retrooper.packetevents.protocol.item.ItemStack peItem = SpigotConversionUtil.fromBukkitItemStack(bukkitMap);

                    FrameNode node = new FrameNode(frame.getEntityId(), frame.getUniqueId(), frame.getWorld().getUID(),
                            frame.getLocation().getChunk().getX(), frame.getLocation().getChunk().getZ(),
                            frame.getLocation().getBlockX(), frame.getLocation().getBlockY(), frame.getLocation().getBlockZ(), mapId, peItem);

                    chunkTrackerListener.addNodeToCache(node);
                    createdNodes.add(node);
                }
            }
        }

        UUID worldUuid = event.getChunk().getWorld().getUID();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<UUID> dbExpected = repository.getPlacedFramesInChunk(worldUuid, cx, cz);
                Set<UUID> actualEmageFrames = new HashSet<>();

                for (ItemFrame frame : loadedFrames) {
                    actualEmageFrames.add(frame.getUniqueId());

                    if (!dbExpected.contains(frame.getUniqueId())) {
                        int mapId = frame.getPersistentDataContainer().get(interactListener.getEmageKey(), PersistentDataType.INTEGER);
                        Optional<MapMetadata> metaOpt = repository.getMetadataByMapId(mapId);
                        metaOpt.ifPresent(meta -> {
                            try { repository.addPlacedFrame(meta.syncGroupID(), frame); } catch (Exception ignored) {}
                        });
                    }
                }

                for (UUID expected : dbExpected) {
                    if (!actualEmageFrames.contains(expected)) {
                        int groupId = repository.getGroupIdByFrameUUID(expected);
                        repository.removePlacedFrameByUUID(expected);

                        if (groupId != -1 && repository.countPlacedFrames(groupId) == 0) {
                            flatFileStorage.deleteSyncGroup(groupId);
                            repository.deleteSyncGroup(groupId);
                            Bukkit.getScheduler().runTask(plugin, () -> renderManager.unregisterSyncGroup(groupId));
                        }
                    }
                }

                for (int i = 0; i < loadedFrames.size(); i++) {
                    ItemFrame frame = loadedFrames.get(i);
                    FrameNode node = createdNodes.get(i);
                    int mapId = frame.getPersistentDataContainer().get(interactListener.getEmageKey(), PersistentDataType.INTEGER);

                    Optional<MapMetadata> metaOpt = repository.getMetadataByMapId(mapId);

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (metaOpt.isPresent()) {
                            MapMetadata meta = metaOpt.get();
                            SyncGroup group = renderManager.getSyncGroup(meta.syncGroupID());

                            if (group == null) {
                                group = new SyncGroup(meta, new CopyOnWriteArrayList<>(Collections.singletonList(node)), flatFileStorage, configManager, null);
                                renderManager.registerSyncGroup(meta.syncGroupID(), group);
                            } else {
                                boolean exists = false;
                                for (FrameNode existing : group.getNodes()) {
                                    if (existing.getFrameUUID().equals(node.getFrameUUID())) {
                                        exists = true;
                                        break;
                                    }
                                }
                                if (!exists) {
                                    group.addNewWall(Collections.singletonList(node));
                                }
                            }
                        } else {
                            frame.getPersistentDataContainer().remove(interactListener.getEmageKey());
                            frame.setItem(new ItemStack(Material.AIR));
                            frame.setVisible(true);
                        }
                    });
                }

            } catch (Exception e) {
                plugin.getLogger().warning("Failed GC healing / restore for Emage chunk: " + e.getMessage());
            }
        });
    }
}