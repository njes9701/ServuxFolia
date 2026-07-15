package dev.njes.servuxfolia.structures;

import dev.njes.servuxfolia.protocol.PacketSplitter;
import dev.njes.servuxfolia.protocol.ProtocolCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/** Wire codec for MiniHUD's public {@code servux:structures} protocol v2. */
public final class StructureProtocol {
    public static final String CHANNEL = "servux:structures";
    public static final int VERSION = 2;

    public static final int S2C_METADATA = 1;
    public static final int S2C_STRUCTURE_DATA = 2;
    public static final int C2S_REGISTER = 3;
    public static final int C2S_UNREGISTER = 4;

    private StructureProtocol() {
    }

    public static ProtocolCodec.Decoded decode(byte[] payload, int maxBytes) {
        return ProtocolCodec.decode(payload, maxBytes);
    }

    public static byte[] metadata(String pluginVersion, int timeoutTicks) {
        CompoundTag metadata = new CompoundTag();
        metadata.putString("name", "structure_bounding_boxes");
        metadata.putString("id", CHANNEL);
        metadata.putInt("version", VERSION);
        metadata.putString("servux", "ServuxFolia " + pluginVersion);
        metadata.putInt("timeout", timeoutTicks);
        return ProtocolCodec.encode(S2C_METADATA, buffer -> buffer.writeNbt(metadata));
    }

    public static List<byte[]> structureData(CompoundTag payload, int sliceBytes, int maxBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeNbt(payload);
            int length = buffer.readableBytes();
            if (length > maxBytes) {
                throw new IllegalArgumentException("structure response exceeds limit: " + length);
            }
            byte[] application = new byte[length];
            buffer.getBytes(buffer.readerIndex(), application);
            return PacketSplitter.split(application, sliceBytes).stream()
                    .map(slice -> ProtocolCodec.splitterSlice(S2C_STRUCTURE_DATA, slice))
                    .toList();
        } finally {
            buffer.release();
        }
    }
}
