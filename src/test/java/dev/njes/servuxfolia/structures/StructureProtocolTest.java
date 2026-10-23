package dev.njes.servuxfolia.structures;

import dev.njes.servuxfolia.protocol.PacketSplitter;
import dev.njes.servuxfolia.protocol.ProtocolCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureProtocolTest {
    @Test
    void metadataMatchesMiniHudStructuresV3() {
        byte[] packet = StructureProtocol.metadata("test", 600);

        try (ProtocolCodec.Decoded decoded = ProtocolCodec.decode(packet, 8_192)) {
            assertEquals(StructureProtocol.S2C_METADATA, decoded.packetType());
            CompoundTag nbt = decoded.body().readNbt();
            assertNotNull(nbt);
            assertEquals("structure_bounding_boxes", nbt.getStringOr("name", ""));
            assertEquals(StructureProtocol.CHANNEL, nbt.getStringOr("id", ""));
            assertEquals(3, nbt.getIntOr("version", -1));
            assertEquals(600, nbt.getIntOr("timeout", -1));
        }
    }

    @Test
    void structureNbtRoundTripsThroughTypeTwoSplitterSlices() throws java.io.IOException {
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
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(complete))) {
            int length = input.readInt();
            byte[] compressed = input.readNBytes(length);
            CompoundTag decoded;
            try (DataInputStream nbtInput = new DataInputStream(
                    new GZIPInputStream(new ByteArrayInputStream(compressed)))) {
                decoded = net.minecraft.nbt.NbtIo.read(nbtInput);
            }
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

    @Test
    void buildsPayloadFromImmutableEmptyList() {
        CompoundTag payload = StructureProtocol.structurePayload(List.of());

        assertTrue(payload.getListOrEmpty("Structures").isEmpty());
    }

    @Test
    void sortsImmutableStructureListWithoutMutatingIt() {
        CompoundTag village = new CompoundTag();
        village.putString("id", "minecraft:village");
        CompoundTag fortress = new CompoundTag();
        fortress.putString("id", "minecraft:fortress");
        List<CompoundTag> immutable = List.of(village, fortress);

        CompoundTag payload = StructureProtocol.structurePayload(immutable);
        ListTag structures = payload.getListOrEmpty("Structures");

        assertEquals(2, structures.size());
        assertEquals("minecraft:fortress",
                structures.getCompoundOrEmpty(0).getStringOr("id", ""));
        assertEquals("minecraft:village",
                structures.getCompoundOrEmpty(1).getStringOr("id", ""));
        assertEquals("minecraft:village", immutable.getFirst().getStringOr("id", ""));
    }

}
