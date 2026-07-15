package dev.njes.servuxfolia.entitydata;

import dev.njes.servuxfolia.ServuxFoliaPlugin;
import dev.njes.servuxfolia.scheduler.FoliaTasks;
import dev.njes.servuxfolia.security.TokenBucket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Folia-safe MiniHUD block-entity data provider, initially scoped to beehives. */
public final class EntityDataService {
    private final ServuxFoliaPlugin plugin;
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TokenBucket> limits = new ConcurrentHashMap<>();
    private volatile Settings settings;

    public EntityDataService(ServuxFoliaPlugin plugin) {
        this.plugin = plugin;
        this.settings = Settings.load(plugin);
    }

    public void reload() {
        this.settings = Settings.load(plugin);
        this.limits.clear();
        if (!settings.enabled()) {
            registered.clear();
        }
    }

    public void shutdown() {
        registered.clear();
        limits.clear();
    }

    public void handle(Player player, byte[] payload) {
        FoliaTasks.entity(plugin, player, () -> handleOnPlayerThread(player, payload),
                () -> forget(player.getUniqueId()));
    }

    public void forget(UUID playerId) {
        registered.remove(playerId);
        limits.remove(playerId);
    }

    public int registeredCount() {
        return registered.size();
    }

    private void handleOnPlayerThread(Player player, byte[] payload) {
        Settings current = settings;
        try (var decoded = EntityDataProtocol.decode(payload, current.maxInboundBytes())) {
            switch (decoded.packetType()) {
                case EntityDataProtocol.C2S_METADATA_REQUEST -> {
                    decoded.body().readNbt(NbtAccounter.create(current.maxInboundBytes()));
                    register(player, current);
                }
                case EntityDataProtocol.C2S_BLOCK_ENTITY_REQUEST -> {
                    decoded.body().readVarInt(); // legacy transaction id
                    requestBlockEntity(player, decoded.body().readBlockPos(), current);
                }
                case EntityDataProtocol.C2S_ENTITY_REQUEST -> {
                    decoded.body().readVarInt(); // legacy transaction id
                    int entityId = decoded.body().readVarInt();
                    requestEntity(player, entityId, current);
                }
                default -> plugin.debug("Unknown entity_data packet type " + decoded.packetType());
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Rejected malformed MiniHUD entity_data packet from " + player.getName(), exception);
        }
    }

    private void register(Player player, Settings current) {
        if (!current.enabled() || !player.hasPermission("servuxfolia.entitydata")) {
            forget(player.getUniqueId());
            return;
        }
        registered.add(player.getUniqueId());
        limits.put(player.getUniqueId(), new TokenBucket(current.requestsPerSecond(), current.requestsPerSecond()));
        player.sendPluginMessage(plugin, EntityDataProtocol.CHANNEL,
                EntityDataProtocol.metadata(plugin.getPluginMeta().getVersion()));
    }

    private void requestBlockEntity(Player player, BlockPos pos, Settings current) {
        UUID playerId = player.getUniqueId();
        if (!current.enabled() || !registered.contains(playerId)
                || !player.hasPermission("servuxfolia.entitydata")) {
            return;
        }
        TokenBucket limit = limits.computeIfAbsent(playerId,
                ignored -> new TokenBucket(current.requestsPerSecond(), current.requestsPerSecond()));
        if (!limit.tryAcquire()) {
            return;
        }

        Location origin = player.getLocation();
        World world = origin.getWorld();
        UUID worldId = world.getUID();
        if (distanceSquared(origin, pos) > (long) current.maxDistance() * current.maxDistance()) {
            sendEmpty(player, pos, worldId);
            return;
        }

        try {
            FoliaTasks.region(plugin, world, pos.getX() >> 4, pos.getZ() >> 4, () -> {
                CompoundTag nbt = new CompoundTag();
                try {
                    if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                        var level = ((CraftWorld) world).getHandle();
                        BlockEntity blockEntity = level.getBlockEntity(pos);
                        if (blockEntity != null) {
                            var typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                            if (typeId != null && (!current.allowlistEnabled()
                                    || current.allowedBlockEntities().contains(typeId.toString()))) {
                                nbt = blockEntity.saveWithFullMetadata(level.registryAccess());
                            }
                        }
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.FINE,
                            "Failed to serialize MiniHUD block entity at " + pos, exception);
                }

                byte[] response = EntityDataProtocol.blockEntity(pos, nbt);
                if (response.length > current.maxResponseBytes()) {
                    response = EntityDataProtocol.blockEntity(pos, new CompoundTag());
                }
                send(player, response, worldId);
            });
        } catch (RuntimeException exception) {
            sendEmpty(player, pos, worldId);
        }
    }

    private void requestEntity(Player player, int entityId, Settings current) {
        UUID playerId = player.getUniqueId();
        if (!current.enabled() || !registered.contains(playerId)
                || !player.hasPermission("servuxfolia.entitydata")) {
            return;
        }
        TokenBucket limit = limits.computeIfAbsent(playerId,
                ignored -> new TokenBucket(current.requestsPerSecond(), current.requestsPerSecond()));
        if (!limit.tryAcquire()) {
            return;
        }

        // Entity lookup by numeric id can cross Folia region ownership. Until
        // a safe lookup route is available, answer explicitly with empty NBT
        // instead of touching another region's entity map.
        send(player, EntityDataProtocol.entity(entityId, new CompoundTag()), player.getWorld().getUID());
    }

    private void sendEmpty(Player player, BlockPos pos, UUID worldId) {
        send(player, EntityDataProtocol.blockEntity(pos, new CompoundTag()), worldId);
    }

    private void send(Player player, byte[] payload, UUID worldId) {
        FoliaTasks.entity(plugin, player, () -> {
            if (player.isOnline() && registered.contains(player.getUniqueId())
                    && player.getWorld().getUID().equals(worldId)) {
                player.sendPluginMessage(plugin, EntityDataProtocol.CHANNEL, payload);
            }
        }, () -> forget(player.getUniqueId()));
    }

    private static long distanceSquared(Location location, BlockPos pos) {
        double dx = location.getX() - (pos.getX() + 0.5D);
        double dy = location.getY() - (pos.getY() + 0.5D);
        double dz = location.getZ() - (pos.getZ() + 0.5D);
        return (long) (dx * dx + dy * dy + dz * dz);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Settings(
            boolean enabled,
            int requestsPerSecond,
            int maxDistance,
            int maxInboundBytes,
            int maxResponseBytes,
            boolean allowlistEnabled,
            Set<String> allowedBlockEntities
    ) {
        private static Settings load(ServuxFoliaPlugin plugin) {
            var config = plugin.getConfig();
            return new Settings(
                    config.getBoolean("entity-data.enabled", true),
                    clamp(config.getInt("entity-data.requests-per-second", 20), 1, 200),
                    clamp(config.getInt("entity-data.max-distance", 32), 4, 256),
                    clamp(config.getInt("entity-data.max-inbound-payload-bytes", 8_192), 64, 65_536),
                    clamp(config.getInt("entity-data.max-response-bytes", 262_144), 4_096, 1_048_576),
                    config.getBoolean("entity-data.block-entity-allowlist-enabled", true),
                    Set.copyOf(config.getStringList("entity-data.allowed-block-entities"))
            );
        }
    }
}
