package fr.ekaii.litematica.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Top-level POJO representation of a parsed {@code .litematic} file.
 *
 * <p>{@link #version} is the Litematica schema version (6 or 7).
 * {@link #subVersion} is present from v7 onward.
 * {@link #minecraftDataVersion} is the standard Mojang data version code.
 *
 * <p>The schematic owns its metadata and an ordered map of named regions
 * (insertion order matches the on-disk {@code Regions} compound for clean
 * round-tripping).
 */
public final class LitematicSchematic {

    public int version = 6;
    public Integer subVersion;          // optional (v7+)
    public int minecraftDataVersion;

    public final LitematicMetadata metadata = new LitematicMetadata();

    /** Region name → region. Order is preserved across read/write. */
    public final Map<String, LitematicRegion> regions = new LinkedHashMap<>();

    public LitematicSchematic() {
    }

    // ----- legacy accessors so call-sites authored against the prior stub
    // interface keep compiling without modification. -----

    public int minecraftDataVersion() {
        return minecraftDataVersion;
    }

    public LitematicMetadata metadata() {
        return metadata;
    }

    public List<LitematicRegion> regionsList() {
        return new ArrayList<>(regions.values());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LitematicSchematic s)) return false;
        return version == s.version
                && minecraftDataVersion == s.minecraftDataVersion
                && Objects.equals(subVersion, s.subVersion)
                && metadata.equals(s.metadata)
                && regions.equals(s.regions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, subVersion, minecraftDataVersion, metadata, regions);
    }
}
