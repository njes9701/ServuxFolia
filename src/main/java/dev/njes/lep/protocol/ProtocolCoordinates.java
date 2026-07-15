package dev.njes.lep.protocol;

import java.util.Optional;

/** Pure coordinate codec shared by the Netty adapter and unit tests. */
public final class ProtocolCoordinates {
    private ProtocolCoordinates() {
    }

    public static Optional<DecodedCoordinates> decode(double absoluteHitX, int clickedBlockX, int maxPayload) {
        double relativeX = absoluteHitX - clickedBlockX;

        if (!Double.isFinite(relativeX) || relativeX < 2.0D) {
            return Optional.empty();
        }

        int integralPart = (int) Math.floor(relativeX);
        int encodedPayload = integralPart - 2;

        // Both upstream formats reserve bit zero. A hit exactly on the EAST
        // face has relX == 1.0 and therefore sets that unused bit after the
        // client adds the protocol payload. Upstream decoders ignore it; clear
        // it here instead of rejecting otherwise valid EAST-face placements.
        if (encodedPayload < 0) {
            return Optional.empty();
        }

        int payload = encodedPayload & ~1;
        if (payload > maxPayload) {
            return Optional.empty();
        }

        double fraction = relativeX - integralPart;
        return Optional.of(new DecodedCoordinates(payload, clickedBlockX + fraction));
    }

    /**
     * Returns the center of one face of the clicked block. Easy Place encodes
     * state in an intentionally out-of-range hit vector, but the vanilla
     * server rejects the packet before item use when any axis is too far from
     * the block center. Servux bypasses that check with a mixin; a plugin must
     * instead forward a valid replacement hit vector after saving the payload.
     */
    public static double safeFaceCoordinate(int blockCoordinate, int directionStep) {
        if (directionStep < -1 || directionStep > 1) {
            throw new IllegalArgumentException("directionStep must be -1, 0, or 1");
        }
        return blockCoordinate + 0.5D + directionStep * 0.5D;
    }

    public record DecodedCoordinates(int payload, double normalizedAbsoluteX) {
    }
}
