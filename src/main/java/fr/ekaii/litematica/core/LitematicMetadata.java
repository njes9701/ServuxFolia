package fr.ekaii.litematica.core;

import java.util.Objects;

/**
 * Schematic-level metadata block (the {@code Metadata} compound).
 * Most fields mirror Litematica's on-disk schema verbatim.
 *
 * <p>{@code enclosingSizeX/Y/Z} are always recorded as non-negative — the
 * raw NBT may carry negative axis sizes when the schematic was created in
 * a "flipped" direction; the reader normalises them.
 */
public final class LitematicMetadata {

    public String name;
    public String author;
    public String description;

    public int enclosingSizeX;
    public int enclosingSizeY;
    public int enclosingSizeZ;

    public long totalBlocks;
    public long totalVolume;
    public long timeCreated;
    public long timeModified;
    public int regionCount;

    /** Optional preview image, ARGB packed ints. May be null. */
    public int[] previewImageData;

    public LitematicMetadata() {
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LitematicMetadata m)) return false;
        return enclosingSizeX == m.enclosingSizeX
                && enclosingSizeY == m.enclosingSizeY
                && enclosingSizeZ == m.enclosingSizeZ
                && totalBlocks == m.totalBlocks
                && totalVolume == m.totalVolume
                && timeCreated == m.timeCreated
                && timeModified == m.timeModified
                && regionCount == m.regionCount
                && Objects.equals(name, m.name)
                && Objects.equals(author, m.author)
                && Objects.equals(description, m.description)
                && java.util.Arrays.equals(previewImageData, m.previewImageData);
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(name, author, description,
                enclosingSizeX, enclosingSizeY, enclosingSizeZ,
                totalBlocks, totalVolume, timeCreated, timeModified, regionCount);
        return 31 * h + java.util.Arrays.hashCode(previewImageData);
    }
}
