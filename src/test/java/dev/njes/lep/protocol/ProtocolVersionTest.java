package dev.njes.lep.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolVersionTest {
    @Test
    void parsesKnownValuesAndSafelyDefaultsToV2() {
        assertEquals(ProtocolVersion.V3, ProtocolVersion.parse("V3"));
        assertEquals(ProtocolVersion.V3, ProtocolVersion.parse("3"));
        assertEquals(ProtocolVersion.V2, ProtocolVersion.parse("unexpected"));
        assertEquals(ProtocolVersion.V2, ProtocolVersion.parse(null));
    }
}
