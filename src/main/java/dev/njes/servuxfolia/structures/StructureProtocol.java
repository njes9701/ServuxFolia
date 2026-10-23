package dev.njes.servuxfolia.structures;

import dev.njes.servuxfolia.protocol.PacketSplitter;
import dev.njes.servuxfolia.protocol.ProtocolCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Wire codec for MiniHUD's public {@code servux:structures} protocol v3. */
public final class StructureProtocol {
    public static final String CHANNEL = "servux:structures";
    public static final String MINIHUD_26_3_METADATA_PREFIX = "servux-fabric-26.3";
    // MiniHUD validates this against ServuxStructuresPacket.PROTOCOL_VERSION.
    // Protocol 3 is required by current MiniHUD releases.
    public static final int VERSION = 3;

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
        // MiniHUD 0.41.2 checks this exact Minecraft-versioned prefix before
        // accepting a structures provider, even when it is backed by Folia.
        metadata.putString("servux", MINIHUD_26_3_METADATA_PREFIX + "-ServuxFolia-" + pluginVersion);
        metadata.putInt("timeout", timeoutTicks);
        return ProtocolCodec.encode(S2C_METADATA, buffer -> buffer.writeNbt(metadata));
    }

    static CompoundTag structurePayload(List<CompoundTag> starts) {
        List<CompoundTag> ordered = new ArrayList<>(starts);
        ordered.sort(Comparator.comparing(tag -> tag.getStringOr("id", "")));

        ListTag structures = new ListTag();
        ordered.forEach(structures::add);
        CompoundTag payload = new CompoundTag();
        payload.put("Structures", structures);
        return payload;
    }

    public static List<byte[]> structureData(CompoundTag payload, int sliceBytes, int maxBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writeMiniHudData(buffer, payload);
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

    /**
     * MiniHUD's type-2 payload is not a vanilla FriendlyByteBuf NBT value.
     * It is a big-endian length followed by a gzip-compressed named NBT root,
     * as produced by MaLiLib's DataByteBufUtils.
     */
    private static void writeMiniHudData(FriendlyByteBuf buffer, CompoundTag payload) {
        try {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(compressed))) {
                NbtIo.write(payload, output);
            }
            byte[] bytes = compressed.toByteArray();
            buffer.writeInt(bytes.length);
            buffer.writeBytes(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode MiniHUD structure data", exception);
        }
    }
}
