package dev.njes.lep.protocol;

public enum ProtocolVersion {
    V2,
    V3;

    public static ProtocolVersion parse(String value) {
        if (value == null) {
            return V2;
        }

        return switch (value.trim().toLowerCase()) {
            case "v3", "3" -> V3;
            default -> V2;
        };
    }
}
