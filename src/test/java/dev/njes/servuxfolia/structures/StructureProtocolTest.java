package dev.njes.servuxfolia.structures;

import dev.njes.servuxfolia.protocol.PacketSplitter;
import dev.njes.servuxfolia.protocol.ProtocolCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureProtocolTest {
    @Test
    void metadataMatchesMiniHudStructuresV2() {
        byte[] packet = StructureProtocol.metadata("test", 600);

        try (ProtocolCodec.Decoded decoded = ProtocolCodec.decode(packet, 8_192)) {
            assertEquals(StructureProtocol.S2C_METADATA, decoded.packetType());
            CompoundTag nbt = decoded.body().readNbt();
            assertNotNull(nbt);
            assertEquals("structure_bounding_boxes", nbt.getStringOr("name", ""));
            assertEquals(StructureProtocol.CHANNEL, nbt.getStringOr("id", ""));
            assertEquals(2, nbt.getIntOr("version", -1));
            assertEquals(600, nbt.getIntOr("timeout", -1));
        }
    }

    @Test
    void structureNbtRoundTripsThroughTypeTwoSplitterSlices() {
        CompoundTag child = new CompoundTag();
        child.putIntArray("BB", new int[]{1, 2, 3, 4, 5, 6});
        ListTag children = new ListTag();
        children.add(child);
        CompoundTag start = new CompoundTag();
        start.putString("id", "minecraft:fortress");
        start.put("Children", children);
        start.putBoolean("ExpandBox", false);
        ListTag structures = new ListTag();
        structures.add(start);
        CompoundTag response = new CompoundTag();
        response.put("Structures", structures);

        List<byte[]> packets = StructureProtocol.structureData(response, 24, 8_192);
        assertTrue(packets.size() > 1);

        PacketSplitter receiver = new PacketSplitter(8_192);
        UUID session = UUID.randomUUID();
        byte[] complete = null;
        for (byte[] packet : packets) {
            try (ProtocolCodec.Decoded decoded = ProtocolCodec.decode(packet, 8_192)) {
                assertEquals(StructureProtocol.S2C_STRUCTURE_DATA, decoded.packetType());
                byte[] slice = new byte[decoded.body().readableBytes()];
                decoded.body().readBytes(slice);
                complete = receiver.receive(session, slice);
            }
        }

        assertNotNull(complete);
        try (ProtocolCodec.Decoded nbtPayload = ProtocolCodec.decode(
                prependZeroPacketType(complete), 8_192)) {
            CompoundTag decoded = nbtPayload.body().readNbt();
            assertNotNull(decoded);
            ListTag decodedStructures = decoded.getListOrEmpty("Structures");
            assertEquals(1, decodedStructures.size());
            assertEquals("minecraft:fortress",
                    decodedStructures.getCompoundOrEmpty(0).getStringOr("id", ""));
        }
    }

    @Test
    void rejectsEncodedStructureResponsesAboveTheConfiguredLimit() {
        CompoundTag response = new CompoundTag();
        response.putString("padding", "x".repeat(256));

        assertThrows(IllegalArgumentException.class,
                () -> StructureProtocol.structureData(response, 64, 32));
    }

    private static byte[] prependZeroPacketType(byte[] payload) {
        byte[] result = new byte[payload.length + 1];
        System.arraycopy(payload, 0, result, 1, payload.length);
        return result;
    }
}
