package fr.ekaii.litematica.core;

import fr.ekaii.litematica.core.LitematicNbt.NamedTag;
import fr.ekaii.litematica.core.LitematicNbt.NbtCompound;
import fr.ekaii.litematica.core.LitematicNbt.NbtList;
import fr.ekaii.litematica.core.LitematicNbt.NbtTag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decodes a {@code .litematic} (gzipped NBT) byte stream into a
 * {@link LitematicSchematic} POJO. Decoding is forgiving on optional fields
 * but strict on required structure — anything malformed enough to risk a
 * silent corruption surfaces as an {@link IOException}.
 *
 * <p>Tile-entity / entity / pending-tick lists are kept as opaque NBT
 * (passthrough) so we can rewrite them byte-identically.
 */
public final class LitematicReader {

    private LitematicReader() {
    }

    public static LitematicSchematic read(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in);
        }
    }

    public static LitematicSchematic read(byte[] bytes) throws IOException {
        return read(new ByteArrayInputStream(bytes));
    }

    public static LitematicSchematic read(InputStream in) throws IOException {
        NamedTag named = LitematicNbt.read(in);
        if (!(named.tag() instanceof NbtCompound root)) {
            throw new IOException("Litematic root is not a Compound tag");
        }
        return fromCompound(root);
    }

    /** Builds a schematic from an already-parsed NBT root compound. */
    public static LitematicSchematic fromCompound(NbtCompound root) throws IOException {
        LitematicSchematic schem = new LitematicSchematic();

        Integer version = root.getInt("Version");
        if (version == null) {
            throw new IOException("Missing Version tag (schematic is probably not a .litematic)");
        }
        schem.version = version;

        Integer subVersion = root.getInt("SubVersion");
        schem.subVersion = subVersion;     // optional

        Integer mcDV = root.getInt("MinecraftDataVersion");
        schem.minecraftDataVersion = mcDV == null ? 0 : mcDV;

        NbtCompound metaTag = root.getCompound("Metadata");
        if (metaTag == null) throw new IOException("Missing Metadata compound");
        readMetadata(metaTag, schem.metadata);

        NbtCompound regionsTag = root.getCompound("Regions");
        if (regionsTag == null) throw new IOException("Missing Regions compound");

        for (Map.Entry<String, NbtTag> e : regionsTag.entries().entrySet()) {
            if (!(e.getValue() instanceof NbtCompound rc)) {
                throw new IOException("Region '" + e.getKey() + "' is not a Compound");
            }
            LitematicRegion region = readRegion(e.getKey(), rc);
            schem.regions.put(e.getKey(), region);
        }
        return schem;
    }

    // --------------------------------------------------------------- Metadata

    private static void readMetadata(NbtCompound t, LitematicMetadata m) {
        m.name = t.getString("Name");
        m.author = t.getString("Author");
        m.description = t.getString("Description");

        NbtCompound size = t.getCompound("EnclosingSize");
        if (size != null) {
            // EnclosingSize is recorded as signed ints; we normalise to absolute
            // (the per-axis sign lives on each region via signX/signY/signZ).
            Integer x = size.getInt("x"), y = size.getInt("y"), z = size.getInt("z");
            m.enclosingSizeX = x == null ? 0 : Math.abs(x);
            m.enclosingSizeY = y == null ? 0 : Math.abs(y);
            m.enclosingSizeZ = z == null ? 0 : Math.abs(z);
        }

        Long tb = t.getLong("TotalBlocks");
        Long tv = t.getLong("TotalVolume");
        Long tc = t.getLong("TimeCreated");
        Long tm = t.getLong("TimeModified");
        Integer rc = t.getInt("RegionCount");

        m.totalBlocks = tb == null ? 0 : tb;
        m.totalVolume = tv == null ? 0 : tv;
        m.timeCreated = tc == null ? 0 : tc;
        m.timeModified = tm == null ? 0 : tm;
        m.regionCount = rc == null ? 0 : rc;

        m.previewImageData = t.getIntArray("PreviewImageData");
    }

    // ---------------------------------------------------------------- Region

    private static LitematicRegion readRegion(String name, NbtCompound rc) throws IOException {
        LitematicRegion r = new LitematicRegion();
        r.name = name;

        readPosition(rc, r);
        readSize(rc, r);
        readPalette(rc, r);

        long[] blockStates = rc.getLongArray("BlockStates");
        if (blockStates == null) blockStates = new long[0];
        long volume = r.volume();
        if (volume > Integer.MAX_VALUE) {
            throw new IOException("Region '" + name + "' too large (" + volume + " cells)");
        }
        int bits = PackedLongArray.recommendedBitsPerEntry(r.palette.size());
        r.blocks = PackedLongArray.decode(blockStates, bits, (int) volume);

        r.tileEntities      = asCompoundList(rc.get("TileEntities"));
        r.entities          = asCompoundList(rc.get("Entities"));
        r.pendingBlockTicks = asCompoundList(rc.get("PendingBlockTicks"));
        r.pendingFluidTicks = asCompoundList(rc.get("PendingFluidTicks"));

        return r;
    }

    /**
     * Normalises a passthrough list into a {@code TAG_COMPOUND}-typed list,
     * matching Litematica's canonical wire form. Empty lists on disk are
     * encoded with element type {@code TAG_END}; we re-tag them so that
     * round-tripping byte-for-byte produces a stable layout.
     */
    private static NbtList asCompoundList(NbtTag t) {
        if (!(t instanceof NbtList l)) {
            return new NbtList(LitematicNbt.TAG_COMPOUND, new java.util.ArrayList<>());
        }
        if (l.size() == 0 && l.elementType() != LitematicNbt.TAG_COMPOUND) {
            return new NbtList(LitematicNbt.TAG_COMPOUND, new java.util.ArrayList<>());
        }
        return l;
    }

    private static void readPosition(NbtCompound rc, LitematicRegion r) throws IOException {
        NbtCompound pos = rc.getCompound("Position");
        if (pos != null) {
            Integer x = pos.getInt("x"), y = pos.getInt("y"), z = pos.getInt("z");
            r.originX = x == null ? 0 : x;
            r.originY = y == null ? 0 : y;
            r.originZ = z == null ? 0 : z;
        } else {
            throw new IOException("Region '" + r.name + "' missing Position");
        }
    }

    private static void readSize(NbtCompound rc, LitematicRegion r) throws IOException {
        NbtCompound size = rc.getCompound("Size");
        if (size == null) {
            throw new IOException("Region '" + r.name + "' missing Size");
        }
        Integer rawX = size.getInt("x"), rawY = size.getInt("y"), rawZ = size.getInt("z");
        int sx = rawX == null ? 0 : rawX;
        int sy = rawY == null ? 0 : rawY;
        int sz = rawZ == null ? 0 : rawZ;

        // Normalise: a negative axis means the region extends in the negative
        // direction from origin; we record the sign and shift the origin so
        // that {@code sizeX/Y/Z} is always strictly positive.
        r.signX = sx < 0 ? -1 : 1;
        r.signY = sy < 0 ? -1 : 1;
        r.signZ = sz < 0 ? -1 : 1;

        r.sizeX = Math.abs(sx);
        r.sizeY = Math.abs(sy);
        r.sizeZ = Math.abs(sz);

        if (sx < 0) r.originX += sx + 1;
        if (sy < 0) r.originY += sy + 1;
        if (sz < 0) r.originZ += sz + 1;

        if (r.sizeX == 0 || r.sizeY == 0 || r.sizeZ == 0) {
            throw new IOException("Region '" + r.name + "' has zero-volume Size");
        }
    }

    private static void readPalette(NbtCompound rc, LitematicRegion r) throws IOException {
        NbtList palTag = rc.getList("BlockStatePalette");
        if (palTag == null) {
            throw new IOException("Region '" + r.name + "' missing BlockStatePalette");
        }
        for (int i = 0; i < palTag.size(); i++) {
            NbtTag t = palTag.get(i);
            if (!(t instanceof NbtCompound c)) {
                throw new IOException("Palette entry " + i + " is not a Compound");
            }
            String blockName = c.getString("Name");
            if (blockName == null) {
                throw new IOException("Palette entry " + i + " missing Name");
            }
            NbtCompound props = c.getCompound("Properties");
            Map<String, String> propsMap = new LinkedHashMap<>();
            if (props != null) {
                for (Map.Entry<String, NbtTag> e : props.entries().entrySet()) {
                    if (e.getValue() instanceof LitematicNbt.NbtString s) {
                        propsMap.put(e.getKey(), s.value());
                    }
                }
            }
            r.palette.add(new BlockStateEntry(blockName, propsMap));
        }
        if (r.palette.isEmpty()) {
            throw new IOException("Region '" + r.name + "' has empty BlockStatePalette");
        }
    }
}
