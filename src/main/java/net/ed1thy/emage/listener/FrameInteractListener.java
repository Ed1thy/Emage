package net.ed1thy.emage.listener;

import net.ed1thy.emage.Emage;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

public class FrameInteractListener implements Listener {

    private final NamespacedKey emageKey;

    public FrameInteractListener(@NotNull Emage plugin) {
        this.emageKey = new NamespacedKey(plugin, "emage_map_id");
    }

    @NotNull
    public NamespacedKey getEmageKey() {
        return emageKey;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame frame) {
            if (frame.getPersistentDataContainer().has(emageKey, PersistentDataType.INTEGER)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            if (frame.getPersistentDataContainer().has(emageKey, PersistentDataType.INTEGER)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            if (frame.getPersistentDataContainer().has(emageKey, PersistentDataType.INTEGER)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            if (frame.getPersistentDataContainer().has(emageKey, PersistentDataType.INTEGER)) {
                event.setCancelled(true);
            }
        }
    }
}