package fr.ekaii.litematica.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes the canonical fixtures to {@code schematics-fixtures/} at the
 * project root, so they are produced as a side-effect of the standard
 * {@code ./gradlew test} run.
 */
class FixtureGenerationTest {

    private static final Path TARGET_DIR;
    static {
        Path explicit = Paths.get("schematics-fixtures");
        // Tests run with cwd = project root by default — fall back to user.dir.
        if (!Files.exists(explicit.getParent() != null ? explicit.getParent() : Paths.get("."))) {
            TARGET_DIR = Paths.get(System.getProperty("user.dir"), "schematics-fixtures");
        } else {
            TARGET_DIR = explicit.toAbsolutePath();
        }
    }

    @Test
    void writeAndReadBackFixtures() throws Exception {
        Fixtures.writeAll(TARGET_DIR);

        Path stone = TARGET_DIR.resolve("stone-cube-4.litematic");
        Path mixed = TARGET_DIR.resolve("mixed-room-8.litematic");
        assertTrue(Files.exists(stone), "stone fixture written");
        assertTrue(Files.exists(mixed), "mixed fixture written");
        assertTrue(Files.size(stone) > 16, "stone fixture non-trivial");
        assertTrue(Files.size(mixed) > 16, "mixed fixture non-trivial");

        // Cross-check: reading the bytes from disk equals reading from memory.
        byte[] stoneBytes = Files.readAllBytes(stone);
        byte[] mixedBytes = Files.readAllBytes(mixed);

        LitematicSchematic stoneFromDisk = LitematicReader.read(new ByteArrayInputStream(stoneBytes));
        LitematicSchematic mixedFromDisk = LitematicReader.read(new ByteArrayInputStream(mixedBytes));

        assertEquals(Fixtures.stoneCube4(), stoneFromDisk);
        assertEquals(Fixtures.mixedRoom8(), mixedFromDisk);
    }
}
