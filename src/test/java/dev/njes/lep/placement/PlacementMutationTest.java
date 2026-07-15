package dev.njes.lep.placement;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlacementMutationTest {
    @Test
    void identicalDecodedStateDoesNotCallWriterAndStillSucceeds() {
        AtomicBoolean wrote = new AtomicBoolean();
        PlacementMutation.Result result = PlacementMutation.apply(
                "north", Optional.of("north"), target -> {
                    wrote.set(true);
                    return false;
                }, () -> "north");

        assertEquals(PlacementMutation.Result.APPLIED_UNCHANGED, result);
        assertFalse(wrote.get());
    }

    @Test
    void invalidDecodedStateFallsBackWithoutDeletingVanillaPlacement() {
        PlacementMutation.Result result = PlacementMutation.apply(
                "vanilla", Optional.empty(), ignored -> false, () -> "vanilla");
        assertEquals(PlacementMutation.Result.FALLBACK_INVALID_STATE, result);
    }

    @Test
    void falseWriterResultIsAcceptedWhenWorldAlreadyHasTargetState() {
        PlacementMutation.Result result = PlacementMutation.apply(
                "north", Optional.of("south"), ignored -> false, () -> "south");
        assertEquals(PlacementMutation.Result.APPLIED_CHANGED, result);
    }

    @Test
    void failedWriteFallsBackToOriginalPlacement() {
        PlacementMutation.Result result = PlacementMutation.apply(
                "north", Optional.of("south"), ignored -> false, () -> "north");
        assertEquals(PlacementMutation.Result.FALLBACK_WRITE_FAILED, result);
    }
}
