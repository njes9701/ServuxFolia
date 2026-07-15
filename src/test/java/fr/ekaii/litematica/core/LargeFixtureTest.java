package fr.ekaii.litematica.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress test: generates the 256×64×256 large fixture (≈4.2 M cells),
 * writes it to {@code schematics-fixtures/large-256.litematic}, then reads
 * it back and verifies palette + block-count integrity.
 *
 * <p>Skipped by default (heavy: ~16 MB int array + gzip). Enable with
 * {@code -Dlitematica.stress=true} on the {@code test} task. The
 * stress-smoke shell harness in {@code test-harness/} flips the flag so
 * the fixture is regenerated before stress-paste runs.
 */
@EnabledIfSystemProperty(named = "litematica.stress", matches = "true")
class LargeFixtureTest {

    private static final Path TARGET_DIR = Paths.get(System.getProperty("user.dir"), "schematics-fixtures");

    @Test
    void generateAndRoundtripLargeFixture() throws Exception {
        Fixtures.writeLarge(TARGET_DIR);

        Path large = TARGET_DIR.resolve("large-256.litematic");
        assertTrue(Files.exists(large), "large fixture written");

        long sizeBytes = Files.size(large);
        // Sanity: gzipped size must be in a reasonable band. With ~80% terrain
        // (4-entry palette) + 15% chests + 5% redstone, packed-long bitsPerEntry
        // = 4 → raw blocks ≈ 4.2M*4/8 = 2.1 MB → gzipped well under 16 MB.
        assertTrue(sizeBytes > 64 * 1024, "fixture > 64 KB (got " + sizeBytes + ")");
        assertTrue(sizeBytes < 16L * 1024 * 1024, "fixture < 16 MB (got " + sizeBytes + ")");

        // Read back & verify palette + block grid sanity.
        byte[] bytes = Files.readAllBytes(large);
        LitematicSchematic read = LitematicReader.read(new ByteArrayInputStream(bytes));

        assertEquals(1, read.regions.size(), "single region");
        LitematicRegion r = read.regions.get("main");
        assertEquals(256, r.sizeX);
        assertEquals(64,  r.sizeY);
        assertEquals(256, r.sizeZ);
        assertEquals(11, r.palette.size(), "palette = 11 entries (air + 4 terrain + chest + 5 redstone)");

        // Palette content spot-check.
        assertEquals("minecraft:air",      r.palette.get(0).name());
        assertEquals("minecraft:stone",    r.palette.get(1).name());
        assertEquals("minecraft:chest",    r.palette.get(5).name());
        assertEquals("minecraft:observer", r.palette.get(6).name());

        // Block grid full-length check.
        assertEquals(256L * 64 * 256, r.blocks.length, "blocks array sized to volume");
        assertEquals(256L * 64 * 256, r.volume(),      "volume matches");

        // Round-trip equality on the in-memory POJO.
        LitematicSchematic regenerated = Fixtures.largeFixture256();
        assertEquals(regenerated, read, "round-trip equality");

        // Bookkeeping: total non-air blocks should be 100% of cells (no air in mix).
        assertEquals(256L * 64 * 256, read.metadata.totalBlocks, "no air cells in deterministic fill");
    }
}
