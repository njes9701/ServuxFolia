package fr.ekaii.litematica.core;

/**
 * Compact (cross-word) packed-long-array codec used by the {@code .litematic}
 * format. Each entry occupies {@code bitsPerEntry} bits laid out
 * sequentially across the long array; an entry MAY span the boundary
 * between two adjacent longs.
 *
 * <p>This differs from the post-1.16 Minecraft chunk-section palette
 * format, which is word-aligned (one entry never crosses a long
 * boundary). Litematica's {@code BlockStates} long array follows the old
 * compact layout regardless of MC version — see
 * {@code LitematicaBlockStateContainerBase} in upstream.
 *
 * <pre>
 *   bitsPerEntry = max(ceil(log2(paletteSize)), 2)
 *   longCount    = ceil(totalEntries * bitsPerEntry / 64)
 *
 *   bitPos   = i * bitsPerEntry
 *   longIdx  = bitPos / 64
 *   bitInLong= bitPos % 64
 *   if bitInLong + bitsPerEntry <= 64:
 *       value = (data[longIdx] >>> bitInLong) & mask
 *   else:
 *       low   = data[longIdx]   >>> bitInLong
 *       high  = data[longIdx+1] &lt;&lt; (64 - bitInLong)
 *       value = (low | high) & mask
 * </pre>
 */
public final class PackedLongArray {

    private PackedLongArray() {
    }

    /**
     * Recommended bit width for a palette of the given size, clamped to a
     * minimum of 2 bits (Litematica's invariant for non-trivial palettes).
     */
    public static int recommendedBitsPerEntry(int paletteSize) {
        if (paletteSize <= 0) return 2;
        int bits = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        return Math.max(bits, 2);
    }

    /**
     * Number of longs required to pack {@code totalEntries} entries at
     * {@code bitsPerEntry} bits each, using the compact cross-word layout.
     */
    public static int requiredLongs(int totalEntries, int bitsPerEntry) {
        if (totalEntries == 0) return 0;
        long totalBits = (long) totalEntries * (long) bitsPerEntry;
        return (int) ((totalBits + 63L) / 64L);
    }

    /** Decodes a compact packed long array back to per-cell palette indices. */
    public static int[] decode(long[] data, int bitsPerEntry, int totalEntries) {
        if (bitsPerEntry < 1 || bitsPerEntry > 32) {
            throw new IllegalArgumentException("bitsPerEntry out of range: " + bitsPerEntry);
        }
        if (totalEntries < 0) {
            throw new IllegalArgumentException("totalEntries < 0: " + totalEntries);
        }
        int[] out = new int[totalEntries];
        if (totalEntries == 0) return out;

        long mask = (1L << bitsPerEntry) - 1L;
        int expectedLongs = requiredLongs(totalEntries, bitsPerEntry);
        if (data.length < expectedLongs) {
            throw new IllegalArgumentException(
                    "Packed array too short: have " + data.length + " longs, need " + expectedLongs);
        }
        for (int i = 0; i < totalEntries; i++) {
            long bitPos    = (long) i * (long) bitsPerEntry;
            int  longIdx   = (int) (bitPos >>> 6);            // /64
            int  bitInLong = (int) (bitPos & 63L);            // %64
            long lo = data[longIdx] >>> bitInLong;
            long value;
            int endBit = bitInLong + bitsPerEntry;
            if (endBit <= 64) {
                value = lo & mask;
            } else {
                long hi = data[longIdx + 1] << (64 - bitInLong);
                value = (lo | hi) & mask;
            }
            out[i] = (int) value;
        }
        return out;
    }

    /** Encodes per-cell palette indices into a compact packed long array. */
    public static long[] encode(int[] entries, int bitsPerEntry) {
        if (bitsPerEntry < 1 || bitsPerEntry > 32) {
            throw new IllegalArgumentException("bitsPerEntry out of range: " + bitsPerEntry);
        }
        int total = entries.length;
        if (total == 0) return new long[0];

        long mask = (1L << bitsPerEntry) - 1L;
        long[] out = new long[requiredLongs(total, bitsPerEntry)];
        for (int i = 0; i < total; i++) {
            int v = entries[i];
            if (v < 0 || (long) v > mask) {
                throw new IllegalArgumentException(
                        "entry " + v + " at index " + i + " exceeds " + bitsPerEntry + " bits");
            }
            long val       = (long) v & mask;
            long bitPos    = (long) i * (long) bitsPerEntry;
            int  longIdx   = (int) (bitPos >>> 6);
            int  bitInLong = (int) (bitPos & 63L);
            out[longIdx] |= val << bitInLong;
            int endBit = bitInLong + bitsPerEntry;
            if (endBit > 64) {
                out[longIdx + 1] |= val >>> (64 - bitInLong);
            }
        }
        return out;
    }
}
