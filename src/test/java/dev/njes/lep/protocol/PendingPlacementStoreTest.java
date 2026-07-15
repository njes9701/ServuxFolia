package dev.njes.lep.protocol;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingPlacementStoreTest {
    private static final PendingPlacement.Position CLICKED = new PendingPlacement.Position(1, 2, 3);
    private static final PendingPlacement.Position PLACED = new PendingPlacement.Position(1, 3, 3);

    @Test
    void consumesTheMatchingHandAndPositionWithoutOverwritingQueuedPackets() {
        UUID player = UUID.randomUUID();
        PendingPlacementStore store = new PendingPlacementStore(Duration.ofSeconds(1), 8);
        store.enqueue(player, placement(0, 2, 100));
        store.enqueue(player, placement(1, 4, 110));

        PendingPlacement second = store.consume(player, 1, CLICKED, PLACED, 120).orElseThrow();
        PendingPlacement first = store.consume(player, 0, CLICKED, PLACED, 120).orElseThrow();

        assertEquals(4, second.payload());
        assertEquals(2, first.payload());
        assertEquals(0, store.pendingCount());
    }

    @Test
    void expiresOldPacketsAndBoundsEachPlayersQueue() {
        UUID player = UUID.randomUUID();
        PendingPlacementStore store = new PendingPlacementStore(Duration.ofNanos(20), 2);
        store.enqueue(player, placement(0, 2, 100));
        store.enqueue(player, placement(0, 4, 101));
        store.enqueue(player, placement(0, 6, 102));

        assertEquals(2, store.pendingCount());
        assertTrue(store.consume(player, 0, CLICKED, PLACED, 200).isEmpty());
        assertEquals(0, store.pendingCount());
    }

    @Test
    void findsTheRelativePlacementPositionWithoutConsumingThePacket() {
        UUID player = UUID.randomUUID();
        PendingPlacementStore store = new PendingPlacementStore(Duration.ofSeconds(1), 8);
        store.enqueue(player, placement(0, 2, 100));

        PendingPlacement.Position eastOfClicked = new PendingPlacement.Position(2, 2, 3);
        assertTrue(store.find(player, 0, eastOfClicked, 120).isPresent());
        assertEquals(1, store.pendingCount());
    }

    private PendingPlacement placement(int hand, int payload, long timestamp) {
        return new PendingPlacement(1, 2, 3, 1, 0, 0,
                hand, payload, ProtocolVersion.V3, timestamp);
    }
}
