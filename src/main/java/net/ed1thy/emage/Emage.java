package net.ed1thy.emage;

import net.ed1thy.emage.command.CommandRegistry;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.config.MessageManager;
import net.ed1thy.emage.listener.ChunkTrackerListener;
import net.ed1thy.emage.listener.FrameInteractListener;
import net.ed1thy.emage.listener.PersistenceListener;
import net.ed1thy.emage.listener.UpdateNotifyListener;
import net.ed1thy.emage.network.ImageDownloader;
import net.ed1thy.emage.network.DnsResolver;
import net.ed1thy.emage.network.EHttpClient;
import net.ed1thy.emage.network.UpdateChecker;
import net.ed1thy.emage.processing.ColorPalette;
import net.ed1thy.emage.processing.ImagePipeline;
import net.ed1thy.emage.render.ChunkViewerTracker;
import net.ed1thy.emage.render.PacketSender;
import net.ed1thy.emage.render.RenderManager;
import net.ed1thy.emage.storage.DatabaseManager;
import net.ed1thy.emage.storage.FlatFileStorage;
import net.ed1thy.emage.storage.MapMetadataRepository;
import net.ed1thy.emage.storage.SchemaInitializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class Emage extends JavaPlugin {

    private DatabaseManager databaseManager;
    private RenderManager renderManager;
    private FlatFileStorage flatFileStorage;

    @Override
    public void onEnable() {
        ConfigManager configManager = new ConfigManager(this);
        configManager.load();

        MessageManager messageManager = new MessageManager(configManager);
        messageManager.load();

        this.databaseManager = new DatabaseManager(this, configManager);
        new SchemaInitializer(databaseManager).initialize();

        MapMetadataRepository metadataRepository = new MapMetadataRepository(databaseManager);
        this.flatFileStorage = new FlatFileStorage(configManager);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                java.util.Set<Integer> activeIds = metadataRepository.getAllSyncGroupIDs();

                int brokenDbEntries = 0;
                for (int id : activeIds) {
                    if (!flatFileStorage.groupExists(id)) {
                        metadataRepository.deleteSyncGroup(id);
                        brokenDbEntries++;
                    }
                }

                activeIds = metadataRepository.getAllSyncGroupIDs();
                int deletedFolders = flatFileStorage.cleanupOrphanedDirectories(activeIds);

                if (brokenDbEntries > 0 || deletedFolders > 0) {
                    getLogger().info(String.format("Removed %d broken DB entries and %d orphaned folders.", brokenDbEntries, deletedFolders));
                }
            } catch (Exception e) {
                getLogger().warning("Failed to run Storage Sweeper: " + e.getMessage());
            }
        });

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            databaseManager.runWalCheckpoint();
        }, 20 * 60 * 60 * 24L, 20 * 60 * 60 * 24L);

        DnsResolver dnsChecker = new DnsResolver(configManager);
        EHttpClient httpClient = new EHttpClient(configManager);
        ImageDownloader imageDownloader = new ImageDownloader(httpClient, dnsChecker, configManager);

        ColorPalette colorLUT = new ColorPalette();
        colorLUT.generateLUT().thenRun(() -> getLogger().info("Color LUT successfully generated."));

        ImagePipeline imagePipeline = new ImagePipeline(colorLUT, flatFileStorage);

        ChunkViewerTracker viewerTracker = new ChunkViewerTracker();
        PacketSender packetSender = new PacketSender();
        this.renderManager = new RenderManager(this, viewerTracker, packetSender, configManager);

        renderManager.start();

        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            viewerTracker.registerPlayer(player);
        }

        ChunkTrackerListener chunkListener = new ChunkTrackerListener(viewerTracker, renderManager);
        FrameInteractListener interactListener = new FrameInteractListener(this);

        getServer().getPluginManager().registerEvents(chunkListener, this);
        getServer().getPluginManager().registerEvents(interactListener, this);
        PersistenceListener persistenceListener = new PersistenceListener(this, interactListener, metadataRepository, renderManager, chunkListener, configManager, flatFileStorage);
        getServer().getPluginManager().registerEvents(persistenceListener, this);

        CommandRegistry commandRegistry = new CommandRegistry(
                this, configManager, messageManager, imageDownloader, metadataRepository,
                imagePipeline, flatFileStorage, renderManager, packetSender, chunkListener,
                interactListener
        );
        commandRegistry.registerCommands();

        if (configManager.checkForUpdates) {
            UpdateChecker updateChecker = new UpdateChecker(this, messageManager);

            getServer().getScheduler().runTaskTimerAsynchronously(this, updateChecker::checkForUpdates, 0L, 20L * 60 * 30);

            getServer().getPluginManager().registerEvents(new UpdateNotifyListener(updateChecker), this);
        } else {
            getLogger().warning("=======================================================");
            getLogger().warning("Update checking is DISABLED in the config.");
            getLogger().warning("This is NOT recommended. You will not be notified");
            getLogger().warning("of critical bug fixes or performance improvements!");
            getLogger().warning("=======================================================");
        }

        getLogger().info("Emage enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Emage shutting down..");

        if (renderManager != null) {
            renderManager.shutdown();
        }

        if (databaseManager != null) {
            databaseManager.closePool();
        }
    }
}