package fr.ekaii.litematica.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitematicReaderWriterTest {

    @Test
    void roundtripStoneCubeFixture() throws Exception {
        LitematicSchematic original = Fixtures.stoneCube4();
        byte[] bytes = Fixtures.bytes(original);

        // Sanity: gzip magic bytes
        assertEquals((byte) 0x1f, bytes[0]);
        assertEquals((byte) 0x8b, bytes[1]);

        LitematicSchematic decoded = LitematicReader.read(new ByteArrayInputStream(bytes));
        assertEquals(original, decoded);
    }

    @Test
    void roundtripMixedRoomFixture() throws Exception {
        LitematicSchematic original = Fixtures.mixedRoom8();
        byte[] bytes = Fixtures.bytes(original);
        LitematicSchematic decoded = LitematicReader.read(new ByteArrayInputStream(bytes));
        assertEquals(original, decoded);
    }

    @Test
    void stoneCubeDecodeAssertions() throws Exception {
        byte[] bytes = Fixtures.bytes(Fixtures.stoneCube4());
        LitematicSchematic s = LitematicReader.read(new ByteArrayInputStream(bytes));

        assertEquals(1, s.regions.size());
        LitematicRegion r = s.regions.get("main");
        assertNotNull(r);
        assertEquals(4, r.sizeX);
        assertEquals(4, r.sizeY);
        assertEquals(4, r.sizeZ);
        assertEquals(2, r.palette.size());
        assertEquals("minecraft:air", r.palette.get(0).toMinecraftString());
        assertEquals("minecraft:stone", r.palette.get(1).toMinecraftString());

        // All cells should be stone (palette index 1)
        for (int b : r.blocks) assertEquals(1, b);
        assertEquals(64L, r.countNonAir());
        assertEquals(64L, s.metadata.totalBlocks);
    }

    @Test
    void mixedRoomDecodeAssertions() throws Exception {
        byte[] bytes = Fixtures.bytes(Fixtures.mixedRoom8());
        LitematicSchematic s = LitematicReader.read(new ByteArrayInputStream(bytes));

        LitematicRegion r = s.regions.get("main");
        assertEquals(8, r.sizeX);
        assertEquals(8, r.sizeY);
        assertEquals(8, r.sizeZ);
        assertEquals(6, r.palette.size());

        // chest entry — properties preserved
        BlockStateEntry chest = r.palette.get(3);
        assertEquals("minecraft:chest", chest.name());
        assertEquals("north", chest.properties().get("facing"));
        assertEquals("single", chest.properties().get("type"));

        // chest block in the region
        assertEquals(3, r.blockIndexAt(1, 1, 1));

        // One tile entity (chest) and one entity (item frame), passthrough
        assertNotNull(r.tileEntities);
        assertEquals(1, r.tileEntities.size());
        assertEquals(1, r.entities.size());

        // Tile-entity passthrough sanity: chest position recorded inside the TE NBT
        LitematicNbt.NbtCompound te = (LitematicNbt.NbtCompound) r.tileEntities.get(0);
        assertEquals(Integer.valueOf(1), te.getInt("x"));
        assertEquals(Integer.valueOf(1), te.getInt("y"));
        assertEquals(Integer.valueOf(1), te.getInt("z"));
        assertEquals("minecraft:chest", te.getString("id"));
    }

    @Test
    void emptyRegionAirOnly() throws Exception {
        // Palette = [air] only → bitsPerEntry clamped to 2
        LitematicSchematic schem = new LitematicSchematic();
        schem.version = 6;
        schem.minecraftDataVersion = 4325;
        schem.metadata.name = "air";
        schem.metadata.enclosingSizeX = 2;
        schem.metadata.enclosingSizeY = 2;
        schem.metadata.enclosingSizeZ = 2;
        schem.metadata.regionCount = 1;

        LitematicRegion r = new LitematicRegion();
        r.name = "air";
        r.sizeX = 2; r.sizeY = 2; r.sizeZ = 2;
        r.palette.add(new BlockStateEntry("minecraft:air"));
        r.blocks = new int[8]; // all zero
        r.tileEntities      = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.entities          = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingBlockTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingFluidTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        schem.regions.put("air", r);

        byte[] bytes = Fixtures.bytes(schem);
        LitematicSchematic decoded = LitematicReader.read(new ByteArrayInputStream(bytes));
        assertEquals(schem, decoded);
        assertEquals(0, decoded.regions.get("air").countNonAir());
    }

    @Test
    void bitsPerEntry5LargePaletteRoundtrips() throws Exception {
        LitematicSchematic schem = new LitematicSchematic();
        schem.version = 6;
        schem.minecraftDataVersion = 4325;
        schem.metadata.enclosingSizeX = 4;
        schem.metadata.enclosingSizeY = 4;
        schem.metadata.enclosingSizeZ = 4;
        schem.metadata.regionCount = 1;

        LitematicRegion r = new LitematicRegion();
        r.name = "main";
        r.sizeX = 4; r.sizeY = 4; r.sizeZ = 4;
        // 20-entry palette → bitsPerEntry = 5
        for (int i = 0; i < 20; i++) {
            r.palette.add(new BlockStateEntry("minecraft:wool_" + i,
                    Map.of("color", String.valueOf(i))));
        }
        r.blocks = new int[64];
        for (int i = 0; i < 64; i++) r.blocks[i] = i % 20;
        r.tileEntities      = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.entities          = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingBlockTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingFluidTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        schem.regions.put("main", r);

        // Confirm we are testing bitsPerEntry=5
        assertEquals(5, PackedLongArray.recommendedBitsPerEntry(r.palette.size()));

        byte[] bytes = Fixtures.bytes(schem);
        LitematicSchematic decoded = LitematicReader.read(new ByteArrayInputStream(bytes));
        assertEquals(schem, decoded);
    }

    @Test
    void negativeSizeIsNormalisedAndPreservedOnRoundtrip() throws Exception {
        // Build a schematic on disk with negative Size.x and Size.z
        LitematicNbt.NbtCompound root = new LitematicNbt.NbtCompound();
        root.putInt("Version", 6);
        root.putInt("MinecraftDataVersion", 4325);

        LitematicNbt.NbtCompound metadata = new LitematicNbt.NbtCompound();
        metadata.putString("Name", "neg-size");
        LitematicNbt.NbtCompound encl = new LitematicNbt.NbtCompound();
        encl.putInt("x", 3); encl.putInt("y", 2); encl.putInt("z", 4);
        metadata.put("EnclosingSize", encl);
        metadata.putLong("TotalBlocks", 0);
        metadata.putLong("TotalVolume", 24);
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TimeCreated", 0);
        metadata.putLong("TimeModified", 0);
        root.put("Metadata", metadata);

        LitematicNbt.NbtCompound regionsTag = new LitematicNbt.NbtCompound();
        LitematicNbt.NbtCompound regionTag = new LitematicNbt.NbtCompound();

        LitematicNbt.NbtCompound pos = new LitematicNbt.NbtCompound();
        pos.putInt("x", 10); pos.putInt("y", 64); pos.putInt("z", 20);
        regionTag.put("Position", pos);

        LitematicNbt.NbtCompound size = new LitematicNbt.NbtCompound();
        // Negative axis sizes — the reader must normalise these.
        size.putInt("x", -3); size.putInt("y", 2); size.putInt("z", -4);
        regionTag.put("Size", size);

        LitematicNbt.NbtList palette = new LitematicNbt.NbtList(
                LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        LitematicNbt.NbtCompound air = new LitematicNbt.NbtCompound();
        air.putString("Name", "minecraft:air");
        palette.values().add(air);
        LitematicNbt.NbtCompound stone = new LitematicNbt.NbtCompound();
        stone.putString("Name", "minecraft:stone");
        palette.values().add(stone);
        regionTag.put("BlockStatePalette", palette);

        // 24 cells, all index 1 (stone). bitsPerEntry = 2.
        int[] blocks = new int[24];
        for (int i = 0; i < 24; i++) blocks[i] = 1;
        long[] packed = PackedLongArray.encode(blocks, 2);
        regionTag.putLongArray("BlockStates", packed);

        regionTag.put("TileEntities", new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>()));
        regionTag.put("Entities", new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>()));
        regionTag.put("PendingBlockTicks", new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>()));
        regionTag.put("PendingFluidTicks", new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>()));

        regionsTag.put("flipped", regionTag);
        root.put("Regions", regionsTag);

        LitematicSchematic decoded = LitematicReader.fromCompound(root);
        LitematicRegion r = decoded.regions.get("flipped");
        // Absolute, normalised
        assertEquals(3, r.sizeX);
        assertEquals(2, r.sizeY);
        assertEquals(4, r.sizeZ);
        assertEquals(-1, r.signX);
        assertEquals(1, r.signY);
        assertEquals(-1, r.signZ);
        // Origin adjusted: for negative axes, position is the "far" corner;
        // normalised origin = pos + size + 1 (where size < 0)
        // x: 10 + (-3 + 1) = 8 ; z: 20 + (-4 + 1) = 17
        assertEquals(8, r.originX);
        assertEquals(64, r.originY);
        assertEquals(17, r.originZ);

        // Roundtrip: re-emit and re-read; sign+origin must come back to the original on disk
        byte[] bytes = Fixtures.bytes(decoded);
        LitematicSchematic redecoded = LitematicReader.read(new ByteArrayInputStream(bytes));
        assertEquals(decoded, redecoded);

        // And the encoded Size NBT preserves the original signs.
        LitematicNbt.NamedTag named = LitematicNbt.read(new ByteArrayInputStream(bytes));
        LitematicNbt.NbtCompound rootBack = (LitematicNbt.NbtCompound) named.tag();
        LitematicNbt.NbtCompound regionsBack = rootBack.getCompound("Regions");
        LitematicNbt.NbtCompound flippedBack = regionsBack.getCompound("flipped");
        LitematicNbt.NbtCompound sizeBack = flippedBack.getCompound("Size");
        LitematicNbt.NbtCompound posBack = flippedBack.getCompound("Position");
        assertEquals(Integer.valueOf(-3), sizeBack.getInt("x"));
        assertEquals(Integer.valueOf(2),  sizeBack.getInt("y"));
        assertEquals(Integer.valueOf(-4), sizeBack.getInt("z"));
        assertEquals(Integer.valueOf(10), posBack.getInt("x"));
        assertEquals(Integer.valueOf(64), posBack.getInt("y"));
        assertEquals(Integer.valueOf(20), posBack.getInt("z"));

        // And volume + indices still consistent
        assertTrue(r.volume() == 24);
    }
}
