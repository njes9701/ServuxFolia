package dev.njes.lep.protocol;

public record PendingPlacement(
        int clickedX,
        int clickedY,
        int clickedZ,
        int faceStepX,
        int faceStepY,
        int faceStepZ,
        int hand,
        int payload,
        ProtocolVersion version,
        long createdAtNanos
) {
    public boolean matches(int hand, Position first, Position second) {
        return this.hand == hand && (matches(first) || matches(second));
    }

    private boolean matches(Position position) {
        return position != null
                && ((clickedX == position.x()
                && clickedY == position.y()
                && clickedZ == position.z())
                || (clickedX + faceStepX == position.x()
                && clickedY + faceStepY == position.y()
                && clickedZ + faceStepZ == position.z()));
    }

    public record Position(int x, int y, int z) {
    }
}
