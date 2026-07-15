package dev.njes.servuxfolia.litematics;

import dev.njes.servuxfolia.ServuxFoliaPlugin;
import dev.njes.servuxfolia.scheduler.FoliaTasks;
import fr.ekaii.litematica.core.LitematicReader;
import fr.ekaii.litematica.core.LitematicRegion;
import fr.ekaii.litematica.core.LitematicSchematic;
import fr.ekaii.litematica.paste.PasteOperation;
import fr.ekaii.litematica.paste.PasteOptions;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Decodes inline LitematicaPaste frames and starts the regionized pipeline. */
public final class DirectPasteService {
    private final ServuxFoliaPlugin plugin;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Object activeLock = new Object();

    public DirectPasteService(ServuxFoliaPlugin plugin) {
        this.plugin = plugin;
    }

    public void accept(Player player, byte[] applicationPayload) {
        if (!plugin.getConfig().getBoolean("direct-paste.enabled", false)) {
            message(player, "Servux Direct Paste is disabled on this server.");
            return;
        }
        if (!player.hasPermission("servuxfolia.paste")) {
            message(player, "You do not have permission to use Servux Direct Paste.");
            return;
        }
        StartResult start = tryStart(player.getUniqueId());
        if (start != StartResult.STARTED) {
            message(player, start == StartResult.ALREADY_ACTIVE
                    ? "A Direct Paste is already running for you."
                    : "The server-wide Direct Paste limit is currently in use. Try again later.");
            return;
        }

        CompoundTag payload;
        try {
            int maxBytes = plugin.getConfig().getInt("direct-paste.max-uncompressed-bytes", 32 * 1024 * 1024);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(applicationPayload));
            try {
                buffer.readVarInt();
                net.minecraft.nbt.Tag decoded = buffer.readNbt(NbtAccounter.create(maxBytes));
                payload = decoded instanceof CompoundTag compound ? compound : null;
            } finally {
                buffer.release();
            }
            if (payload == null || !payload.getStringOr("Task", "").equals("LitematicaPaste")) {
                throw new IllegalArgumentException("unsupported Servux stream task");
            }
        } catch (RuntimeException exception) {
            active.remove(player.getUniqueId());
            plugin.getLogger().log(Level.WARNING, "Rejected Direct Paste payload from " + player.getName(), exception);
            message(player, "Direct Paste payload was malformed or unsupported.");
            return;
        }

        FoliaTasks.entity(plugin, player, () -> validateAndDecode(player, payload),
                () -> active.remove(player.getUniqueId()));
    }

    public void forget(UUID playerId) {
        active.remove(playerId);
    }

    private void validateAndDecode(Player player, CompoundTag payload) {
        if (plugin.getConfig().getBoolean("direct-paste.require-creative", true)
                && player.getGameMode() != GameMode.CREATIVE) {
            active.remove(player.getUniqueId());
            message(player, "Direct Paste requires Creative mode.");
            return;
        }

        int[] origin = payload.getIntArray("Origin").orElse(null);
        CompoundTag schematicNbt = payload.getCompound("Schematics").orElse(null);
        if (origin == null || origin.length < 3 || schematicNbt == null) {
            active.remove(player.getUniqueId());
            message(player, "Direct Paste is missing Origin or Schematics data.");
            return;
        }

        World world = player.getWorld();
        Location location = new Location(world, origin[0], origin[1], origin[2]);
        int yaw = switch (payload.getIntOr("Rotation", 0)) {
            case 1 -> 90;
            case 2 -> 180;
            case 3 -> 270;
            default -> 0;
        };
        Mirror mirror = switch (payload.getIntOr("Mirror", 0)) {
            case 1 -> Mirror.LEFT_RIGHT;
            case 2 -> Mirror.FRONT_BACK;
            default -> Mirror.NONE;
        };
        PasteOptions.ReplaceMode replaceMode = switch (
                payload.getStringOr("ReplaceMode", "none").toLowerCase(java.util.Locale.ROOT)) {
            case "all" -> PasteOptions.ReplaceMode.ALL;
            case "with_non_air" -> PasteOptions.ReplaceMode.WITH_NON_AIR;
            default -> PasteOptions.ReplaceMode.NONE;
        };

        FoliaTasks.async(plugin, () -> decodeAndPaste(
                player, payload, schematicNbt, location, yaw, mirror, replaceMode));
    }

    private void decodeAndPaste(Player player, CompoundTag placement, CompoundTag schematicNbt,
                                Location origin, int yaw, Mirror mirror,
                                PasteOptions.ReplaceMode replaceMode) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                NbtIo.write(schematicNbt, output);
            }
            LitematicSchematic schematic = LitematicReader.read(bytes.toByteArray());
            applySubRegionOverrides(schematic, placement.getCompoundOrEmpty("SubRegions"));

            long volume = schematic.regionsList().stream().mapToLong(LitematicRegion::volume).sum();
            long maxVolume = plugin.getConfig().getLong("direct-paste.max-volume", 5_000_000L);
            if (volume > maxVolume) {
                throw new IllegalArgumentException("schematic volume " + volume + " exceeds limit " + maxVolume);
            }

            PasteOptions options = new PasteOptions(
                    origin, true, true, true,
                    plugin.getConfig().getBoolean("direct-paste.deferred-physics", true),
                    plugin.getConfig().getBoolean("direct-paste.observers-last", true),
                    4096, yaw, mirror, replaceMode, null);

            message(player, "Direct Paste started: " + schematic.regions.size()
                    + " region(s), volume " + volume + ".");
            new PasteOperation(plugin, schematic, options).execute().whenComplete((result, error) -> {
                active.remove(player.getUniqueId());
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Direct Paste failed for " + player.getName(), error);
                    message(player, "Direct Paste failed: " + error.getClass().getSimpleName());
                } else {
                    message(player, "Direct Paste complete: " + result.blocksPlaced() + " blocks, "
                            + result.tileEntitiesPlaced() + " block entities, "
                            + result.entitiesSpawned() + " entities, "
                            + result.errors().size() + " errors in " + result.durationMs() + " ms.");
                }
            });
        } catch (Exception exception) {
            active.remove(player.getUniqueId());
            plugin.getLogger().log(Level.WARNING, "Direct Paste decode failed for " + player.getName(), exception);
            message(player, "Direct Paste rejected: " + exception.getMessage());
        }
    }

    private static void applySubRegionOverrides(LitematicSchematic schematic, CompoundTag overrides) {
        Iterator<java.util.Map.Entry<String, LitematicRegion>> iterator = schematic.regions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            CompoundTag override = overrides.getCompound(entry.getKey()).orElse(null);
            if (override == null) {
                continue;
            }
            if (!override.getBooleanOr("Enabled", true)) {
                iterator.remove();
                continue;
            }
            if (override.getIntOr("Rotation", 0) != 0 || override.getIntOr("Mirror", 0) != 0) {
                throw new IllegalArgumentException(
                        "per-subregion rotation/mirror is not supported yet: " + entry.getKey());
            }
            int[] position = override.getIntArray("Pos").orElse(null);
            if (position != null && position.length >= 3) {
                entry.getValue().originX = position[0];
                entry.getValue().originY = position[1];
                entry.getValue().originZ = position[2];
            }
        }
    }

    private void message(Player player, String text) {
        FoliaTasks.entity(plugin, player, () -> {
            if (player.isOnline()) {
                player.sendMessage("[ServuxFolia] " + text);
            }
        }, () -> active.remove(player.getUniqueId()));
    }

    private StartResult tryStart(UUID playerId) {
        synchronized (activeLock) {
            if (active.contains(playerId)) {
                return StartResult.ALREADY_ACTIVE;
            }
            int configured = plugin.getConfig().getInt("direct-paste.max-concurrent", 1);
            int maxConcurrent = Math.max(1, Math.min(configured, 8));
            if (active.size() >= maxConcurrent) {
                return StartResult.SERVER_BUSY;
            }
            active.add(playerId);
            return StartResult.STARTED;
        }
    }

    public int activeCount() {
        return active.size();
    }

    private enum StartResult {
        STARTED,
        ALREADY_ACTIVE,
        SERVER_BUSY
    }
}
