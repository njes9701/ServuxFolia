package dev.njes.lep;

import dev.njes.lep.protocol.ProtocolVersion;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;

public record PluginSettings(
        boolean enabled,
        ProtocolVersion protocolVersion,
        boolean advertiseCarpetV2,
        boolean validateStates,
        boolean debug,
        int maxPayload,
        Duration pendingTtl,
        int maxPendingPerPlayer
) {
    public static PluginSettings load(FileConfiguration config) {
        ProtocolVersion version = ProtocolVersion.parse(config.getString("protocol.version", "v2"));
        boolean enabled = config.getBoolean("protocol.enabled", true);
        boolean advertise = enabled
                && config.getBoolean("protocol.advertise-carpet-v2", true)
                && version == ProtocolVersion.V2;

        return new PluginSettings(
                enabled,
                version,
                advertise,
                config.getBoolean("protocol.validate-states", true),
                config.getBoolean("debug", false),
                clamp(config.getInt("security.max-payload", 1_048_575), 64, 16_777_215),
                Duration.ofMillis(clamp(config.getLong("security.pending-ttl-ms", 2_000L), 100L, 5_000L)),
                clamp(config.getInt("security.max-pending-per-player", 16), 2, 128)
        );
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
