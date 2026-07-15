package fr.ekaii.litematica.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A single region within a Litematica schematic. Holds the per-cell palette
 * index array along with passthrough NBT for tile entities, entities and
 * pending ticks.
 *
 * <h2>Coordinate normalisation</h2>
 * On disk, {@code Size} components may be negative — meaning the region
 * extends in the negative direction from {@code Position}. This reader
 * normalises so that {@link #sizeX}, {@link #sizeY}, {@link #sizeZ} are
 * always positive, with {@link #originX}, {@link #originY}, {@link #originZ}
 * adjusted accordingly (and remembers the original sign in
 * {@link #signX}/{@link #signY}/{@link #signZ} so we can write back the
 * file in its original form).
 *
 * <h2>Block index addressing</h2>
 * {@code blocks[ y * sizeX * sizeZ + z * sizeX + x ]} (Y-major, then Z, then X).
 */
public final class LitematicRegion {

    public String name;

    /** Absolute origin (sign-normalised). */
    public int originX, originY, originZ;
    /** Absolute size, always > 0. */
    public int sizeX, sizeY, sizeZ;
    /** Sign of the original on-disk Size axis: +1 or -1. */
    public int signX = 1, signY = 1, signZ = 1;

    public final List<BlockStateEntry> palette = new ArrayList<>();
    /** Flat array of palette indices, length = sizeX * sizeY * sizeZ. */
    public int[] blocks;

    /** Passthrough NBT — preserved byte-for-byte across reads & writes. */
    public LitematicNbt.NbtList tileEntities;
    public LitematicNbt.NbtList entities;
    public LitematicNbt.NbtList pendingBlockTicks;
    public LitematicNbt.NbtList pendingFluidTicks;

    public LitematicRegion() {
    }

    /** Total number of cells in this region. */
    public long volume() {
        return (long) sizeX * sizeY * sizeZ;
    }

    /** Lookup index of (x,y,z) inside the {@link #blocks} array. */
    public int indexOf(int x, int y, int z) {
        return y * sizeX * sizeZ + z * sizeX + x;
    }

    /** Reads the palette index at (x,y,z). */
    public int blockIndexAt(int x, int y, int z) {
        return blocks[indexOf(x, y, z)];
    }

    /** Counts non-air cells (palette entry 0 is by convention air). */
    public long countNonAir() {
        long count = 0;
        for (int b : blocks) if (b != 0) count++;
        return count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LitematicRegion r)) return false;
        return originX == r.originX && originY == r.originY && originZ == r.originZ
                && sizeX == r.sizeX && sizeY == r.sizeY && sizeZ == r.sizeZ
                && signX == r.signX && signY == r.signY && signZ == r.signZ
                && Objects.equals(name, r.name)
                && palette.equals(r.palette)
                && java.util.Arrays.equals(blocks, r.blocks)
                && Objects.equals(tileEntities, r.tileEntities)
                && Objects.equals(entities, r.entities)
                && Objects.equals(pendingBlockTicks, r.pendingBlockTicks)
                && Objects.equals(pendingFluidTicks, r.pendingFluidTicks);
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(name, originX, originY, originZ, sizeX, sizeY, sizeZ,
                signX, signY, signZ, palette,
                tileEntities, entities, pendingBlockTicks, pendingFluidTicks);
        return 31 * h + java.util.Arrays.hashCode(blocks);
    }
}
