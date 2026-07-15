package fr.ekaii.litematica.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A single block state in a Litematica palette: an identifier plus an
 * optional ordered map of blockstate properties.
 *
 * <p>Block names are stored as written (e.g. {@code "minecraft:stone"} or
 * {@code "stone"}); {@link #toMinecraftString()} ensures the namespace
 * defaults to {@code minecraft:} and orders properties alphabetically.
 */
public final class BlockStateEntry {

    private final String name;
    private final Map<String, String> properties;

    public BlockStateEntry(String name, Map<String, String> properties) {
        this.name = Objects.requireNonNull(name, "name");
        // copy + preserve given order (palette property ordering is recorded as-is on disk)
        this.properties = properties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public BlockStateEntry(String name) {
        this(name, Map.of());
    }

    public String name() {
        return name;
    }

    public Map<String, String> properties() {
        return properties;
    }

    /**
     * Canonical Minecraft block state representation:
     * {@code minecraft:<name>[prop1=val1,prop2=val2]} with properties sorted
     * alphabetically and the {@code minecraft:} namespace defaulted.
     */
    public String toMinecraftString() {
        String fullName = name.contains(":") ? name : "minecraft:" + name;
        if (properties.isEmpty()) {
            return fullName;
        }
        TreeMap<String, String> sorted = new TreeMap<>(properties);
        StringBuilder sb = new StringBuilder(fullName).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    @Override
    public String toString() {
        return toMinecraftString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockStateEntry b)) return false;
        return name.equals(b.name) && properties.equals(b.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, properties);
    }
}
