package dev.njes.lep.placement;

import dev.njes.lep.PluginSettings;
import dev.njes.lep.RuntimeStats;
import dev.njes.lep.protocol.PendingPlacement;
import dev.njes.lep.protocol.PendingPlacementStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.bukkit.block.Block;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockCanBuildEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class BlockPlaceListener implements Listener {
    private final JavaPlugin plugin;
    private final Supplier<PluginSettings> settings;
    private final PendingPlacementStore store;
    private final PlacementStateDecoder decoder;
    private final RuntimeStats stats;

    public BlockPlaceListener(
            JavaPlugin plugin,
            Supplier<PluginSettings> settings,
            PendingPlacementStore store,
            PlacementStateDecoder decoder,
            RuntimeStats stats
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.decoder = decoder;
        this.stats = stats;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockCanBuild(BlockCanBuildEvent event) {
        if (event.getPlayer() == null
                || !event.getPlayer().hasPermission("litematica.place")
                || !(event.getBlockData() instanceof CraftBlockData craftData)) {
            return;
        }

        int hand = event.getHand() == EquipmentSlot.OFF_HAND ? 1 : 0;
        Block block = event.getBlock();
        Optional<PendingPlacement> pending = store.find(
                event.getPlayer().getUniqueId(), hand, position(block), System.nanoTime());
        if (pending.isEmpty()) {
            return;
        }
        stats.buildChecked();
        if (event.isBuildable()) {
            return;
        }

        ServerLevel level = ((CraftWorld) block.getWorld()).getHandle();
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        BlockPos blockPosition = new BlockPos(block.getX(), block.getY(), block.getZ());
        PluginSettings current = settings.get();
        Optional<BlockState> decoded = decoder.decode(
                craftData.getState(),
                pending.get().payload(),
                pending.get().version(),
                level,
                blockPosition,
                player,
                current.validateStates()
        );

        if (decoded.isEmpty()) {
            return;
        }

        BlockState target = decoded.get();
        boolean survives = !current.validateStates() || target.canSurvive(level, blockPosition);
        boolean collisionFree = level.checkEntityCollision(
                target, player, CollisionContext.placementContext(player), blockPosition, true);

        if (survives && collisionFree) {
            event.setBuildable(true);
            stats.prevalidated();
            if (current.debug()) {
                plugin.getLogger().info("Pre-validated " + pending.get().version()
                        + " payload 0x" + Integer.toHexString(pending.get().payload())
                        + " at " + block.getX() + "," + block.getY() + "," + block.getZ());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!event.getPlayer().hasPermission("litematica.place")) {
            return;
        }

        Block against = event.getBlockAgainst();
        Block placed = event.getBlockPlaced();
        int hand = event.getHand() == EquipmentSlot.OFF_HAND ? 1 : 0;

        Optional<PendingPlacement> pending = store.consume(
                event.getPlayer().getUniqueId(),
                hand,
                position(against),
                position(placed),
                System.nanoTime()
        );

        if (pending.isEmpty()) {
            return;
        }

        PluginSettings current = settings.get();
        try {
            ServerLevel level = ((CraftWorld) placed.getWorld()).getHandle();
            ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
            BlockPos blockPosition = new BlockPos(placed.getX(), placed.getY(), placed.getZ());
            BlockState initialState = level.getBlockState(blockPosition);

            Optional<BlockState> decoded = decoder.decode(
                    initialState,
                    pending.get().payload(),
                    pending.get().version(),
                    level,
                    blockPosition,
                    player,
                    current.validateStates()
            );

            PlacementMutation.Result mutation = PlacementMutation.apply(
                    initialState,
                    decoded,
                    target -> level.setBlock(blockPosition, target, 3),
                    () -> level.getBlockState(blockPosition)
            );

            if (!mutation.protocolApplied()) {
                stats.rejected();
            } else {
                stats.applied();
            }
            scheduleClientResync(event.getPlayer(), placed, false);

            if (current.debug()) {
                plugin.getLogger().info(mutation + " " + pending.get().version()
                        + " payload 0x" + Integer.toHexString(pending.get().payload())
                        + " at " + placed.getX() + "," + placed.getY() + "," + placed.getZ());
            }
        } catch (RuntimeException exception) {
            stats.rejected();
            scheduleClientResync(event.getPlayer(), placed, false);
            plugin.getLogger().log(Level.WARNING, "Failed to apply placement protocol state", exception);
        }
    }

    /**
     * Waits until the Bukkit placement transaction has committed or rolled
     * back, reads the authoritative block state on its owning region, then
     * sends that state to the player on the entity scheduler. This repairs
     * client-side placement prediction after a V3 rewrite or rejection.
     */
    private void scheduleClientResync(org.bukkit.entity.Player player, Block block, boolean inventory) {
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        long delay = Math.max(1L, plugin.getConfig().getLong("security.client-resync-delay-ticks", 1L));

        Bukkit.getRegionScheduler().runDelayed(
                plugin,
                world,
                x >> 4,
                z >> 4,
                ignored -> {
                    String authoritative = world.getBlockAt(x, y, z).getBlockData().getAsString();
                    player.getScheduler().run(plugin, playerTask -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (player.getWorld().equals(world)) {
                            Location location = new Location(world, x, y, z);
                            player.sendBlockChange(location, Bukkit.createBlockData(authoritative));
                        }
                        if (inventory) {
                            player.updateInventory();
                        }
                        stats.resynced();
                    }, null);
                },
                delay
        );
    }

    private PendingPlacement.Position position(Block block) {
        return new PendingPlacement.Position(block.getX(), block.getY(), block.getZ());
    }
}
