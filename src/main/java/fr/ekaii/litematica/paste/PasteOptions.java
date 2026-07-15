package fr.ekaii.litematica.paste;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.block.structure.Mirror;

import java.util.function.BiConsumer;

/**
 * Configuration for a single {@link PasteOperation} execution.
 *
 * @param origin             World location where the schematic's
 *                           (0,0,0) corner lands.
 * @param placeEntities      Whether to spawn entities recorded in the
 *                           regions.
 * @param placeTileEntities  Whether to load tile-entity NBT into the
 *                           placed blocks.
 * @param placePendingTicks  Whether to re-schedule pending block & fluid
 *                           ticks.
 * @param deferredPhysics    If {@code true}, blocks are placed with
 *                           physics disabled and a sweep is performed
 *                           after to trigger physics in a controlled
 *                           order.
 * @param observersLast      Two-pass placement — inert blocks first,
 *                           then observers / pistons / comparators /
 *                           repeaters / redstone_wire / sticky_piston.
 *                           Mitigates the Litematica issue #538 class of
 *                           feedback-loop activation. See
 *                           {@code PasteOperation#ACTIVE_BLOCK_NAMES}.
 * @param maxBlocksPerChunkTask Soft cap on blocks per per-chunk
 *                           scheduled task. Currently advisory — the
 *                           operation places all in-chunk blocks in a
 *                           single region task.
 * @param yawRotation        0 / 90 / 180 / 270. Applied around the
 *                           origin.
 * @param progress           Optional progress callback. Invoked from
 *                           arbitrary threads; implementations must be
 *                           thread-safe.
 */
public record PasteOptions(
        Location origin,
        boolean placeEntities,
        boolean placeTileEntities,
        boolean placePendingTicks,
        boolean deferredPhysics,
        boolean observersLast,
        int maxBlocksPerChunkTask,
        int yawRotation,
        Mirror mirror,
        ReplaceMode replaceMode,
        BiConsumer<Player, String> progress
) {
    public enum ReplaceMode {
        NONE,
        ALL,
        WITH_NON_AIR
    }

    public PasteOptions {
        if (origin == null) throw new IllegalArgumentException("origin is null");
        if (yawRotation != 0 && yawRotation != 90 && yawRotation != 180 && yawRotation != 270) {
            throw new IllegalArgumentException("yawRotation must be 0, 90, 180 or 270, got " + yawRotation);
        }
        if (maxBlocksPerChunkTask <= 0) {
            throw new IllegalArgumentException("maxBlocksPerChunkTask must be > 0");
        }
        if (mirror == null) mirror = Mirror.NONE;
        if (replaceMode == null) replaceMode = ReplaceMode.NONE;
    }

    /** Sensible defaults: everything on, deferred physics, observers-last. */
    public static PasteOptions defaults(Location origin) {
        return new PasteOptions(
                origin,
                /* placeEntities */     true,
                /* placeTileEntities */ true,
                /* placePendingTicks */ true,
                /* deferredPhysics */   true,
                /* observersLast */     true,
                /* maxBlocksPerChunkTask */ 4096,
                /* yawRotation */ 0,
                /* mirror */ Mirror.NONE,
                /* replaceMode */ ReplaceMode.NONE,
                /* progress */    null
        );
    }
}
