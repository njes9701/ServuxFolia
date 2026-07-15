package fr.ekaii.litematica.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockStateEntryTest {

    @Test
    void plainBlockHasNamespaceDefaulted() {
        assertEquals("minecraft:stone",
                new BlockStateEntry("stone").toMinecraftString());
    }

    @Test
    void namespacedBlockIsPreserved() {
        assertEquals("ekaii:custom_block",
                new BlockStateEntry("ekaii:custom_block").toMinecraftString());
    }

    @Test
    void propertiesAreAlphabetisedRegardlessOfInputOrder() {
        // input order: facing, waterlogged, type — alphabetical: facing, type, waterlogged
        Map<String, String> props = new LinkedHashMap<>();
        props.put("facing", "north");
        props.put("waterlogged", "false");
        props.put("type", "single");
        BlockStateEntry e = new BlockStateEntry("minecraft:chest", props);
        assertEquals("minecraft:chest[facing=north,type=single,waterlogged=false]",
                e.toMinecraftString());
    }

    @Test
    void noPropertiesYieldsBareName() {
        Map<String, String> empty = new LinkedHashMap<>();
        assertEquals("minecraft:stone",
                new BlockStateEntry("minecraft:stone", empty).toMinecraftString());
    }

    @Test
    void singlePropertyFormat() {
        assertEquals("minecraft:oak_log[axis=y]",
                new BlockStateEntry("oak_log", Map.of("axis", "y")).toMinecraftString());
    }

    @Test
    void equalityAndHashCodeRespectProperties() {
        BlockStateEntry a = new BlockStateEntry("stone", Map.of("p", "v"));
        BlockStateEntry b = new BlockStateEntry("stone", Map.of("p", "v"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
