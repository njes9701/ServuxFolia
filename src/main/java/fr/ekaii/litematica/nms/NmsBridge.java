package fr.ekaii.litematica.nms;

import fr.ekaii.litematica.core.LitematicNbt;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

/**
 * Interface to NMS-only functionality needed by the paste pipeline:
 * block-entity NBT load, entity spawn from NBT, pending-tick re-scheduling,
 * data-fixer-upper (DFU) migration of older data versions.
 *
 * <p>The {@code nms/} package depends on {@code net.minecraft.*} directly
 * — this is why the plugin uses paperweight-userdev (mojang mappings at
 * compile time).
 *
 * <h2>NbtTag conversion</h2>
 * The schematic carries NBT as {@link LitematicNbt.NbtTag}. To call NMS
 * code we round-trip via {@code net.minecraft.nbt.CompoundTag}. The
 * conversion methods are {@link #toNmsCompound(LitematicNbt.NbtTag)} and
 * {@link #fromNmsCompound(Object)}.
 *
 * <p>The conversion uses {@code Object} for the NMS type at the interface
 * level so that callers without a paperweight classpath can still compile
 * against this interface (e.g. unit tests). Concrete implementations cast
 * to {@code net.minecraft.nbt.CompoundTag}.
 */
public interface NmsBridge {

    enum DfuKind {
        BLOCK_STATE,
        BLOCK_ENTITY,
        ENTITY
    }

    /**
     * Apply {@code nbt} to the block-entity at {@code block}. If no
     * block-entity exists yet it is created (via the EntityBlock factory)
     * before NBT load.
     *
     * <p>Must be called on the region owning the chunk containing
     * {@code block} (or wrapped by the caller).
     */
    void loadTileEntityNbt(Block block, LitematicNbt.NbtTag nbt);

    /**
     * Spawn an entity at {@code loc} loaded from {@code nbt}.
     * Returns the spawned entity, or {@code null} if loading failed
     * (unknown entity id, player entities, etc.).
     */
    Entity spawnEntityFromNbt(Location loc, LitematicNbt.NbtTag nbt);

    /**
     * Re-schedule a pending block tick at {@code (x, y, z)}.
     * The {@code nbt} encodes the original tick: target block id, delay,
     * priority. Implementations must convert delay to absolute world time.
     */
    void scheduleBlockTick(World world, int x, int y, int z, LitematicNbt.NbtTag nbt);

    /**
     * Re-schedule a pending fluid tick at {@code (x, y, z)}.
     */
    void scheduleFluidTick(World world, int x, int y, int z, LitematicNbt.NbtTag nbt);

    // -------------------------------------------------------- Extraction (save)

    /**
     * Extract the NBT of the block-entity at {@code (x, y, z)} in
     * {@code world} (full metadata, equivalent to what gets written into the
     * region file). Returns {@code null} if no block-entity is present.
     *
     * <p>Must be called on the chunk's owning region (Folia) or the main
     * thread (Paper). The returned compound includes the {@code id} key plus
     * the canonical positional keys {@code x/y/z} (absolute world coords) —
     * callers may overwrite the positional fields if they want region-local
     * coords (the save command does just that).
     */
    LitematicNbt.NbtTag extractTileEntityNbt(World world, int x, int y, int z);

    /**
     * Extract the NBT of a live bukkit {@link Entity} (uses NMS
     * {@code Entity#save}). Returns {@code null} for players or when the
     * entity refuses to serialise (e.g. removed, fake players).
     */
    LitematicNbt.NbtTag extractEntityNbt(Entity entity);

    /**
     * Return the NBT of every pending block tick scheduled at
     * {@code (x, y, z)} in {@code world}. Each entry is the canonical
     * {@code SavedTick} compound shape: {@code i} (target block id /
     * resource location), {@code x/y/z} (world coords), {@code t} (delay
     * in game-ticks, NOT absolute trigger time), {@code p} (priority).
     * Returns an empty list if no block tick is scheduled.
     */
    java.util.List<LitematicNbt.NbtTag> extractPendingBlockTicks(World world, int x, int y, int z);

    /**
     * Same as {@link #extractPendingBlockTicks} but for pending fluid
     * ticks (target type is a fluid resource location).
     */
    java.util.List<LitematicNbt.NbtTag> extractPendingFluidTicks(World world, int x, int y, int z);

    /**
     * Current Minecraft data version
     * ({@code SharedConstants.getCurrentVersion().getDataVersion().getVersion()}).
     */
    int currentDataVersion();

    /**
     * Migrate {@code input} from {@code fromDataVersion} to the current
     * data version using the DFU.
     */
    LitematicNbt.NbtTag dataFix(LitematicNbt.NbtTag input, int fromDataVersion, DfuKind kind);

    /**
     * Convert a portable {@link LitematicNbt.NbtTag} to a
     * {@code net.minecraft.nbt.CompoundTag}. Caller is responsible for
     * casting the result. Returns {@code null} for non-compound input.
     */
    Object toNmsCompound(LitematicNbt.NbtTag tag);

    /**
     * Inverse of {@link #toNmsCompound(LitematicNbt.NbtTag)}. Caller passes
     * an NMS {@code CompoundTag}. Returns {@code null} for null input.
     */
    LitematicNbt.NbtTag fromNmsCompound(Object compoundTag);

    // -------------------------------------------------------------- Factory

    /**
     * Returns the active bridge implementation. On Paper 26.1.2 this is the
     * {@code NmsBridge26_2} class loaded reflectively. If class resolution fails,
     * returns a {@link NoopNmsBridge} that logs warnings.
     */
    static NmsBridge get() {
        try {
            Class<?> cls = Class.forName("fr.ekaii.litematica.nms.NmsBridge26_2");
            return (NmsBridge) cls.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("LitematicaFolia")
                    .log(java.util.logging.Level.WARNING,
                            "NmsBridge26_2 unavailable, falling back to no-op", t);
            return new NoopNmsBridge();
        }
    }
}
