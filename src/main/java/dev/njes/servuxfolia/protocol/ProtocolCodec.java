package dev.njes.servuxfolia.protocol;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Consumer;

public final class ProtocolCodec {
    private ProtocolCodec() {
    }

    public static Decoded decode(byte[] payload, int maxBytes) {
        if (payload.length == 0 || payload.length > maxBytes) {
            throw new IllegalArgumentException("invalid payload size: " + payload.length);
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        return new Decoded(buffer.readVarInt(), buffer);
    }

    public static byte[] encode(int packetType, Consumer<FriendlyByteBuf> body) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(packetType);
            body.accept(buffer);
            byte[] result = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), result);
            return result;
        } finally {
            buffer.release();
        }
    }

    public static byte[] metadata(String version) {
        CompoundTag metadata = new CompoundTag();
        metadata.putString("name", "litematic_data");
        metadata.putString("id", ProtocolConstants.LITEMATICS_CHANNEL);
        metadata.putInt("version", ProtocolConstants.LITEMATICS_PROTOCOL_VERSION);
        metadata.putString("servux", "ServuxFolia " + version);
        return encode(ProtocolConstants.S2C_METADATA, buffer -> buffer.writeNbt(metadata));
    }

    public static byte[] blockEntity(BlockPos pos, CompoundTag nbt) {
        return encode(ProtocolConstants.S2C_BLOCK_NBT, buffer -> {
            buffer.writeBlockPos(pos);
            buffer.writeNbt(nbt == null ? new CompoundTag() : nbt);
        });
    }

    public static byte[] entity(int entityId, CompoundTag nbt) {
        return encode(ProtocolConstants.S2C_ENTITY_NBT, buffer -> {
            buffer.writeVarInt(entityId);
            buffer.writeNbt(nbt == null ? new CompoundTag() : nbt);
        });
    }

    /** Application payload carried inside Servux type-11 splitter slices. */
    public static byte[] splitNbtPayload(int transactionId, CompoundTag nbt) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(transactionId);
            buffer.writeNbt(nbt);
            byte[] result = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), result);
            return result;
        } finally {
            buffer.release();
        }
    }

    public static byte[] splitterSlice(int packetType, byte[] slice) {
        return encode(packetType, buffer -> buffer.writeBytes(slice));
    }

    public record Decoded(int packetType, FriendlyByteBuf body) implements AutoCloseable {
        @Override
        public void close() {
            body.release();
        }
    }
}
