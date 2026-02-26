package net.edithymaster.emage.Manager;

import net.edithymaster.emage.EmagePlugin;
import net.edithymaster.emage.Render.GifRenderer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

public class EmageListener implements Listener {

    private final EmagePlugin plugin;
    private final EmageManager manager;

    public EmageListener(EmagePlugin plugin, EmageManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof ItemFrame frame) {
                ItemStack item = frame.getItem();
                if (item.getType() == org.bukkit.Material.FILLED_MAP) {
                    MapMeta meta = (MapMeta) item.getItemMeta();
                    if (meta != null && meta.hasMapView()) {
                        MapView view = meta.getMapView();
                        if (view != null) {
                            int mapId = view.getId();
                            if (manager.getMapCache().containsKey(mapId)) {
                                GifRenderer.registerMapLocation(mapId, frame.getLocation());
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        if (!manager.isDatabaseReady()) return;

        long now = System.currentTimeMillis();
        if (now - manager.getLastWorldSaveTime().get() < 5000) return;
        manager.getLastWorldSaveTime().set(now);

        manager.flushAllPendingSavesAsync();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World loadedWorld = event.getWorld();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Integer mapId : manager.getMapCache().keySet()) {
                if (manager.getAppliedMaps().contains(mapId)) continue;

                MapView mapView = Bukkit.getMap(mapId);
                if (mapView != null && loadedWorld.equals(mapView.getWorld())) {
                    manager.applyRendererToMapPublic(mapId);
                }
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            handleFrameDestruction(frame);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            handleFrameDestruction(frame);
        }
    }

    private void handleFrameDestruction(ItemFrame frame) {
        ItemStack item = frame.getItem();
        if (item.getType() == org.bukkit.Material.FILLED_MAP) {
            MapMeta meta = (MapMeta) item.getItemMeta();
            if (meta != null && meta.hasMapView()) {
                MapView mapView = meta.getMapView();
                if (mapView == null) return;

                int mapId = mapView.getId();
                if (manager.getManagedMaps().contains(mapId)) {
                    manager.destroyMapDataPublic(mapId);
                }
            }
        }
    }
}