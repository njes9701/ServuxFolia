package dev.njes.servuxfolia.protocol;

/** Public Servux/Litematica wire identifiers used for interoperability. */
public final class ProtocolConstants {
    public static final String LITEMATICS_CHANNEL = "servux:litematics";
    public static final int LITEMATICS_PROTOCOL_VERSION = 1;

    public static final int S2C_METADATA = 1;
    public static final int C2S_METADATA_REQUEST = 2;
    public static final int C2S_BLOCK_ENTITY_REQUEST = 3;
    public static final int C2S_ENTITY_REQUEST = 4;
    public static final int S2C_BLOCK_NBT = 5;
    public static final int S2C_ENTITY_NBT = 6;
    public static final int C2S_BULK_NBT_REQUEST = 7;
    public static final int S2C_NBT_RESPONSE_START = 10;
    public static final int S2C_NBT_RESPONSE_DATA = 11;
    public static final int C2S_NBT_RESPONSE_START = 12;
    public static final int C2S_NBT_RESPONSE_DATA = 13;

    private ProtocolConstants() {
    }
}
