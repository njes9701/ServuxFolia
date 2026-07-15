package dev.njes.servuxfolia.entitydata;

import dev.njes.servuxfolia.protocol.ProtocolCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EntityDataProtocolTest {
    @Test
    void metadataMatchesMiniHudEntityDataV1() {
        byte[] packet = EntityDataProtocol.metadata("test");

        try (ProtocolCodec.Decoded decoded = ProtocolCodec.decode(packet, 8_192)) {
            assertEquals(EntityDataProtocol.S2C_METADATA, decoded.packetType());
            CompoundTag nbt = decoded.body().readNbt();
            assertNotNull(nbt);
            assertEquals("entity_data", nbt.getStringOr("name", ""));
            assertEquals(EntityDataProtocol.CHANNEL, nbt.getStringOr("id", ""));
            assertEquals(1, nbt.getIntOr("version", -1));
        }
    }

    @Test
    void beehiveBlockEntityResponseContainsPositionAndOccupants() {
        BlockPos pos = new BlockPos(12, 70, -8);
        CompoundTag bee = new CompoundTag();
        bee.putInt("TicksInHive", 40);
        ListTag bees = new ListTag();
        bees.add(bee);
        CompoundTag beehive = new CompoundTag();
        beehive.putString("id", "minecraft:beehive");
        beehive.put("bees", bees);

        byte[] packet = EntityDataProtocol.blockEntity(pos, beehive);

        try (ProtocolCodec.Decoded decoded = ProtocolCodec.decode(packet, 262_144)) {
            assertEquals(EntityDataProtocol.S2C_BLOCK_ENTITY_RESPONSE, decoded.packetType());
            assertEquals(pos, decoded.body().readBlockPos());
            CompoundTag nbt = decoded.body().readNbt();
            assertNotNull(nbt);
            assertEquals("minecraft:beehive", nbt.getStringOr("id", ""));
            assertEquals(1, nbt.getListOrEmpty("bees").size());
        }
    }

    @Test
    void decodesMiniHudLegacyTransactionBeforeBlockPosition() {
        BlockPos pos = new BlockPos(-1, 64, 3);
        byte[] request = ProtocolCodec.encode(EntityDataProtocol.C2S_BLOCK_ENTITY_REQUEST, buffer -> {
            buffer.writeVarInt(-1);
            buffer.writeBlockPos(pos);
        });

        try (ProtocolCodec.Decoded decoded = EntityDataProtocol.decode(request, 8_192)) {
            assertEquals(EntityDataProtocol.C2S_BLOCK_ENTITY_REQUEST, decoded.packetType());
            assertEquals(-1, decoded.body().readVarInt());
            assertEquals(pos, decoded.body().readBlockPos());
        }
    }
}
