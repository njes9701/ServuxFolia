package dev.njes.servuxfolia.entitydata;

import dev.njes.servuxfolia.protocol.ProtocolCodec;
import dev.njes.servuxfolia.structures.StructureProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/** MiniHUD {@code servux:entity_data} protocol v2 codec. */
public final class EntityDataProtocol {
    public static final String CHANNEL = "servux:entity_data";
    public static final int VERSION = 2;

    public static final int S2C_METADATA = 1;
    public static final int C2S_METADATA_REQUEST = 2;
    public static final int C2S_BLOCK_ENTITY_REQUEST = 3;
    public static final int C2S_ENTITY_REQUEST = 4;
    public static final int S2C_BLOCK_ENTITY_RESPONSE = 5;
    public static final int S2C_ENTITY_RESPONSE = 6;

    private EntityDataProtocol() {
    }

    public static ProtocolCodec.Decoded decode(byte[] payload, int maxBytes) {
        return ProtocolCodec.decode(payload, maxBytes);
    }

    public static byte[] metadata(String pluginVersion) {
        CompoundTag metadata = new CompoundTag();
        metadata.putString("name", "entity_data");
        metadata.putString("id", CHANNEL);
        metadata.putInt("version", VERSION);
        metadata.putString("servux", StructureProtocol.MINIHUD_26_3_METADATA_PREFIX
                + "-ServuxFolia-" + pluginVersion);
        return ProtocolCodec.encode(S2C_METADATA, buffer -> buffer.writeNbt(metadata));
    }

    public static byte[] blockEntity(BlockPos pos, CompoundTag nbt) {
        return ProtocolCodec.encode(S2C_BLOCK_ENTITY_RESPONSE, buffer -> {
            buffer.writeBlockPos(pos);
            writeMiniHudData(buffer, nbt == null ? new CompoundTag() : nbt);
        });
    }

    public static byte[] entity(int entityId, CompoundTag nbt) {
        return ProtocolCodec.encode(S2C_ENTITY_RESPONSE, buffer -> {
            buffer.writeVarInt(entityId);
            writeMiniHudData(buffer, nbt == null ? new CompoundTag() : nbt);
        });
    }

    private static void writeMiniHudData(FriendlyByteBuf buffer, CompoundTag data) {
        try {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(compressed))) {
                NbtIo.write(data, output);
            }
            byte[] bytes = compressed.toByteArray();
            buffer.writeInt(bytes.length);
            buffer.writeBytes(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode MiniHUD entity data", exception);
        }
    }
}
