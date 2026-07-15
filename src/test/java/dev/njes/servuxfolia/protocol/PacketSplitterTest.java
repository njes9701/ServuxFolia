package dev.njes.servuxfolia.protocol;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketSplitterTest {
    @Test
    void roundTripsAcrossManySlices() {
        byte[] input = new byte[250_000];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (i * 31);
        }
        PacketSplitter receiver = new PacketSplitter(300_000);
        UUID player = UUID.randomUUID();
        byte[] result = null;
        for (byte[] slice : PacketSplitter.split(input, 30_000)) {
            result = receiver.receive(player, slice);
        }
        assertArrayEquals(input, result);
    }

    @Test
    void incompleteStreamReturnsNull() {
        PacketSplitter receiver = new PacketSplitter(100);
        assertNull(receiver.receive(UUID.randomUUID(), new byte[]{10, 1, 2}));
    }

    @Test
    void rejectsDeclaredPayloadAboveLimit() {
        PacketSplitter receiver = new PacketSplitter(16);
        assertThrows(IllegalArgumentException.class,
                () -> receiver.receive(UUID.randomUUID(), new byte[]{17}));
    }

    @Test
    void boundsConcurrentInboundSessions() {
        PacketSplitter receiver = new PacketSplitter(100, 1);
        receiver.receive(UUID.randomUUID(), new byte[]{10, 1});

        assertThrows(IllegalStateException.class,
                () -> receiver.receive(UUID.randomUUID(), new byte[]{10, 1}));
    }
}
