package dev.njes.servuxfolia.structures;

import dev.njes.servuxfolia.ServuxFoliaPlugin;
import dev.njes.servuxfolia.scheduler.FoliaTasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Folia-native provider for MiniHUD structure main/component bounding boxes. */
public final class StructureService {
    private static final long TICK_NANOS = 50_000_000L;

    private final ServuxFoliaPlugin plugin;
    private final Map<UUID, ClientState> clients = new ConcurrentHashMap<>();
    private final Map<CacheKey, CachedStart> startCache = new ConcurrentHashMap<>();
    private volatile Settings settings;
    private ScheduledTask updateTask;

    public StructureService(ServuxFoliaPlugin plugin) {
        this.plugin = plugin;
        this.settings = Settings.load(plugin);
    }

    public void start() {
        long interval = settings.updateIntervalTicks();
        updateTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin, ignored -> tickRegisteredPlayers(), interval, interval);
    }

    public void reload() {
        this.settings = Settings.load(plugin);
        if (updateTask != null) {
            updateTask.cancel();
        }
        start();
    }

    public void shutdown() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        clients.values().forEach(ClientState::invalidate);
        clients.clear();
        startCache.clear();
    }

    public void handle(Player player, byte[] payload) {
        FoliaTasks.entity(plugin, player, () -> handleOnPlayerThread(player, payload),
                () -> forget(player.getUniqueId()));
    }

    public void forget(UUID playerId) {
        ClientState state = clients.remove(playerId);
        if (state != null) {
            state.invalidate();
        }
    }

    public int registeredCount() {
        return clients.size();
    }

    private void handleOnPlayerThread(Player player, byte[] payload) {
        int maxInbound = settings.maxInboundBytes();
        try (var decoded = StructureProtocol.decode(payload, maxInbound)) {
            // MiniHUD sends an NBT body for both operations. It is deliberately
            // ignored after bounded decoding; the register body only contains
            // the client version at present.
            switch (decoded.packetType()) {
                case StructureProtocol.C2S_REGISTER -> register(player);
                case StructureProtocol.C2S_UNREGISTER -> forget(player.getUniqueId());
                default -> plugin.debug("Unknown structures packet type " + decoded.packetType());
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Rejected malformed MiniHUD structures packet from " + player.getName(), exception);
        }
    }

    private void register(Player player) {
        Settings current = settings;
        if (!current.enabled()
                || !player.hasPermission("servuxfolia.structures")) {
            forget(player.getUniqueId());
            return;
        }

        ClientState previous = clients.remove(player.getUniqueId());
        if (previous != null) {
            previous.invalidate();
        }
        ClientState state = new ClientState();
        clients.put(player.getUniqueId(), state);

        player.sendPluginMessage(plugin, StructureProtocol.CHANNEL,
                StructureProtocol.metadata(plugin.getPluginMeta().getVersion(), current.timeoutTicks()));
        requestScan(player, state, true);
    }

    private void tickRegisteredPlayers() {
        if (!settings.enabled()) {
            clients.values().forEach(ClientState::invalidate);
            clients.clear();
            return;
        }
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            ClientState state = clients.get(player.getUniqueId());
            if (state != null) {
                player.getScheduler().execute(plugin, () -> {
                    if (!player.hasPermission("servuxfolia.structures")) {
                        forget(player.getUniqueId());
                    } else {
                        requestScan(player, state, false);
                    }
                }, () -> forget(player.getUniqueId()), 1L);
            }
        });
    }

    private void requestScan(Player player, ClientState state, boolean force) {
        if (clients.get(player.getUniqueId()) != state || state.scanning.get()) {
            return;
        }

        World world = player.getWorld();
        Settings current = settings;
        int centerX = player.getLocation().getBlockX() >> 4;
        int centerZ = player.getLocation().getBlockZ() >> 4;
        long now = System.nanoTime();
        int movement = current.movementThresholdChunks();
        long refreshNanos = current.timeoutTicks() * TICK_NANOS;

        boolean sameWorld = world.getUID().equals(state.worldId);
        boolean moved = !sameWorld || Math.abs(centerX - state.chunkX) >= movement
                || Math.abs(centerZ - state.chunkZ) >= movement;
        if (!force && !moved && now - state.lastScanNanos < refreshNanos) {
            return;
        }
        if (!state.scanning.compareAndSet(false, true)) {
            return;
        }

        state.worldId = world.getUID();
        state.chunkX = centerX;
        state.chunkZ = centerZ;
        state.lastScanNanos = now;
        long generation = state.generation.incrementAndGet();

        List<ChunkCoordinate> chunks = chunksAround(
                centerX, centerZ, current.radiusChunks(), current.maxChunksPerScan());
        collectReferences(player, state, generation, world, chunks, current);
    }

    private void collectReferences(Player player, ClientState state, long generation,
                                   World world, List<ChunkCoordinate> chunks, Settings scanSettings) {
        if (chunks.isEmpty()) {
            finishWithoutResponse(state, generation);
            return;
        }

        int maxStructures = scanSettings.maxStructuresPerResponse();
        Map<ReferenceKey, Structure> references = new ConcurrentHashMap<>();
        AtomicInteger remaining = new AtomicInteger(chunks.size());

        for (ChunkCoordinate chunk : chunks) {
            try {
                FoliaTasks.region(plugin, world, chunk.x(), chunk.z(), () -> {
                    try {
                        if (!isCurrent(player, state, generation, world.getUID())
                                || !world.isChunkLoaded(chunk.x(), chunk.z())) {
                            return;
                        }
                        ServerLevel level = ((CraftWorld) world).getHandle();
                        LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
                        if (loaded == null) {
                            return;
                        }
                        for (Map.Entry<Structure, LongSet> entry : loaded.getAllReferences().entrySet()) {
                            Identifier typeId = BuiltInRegistries.STRUCTURE_TYPE.getKey(entry.getKey().type());
                            if (typeId == null || !shouldSend(typeId.toString(), scanSettings)) {
                                continue;
                            }
                            LongIterator iterator = entry.getValue().iterator();
                            while (iterator.hasNext() && references.size() < maxStructures) {
                                ChunkPos start = ChunkPos.unpack(iterator.nextLong());
                                references.putIfAbsent(
                                        new ReferenceKey(typeId.toString(), start.x(), start.z()), entry.getKey());
                            }
                        }
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.FINE, "Structure reference scan failed", exception);
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            collectStarts(player, state, generation, world, references, scanSettings);
                        }
                    }
                });
            } catch (RuntimeException exception) {
                if (remaining.decrementAndGet() == 0) {
                    collectStarts(player, state, generation, world, references, scanSettings);
                }
            }
        }
    }

    private void collectStarts(Player player, ClientState state, long generation, World world,
                               Map<ReferenceKey, Structure> references, Settings scanSettings) {
        if (!isCurrent(player, state, generation, world.getUID())) {
            finishWithoutResponse(state, generation);
            return;
        }
        if (references.isEmpty()) {
            sendStructures(player, state, generation, List.of(), scanSettings, world.getUID());
            return;
        }

        ConcurrentLinkedQueue<CompoundTag> starts = new ConcurrentLinkedQueue<>();
        AtomicInteger remaining = new AtomicInteger(references.size());
        references.forEach((reference, structure) -> {
            CacheKey cacheKey = new CacheKey(world.getUID(), reference);
            CachedStart cached = startCache.get(cacheKey);
            long now = System.nanoTime();
            if (cached != null && now - cached.createdAtNanos()
                    <= scanSettings.timeoutTicks() * TICK_NANOS) {
                starts.add(cached.tag().copy());
                if (remaining.decrementAndGet() == 0) {
                    sendStructures(player, state, generation, new ArrayList<>(starts),
                            scanSettings, world.getUID());
                }
                return;
            }
            if (cached != null) {
                startCache.remove(cacheKey, cached);
            }
            try {
                FoliaTasks.region(plugin, world, reference.chunkX(), reference.chunkZ(), () -> {
                    try {
                        if (!isCurrent(player, state, generation, world.getUID())
                                || !world.isChunkLoaded(reference.chunkX(), reference.chunkZ())) {
                            return;
                        }
                        ServerLevel level = ((CraftWorld) world).getHandle();
                        LevelChunk chunk = level.getChunkSource().getChunkNow(reference.chunkX(), reference.chunkZ());
                        if (chunk == null) {
                            return;
                        }
                        StructureStart start = chunk.getStartForStructure(structure);
                        if (start == null || !start.isValid()) {
                            return;
                        }
                        StructurePieceSerializationContext context = StructurePieceSerializationContext.fromLevel(level);
                        CompoundTag tag = start.createTag(context,
                                new ChunkPos(reference.chunkX(), reference.chunkZ()));
                        tag.putBoolean("ExpandBox", structure.terrainAdaptation() != TerrainAdjustment.NONE);
                        starts.add(tag);
                        cacheStart(cacheKey, tag, scanSettings.maxCacheEntries());
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.FINE, "Structure start serialization failed", exception);
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            sendStructures(player, state, generation, new ArrayList<>(starts),
                                    scanSettings, world.getUID());
                        }
                    }
                });
            } catch (RuntimeException exception) {
                if (remaining.decrementAndGet() == 0) {
                    sendStructures(player, state, generation, new ArrayList<>(starts),
                            scanSettings, world.getUID());
                }
            }
        });
    }

    private void sendStructures(Player player, ClientState state, long generation,
                                List<CompoundTag> starts, Settings scanSettings, UUID worldId) {
        if (!isCurrent(player, state, generation, worldId)) {
            finishWithoutResponse(state, generation);
            return;
        }
        FoliaTasks.async(plugin, () -> {
            try {
                CompoundTag response = StructureProtocol.structurePayload(starts);
                List<byte[]> packets = StructureProtocol.structureData(
                        response, scanSettings.splitterSliceBytes(), scanSettings.maxResponseBytes());
                player.getScheduler().execute(plugin, () -> {
                    try {
                        if (isCurrent(player, state, generation, worldId)
                                && player.getWorld().getUID().equals(worldId)) {
                            packets.forEach(packet -> player.sendPluginMessage(
                                    plugin, StructureProtocol.CHANNEL, packet));
                            plugin.debug("Sent " + starts.size() + " structure(s) to " + player.getName());
                        }
                    } finally {
                        finishWithoutResponse(state, generation);
                    }
                }, () -> forget(player.getUniqueId()), 1L);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to encode MiniHUD structures for " + player.getName(), exception);
                finishWithoutResponse(state, generation);
            }
        });
    }

    private static boolean shouldSend(String id, Settings settings) {
        if (settings.whitelistEnabled()) {
            return settings.whitelist().contains(id);
        }
        return !settings.blacklistEnabled() || !settings.blacklist().contains(id);
    }

    private void cacheStart(CacheKey key, CompoundTag tag, int maximum) {
        if (startCache.size() >= maximum) {
            int remove = Math.max(1, maximum / 10);
            var iterator = startCache.keySet().iterator();
            while (remove-- > 0 && iterator.hasNext()) {
                startCache.remove(iterator.next());
            }
        }
        startCache.put(key, new CachedStart(tag.copy(), System.nanoTime()));
    }

    private boolean isCurrent(Player player, ClientState state, long generation, UUID worldId) {
        return clients.get(player.getUniqueId()) == state
                && state.generation.get() == generation
                && worldId.equals(state.worldId);
    }

    private static void finishWithoutResponse(ClientState state, long generation) {
        if (state.generation.get() == generation) {
            state.scanning.set(false);
        }
    }

    private static List<ChunkCoordinate> chunksAround(int centerX, int centerZ, int radius, int maximum) {
        List<ChunkCoordinate> chunks = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(new ChunkCoordinate(centerX + dx, centerZ + dz, dx * dx + dz * dz));
            }
        }
        chunks.sort(Comparator.comparingInt(ChunkCoordinate::distanceSquared));
        if (chunks.size() > maximum) {
            return new ArrayList<>(chunks.subList(0, maximum));
        }
        return chunks;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ChunkCoordinate(int x, int z, int distanceSquared) {
    }

    private record ReferenceKey(String type, int chunkX, int chunkZ) {
    }

    private record CacheKey(UUID worldId, ReferenceKey reference) {
    }

    private record CachedStart(CompoundTag tag, long createdAtNanos) {
    }

    private static final class ClientState {
        private final AtomicLong generation = new AtomicLong();
        private final AtomicBoolean scanning = new AtomicBoolean();
        private volatile UUID worldId;
        private volatile int chunkX;
        private volatile int chunkZ;
        private volatile long lastScanNanos;

        private void invalidate() {
            generation.incrementAndGet();
            scanning.set(false);
        }
    }

    private record Settings(
            boolean enabled,
            int updateIntervalTicks,
            int timeoutTicks,
            int movementThresholdChunks,
            int radiusChunks,
            int maxChunksPerScan,
            int maxStructuresPerResponse,
            int maxCacheEntries,
            int maxInboundBytes,
            int splitterSliceBytes,
            int maxResponseBytes,
            boolean whitelistEnabled,
            Set<String> whitelist,
            boolean blacklistEnabled,
            Set<String> blacklist
    ) {
        private static Settings load(ServuxFoliaPlugin plugin) {
            var config = plugin.getConfig();
            return new Settings(
                    config.getBoolean("structures.enabled", true),
                    clamp(config.getInt("structures.update-interval-ticks", 40), 20, 1_200),
                    clamp(config.getInt("structures.timeout-ticks", 600), 40, 1_200),
                    clamp(config.getInt("structures.movement-threshold-chunks", 2), 1, 8),
                    clamp(config.getInt("structures.radius-chunks", 8), 1, 16),
                    clamp(config.getInt("structures.max-chunks-per-scan", 289), 9, 1_089),
                    clamp(config.getInt("structures.max-structures-per-response", 512), 1, 4_096),
                    clamp(config.getInt("structures.max-cache-entries", 4_096), 64, 65_536),
                    clamp(config.getInt("structures.max-inbound-payload-bytes", 8_192), 64, 65_536),
                    clamp(config.getInt("structures.splitter-slice-bytes", 30_000), 1_024, 1_000_000),
                    clamp(config.getInt("structures.max-response-bytes", 8 * 1_024 * 1_024),
                            65_536, 32 * 1_024 * 1_024),
                    config.getBoolean("structures.whitelist-enabled", false),
                    Set.copyOf(config.getStringList("structures.whitelist")),
                    config.getBoolean("structures.blacklist-enabled", true),
                    Set.copyOf(config.getStringList("structures.blacklist"))
            );
        }
    }
}
