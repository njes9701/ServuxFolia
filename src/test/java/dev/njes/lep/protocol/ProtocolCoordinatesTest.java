package dev.njes.lep.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCoordinatesTest {
    @Test
    void decodesPayloadAndPreservesFractionAtNegativeWorldCoordinates() {
        int clickedX = -42;
        int payload = 1_000;
        double encodedX = clickedX + 0.375D + 2D + payload;

        ProtocolCoordinates.DecodedCoordinates decoded = ProtocolCoordinates
                .decode(encodedX, clickedX, 1_000)
                .orElseThrow();

        assertEquals(payload, decoded.payload());
        assertEquals(clickedX + 0.375D, decoded.normalizedAbsoluteX(), 1.0E-9D);
    }

    @Test
    void acceptsZeroPayloadBecauseDownFacingUsesIt() {
        assertEquals(0, ProtocolCoordinates.decode(12.5D, 10, 100).orElseThrow().payload());
    }

    @Test
    void acceptsTheReservedBitFromAnExactEastFaceHit() {
        int clickedX = 10;
        int payload = 0x52;
        double encodedEastFaceX = clickedX + 1.0D + 2.0D + payload;

        ProtocolCoordinates.DecodedCoordinates decoded = ProtocolCoordinates
                .decode(encodedEastFaceX, clickedX, payload)
                .orElseThrow();

        assertEquals(payload, decoded.payload());
    }

    @Test
    void rejectsVanillaAndOversizedCoordinates() {
        assertTrue(ProtocolCoordinates.decode(10.9D, 10, 100).isEmpty());
        assertTrue(ProtocolCoordinates.decode(212.2D, 10, 100).isEmpty());
        assertTrue(ProtocolCoordinates.decode(Double.NaN, 10, 100).isEmpty());
    }

    @Test
    void createsSafeFaceCoordinatesForEveryDirectionStep() {
        assertEquals(-42.0D, ProtocolCoordinates.safeFaceCoordinate(-42, -1));
        assertEquals(-41.5D, ProtocolCoordinates.safeFaceCoordinate(-42, 0));
        assertEquals(-41.0D, ProtocolCoordinates.safeFaceCoordinate(-42, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolCoordinates.safeFaceCoordinate(0, 2));
    }
}
