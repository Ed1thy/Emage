package net.ed1thy.emage.listener;

import net.ed1thy.emage.network.UpdateChecker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

public class UpdateNotifyListener implements Listener {

    private final UpdateChecker updateChecker;

    public UpdateNotifyListener(@NotNull UpdateChecker updateChecker) {
        this.updateChecker = updateChecker;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event.getPlayer().hasPermission("emage.admin")) {
            updateChecker.notifyPlayer(event.getPlayer());
        }
    }
}