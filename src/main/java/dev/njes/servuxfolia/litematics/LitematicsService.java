package dev.njes.servuxfolia.litematics;

import dev.njes.servuxfolia.ServuxFoliaPlugin;
import dev.njes.servuxfolia.protocol.ProtocolCodec;
import dev.njes.servuxfolia.protocol.ProtocolConstants;
import dev.njes.servuxfolia.protocol.PacketSplitter;
import dev.njes.servuxfolia.scheduler.FoliaTasks;
import dev.njes.servuxfolia.security.TokenBucket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class LitematicsService {
    private final ServuxFoliaPlugin plugin;
    private final Map<UUID, Limits> limits = new ConcurrentHashMap<>();
    private final PacketSplitter inboundSplitter;
    private final DirectPasteService directPaste;

    public LitematicsService(ServuxFoliaPlugin plugin) {
        this.plugin = plugin;
        this.inboundSplitter = new PacketSplitter(
                plugin.getConfig().getInt("litematics.max-reassembled-payload-bytes", 32 * 1024 * 1024),
                Math.max(1, Math.min(plugin.getConfig().getInt("litematics.max-inbound-sessions", 4), 64)));
        this.directPaste = new DirectPasteService(plugin);
    }

    public void handle(Player player, byte[] payload) {
        FoliaTasks.entity(plugin, player, () -> handleOnPlayerThread(player, payload),
                () -> forget(player.getUniqueId()));
    }

    private void handleOnPlayerThread(Player player, byte[] payload) {
        if (!player.hasPermission("servuxfolia.litematics")) {
            return;
        }

        int maxBytes = plugin.getConfig().getInt("litematics.max-inbound-payload-bytes", 16 * 1024 * 1024);
        try (ProtocolCodec.Decoded decoded = ProtocolCodec.decode(payload, maxBytes)) {
            switch (decoded.packetType()) {
                case ProtocolConstants.C2S_METADATA_REQUEST -> handleMetadata(player);
                case ProtocolConstants.C2S_BLOCK_ENTITY_REQUEST -> {
                    decoded.body().readVarInt(); // legacy transaction id
                    handleBlockEntity(player, decoded.body().readBlockPos());
                }
                case ProtocolConstants.C2S_ENTITY_REQUEST -> {
                    decoded.body().readVarInt(); // legacy transaction id
                    handleEntity(player, decoded.body().readVarInt());
                }
                case ProtocolConstants.C2S_BULK_NBT_REQUEST -> handleBulk(
                        player, decoded.body().readChunkPos(), decoded.body().readNbt());
                case ProtocolConstants.C2S_NBT_RESPONSE_DATA -> handleSplitClientPayload(player, decoded.body());
                default -> plugin.debug("Unknown litematics packet type " + decoded.packetType());
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Rejected malformed Servux packet from " + player.getName(), exception);
        }
    }

    public void forget(UUID playerId) {
        limits.remove(playerId);
        inboundSplitter.forget(playerId);
        directPaste.forget(playerId);
    }

    public int activePasteCount() {
        return directPaste.activeCount();
    }

    private void handleMetadata(Player player) {
        if (!plugin.getConfig().getBoolean("litematics.enabled", true)
                || !plugin.getConfig().getBoolean("litematics.advertise", false)) {
            return;
        }
        send(player, ProtocolCodec.metadata(plugin.getPluginMeta().getVersion()));
    }

    private void handleBlockEntity(Player player, BlockPos pos) {
        Limits limit = limits.computeIfAbsent(player.getUniqueId(), ignored -> newLimits());
        if (!limit.blockEntities().tryAcquire()) {
            return;
        }

        FoliaTasks.entity(plugin, player, () -> {
            Location origin = player.getLocation();
            World world = origin.getWorld();
            int maxDistance = plugin.getConfig().getInt("litematics.max-distance", 128);
            if (distanceSquared(origin, pos) > (long) maxDistance * maxDistance) {
                send(player, ProtocolCodec.blockEntity(pos, new CompoundTag()));
                return;
            }

            FoliaTasks.region(plugin, world, pos.getX() >> 4, pos.getZ() >> 4, () -> {
                CompoundTag nbt = new CompoundTag();
                try {
                    ServerLevel level = ((CraftWorld) world).getHandle();
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        nbt = blockEntity.saveWithFullMetadata(level.registryAccess());
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.FINE, "Failed to serialize block entity at " + pos, exception);
                }
                send(player, ProtocolCodec.blockEntity(pos, nbt));
            });
        }, () -> limits.remove(player.getUniqueId()));
    }

    private void handleEntity(Player player, int entityId) {
        Limits limit = limits.computeIfAbsent(player.getUniqueId(), ignored -> newLimits());
        if (!limit.entities().tryAcquire()) {
            return;
        }

        FoliaTasks.entity(plugin, player, () -> {
            net.minecraft.world.entity.Entity nmsEntity = ((CraftPlayer) player).getHandle().level().getEntity(entityId);
            if (nmsEntity == null) {
                send(player, ProtocolCodec.entity(entityId, new CompoundTag()));
                return;
            }
            Entity bukkitEntity = nmsEntity.getBukkitEntity();
            FoliaTasks.entity(plugin, bukkitEntity, () -> {
                CompoundTag nbt = new CompoundTag();
                try {
                    net.minecraft.world.entity.Entity handle = ((CraftEntity) bukkitEntity).getHandle();
                    TagValueOutput output = TagValueOutput.createWithContext(
                            ProblemReporter.DISCARDING, handle.registryAccess());
                    handle.saveWithoutId(output);
                    nbt = output.buildResult();
                    Identifier id = net.minecraft.world.entity.EntityType.getKey(handle.getType());
                    if (id != null) {
                        nbt.putString("id", id.toString());
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.FINE, "Failed to serialize entity " + entityId, exception);
                }
                send(player, ProtocolCodec.entity(entityId, nbt));
            }, () -> send(player, ProtocolCodec.entity(entityId, new CompoundTag())));
        }, () -> limits.remove(player.getUniqueId()));
    }

    private void handleBulk(Player player, ChunkPos chunkPos, CompoundTag request) {
        Limits limit = limits.computeIfAbsent(player.getUniqueId(), ignored -> newLimits());
        if (!limit.bulk().tryAcquire() || request == null) {
            return;
        }

        FoliaTasks.entity(plugin, player, () -> {
            World world = player.getWorld();
            Location origin = player.getLocation();
            int centerX = chunkPos.getMinBlockX() + 8;
            int centerZ = chunkPos.getMinBlockZ() + 8;
            int maxDistance = plugin.getConfig().getInt("litematics.max-distance", 128);
            double dx = origin.getX() - centerX;
            double dz = origin.getZ() - centerZ;
            if (dx * dx + dz * dz > (long) maxDistance * maxDistance) {
                return;
            }

            FoliaTasks.region(plugin, world, chunkPos.x(), chunkPos.z(),
                    () -> collectBulk(player, world, chunkPos, request));
        }, () -> forget(player.getUniqueId()));
    }

    private void collectBulk(Player player, World world, ChunkPos chunkPos, CompoundTag request) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
            if (chunk == null) {
                return;
            }

            int minY = request.getIntOr("minY", level.getMinY());
            int maxY = request.getIntOr("maxY", level.getMaxY() - 1);
            minY = Math.max(minY, level.getMinY());
            maxY = Math.min(maxY, level.getMaxY() - 1);

            ListTag tileEntities = new ListTag();
            for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                if (pos.getY() < minY || pos.getY() > maxY) {
                    continue;
                }
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    tileEntities.add(blockEntity.saveWithFullMetadata(level.registryAccess()));
                }
            }

            int minX = chunkPos.getMinBlockX();
            int minZ = chunkPos.getMinBlockZ();
            AABB bounds = new AABB(minX, minY, minZ, minX + 16, maxY + 1, minZ + 16);
            ListTag entities = new ListTag();
            for (net.minecraft.world.entity.Entity entity :
                    level.getEntities((net.minecraft.world.entity.Entity) null, bounds,
                            candidate -> !(candidate instanceof net.minecraft.world.entity.player.Player))) {
                TagValueOutput output = TagValueOutput.createWithContext(
                        ProblemReporter.DISCARDING, level.registryAccess());
                entity.saveWithoutId(output);
                CompoundTag entityNbt = output.buildResult();
                Identifier id = net.minecraft.world.entity.EntityType.getKey(entity.getType());
                if (id == null) {
                    continue;
                }
                entityNbt.putString("id", id.toString());
                entityNbt.putInt("entityId", entity.getId());
                entityNbt.put("Pos", relativePosition(
                        entity.getX() - minX, entity.getY() - minY, entity.getZ() - minZ));
                entities.add(entityNbt);
            }

            CompoundTag response = new CompoundTag();
            response.putString("Task", "BulkEntityReply");
            response.put("TileEntities", tileEntities);
            response.put("Entities", entities);
            response.putInt("chunkX", chunkPos.x());
            response.putInt("chunkZ", chunkPos.z());
            sendSplitNbt(player, response);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Bulk NBT collection failed for " + chunkPos, exception);
        }
    }

    private void sendSplitNbt(Player player, CompoundTag response) {
        FoliaTasks.async(plugin, () -> {
            byte[] application = ProtocolCodec.splitNbtPayload(0, response);
            int sliceBytes = plugin.getConfig().getInt("litematics.splitter-slice-bytes", 30_000);
            var slices = PacketSplitter.split(application, sliceBytes);
            byte[][] packets = slices.stream()
                    .map(slice -> ProtocolCodec.splitterSlice(ProtocolConstants.S2C_NBT_RESPONSE_DATA, slice))
                    .toArray(byte[][]::new);
            FoliaTasks.entity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                for (byte[] packet : packets) {
                    player.sendPluginMessage(plugin, ProtocolConstants.LITEMATICS_CHANNEL, packet);
                }
            }, () -> forget(player.getUniqueId()));
        });
    }

    private void handleSplitClientPayload(Player player, net.minecraft.network.FriendlyByteBuf body) {
        byte[] slice = new byte[body.readableBytes()];
        body.readBytes(slice);
        byte[] complete = inboundSplitter.receive(player.getUniqueId(), slice);
        if (complete != null) {
            directPaste.accept(player, complete);
        }
    }

    private static ListTag relativePosition(double x, double y, double z) {
        ListTag result = new ListTag();
        result.add(DoubleTag.valueOf(x));
        result.add(DoubleTag.valueOf(y));
        result.add(DoubleTag.valueOf(z));
        return result;
    }

    private void send(Player player, byte[] payload) {
        FoliaTasks.entity(plugin, player, () -> {
            if (player.isOnline()) {
                player.sendPluginMessage(plugin, ProtocolConstants.LITEMATICS_CHANNEL, payload);
            }
        }, () -> limits.remove(player.getUniqueId()));
    }

    private Limits newLimits() {
        int blockRate = plugin.getConfig().getInt("litematics.block-entity-requests-per-second", 64);
        int entityRate = plugin.getConfig().getInt("litematics.entity-requests-per-second", 32);
        int bulkRate = plugin.getConfig().getInt("litematics.bulk-requests-per-second", 4);
        return new Limits(new TokenBucket(blockRate, blockRate), new TokenBucket(entityRate, entityRate),
                new TokenBucket(bulkRate, bulkRate));
    }

    private static long distanceSquared(Location location, BlockPos pos) {
        double dx = location.getX() - (pos.getX() + 0.5D);
        double dy = location.getY() - (pos.getY() + 0.5D);
        double dz = location.getZ() - (pos.getZ() + 0.5D);
        return (long) (dx * dx + dy * dy + dz * dz);
    }

    private record Limits(TokenBucket blockEntities, TokenBucket entities, TokenBucket bulk) {
    }
}
