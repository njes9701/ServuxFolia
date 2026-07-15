package fr.ekaii.litematica.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save-v2 parser round-trip: build an in-memory fixture with
 * <ul>
 *   <li>a chest tile-entity (TE NBT compound with id=minecraft:chest)</li>
 *   <li>an item-frame entity (Entity NBT compound with id=minecraft:item_frame)</li>
 *   <li>a pending block tick on a redstone repeater</li>
 *   <li>a pending fluid tick on a flowing-water cell</li>
 * </ul>
 * Write via {@link LitematicWriter}, read back via {@link LitematicReader},
 * and assert each list survives the gzipped-NBT round-trip with the right
 * shape. This exercises the same code path that
 * {@code /litematica save} writes from after live NMS extraction.
 *
 * <p>The live NMS extraction (server-side) is exercised by the running
 * Luminol 26.1.2 smoke server — this test isolates the file-format edge.
 */
class LitematicSaveRoundtripTest {

    /** Build a 4×4×4 fixture with TE + entity + pending ticks. */
    private static LitematicSchematic buildSaveLikeFixture() {
        LitematicSchematic s = new LitematicSchematic();
        s.version = 6;
        s.minecraftDataVersion = 4325;
        s.metadata.name = "save-roundtrip-test";
        s.metadata.author = "ekaii-litematica-test";
        s.metadata.description = "Fixture for save-v2 round-trip";
        s.metadata.enclosingSizeX = 4;
        s.metadata.enclosingSizeY = 4;
        s.metadata.enclosingSizeZ = 4;
        s.metadata.totalVolume = 64;
        s.metadata.regionCount = 1;
        s.metadata.timeCreated = 1_700_000_000_000L;
        s.metadata.timeModified = 1_700_000_000_000L;

        LitematicRegion r = new LitematicRegion();
        r.name = "main";
        r.originX = 0;
        r.originY = 65;
        r.originZ = 0;
        r.sizeX = 4;
        r.sizeY = 4;
        r.sizeZ = 4;

        // Palette: air, stone, chest, repeater, water
        r.palette.add(new BlockStateEntry("minecraft:air"));
        r.palette.add(new BlockStateEntry("minecraft:stone"));
        LinkedHashMap<String, String> chestProps = new LinkedHashMap<>();
        chestProps.put("facing", "north");
        chestProps.put("type", "single");
        chestProps.put("waterlogged", "false");
        r.palette.add(new BlockStateEntry("minecraft:chest", chestProps));
        LinkedHashMap<String, String> repProps = new LinkedHashMap<>();
        repProps.put("delay", "2");
        repProps.put("facing", "north");
        repProps.put("locked", "false");
        repProps.put("powered", "false");
        r.palette.add(new BlockStateEntry("minecraft:repeater", repProps));
        LinkedHashMap<String, String> waterProps = new LinkedHashMap<>();
        waterProps.put("level", "1");
        r.palette.add(new BlockStateEntry("minecraft:water", waterProps));

        r.blocks = new int[64];
        // Stone floor (y=0)
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) r.blocks[r.indexOf(x, 0, z)] = 1;
        // Chest at (1,1,1)
        r.blocks[r.indexOf(1, 1, 1)] = 2;
        // Repeater at (2,1,1)
        r.blocks[r.indexOf(2, 1, 1)] = 3;
        // Water at (3,1,1)
        r.blocks[r.indexOf(3, 1, 1)] = 4;

        // Tile entity for the chest (region-local coords)
        LitematicNbt.NbtList tes = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        LitematicNbt.NbtCompound te = new LitematicNbt.NbtCompound();
        te.putInt("x", 1);
        te.putInt("y", 1);
        te.putInt("z", 1);
        te.putString("id", "minecraft:chest");
        LitematicNbt.NbtList items = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        LitematicNbt.NbtCompound item = new LitematicNbt.NbtCompound();
        item.putByte("Slot", (byte) 0);
        item.putString("id", "minecraft:redstone");
        item.putInt("count", 7);
        items.values().add(item);
        te.put("Items", items);
        tes.values().add(te);
        r.tileEntities = tes;

        // Entity: one item_frame at (2.5, 2.5, 2.5) region-local
        LitematicNbt.NbtList ents = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        LitematicNbt.NbtCompound ent = new LitematicNbt.NbtCompound();
        ent.putString("id", "minecraft:item_frame");
        LitematicNbt.NbtList pos = new LitematicNbt.NbtList(LitematicNbt.TAG_DOUBLE, new ArrayList<>());
        pos.values().add(new LitematicNbt.NbtDouble(2.5));
        pos.values().add(new LitematicNbt.NbtDouble(2.5));
        pos.values().add(new LitematicNbt.NbtDouble(2.5));
        ent.put("Pos", pos);
        ent.putByte("Facing", (byte) 1);
        ents.values().add(ent);
        r.entities = ents;

        // Pending block tick on the repeater (delay=2, priority=0)
        LitematicNbt.NbtList bt = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        LitematicNbt.NbtCompound tick = new LitematicNbt.NbtCompound();
        tick.putString("i", "minecraft:repeater");
        tick.putInt("x", 2);
        tick.putInt("y", 1);
        tick.putInt("z", 1);
        tick.putInt("t", 2);
        tick.putInt("p", 0);
        bt.values().add(tick);
        r.pendingBlockTicks = bt;

        // Pending fluid tick on the water (delay=5, priority=0)
        LitematicNbt.NbtList ft = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        LitematicNbt.NbtCompound fluidTick = new LitematicNbt.NbtCompound();
        fluidTick.putString("i", "minecraft:flowing_water");
        fluidTick.putInt("x", 3);
        fluidTick.putInt("y", 1);
        fluidTick.putInt("z", 1);
        fluidTick.putInt("t", 5);
        fluidTick.putInt("p", 0);
        ft.values().add(fluidTick);
        r.pendingFluidTicks = ft;

        s.regions.put(r.name, r);
        s.metadata.totalBlocks = r.countNonAir();
        return s;
    }

    @Test
    void saveLikeFixtureRoundTrips() throws Exception {
        LitematicSchematic original = buildSaveLikeFixture();
        byte[] bytes = Fixtures.bytes(original);
        LitematicSchematic decoded = LitematicReader.read(new ByteArrayInputStream(bytes));
        assertEquals(original, decoded);
    }

    @Test
    void tileEntitiesSurviveRoundTripWithChestId() throws Exception {
        byte[] bytes = Fixtures.bytes(buildSaveLikeFixture());
        LitematicSchematic s = LitematicReader.read(new ByteArrayInputStream(bytes));
        LitematicRegion r = s.regions.get("main");

        assertNotNull(r.tileEntities, "tileEntities list must be present");
        assertEquals(1, r.tileEntities.size(), "expected exactly 1 TE");
        LitematicNbt.NbtCompound te = (LitematicNbt.NbtCompound) r.tileEntities.get(0);
        assertEquals("minecraft:chest", te.getString("id"));
        assertEquals(Integer.valueOf(1), te.getInt("x"));
        assertEquals(Integer.valueOf(1), te.getInt("y"));
        assertEquals(Integer.valueOf(1), te.getInt("z"));

        // Items list passthrough
        LitematicNbt.NbtList items = te.getList("Items");
        assertNotNull(items);
        assertEquals(1, items.size());
        LitematicNbt.NbtCompound item = (LitematicNbt.NbtCompound) items.get(0);
        assertEquals("minecraft:redstone", item.getString("id"));
        assertEquals(Integer.valueOf(7), item.getInt("count"));
    }

    @Test
    void entitiesSurviveRoundTripWithItemFrameId() throws Exception {
        byte[] bytes = Fixtures.bytes(buildSaveLikeFixture());
        LitematicSchematic s = LitematicReader.read(new ByteArrayInputStream(bytes));
        LitematicRegion r = s.regions.get("main");

        assertNotNull(r.entities, "entities list must be present");
        assertEquals(1, r.entities.size(), "expected exactly 1 entity");
        LitematicNbt.NbtCompound e = (LitematicNbt.NbtCompound) r.entities.get(0);
        assertEquals("minecraft:item_frame", e.getString("id"));

        LitematicNbt.NbtList pos = e.getList("Pos");
        assertNotNull(pos);
        assertEquals(3, pos.size());
        assertTrue(pos.get(0) instanceof LitematicNbt.NbtDouble);
        assertEquals(2.5, ((LitematicNbt.NbtDouble) pos.get(0)).value(), 0.0);
        assertEquals(2.5, ((LitematicNbt.NbtDouble) pos.get(1)).value(), 0.0);
        assertEquals(2.5, ((LitematicNbt.NbtDouble) pos.get(2)).value(), 0.0);
    }

    @Test
    void pendingBlockTicksSurviveRoundTrip() throws Exception {
        byte[] bytes = Fixtures.bytes(buildSaveLikeFixture());
        LitematicSchematic s = LitematicReader.read(new ByteArrayInputStream(bytes));
        LitematicRegion r = s.regions.get("main");

        assertNotNull(r.pendingBlockTicks, "pendingBlockTicks list must be present");
        assertEquals(1, r.pendingBlockTicks.size(), "expected exactly 1 pending block tick");
        LitematicNbt.NbtCompound tick = (LitematicNbt.NbtCompound) r.pendingBlockTicks.get(0);
        assertEquals("minecraft:repeater", tick.getString("i"));
        assertEquals(Integer.valueOf(2), tick.getInt("x"));
        assertEquals(Integer.valueOf(1), tick.getInt("y"));
        assertEquals(Integer.valueOf(1), tick.getInt("z"));
        assertEquals(Integer.valueOf(2), tick.getInt("t"));
        assertEquals(Integer.valueOf(0), tick.getInt("p"));
    }

    @Test
    void pendingFluidTicksSurviveRoundTrip() throws Exception {
        byte[] bytes = Fixtures.bytes(buildSaveLikeFixture());
        LitematicSchematic s = LitematicReader.read(new ByteArrayInputStream(bytes));
        LitematicRegion r = s.regions.get("main");

        assertNotNull(r.pendingFluidTicks, "pendingFluidTicks list must be present");
        assertEquals(1, r.pendingFluidTicks.size(), "expected exactly 1 pending fluid tick");
        LitematicNbt.NbtCompound tick = (LitematicNbt.NbtCompound) r.pendingFluidTicks.get(0);
        assertEquals("minecraft:flowing_water", tick.getString("i"));
        assertEquals(Integer.valueOf(3), tick.getInt("x"));
        assertEquals(Integer.valueOf(5), tick.getInt("t"));
    }

    @Test
    void emptyExtractionListsRoundTrip() throws Exception {
        // /litematica save on a featureless region must emit 0-length lists,
        // not omit the keys — readers (including future stricter ones) expect
        // them. Verify that an air-only fixture round-trips with empty lists.
        LitematicSchematic s = new LitematicSchematic();
        s.version = 6;
        s.minecraftDataVersion = 4325;
        s.metadata.enclosingSizeX = 2;
        s.metadata.enclosingSizeY = 2;
        s.metadata.enclosingSizeZ = 2;
        s.metadata.regionCount = 1;

        LitematicRegion r = new LitematicRegion();
        r.name = "empty";
        r.sizeX = 2;
        r.sizeY = 2;
        r.sizeZ = 2;
        r.palette.add(new BlockStateEntry("minecraft:air"));
        r.blocks = new int[8];
        r.tileEntities      = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.entities          = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingBlockTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingFluidTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        s.regions.put("empty", r);

        byte[] bytes = Fixtures.bytes(s);
        LitematicSchematic decoded = LitematicReader.read(new ByteArrayInputStream(bytes));
        LitematicRegion dr = decoded.regions.get("empty");
        assertEquals(0, dr.tileEntities.size());
        assertEquals(0, dr.entities.size());
        assertEquals(0, dr.pendingBlockTicks.size());
        assertEquals(0, dr.pendingFluidTicks.size());
    }
}
