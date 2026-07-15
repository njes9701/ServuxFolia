package dev.njes.servuxfolia.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Compatible and bounded implementation of the Servux sequential splitter. */
public final class PacketSplitter {
    private static final long SESSION_TIMEOUT_NANOS = 30_000_000_000L;

    private final int maxReceiveBytes;
    private final int maxSessions;
    private final Map<UUID, Session> inbound = new ConcurrentHashMap<>();

    public PacketSplitter(int maxReceiveBytes) {
        this(maxReceiveBytes, 64);
    }

    public PacketSplitter(int maxReceiveBytes, int maxSessions) {
        if (maxReceiveBytes < 1) {
            throw new IllegalArgumentException("maxReceiveBytes must be positive");
        }
        if (maxSessions < 1) {
            throw new IllegalArgumentException("maxSessions must be positive");
        }
        this.maxReceiveBytes = maxReceiveBytes;
        this.maxSessions = maxSessions;
    }

    public byte[] receive(UUID playerId, byte[] slice) {
        long now = System.nanoTime();
        Session session;
        synchronized (inbound) {
            Session current = inbound.get(playerId);
            if (current == null || now - current.lastActivity > SESSION_TIMEOUT_NANOS) {
                if (current == null && inbound.size() >= maxSessions) {
                    throw new IllegalStateException("too many concurrent splitter sessions");
                }
                session = new Session(now);
                inbound.put(playerId, session);
            } else {
                session = current;
            }
        }
        try {
            byte[] complete = session.accept(slice, maxReceiveBytes, now);
            if (complete != null) {
                inbound.remove(playerId, session);
            }
            return complete;
        } catch (RuntimeException exception) {
            inbound.remove(playerId, session);
            throw exception;
        }
    }

    public void forget(UUID playerId) {
        inbound.remove(playerId);
    }

    public static List<byte[]> split(byte[] payload, int sliceBytes) {
        if (sliceBytes < 8) {
            throw new IllegalArgumentException("sliceBytes is too small");
        }
        List<byte[]> result = new ArrayList<>();
        int offset = 0;
        boolean first = true;
        do {
            ByteArrayOutputStream slice = new ByteArrayOutputStream(sliceBytes);
            if (first) {
                writeVarInt(slice, payload.length);
            }
            int count = Math.min(payload.length - offset, sliceBytes - slice.size());
            if (count > 0) {
                slice.write(payload, offset, count);
                offset += count;
            }
            result.add(slice.toByteArray());
            first = false;
        } while (offset < payload.length);
        return result;
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7F) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static final class Session {
        private int expected = -1;
        private ByteArrayOutputStream body;
        private long lastActivity;

        private Session(long now) {
            this.lastActivity = now;
        }

        private synchronized byte[] accept(byte[] slice, int maxBytes, long now) {
            if (slice.length == 0) {
                throw new IllegalArgumentException("empty splitter slice");
            }
            lastActivity = now;
            int offset = 0;
            if (expected < 0) {
                int[] header = readVarInt(slice);
                expected = header[0];
                offset = header[1];
                if (expected < 0 || expected > maxBytes) {
                    throw new IllegalArgumentException("declared splitter payload is out of bounds: " + expected);
                }
                body = new ByteArrayOutputStream(Math.min(expected, 1_048_576));
            }
            int incoming = slice.length - offset;
            if (incoming > expected - body.size()) {
                throw new IllegalArgumentException("splitter payload exceeds declared size");
            }
            body.write(slice, offset, incoming);
            return body.size() == expected ? body.toByteArray() : null;
        }

        private static int[] readVarInt(byte[] bytes) {
            int value = 0;
            int position = 0;
            for (int index = 0; index < Math.min(5, bytes.length); index++) {
                int current = bytes[index] & 0xFF;
                value |= (current & 0x7F) << position;
                if ((current & 0x80) == 0) {
                    return new int[]{value, index + 1};
                }
                position += 7;
            }
            throw new IllegalArgumentException("invalid splitter length VarInt: " + Arrays.toString(bytes));
        }
    }
}
