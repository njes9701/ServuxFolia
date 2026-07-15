package dev.njes.lep.placement;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Decides whether a decoded state needs a write or should keep vanilla placement. */
public final class PlacementMutation {
    private PlacementMutation() {
    }

    public static <T> Result apply(
            T initialState,
            Optional<T> decodedState,
            Predicate<T> writer,
            Supplier<T> authoritativeState
    ) {
        if (decodedState.isEmpty()) {
            return Result.FALLBACK_INVALID_STATE;
        }

        T target = decodedState.get();
        if (Objects.equals(initialState, target)) {
            return Result.APPLIED_UNCHANGED;
        }
        if (writer.test(target) || Objects.equals(authoritativeState.get(), target)) {
            return Result.APPLIED_CHANGED;
        }
        return Result.FALLBACK_WRITE_FAILED;
    }

    public enum Result {
        APPLIED_UNCHANGED(true),
        APPLIED_CHANGED(true),
        FALLBACK_INVALID_STATE(false),
        FALLBACK_WRITE_FAILED(false);

        private final boolean protocolApplied;

        Result(boolean protocolApplied) {
            this.protocolApplied = protocolApplied;
        }

        public boolean protocolApplied() {
            return protocolApplied;
        }
    }
}
