package dev.njes.servuxfolia;

import dev.njes.servuxfolia.entitydata.EntityDataProtocol;
import dev.njes.servuxfolia.entitydata.EntityDataService;
import dev.njes.servuxfolia.litematics.LitematicsService;
import dev.njes.servuxfolia.protocol.ProtocolConstants;
import dev.njes.servuxfolia.structures.StructureProtocol;
import dev.njes.servuxfolia.structures.StructureService;
import dev.njes.lep.PluginSettings;
import dev.njes.lep.RuntimeStats;
import dev.njes.lep.network.ProtocolPacketInterceptor;
import dev.njes.lep.placement.BlockPlaceListener;
import dev.njes.lep.placement.PlacementStateDecoder;
import dev.njes.lep.protocol.PendingPlacementStore;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

public final class ServuxFoliaPlugin extends JavaPlugin implements Listener {
    private LitematicsService litematics;
    private StructureService structures;
    private EntityDataService entityData;
    private volatile PluginSettings easyPlaceSettings;
    private PendingPlacementStore pendingPlacements;
    private RuntimeStats easyPlaceStats;
    private ProtocolPacketInterceptor easyPlaceInterceptor;
    private ScheduledTask placementCleanup;
    private ScheduledTask permissionRefresh;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        this.litematics = new LitematicsService(this);
        this.structures = new StructureService(this);
        this.entityData = new EntityDataService(this);
        this.easyPlaceSettings = PluginSettings.load(getConfig());
        this.pendingPlacements = new PendingPlacementStore(
                easyPlaceSettings.pendingTtl(), easyPlaceSettings.maxPendingPerPlayer());
        this.easyPlaceStats = new RuntimeStats();
        this.easyPlaceInterceptor = new ProtocolPacketInterceptor(
                this, () -> easyPlaceSettings, pendingPlacements, easyPlaceStats);
        getServer().getMessenger().registerIncomingPluginChannel(
                this, ProtocolConstants.LITEMATICS_CHANNEL, this::onPluginMessage);
        getServer().getMessenger().registerOutgoingPluginChannel(
                this, ProtocolConstants.LITEMATICS_CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(
                this, StructureProtocol.CHANNEL, this::onPluginMessage);
        getServer().getMessenger().registerOutgoingPluginChannel(
                this, StructureProtocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(
                this, EntityDataProtocol.CHANNEL, this::onPluginMessage);
        getServer().getMessenger().registerOutgoingPluginChannel(
                this, EntityDataProtocol.CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(entityData, this);
        getServer().getPluginManager().registerEvents(easyPlaceInterceptor, this);
        getServer().getPluginManager().registerEvents(new BlockPlaceListener(
                this, () -> easyPlaceSettings, pendingPlacements,
                new PlacementStateDecoder(), easyPlaceStats), this);
        getServer().getOnlinePlayers().forEach(easyPlaceInterceptor::inject);
        this.placementCleanup = getServer().getAsyncScheduler().runAtFixedRate(
                this, ignored -> pendingPlacements.cleanup(System.nanoTime()),
                1L, 1L, TimeUnit.SECONDS);
        this.permissionRefresh = getServer().getGlobalRegionScheduler().runAtFixedRate(
                this, ignored -> refreshEasyPlacePermissions(), 100L, 100L);
        this.structures.start();

        boolean advertise = getConfig().getBoolean("litematics.advertise", false);
        getLogger().info("ServuxFolia " + getPluginMeta().getVersion()
                + " enabled; litematics metadata advertising=" + advertise
                + "; Easy Place=" + easyPlaceSettings.protocolVersion());
        if (!advertise) {
            getLogger().info("Full litematics metadata advertising is disabled by configuration; Easy Place, MiniHUD structures and entity data remain available.");
        }
    }

    @Override
    public void onDisable() {
        if (placementCleanup != null) {
            placementCleanup.cancel();
        }
        if (permissionRefresh != null) {
            permissionRefresh.cancel();
        }
        if (easyPlaceInterceptor != null) {
            easyPlaceInterceptor.shutdown();
        }
        if (structures != null) {
            structures.shutdown();
        }
        if (entityData != null) {
            entityData.shutdown();
        }
        if (pendingPlacements != null) {
            pendingPlacements.clearAll();
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        litematics.forget(event.getPlayer().getUniqueId());
        structures.forget(event.getPlayer().getUniqueId());
        entityData.forget(event.getPlayer().getUniqueId());
    }

    private void onPluginMessage(String channel, Player player, byte[] payload) {
        if (channel.equals(ProtocolConstants.LITEMATICS_CHANNEL)) {
            litematics.handle(player, payload.clone());
        } else if (channel.equals(StructureProtocol.CHANNEL)) {
            structures.handle(player, payload.clone());
        } else if (channel.equals(EntityDataProtocol.CHANNEL)) {
            entityData.handle(player, payload.clone());
        }
    }

    public void debug(String message) {
        if (getConfig().getBoolean("logging.debug", false)) {
            getLogger().info("[debug] " + message);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("servuxfolia.admin")) {
                sender.sendMessage("You do not have permission.");
                return true;
            }
            reloadConfig();
            easyPlaceSettings = PluginSettings.load(getConfig());
            pendingPlacements.reconfigure(
                    easyPlaceSettings.pendingTtl(), easyPlaceSettings.maxPendingPerPlayer());
            structures.reload();
            entityData.reload();
            refreshEasyPlacePermissions();
            sender.sendMessage("ServuxFolia configuration reloaded.");
            return true;
        }
        sender.sendMessage("ServuxFolia " + getPluginMeta().getVersion()
                + " | litematics=" + getConfig().getBoolean("litematics.enabled", true)
                + " | advertise=" + getConfig().getBoolean("litematics.advertise", false)
                + " | directPaste=" + getConfig().getBoolean("direct-paste.enabled", false)
                + " (active=" + litematics.activePasteCount() + ")"
                + " | structures=" + getConfig().getBoolean("structures.enabled", true)
                + " (clients=" + structures.registeredCount() + ")"
                + " | entityData=" + getConfig().getBoolean("entity-data.enabled", true)
                + " (clients=" + entityData.registeredCount()
                + ", tracked=" + entityData.trackedEntityCount() + ")"
                + " | easyPlace=" + easyPlaceSettings.protocolVersion()
                + " | captured/applied/rejected=" + easyPlaceStats.capturedCount()
                + "/" + easyPlaceStats.appliedCount() + "/" + easyPlaceStats.rejectedCount()
                + " | buildChecks/prevalidated=" + easyPlaceStats.buildCheckedCount()
                + "/" + easyPlaceStats.prevalidatedCount()
                + " | pending=" + pendingPlacements.pendingCount()
                + " | resynced=" + easyPlaceStats.resyncedCount());
        return true;
    }

    private void refreshEasyPlacePermissions() {
        if (easyPlaceInterceptor == null) {
            return;
        }
        getServer().getOnlinePlayers().forEach(player -> player.getScheduler().execute(
                this, () -> easyPlaceInterceptor.inject(player),
                () -> pendingPlacements.clear(player.getUniqueId()), 1L));
    }

    private void migrateConfig() {
        int version = getConfig().getInt("config-version", 1);
        if (version >= 5) {
            return;
        }

        // Only migrate values that are still exactly the old shipped
        // defaults. Explicit administrator choices remain untouched.
        if (getConfig().getLong("security.pending-ttl-ms", 750L) == 750L) {
            getConfig().set("security.pending-ttl-ms", 2_000L);
        }
        if (getConfig().getInt("litematics.max-reassembled-payload-bytes", 134_217_728)
                == 134_217_728) {
            getConfig().set("litematics.max-reassembled-payload-bytes", 33_554_432);
        }
        if (getConfig().getInt("direct-paste.max-uncompressed-bytes", 67_108_864)
                == 67_108_864) {
            getConfig().set("direct-paste.max-uncompressed-bytes", 33_554_432);
        }
        if (getConfig().getLong("direct-paste.max-volume", 50_000_000L) == 50_000_000L) {
            getConfig().set("direct-paste.max-volume", 5_000_000L);
        }
        getConfig().addDefault("litematics.max-inbound-sessions", 4);
        getConfig().addDefault("direct-paste.max-concurrent", 1);
        getConfig().addDefault("entity-data.entity-requests-per-second", 64);
        getConfig().addDefault("entity-data.entity-allowlist-enabled", true);
        getConfig().addDefault("entity-data.allowed-entities",
                java.util.List.of("minecraft:villager", "minecraft:zombie_villager"));
        getConfig().set("config-version", 5);
        getConfig().options().copyDefaults(true);
        saveConfig();
        getLogger().info("Migrated configuration defaults to version 5.");
    }
}
