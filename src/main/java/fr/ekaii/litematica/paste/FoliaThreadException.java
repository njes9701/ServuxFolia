package fr.ekaii.litematica.paste;

/**
 * Helper for detecting "wrong region thread" failures on Folia.
 *
 * <p>Ported verbatim from
 * {@code com.moulberry.axiom.operations.SetBlockBufferOperation#isFoliaThreadException}
 * (file: {@code axiom-folia/work/src/main/java/com/moulberry/axiom/operations/SetBlockBufferOperation.java},
 * lines 437-461). Same recipe is duplicated in
 * {@code RequestChunksOperation.java} of the same project.
 *
 * <p>Folia surfaces "wrong region thread" failures in two flavours:
 * <ul>
 *   <li>A direct {@code WrongThreadException}/{@code TickThread} subclass
 *       when Folia's guard fires on entry to a region-bound API.</li>
 *   <li>A downstream {@code NullPointerException} when Paper code that
 *       captures block-entity changes per tick (via
 *       {@code ServerLevel#getCurrentWorldData()}) runs from a non-owning
 *       region thread. The world-data accessor returns {@code null} → NPE
 *       on {@code capturedTileEntities} / {@code captureTreeGeneration}.
 *       Observed in production after deploying to creaekaii (May 2026).</li>
 * </ul>
 */
public final class FoliaThreadException {

    private FoliaThreadException() {
    }

    public static boolean isFoliaThreadException(Throwable t) {
        if (t == null) return false;
        String name = t.getClass().getName();
        if (name.contains("WrongThreadException") || name.contains("TickThread")) return true;
        if (t instanceof NullPointerException) {
            String msg = t.getMessage();
            if (msg != null && (
                    msg.contains("getCurrentWorldData")
                    || msg.contains("capturedTileEntities")
                    || msg.contains("captureTreeGeneration"))) {
                return true;
            }
        }
        Throwable cause = t.getCause();
        return cause != null && cause != t && isFoliaThreadException(cause);
    }
}
