package fr.ekaii.litematica.core;

import fr.ekaii.litematica.core.LitematicNbt.NamedTag;
import fr.ekaii.litematica.core.LitematicNbt.NbtCompound;
import fr.ekaii.litematica.core.LitematicNbt.NbtList;
import fr.ekaii.litematica.core.LitematicNbt.NbtString;
import fr.ekaii.litematica.core.LitematicNbt.NbtTag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Encodes a {@link LitematicSchematic} POJO back into a {@code .litematic}
 * (gzipped NBT) byte stream, fully round-trip compatible with
 * {@link LitematicReader}.
 *
 * <p>Sign normalisation done at read time is reapplied here: if a region's
 * {@code signX/Y/Z} was negative, we re-emit the on-disk {@code Size}
 * component as negative and shift the {@code Position} back to the original
 * far-corner.
 */
public final class LitematicWriter {

    private LitematicWriter() {
    }

    public static void write(Path path, LitematicSchematic schematic) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) {
            write(out, schematic);
        }
    }

    public static byte[] writeToBytes(LitematicSchematic schematic) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        write(baos, schematic);
        return baos.toByteArray();
    }

    public static void write(OutputStream out, LitematicSchematic schematic) throws IOException {
        NbtCompound root = toCompound(schematic);
        LitematicNbt.writeGzipped(out, new NamedTag("", root));
    }

    /** Serialises the schematic as an in-memory NBT compound (no gzip). */
    public static NbtCompound toCompound(LitematicSchematic schem) {
        NbtCompound root = new NbtCompound();
        root.putInt("Version", schem.version);
        if (schem.subVersion != null) {
            root.putInt("SubVersion", schem.subVersion);
        }
        root.putInt("MinecraftDataVersion", schem.minecraftDataVersion);
        root.put("Metadata", writeMetadata(schem.metadata));

        NbtCompound regions = new NbtCompound();
        for (Map.Entry<String, LitematicRegion> e : schem.regions.entrySet()) {
            regions.put(e.getKey(), writeRegion(e.getValue()));
        }
        root.put("Regions", regions);
        return root;
    }

    // --------------------------------------------------------------- Metadata

    private static NbtCompound writeMetadata(LitematicMetadata m) {
        NbtCompound t = new NbtCompound();
        if (m.name != null) t.putString("Name", m.name);
        if (m.author != null) t.putString("Author", m.author);
        if (m.description != null) t.putString("Description", m.description);

        NbtCompound size = new NbtCompound();
        size.putInt("x", m.enclosingSizeX);
        size.putInt("y", m.enclosingSizeY);
        size.putInt("z", m.enclosingSizeZ);
        t.put("EnclosingSize", size);

        t.putLong("TotalBlocks", m.totalBlocks);
        t.putLong("TotalVolume", m.totalVolume);
        t.putLong("TimeCreated", m.timeCreated);
        t.putLong("TimeModified", m.timeModified);
        t.putInt("RegionCount", m.regionCount);

        if (m.previewImageData != null) {
            t.putIntArray("PreviewImageData", m.previewImageData);
        }
        return t;
    }

    // ---------------------------------------------------------------- Region

    private static NbtCompound writeRegion(LitematicRegion r) {
        NbtCompound t = new NbtCompound();

        // Reverse the sign normalisation done at read time.
        int outOriginX = r.originX;
        int outOriginY = r.originY;
        int outOriginZ = r.originZ;
        int outSizeX = r.sizeX * r.signX;
        int outSizeY = r.sizeY * r.signY;
        int outSizeZ = r.sizeZ * r.signZ;

        if (r.signX < 0) outOriginX -= outSizeX + 1;
        if (r.signY < 0) outOriginY -= outSizeY + 1;
        if (r.signZ < 0) outOriginZ -= outSizeZ + 1;

        NbtCompound pos = new NbtCompound();
        pos.putInt("x", outOriginX);
        pos.putInt("y", outOriginY);
        pos.putInt("z", outOriginZ);
        t.put("Position", pos);

        NbtCompound size = new NbtCompound();
        size.putInt("x", outSizeX);
        size.putInt("y", outSizeY);
        size.putInt("z", outSizeZ);
        t.put("Size", size);

        NbtList palTag = new NbtList(LitematicNbt.TAG_COMPOUND, new java.util.ArrayList<>());
        for (BlockStateEntry entry : r.palette) {
            NbtCompound c = new NbtCompound();
            c.putString("Name", entry.name());
            if (!entry.properties().isEmpty()) {
                NbtCompound props = new NbtCompound();
                for (Map.Entry<String, String> p : entry.properties().entrySet()) {
                    props.put(p.getKey(), new NbtString(p.getValue()));
                }
                c.put("Properties", props);
            }
            palTag.values().add(c);
        }
        t.put("BlockStatePalette", palTag);

        int bits = PackedLongArray.recommendedBitsPerEntry(r.palette.size());
        long[] packed = PackedLongArray.encode(r.blocks, bits);
        t.putLongArray("BlockStates", packed);

        t.put("TileEntities",      ensureCompoundList(r.tileEntities));
        t.put("Entities",          ensureCompoundList(r.entities));
        t.put("PendingBlockTicks", ensureCompoundList(r.pendingBlockTicks));
        t.put("PendingFluidTicks", ensureCompoundList(r.pendingFluidTicks));

        return t;
    }

    /**
     * Litematica always writes these passthrough lists with element type
     * {@code TAG_COMPOUND}, even when empty. If we kept an empty list with
     * element type {@code TAG_END} the round-trip would still parse cleanly
     * but the byte layout would drift from Litematica's canonical form.
     */
    private static NbtList ensureCompoundList(NbtList l) {
        if (l == null) {
            return new NbtList(LitematicNbt.TAG_COMPOUND, new java.util.ArrayList<>());
        }
        if (l.size() == 0 && l.elementType() != LitematicNbt.TAG_COMPOUND) {
            return new NbtList(LitematicNbt.TAG_COMPOUND, new java.util.ArrayList<>());
        }
        // copy through (defensive — caller may keep mutating the passthrough)
        java.util.ArrayList<NbtTag> copy = new java.util.ArrayList<>(l.values());
        return new NbtList(l.elementType(), copy);
    }
}
