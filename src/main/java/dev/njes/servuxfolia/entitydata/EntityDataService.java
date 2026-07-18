package dev.njes.servuxfolia.entitydata;

import dev.njes.servuxfolia.ServuxFoliaPlugin;
import dev.njes.servuxfolia.scheduler.FoliaTasks;
import dev.njes.servuxfolia.security.TokenBucket;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

/** Folia-safe MiniHUD block-entity and tracked-entity data provider. */
public final class EntityDataService implements Listener {
    private final ServuxFoliaPlugin plugin;
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Limits> limits = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentMap<Integer, Entity>> trackedEntities = new ConcurrentHashMap<>();
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
        trackedEntities.clear();
    }

    public void handle(Player player, byte[] payload) {
        FoliaTasks.entity(plugin, player, () -> handleOnPlayerThread(player, payload),
                () -> forget(player.getUniqueId()));
    }

    public void forget(UUID playerId) {
        registered.remove(playerId);
        limits.remove(playerId);
        trackedEntities.remove(playerId);
    }

    public int registeredCount() {
        return registered.size();
    }

    public int trackedEntityCount() {
        return trackedEntities.values().stream().mapToInt(Map::size).sum();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTrackEntity(PlayerTrackEntityEvent event) {
        Entity entity = event.getEntity();
        trackedEntities.computeIfAbsent(event.getPlayer().getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .put(entity.getEntityId(), entity);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerUntrackEntity(PlayerUntrackEntityEvent event) {
        removeTracked(event.getPlayer().getUniqueId(), event.getEntity().getEntityId(), event.getEntity());
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
        limits.put(player.getUniqueId(), newLimits(current));
        player.sendPluginMessage(plugin, EntityDataProtocol.CHANNEL,
                EntityDataProtocol.metadata(plugin.getPluginMeta().getVersion()));
    }

    private void requestBlockEntity(Player player, BlockPos pos, Settings current) {
        UUID playerId = player.getUniqueId();
        if (!current.enabled() || !registered.contains(playerId)
                || !player.hasPermission("servuxfolia.entitydata")) {
            return;
        }
        Limits playerLimits = limits.computeIfAbsent(playerId, ignored -> newLimits(current));
        if (!playerLimits.blockEntities().tryAcquire()) {
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
                            if (typeId != null && (!current.blockEntityAllowlistEnabled()
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
        Limits playerLimits = limits.computeIfAbsent(playerId, ignored -> newLimits(current));
        if (!playerLimits.entities().tryAcquire()) {
            return;
        }

        UUID worldId = player.getWorld().getUID();
        Location origin = player.getLocation();
        Map<Integer, Entity> playerEntities = trackedEntities.get(playerId);
        Entity target = playerEntities == null ? null : playerEntities.get(entityId);
        if (target == null) {
            sendEmptyEntity(player, entityId, worldId);
            return;
        }

        FoliaTasks.entity(plugin, target, () -> {
            CompoundTag nbt = new CompoundTag();
            try {
                if (target.isValid()
                        && target.getEntityId() == entityId
                        && target.getWorld().getUID().equals(worldId)
                        && distanceSquared(origin, target) <= (long) current.maxDistance() * current.maxDistance()
                        && isAllowedEntity(target, current)) {
                    net.minecraft.world.entity.Entity handle = ((CraftEntity) target).getHandle();
                    TagValueOutput output = TagValueOutput.createWithContext(
                            ProblemReporter.DISCARDING, handle.registryAccess());
                    handle.saveWithoutId(output);
                    nbt = output.buildResult();
                    Identifier typeId = net.minecraft.world.entity.EntityType.getKey(handle.getType());
                    if (typeId != null) {
                        nbt.putString("id", typeId.toString());
                    }
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.FINE,
                        "Failed to serialize MiniHUD entity " + entityId, exception);
            }

            byte[] response = EntityDataProtocol.entity(entityId, nbt);
            if (response.length > current.maxResponseBytes()) {
                response = EntityDataProtocol.entity(entityId, new CompoundTag());
            }
            send(player, response, worldId);
        }, () -> {
            removeTracked(playerId, entityId, target);
            sendEmptyEntity(player, entityId, worldId);
        });
    }

    private void sendEmpty(Player player, BlockPos pos, UUID worldId) {
        send(player, EntityDataProtocol.blockEntity(pos, new CompoundTag()), worldId);
    }

    private void sendEmptyEntity(Player player, int entityId, UUID worldId) {
        send(player, EntityDataProtocol.entity(entityId, new CompoundTag()), worldId);
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

    private static long distanceSquared(Location location, Entity entity) {
        double dx = location.getX() - entity.getX();
        double dy = location.getY() - entity.getY();
        double dz = location.getZ() - entity.getZ();
        return (long) (dx * dx + dy * dy + dz * dz);
    }

    private static boolean isAllowedEntity(Entity entity, Settings current) {
        return !current.entityAllowlistEnabled()
                || current.allowedEntities().contains(entity.getType().getKey().toString());
    }

    private void removeTracked(UUID playerId, int entityId, Entity entity) {
        trackedEntities.computeIfPresent(playerId, (ignored, entities) -> {
            entities.remove(entityId, entity);
            return entities.isEmpty() ? null : entities;
        });
    }

    private static Limits newLimits(Settings current) {
        return new Limits(
                new TokenBucket(current.blockEntityRequestsPerSecond(),
                        current.blockEntityRequestsPerSecond()),
                new TokenBucket(current.entityRequestsPerSecond(),
                        current.entityRequestsPerSecond()));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Settings(
            boolean enabled,
            int blockEntityRequestsPerSecond,
            int entityRequestsPerSecond,
            int maxDistance,
            int maxInboundBytes,
            int maxResponseBytes,
            boolean blockEntityAllowlistEnabled,
            Set<String> allowedBlockEntities,
            boolean entityAllowlistEnabled,
            Set<String> allowedEntities
    ) {
        private static Settings load(ServuxFoliaPlugin plugin) {
            var config = plugin.getConfig();
            return new Settings(
                    config.getBoolean("entity-data.enabled", true),
                    clamp(config.getInt("entity-data.requests-per-second", 20), 1, 200),
                    clamp(config.getInt("entity-data.entity-requests-per-second", 64), 1, 400),
                    clamp(config.getInt("entity-data.max-distance", 32), 4, 256),
                    clamp(config.getInt("entity-data.max-inbound-payload-bytes", 8_192), 64, 65_536),
                    clamp(config.getInt("entity-data.max-response-bytes", 262_144), 4_096, 1_048_576),
                    config.getBoolean("entity-data.block-entity-allowlist-enabled", true),
                    Set.copyOf(config.getStringList("entity-data.allowed-block-entities")),
                    config.getBoolean("entity-data.entity-allowlist-enabled", true),
                    Set.copyOf(config.getStringList("entity-data.allowed-entities"))
            );
        }
    }

    private record Limits(TokenBucket blockEntities, TokenBucket entities) {
    }
}
