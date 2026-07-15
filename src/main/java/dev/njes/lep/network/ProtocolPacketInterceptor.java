package dev.njes.lep.network;

import dev.njes.lep.PluginSettings;
import dev.njes.lep.RuntimeStats;
import dev.njes.lep.protocol.PendingPlacement;
import dev.njes.lep.protocol.PendingPlacementStore;
import dev.njes.lep.protocol.ProtocolCoordinates;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class ProtocolPacketInterceptor implements Listener {
    private static final String HANDLER_NAME = "lep_protocol_decoder";

    private final JavaPlugin plugin;
    private final Supplier<PluginSettings> settings;
    private final PendingPlacementStore store;
    private final RuntimeStats stats;
    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();

    public ProtocolPacketInterceptor(
            JavaPlugin plugin,
            Supplier<PluginSettings> settings,
            PendingPlacementStore store,
            RuntimeStats stats
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.stats = stats;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        inject(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        store.clear(event.getPlayer().getUniqueId());
        remove(event.getPlayer());
    }

    public void inject(Player player) {
        if (!player.hasPermission("litematica.place")) {
            store.clear(player.getUniqueId());
            remove(player);
            return;
        }

        try {
            UUID playerId = player.getUniqueId();
            Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
            Channel previous = channels.put(playerId, channel);
            if (previous != null && previous != channel) {
                removeHandler(previous);
            }
            channel.eventLoop().execute(() -> {
                // Permission refresh runs periodically. Do not churn the Netty
                // pipeline when this exact connection is already injected.
                if (channel.pipeline().get(HANDLER_NAME) == null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new PacketHandler(playerId));
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to inject protocol handler for " + player.getName(), exception);
        }
    }

    public void remove(Player player) {
        remove(player.getUniqueId());
    }

    public void shutdown() {
        channels.forEach((playerId, channel) -> removeHandler(channel));
        channels.clear();
    }

    private void remove(UUID playerId) {
        Channel channel = channels.remove(playerId);
        if (channel != null) {
            removeHandler(channel);
        }
    }

    private void removeHandler(Channel channel) {
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        });
    }

    private final class PacketHandler extends ChannelDuplexHandler {
        private final UUID playerId;

        private PacketHandler(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            Object forwarded = message;
            if (message instanceof ServerboundUseItemOnPacket packet) {
                forwarded = handleUseItemOn(playerId, packet);
            }
            super.channelRead(context, forwarded);
        }
    }

    private ServerboundUseItemOnPacket handleUseItemOn(UUID playerId, ServerboundUseItemOnPacket packet) {
        PluginSettings current = settings.get();
        if (!current.enabled()) {
            return packet;
        }

        BlockHitResult originalHit = packet.getHitResult();
        BlockPos clicked = originalHit.getBlockPos();
        Vec3 location = originalHit.getLocation();

        return ProtocolCoordinates.decode(location.x, clicked.getX(), current.maxPayload())
                .map(decoded -> {
                    store.enqueue(playerId, new PendingPlacement(
                            clicked.getX(),
                            clicked.getY(),
                            clicked.getZ(),
                            originalHit.getDirection().getStepX(),
                            originalHit.getDirection().getStepY(),
                            originalHit.getDirection().getStepZ(),
                            packet.getHand().ordinal(),
                            decoded.payload(),
                            current.protocolVersion(),
                            System.nanoTime()
                    ));
                    stats.captured();

                    // Upstream Servux bypasses the vanilla distance check for
                    // all three axes. Keeping Litematica's original Y/Z can
                    // still make Folia reject the packet before BlockPlaceEvent,
                    // so forward the center of the selected face instead.
                    Vec3 normalizedLocation = new Vec3(
                            ProtocolCoordinates.safeFaceCoordinate(clicked.getX(), originalHit.getDirection().getStepX()),
                            ProtocolCoordinates.safeFaceCoordinate(clicked.getY(), originalHit.getDirection().getStepY()),
                            ProtocolCoordinates.safeFaceCoordinate(clicked.getZ(), originalHit.getDirection().getStepZ())
                    );
                    BlockHitResult normalizedHit = new BlockHitResult(
                            normalizedLocation,
                            originalHit.getDirection(),
                            clicked,
                            originalHit.isInside()
                    );

                    return new ServerboundUseItemOnPacket(packet.getHand(), normalizedHit, packet.getSequence());
                })
                .orElse(packet);
    }
}
