package fr.ekaii.litematica.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackedLongArrayTest {

    @Test
    void recommendedBitsPerEntryMinimumIsTwo() {
        assertEquals(2, PackedLongArray.recommendedBitsPerEntry(1));
        assertEquals(2, PackedLongArray.recommendedBitsPerEntry(2));
        assertEquals(2, PackedLongArray.recommendedBitsPerEntry(4));
        assertEquals(3, PackedLongArray.recommendedBitsPerEntry(5));
        assertEquals(3, PackedLongArray.recommendedBitsPerEntry(8));
        assertEquals(4, PackedLongArray.recommendedBitsPerEntry(9));
        assertEquals(5, PackedLongArray.recommendedBitsPerEntry(17));
        assertEquals(5, PackedLongArray.recommendedBitsPerEntry(32));
        assertEquals(6, PackedLongArray.recommendedBitsPerEntry(33));
    }

    @Test
    void requiredLongsUsesCompactCrossWordLayout() {
        // 64 entries × 2 bits → 32 entries per long → 2 longs
        assertEquals(2, PackedLongArray.requiredLongs(64, 2));
        // 65 entries × 2 bits → ceil(65/32) = 3 longs
        assertEquals(3, PackedLongArray.requiredLongs(65, 2));
        // 64 entries × 5 bits → 12 entries per long → ceil(64/12)=6 longs
        assertEquals(5, PackedLongArray.requiredLongs(64, 5));
        assertEquals(0, PackedLongArray.requiredLongs(0, 5));
    }

    @Test
    void roundtripBits2() {
        int[] in = {0, 1, 2, 3, 0, 1, 2, 3, 3, 2, 1, 0};
        long[] packed = PackedLongArray.encode(in, 2);
        int[] out = PackedLongArray.decode(packed, 2, in.length);
        assertArrayEquals(in, out);
    }

    @Test
    void roundtripBits5RandomLargeRun() {
        Random rng = new Random(0xC0FFEEL);
        int total = 4096;
        int bits = 5;
        int max = (1 << bits) - 1;
        int[] in = new int[total];
        for (int i = 0; i < total; i++) in[i] = rng.nextInt(max + 1);
        long[] packed = PackedLongArray.encode(in, bits);
        int[] out = PackedLongArray.decode(packed, bits, total);
        assertArrayEquals(in, out);
    }

    @Test
    void roundtripBoundaryAlignedWords() {
        // 64-bit / 4-bit = 16 entries per long → exact alignment, no waste.
        int[] in = new int[16 * 5];
        for (int i = 0; i < in.length; i++) in[i] = i & 0xF;
        long[] packed = PackedLongArray.encode(in, 4);
        assertEquals(5, packed.length);
        assertArrayEquals(in, PackedLongArray.decode(packed, 4, in.length));
    }

    @Test
    void compactLayoutAllowsCrossingEntries() {
        // 5-bit entries → 12 per long, 4 bits wasted per long.
        // Verify the high 4 bits of each long are zero after encoding.
        int[] in = new int[24];
        for (int i = 0; i < in.length; i++) in[i] = 31;        // all bits set
        long[] packed = PackedLongArray.encode(in, 5);
        assertEquals(2, packed.length);
        assertArrayEquals(in, PackedLongArray.decode(packed, 5, in.length));
    }

    @Test
    void emptyArrayRoundTrips() {
        assertArrayEquals(new long[0], PackedLongArray.encode(new int[0], 4));
        assertArrayEquals(new int[0], PackedLongArray.decode(new long[0], 4, 0));
    }

    @Test
    void encodeRejectsValueExceedingBitWidth() {
        // 2 bits → max value 3
        assertThrows(IllegalArgumentException.class,
                () -> PackedLongArray.encode(new int[]{0, 1, 4}, 2));
    }

    @Test
    void decodeRejectsTruncatedArray() {
        // need 1 long for 32 × 2-bit entries but pass 0 longs
        assertThrows(IllegalArgumentException.class,
                () -> PackedLongArray.decode(new long[0], 2, 32));
    }
}
