package fr.ekaii.litematica.nms;

import fr.ekaii.litematica.core.LitematicNbt;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import java.util.logging.Logger;

/**
 * No-op fallback. Used when the real NMS implementation fails to load
 * (e.g. wrong Paper version, missing paperweight, sandboxed unit tests).
 */
public final class NoopNmsBridge implements NmsBridge {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/NoopNmsBridge");

    @Override
    public void loadTileEntityNbt(Block block, LitematicNbt.NbtTag nbt) {
        LOG.warning("loadTileEntityNbt called on no-op bridge (block at " + block.getLocation() + ")");
    }

    @Override
    public Entity spawnEntityFromNbt(Location loc, LitematicNbt.NbtTag nbt) {
        LOG.warning("spawnEntityFromNbt called on no-op bridge (loc " + loc + ")");
        return null;
    }

    @Override
    public void scheduleBlockTick(World world, int x, int y, int z, LitematicNbt.NbtTag nbt) {
        LOG.warning("scheduleBlockTick called on no-op bridge");
    }

    @Override
    public void scheduleFluidTick(World world, int x, int y, int z, LitematicNbt.NbtTag nbt) {
        LOG.warning("scheduleFluidTick called on no-op bridge");
    }

    @Override
    public LitematicNbt.NbtTag extractTileEntityNbt(World world, int x, int y, int z) {
        LOG.warning("extractTileEntityNbt called on no-op bridge");
        return null;
    }

    @Override
    public LitematicNbt.NbtTag extractEntityNbt(Entity entity) {
        LOG.warning("extractEntityNbt called on no-op bridge");
        return null;
    }

    @Override
    public java.util.List<LitematicNbt.NbtTag> extractPendingBlockTicks(World world, int x, int y, int z) {
        return java.util.Collections.emptyList();
    }

    @Override
    public java.util.List<LitematicNbt.NbtTag> extractPendingFluidTicks(World world, int x, int y, int z) {
        return java.util.Collections.emptyList();
    }

    @Override
    public int currentDataVersion() {
        return -1;
    }

    @Override
    public LitematicNbt.NbtTag dataFix(LitematicNbt.NbtTag input, int fromDataVersion, DfuKind kind) {
        return input;
    }

    @Override
    public Object toNmsCompound(LitematicNbt.NbtTag tag) {
        return null;
    }

    @Override
    public LitematicNbt.NbtTag fromNmsCompound(Object compoundTag) {
        return null;
    }
}
