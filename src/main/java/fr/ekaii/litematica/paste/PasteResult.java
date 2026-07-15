package fr.ekaii.litematica.paste;

import java.util.List;

/**
 * Outcome of a {@link PasteOperation} execution.
 *
 * @param blocksPlaced        Number of non-air blocks actually written.
 * @param tileEntitiesPlaced  Number of block-entities successfully
 *                            loaded.
 * @param entitiesSpawned     Number of entities actually spawned.
 * @param durationMs          End-to-end duration in milliseconds (from
 *                            the {@link PasteOperation#execute()} call
 *                            to all per-chunk futures completing).
 * @param errors              Human-readable list of non-fatal errors
 *                            encountered (chunks where placement was
 *                            skipped, NBT load failures, etc.).
 */
public record PasteResult(
        long blocksPlaced,
        long tileEntitiesPlaced,
        long entitiesSpawned,
        long durationMs,
        List<String> errors
) {
    public PasteResult {
        if (errors == null) errors = List.of();
    }
}
