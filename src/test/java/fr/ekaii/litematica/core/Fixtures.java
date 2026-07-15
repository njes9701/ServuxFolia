package fr.ekaii.litematica.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Programmatic {@code .litematic} fixture generator. Builds schematics from
 * scratch (no external libraries) and serialises them through
 * {@link LitematicWriter}, so each fixture is a fully valid gzipped-NBT
 * artifact the reader can ingest end-to-end.
 *
 * <p>Two canonical fixtures are exposed:
 * <ul>
 *     <li>{@link #stoneCube4()} — 4×4×4 solid stone (palette: air + stone)</li>
 *     <li>{@link #mixedRoom8()} — 8×8×8 mixed-block room with one chest tile
 *         entity and one item-frame entity</li>
 * </ul>
 */
public final class Fixtures {

    private Fixtures() {
    }

    // ---------------------------------------------------------------- Helpers

    private static LitematicSchematic skeleton(String name, int dx, int dy, int dz, int regionCount) {
        LitematicSchematic s = new LitematicSchematic();
        s.version = 6;
        s.minecraftDataVersion = 4325;
        s.metadata.name = name;
        s.metadata.author = "ekaii-litematica-test";
        s.metadata.description = "Programmatic fixture";
        s.metadata.enclosingSizeX = dx;
        s.metadata.enclosingSizeY = dy;
        s.metadata.enclosingSizeZ = dz;
        s.metadata.totalVolume = (long) dx * dy * dz;
        s.metadata.regionCount = regionCount;
        s.metadata.timeCreated = 1_700_000_000_000L;
        s.metadata.timeModified = 1_700_000_000_000L;
        return s;
    }

    private static LitematicNbt.NbtCompound chestTileEntity(int x, int y, int z) {
        LitematicNbt.NbtCompound te = new LitematicNbt.NbtCompound();
        te.putInt("x", x);
        te.putInt("y", y);
        te.putInt("z", z);
        te.putString("id", "minecraft:chest");
        // Single emerald in slot 0 — passthrough only, we never interpret it.
        LitematicNbt.NbtList items = new LitematicNbt.NbtList(
                LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        LitematicNbt.NbtCompound item = new LitematicNbt.NbtCompound();
        item.putByte("Slot", (byte) 0);
        item.putString("id", "minecraft:emerald");
        item.putInt("count", 1);
        items.values().add(item);
        te.put("Items", items);
        return te;
    }

    private static LitematicNbt.NbtCompound itemFrameEntity(double x, double y, double z) {
        LitematicNbt.NbtCompound e = new LitematicNbt.NbtCompound();
        e.putString("id", "minecraft:item_frame");
        LitematicNbt.NbtList pos = new LitematicNbt.NbtList(
                LitematicNbt.TAG_DOUBLE, new ArrayList<>());
        pos.values().add(new LitematicNbt.NbtDouble(x));
        pos.values().add(new LitematicNbt.NbtDouble(y));
        pos.values().add(new LitematicNbt.NbtDouble(z));
        e.put("Pos", pos);
        e.putByte("Facing", (byte) 1);
        return e;
    }

    // -------------------------------------------------------------- Fixture A

    /** 4×4×4 solid stone, one region named "main". */
    public static LitematicSchematic stoneCube4() {
        LitematicSchematic s = skeleton("stone-cube-4", 4, 4, 4, 1);

        LitematicRegion r = new LitematicRegion();
        r.name = "main";
        r.originX = 0; r.originY = 64; r.originZ = 0;
        r.sizeX = 4; r.sizeY = 4; r.sizeZ = 4;
        r.palette.add(new BlockStateEntry("minecraft:air"));
        r.palette.add(new BlockStateEntry("minecraft:stone"));
        r.blocks = new int[4 * 4 * 4];
        for (int i = 0; i < r.blocks.length; i++) r.blocks[i] = 1;

        r.tileEntities      = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.entities          = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingBlockTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingFluidTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());

        s.regions.put(r.name, r);
        s.metadata.totalBlocks = r.countNonAir();
        return s;
    }

    // -------------------------------------------------------------- Fixture B

    /**
     * 8×8×8 mixed room: stone floor with oak-plank walls, a chest at
     * (1,1,1) with one emerald, and an item-frame entity hovering at
     * (2.5, 2.5, 2.5).
     *
     * <p>Palette: air, stone, oak_planks, chest[facing=north], glass,
     * oak_log[axis=y]. → 6 entries → bitsPerEntry = 3.
     */
    public static LitematicSchematic mixedRoom8() {
        LitematicSchematic s = skeleton("mixed-room-8", 8, 8, 8, 1);

        LitematicRegion r = new LitematicRegion();
        r.name = "main";
        r.originX = 100; r.originY = 70; r.originZ = -50;
        r.sizeX = 8; r.sizeY = 8; r.sizeZ = 8;

        r.palette.add(new BlockStateEntry("minecraft:air"));
        r.palette.add(new BlockStateEntry("minecraft:stone"));
        r.palette.add(new BlockStateEntry("minecraft:oak_planks"));
        r.palette.add(new BlockStateEntry("minecraft:chest", linked("facing", "north", "type", "single", "waterlogged", "false")));
        r.palette.add(new BlockStateEntry("minecraft:glass"));
        r.palette.add(new BlockStateEntry("minecraft:oak_log", linked("axis", "y")));

        r.blocks = new int[8 * 8 * 8];
        // y=0: full stone floor
        for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) r.blocks[r.indexOf(x, 0, z)] = 1;
        // walls (y=1..6): oak planks at perimeter
        for (int y = 1; y <= 6; y++) {
            for (int x = 0; x < 8; x++) {
                r.blocks[r.indexOf(x, y, 0)] = 2;
                r.blocks[r.indexOf(x, y, 7)] = 2;
            }
            for (int z = 0; z < 8; z++) {
                r.blocks[r.indexOf(0, y, z)] = 2;
                r.blocks[r.indexOf(7, y, z)] = 2;
            }
        }
        // Glass window at y=3..4, x=4, z=0
        r.blocks[r.indexOf(4, 3, 0)] = 4;
        r.blocks[r.indexOf(4, 4, 0)] = 4;
        // Oak log pillars at the four corners
        for (int y = 1; y <= 6; y++) {
            r.blocks[r.indexOf(0, y, 0)] = 5;
            r.blocks[r.indexOf(7, y, 0)] = 5;
            r.blocks[r.indexOf(0, y, 7)] = 5;
            r.blocks[r.indexOf(7, y, 7)] = 5;
        }
        // Chest at (1, 1, 1)
        r.blocks[r.indexOf(1, 1, 1)] = 3;
        // y=7: stone ceiling
        for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) r.blocks[r.indexOf(x, 7, z)] = 1;

        // Tile entity for the chest
        r.tileEntities = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.tileEntities.values().add(chestTileEntity(1, 1, 1));

        // One item-frame entity
        r.entities = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.entities.values().add(itemFrameEntity(2.5, 2.5, 2.5));

        r.pendingBlockTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingFluidTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());

        s.regions.put(r.name, r);
        s.metadata.totalBlocks = r.countNonAir();
        return s;
    }

    // -------------------------------------------------------------- Fixture C

    /**
     * 256×64×256 stress fixture = 4 194 304 cells. Approximate composition:
     * <ul>
     *   <li>~80% terrain: random stone/dirt/grass/cobblestone</li>
     *   <li>~15% chest blocks (each with a populated tile entity)</li>
     *   <li>~5% redstone components (observer, piston, repeater, comparator, redstone_wire)
     *       — to exercise the {@code observersLast} pass in {@link
     *       fr.ekaii.litematica.paste.PasteOperation}</li>
     * </ul>
     *
     * Deterministic via a fixed RNG seed so the fixture is reproducible.
     */
    public static LitematicSchematic largeFixture256() {
        final int DX = 256, DY = 64, DZ = 256;
        LitematicSchematic s = skeleton("large-256", DX, DY, DZ, 1);

        LitematicRegion r = new LitematicRegion();
        r.name = "main";
        r.originX = 0; r.originY = 64; r.originZ = 0;
        r.sizeX = DX; r.sizeY = DY; r.sizeZ = DZ;

        // Palette layout (indices used in blocks[]):
        //  0 air
        //  1 stone               terrain
        //  2 dirt                terrain
        //  3 grass_block         terrain (snowy=false)
        //  4 cobblestone         terrain
        //  5 chest[facing=north,type=single,waterlogged=false]
        //  6 observer[facing=up,powered=false]            redstone
        //  7 piston[facing=up,extended=false]             redstone
        //  8 repeater[facing=north,delay=1,locked=false,powered=false]
        //  9 comparator[facing=north,mode=compare,powered=false]
        // 10 redstone_wire[north=none,east=none,south=none,west=none,power=0]
        r.palette.add(new BlockStateEntry("minecraft:air"));
        r.palette.add(new BlockStateEntry("minecraft:stone"));
        r.palette.add(new BlockStateEntry("minecraft:dirt"));
        r.palette.add(new BlockStateEntry("minecraft:grass_block", linked("snowy", "false")));
        r.palette.add(new BlockStateEntry("minecraft:cobblestone"));
        r.palette.add(new BlockStateEntry("minecraft:chest",
                linked("facing", "north", "type", "single", "waterlogged", "false")));
        r.palette.add(new BlockStateEntry("minecraft:observer",
                linked("facing", "up", "powered", "false")));
        r.palette.add(new BlockStateEntry("minecraft:piston",
                linked("facing", "up", "extended", "false")));
        r.palette.add(new BlockStateEntry("minecraft:repeater",
                linked("delay", "1", "facing", "north", "locked", "false", "powered", "false")));
        r.palette.add(new BlockStateEntry("minecraft:comparator",
                linked("facing", "north", "mode", "compare", "powered", "false")));
        r.palette.add(new BlockStateEntry("minecraft:redstone_wire",
                linked("east", "none", "north", "none", "power", "0", "south", "none", "west", "none")));

        int cells = DX * DY * DZ;
        r.blocks = new int[cells];

        // Deterministic RNG.
        java.util.Random rng = new java.util.Random(0x11717AC711CAL);
        // Terrain (4 codes): 1..4 ; redstone (5 codes): 6..10 ; chest = 5.
        int[] terrain = {1, 2, 3, 4};
        int[] redstone = {6, 7, 8, 9, 10};

        // Build the block grid + tile-entity list together so chest positions
        // and TE NBT stay coherent.
        LitematicNbt.NbtList tes = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());

        for (int y = 0; y < DY; y++) {
            for (int z = 0; z < DZ; z++) {
                for (int x = 0; x < DX; x++) {
                    int idx = r.indexOf(x, y, z);
                    int roll = rng.nextInt(100);
                    if (roll < 80) {
                        r.blocks[idx] = terrain[rng.nextInt(4)];
                    } else if (roll < 95) {
                        // chest
                        r.blocks[idx] = 5;
                        tes.values().add(chestTileEntity(x, y, z));
                    } else {
                        r.blocks[idx] = redstone[rng.nextInt(5)];
                    }
                }
            }
        }

        r.tileEntities      = tes;
        r.entities          = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingBlockTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
        r.pendingFluidTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());

        s.regions.put(r.name, r);
        s.metadata.totalBlocks = r.countNonAir();
        return s;
    }

    // ------------------------------------------------------- Disk-write helper

    /** Writes both canonical fixtures to {@code dir}. */
    public static void writeAll(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve("stone-cube-4.litematic"),
                LitematicWriter.writeToBytes(stoneCube4()));
        Files.write(dir.resolve("mixed-room-8.litematic"),
                LitematicWriter.writeToBytes(mixedRoom8()));
    }

    /** Writes the large stress fixture to {@code dir}. Heavy — call only from stress tests. */
    public static void writeLarge(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve("large-256.litematic"),
                LitematicWriter.writeToBytes(largeFixture256()));
    }

    /** Returns the gzipped-NBT bytes for the given schematic. */
    public static byte[] bytes(LitematicSchematic s) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LitematicWriter.write(baos, s);
        return baos.toByteArray();
    }

    // ------------------------------------------------------- misc

    private static Map<String, String> linked(String... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("uneven k/v list");
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
