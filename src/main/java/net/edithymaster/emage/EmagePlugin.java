package net.edithymaster.emage;

import com.github.retrooper.packetevents.PacketEvents;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import net.edithymaster.emage.Command.EmageCommand;
import net.edithymaster.emage.Config.ConfigManager;
import net.edithymaster.emage.Config.EmageConfig;
import net.edithymaster.emage.Manager.EmageManager;
import net.edithymaster.emage.Packet.MapPacketListener;
import net.edithymaster.emage.Packet.MapPacketSender;
import net.edithymaster.emage.Packet.DirectMapSender;
import net.edithymaster.emage.Processing.EmageCore;
import net.edithymaster.emage.Render.GifRenderer;
import net.edithymaster.emage.Util.GifCache;
import net.edithymaster.emage.Util.UpdateChecker;
import net.edithymaster.emage.Util.MessageUtil;

public final class EmagePlugin extends JavaPlugin {

    private EmageManager manager;
    private UpdateChecker updateChecker;
    private EmageConfig emageConfig;
    private EmageCommand emageCommand;
    private MapPacketSender mapPacketSender;

    @Override
    public void onEnable() {
        ConfigManager.initialize(this);
        MessageUtil.init(this);

        int pluginID = 29638;
        Metrics metrics = new Metrics(this, pluginID);

        getLogger().info("Initializing color palette cache. This may take a few seconds on first run...");
        EmageCore.initColorSystem();
        Bukkit.getScheduler().runTaskAsynchronously(this, EmageCore::initColorSystemAsync);

        emageConfig = new EmageConfig(this);
        EmageCore.setConfig(emageConfig);

        GifCache.init(getLogger());
        GifCache.configure(
                emageConfig.getCacheMaxEntries(),
                emageConfig.getCacheMaxMemoryBytes(),
                emageConfig.getCacheExpireMs()
        );

        initPacketSender();

        GifRenderer.init(this, emageConfig, mapPacketSender);

        manager = new EmageManager(this, emageConfig);
        Bukkit.getPluginManager().registerEvents(new net.edithymaster.emage.Manager.EmageListener(this, manager), this);

        PacketEvents.getAPI().getEventManager().registerListener(new MapPacketListener(manager));

        var cmd = getCommand("emage");
        if (cmd != null) {
            EmageCommand exec = new EmageCommand(this, manager);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }

        manager.loadAllMaps();
        registerCustomMetrics(metrics);

        if (getConfig().getBoolean("check-updates", true)) {
            updateChecker = new UpdateChecker(this, "Ed1thy", "Emage");
        }

        getLogger().info(String.format("Emage v%s is running on %s", getDescription().getVersion(), getServer().getVersion()));
    }

    private void initPacketSender() {
        mapPacketSender = new DirectMapSender();
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);

        GifRenderer.stop();
        Bukkit.getScheduler().cancelTasks(this);

        if (updateChecker != null) {
            updateChecker.shutdown();
            updateChecker = null;
        }

        if (emageConfig != null) {
            emageConfig.shutdown();
        }

        if (emageCommand != null) {
            emageCommand.shutdown();
            emageCommand = null;
        }

        if (manager != null) {
            manager.shutdown();
        }

        EmageCore.shutdown();

        int cached = GifCache.clearCache();
        String cacheNote = cached > 0 ? " (Cleared " + cached + " cached items)" : "";
        getLogger().info("Shut down complete." + cacheNote);
    }

    private void registerCustomMetrics(Metrics metrics) {
        metrics.addCustomChart(new SingleLineChart("total_maps", () ->
                manager.getStats().activeMaps
        ));

        metrics.addCustomChart(new SingleLineChart("total_animations", () ->
                manager.getStats().animations
        ));

        metrics.addCustomChart(new SimplePie("uses_animations", () ->
                manager.getStats().animations > 0 ? "Yes" : "No"
        ));

        metrics.addCustomChart(new SimplePie("maps_range", () -> {
            int count = manager.getStats().activeMaps;
            if (count == 0) return "None";
            if (count <= 5) return "1-5";
            if (count <= 15) return "6-15";
            if (count <= 50) return "16-50";
            if (count <= 100) return "51-100";
            return "100+";
        }));

        metrics.addCustomChart(new SimplePie("adaptive_performance", () ->
                emageConfig.isAdaptivePerformance() ? "Enabled" : "Disabled"
        ));
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public EmageConfig getEmageConfig() {
        return emageConfig;
    }

    public MapPacketSender getMapPacketSender() {
        return mapPacketSender;
    }
}