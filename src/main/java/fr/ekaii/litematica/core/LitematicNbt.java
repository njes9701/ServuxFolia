package fr.ekaii.litematica.core;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tiny inline NBT reader/writer. Compatible with the standard Minecraft NBT
 * wire format. Implemented from scratch — no external schematic libraries.
 *
 * <p>Supported tag IDs:
 * <pre>
 *   END        = 0
 *   BYTE       = 1
 *   SHORT      = 2
 *   INT        = 3
 *   LONG       = 4
 *   FLOAT      = 5
 *   DOUBLE     = 6
 *   BYTE_ARRAY = 7
 *   STRING     = 8
 *   LIST       = 9
 *   COMPOUND   = 10
 *   INT_ARRAY  = 11
 *   LONG_ARRAY = 12
 * </pre>
 *
 * <p>This module is pure {@code java.*} — no Paper / NMS dependencies.
 */
public final class LitematicNbt {

    private LitematicNbt() {
    }

    // --------------------------------------------------------------- Tag IDs

    public static final byte TAG_END = 0;
    public static final byte TAG_BYTE = 1;
    public static final byte TAG_SHORT = 2;
    public static final byte TAG_INT = 3;
    public static final byte TAG_LONG = 4;
    public static final byte TAG_FLOAT = 5;
    public static final byte TAG_DOUBLE = 6;
    public static final byte TAG_BYTE_ARRAY = 7;
    public static final byte TAG_STRING = 8;
    public static final byte TAG_LIST = 9;
    public static final byte TAG_COMPOUND = 10;
    public static final byte TAG_INT_ARRAY = 11;
    public static final byte TAG_LONG_ARRAY = 12;

    // ----------------------------------------------------------- Tag hierarchy

    /** Marker interface for every NBT tag. */
    public sealed interface NbtTag permits
            NbtByte, NbtShort, NbtInt, NbtLong, NbtFloat, NbtDouble,
            NbtByteArray, NbtString, NbtList, NbtCompound,
            NbtIntArray, NbtLongArray {
        byte id();
    }

    public record NbtByte(byte value) implements NbtTag {
        @Override public byte id() { return TAG_BYTE; }
    }

    public record NbtShort(short value) implements NbtTag {
        @Override public byte id() { return TAG_SHORT; }
    }

    public record NbtInt(int value) implements NbtTag {
        @Override public byte id() { return TAG_INT; }
    }

    public record NbtLong(long value) implements NbtTag {
        @Override public byte id() { return TAG_LONG; }
    }

    public record NbtFloat(float value) implements NbtTag {
        @Override public byte id() { return TAG_FLOAT; }
    }

    public record NbtDouble(double value) implements NbtTag {
        @Override public byte id() { return TAG_DOUBLE; }
    }

    public static final class NbtByteArray implements NbtTag {
        private final byte[] value;
        public NbtByteArray(byte[] value) { this.value = Objects.requireNonNull(value); }
        public byte[] value() { return value; }
        @Override public byte id() { return TAG_BYTE_ARRAY; }
        @Override public boolean equals(Object o) {
            return o instanceof NbtByteArray b && java.util.Arrays.equals(value, b.value);
        }
        @Override public int hashCode() { return java.util.Arrays.hashCode(value); }
    }

    public record NbtString(String value) implements NbtTag {
        public NbtString { Objects.requireNonNull(value); }
        @Override public byte id() { return TAG_STRING; }
    }

    /**
     * A list of homogeneously-typed NBT tags. The element type id is fixed at
     * construction time. An empty list has element type {@code TAG_END}.
     */
    public static final class NbtList implements NbtTag {
        private final byte elementType;
        private final List<NbtTag> values;

        public NbtList(byte elementType, List<NbtTag> values) {
            this.elementType = elementType;
            this.values = new ArrayList<>(Objects.requireNonNull(values));
        }

        public static NbtList empty() { return new NbtList(TAG_END, new ArrayList<>()); }

        public byte elementType() { return elementType; }
        /**
         * Direct (mutable) access to the backing list. Callers may use this
         * to append to a freshly-constructed list, or to read-iterate. Use
         * with care: this is the on-disk wire order.
         */
        public List<NbtTag> values() { return values; }
        public int size() { return values.size(); }
        public NbtTag get(int i) { return values.get(i); }

        /** Appends {@code tag} to this list. */
        public NbtList add(NbtTag tag) {
            values.add(Objects.requireNonNull(tag));
            return this;
        }

        @Override public byte id() { return TAG_LIST; }

        @Override public boolean equals(Object o) {
            return o instanceof NbtList l && l.elementType == elementType && l.values.equals(values);
        }
        @Override public int hashCode() { return Objects.hash(elementType, values); }
    }

    /**
     * Compound (named map of tags). Insertion order is preserved so that
     * round-tripping is byte-identical when no semantic edits occur.
     */
    public static final class NbtCompound implements NbtTag {
        private final LinkedHashMap<String, NbtTag> entries;

        public NbtCompound() { this.entries = new LinkedHashMap<>(); }
        public NbtCompound(LinkedHashMap<String, NbtTag> entries) {
            this.entries = new LinkedHashMap<>(Objects.requireNonNull(entries));
        }

        @Override public byte id() { return TAG_COMPOUND; }

        public Map<String, NbtTag> entries() { return Collections.unmodifiableMap(entries); }
        public int size() { return entries.size(); }
        public boolean contains(String key) { return entries.containsKey(key); }
        public NbtTag get(String key) { return entries.get(key); }

        public NbtCompound put(String key, NbtTag tag) {
            entries.put(Objects.requireNonNull(key), Objects.requireNonNull(tag));
            return this;
        }
        public NbtCompound putByte(String key, byte v)     { return put(key, new NbtByte(v)); }
        public NbtCompound putShort(String key, short v)   { return put(key, new NbtShort(v)); }
        public NbtCompound putInt(String key, int v)       { return put(key, new NbtInt(v)); }
        public NbtCompound putLong(String key, long v)     { return put(key, new NbtLong(v)); }
        public NbtCompound putFloat(String key, float v)   { return put(key, new NbtFloat(v)); }
        public NbtCompound putDouble(String key, double v) { return put(key, new NbtDouble(v)); }
        public NbtCompound putString(String key, String v) { return put(key, new NbtString(v)); }
        public NbtCompound putIntArray(String key, int[] v)   { return put(key, new NbtIntArray(v)); }
        public NbtCompound putLongArray(String key, long[] v) { return put(key, new NbtLongArray(v)); }
        public NbtCompound putByteArray(String key, byte[] v) { return put(key, new NbtByteArray(v)); }

        // ---- typed getters (return defaults / null on missing or wrong-type)

        public Byte getByte(String key) {
            return (entries.get(key) instanceof NbtByte b) ? b.value() : null;
        }
        public Short getShort(String key) {
            return (entries.get(key) instanceof NbtShort s) ? s.value() : null;
        }
        public Integer getInt(String key) {
            return (entries.get(key) instanceof NbtInt i) ? i.value() : null;
        }
        public Long getLong(String key) {
            return (entries.get(key) instanceof NbtLong l) ? l.value() : null;
        }
        public Float getFloat(String key) {
            return (entries.get(key) instanceof NbtFloat f) ? f.value() : null;
        }
        public Double getDouble(String key) {
            return (entries.get(key) instanceof NbtDouble d) ? d.value() : null;
        }
        public String getString(String key) {
            return (entries.get(key) instanceof NbtString s) ? s.value() : null;
        }
        public int[] getIntArray(String key) {
            return (entries.get(key) instanceof NbtIntArray a) ? a.value() : null;
        }
        public long[] getLongArray(String key) {
            return (entries.get(key) instanceof NbtLongArray a) ? a.value() : null;
        }
        public byte[] getByteArray(String key) {
            return (entries.get(key) instanceof NbtByteArray a) ? a.value() : null;
        }
        public NbtCompound getCompound(String key) {
            return (entries.get(key) instanceof NbtCompound c) ? c : null;
        }
        public NbtList getList(String key) {
            return (entries.get(key) instanceof NbtList l) ? l : null;
        }

        @Override public boolean equals(Object o) {
            return o instanceof NbtCompound c && c.entries.equals(entries);
        }
        @Override public int hashCode() { return entries.hashCode(); }
    }

    public static final class NbtIntArray implements NbtTag {
        private final int[] value;
        public NbtIntArray(int[] value) { this.value = Objects.requireNonNull(value); }
        public int[] value() { return value; }
        @Override public byte id() { return TAG_INT_ARRAY; }
        @Override public boolean equals(Object o) {
            return o instanceof NbtIntArray a && java.util.Arrays.equals(value, a.value);
        }
        @Override public int hashCode() { return java.util.Arrays.hashCode(value); }
    }

    public static final class NbtLongArray implements NbtTag {
        private final long[] value;
        public NbtLongArray(long[] value) { this.value = Objects.requireNonNull(value); }
        public long[] value() { return value; }
        @Override public byte id() { return TAG_LONG_ARRAY; }
        @Override public boolean equals(Object o) {
            return o instanceof NbtLongArray a && java.util.Arrays.equals(value, a.value);
        }
        @Override public int hashCode() { return java.util.Arrays.hashCode(value); }
    }

    // ---------------------------------------------------------------- Reading

    /**
     * Reads a named NBT root from the (possibly gzipped) stream.
     * Detects gzip via the {@code 0x1f 0x8b} magic bytes.
     */
    public static NamedTag read(InputStream in) throws IOException {
        DataInputStream dis = wrapForRead(in);
        return readNamedTag(dis);
    }

    /** Reads a named root tag from a raw {@link DataInput}. */
    public static NamedTag readNamedTag(DataInput in) throws IOException {
        byte id = in.readByte();
        if (id == TAG_END) {
            return new NamedTag("", null);
        }
        String name = in.readUTF();
        NbtTag tag = readPayload(in, id);
        return new NamedTag(name, tag);
    }

    private static NbtTag readPayload(DataInput in, byte id) throws IOException {
        return switch (id) {
            case TAG_BYTE -> new NbtByte(in.readByte());
            case TAG_SHORT -> new NbtShort(in.readShort());
            case TAG_INT -> new NbtInt(in.readInt());
            case TAG_LONG -> new NbtLong(in.readLong());
            case TAG_FLOAT -> new NbtFloat(in.readFloat());
            case TAG_DOUBLE -> new NbtDouble(in.readDouble());
            case TAG_BYTE_ARRAY -> {
                int n = in.readInt();
                checkLen(n, "byte array");
                byte[] buf = new byte[n];
                in.readFully(buf);
                yield new NbtByteArray(buf);
            }
            case TAG_STRING -> new NbtString(in.readUTF());
            case TAG_LIST -> {
                byte elemType = in.readByte();
                int n = in.readInt();
                checkLen(n, "list");
                List<NbtTag> values = new ArrayList<>(Math.max(0, n));
                if (elemType == TAG_END) {
                    if (n > 0) {
                        throw new IOException("List with TAG_END element type but length " + n);
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        values.add(readPayload(in, elemType));
                    }
                }
                yield new NbtList(elemType, values);
            }
            case TAG_COMPOUND -> {
                NbtCompound c = new NbtCompound();
                while (true) {
                    byte childId = in.readByte();
                    if (childId == TAG_END) break;
                    String name = in.readUTF();
                    c.put(name, readPayload(in, childId));
                }
                yield c;
            }
            case TAG_INT_ARRAY -> {
                int n = in.readInt();
                checkLen(n, "int array");
                int[] arr = new int[n];
                for (int i = 0; i < n; i++) arr[i] = in.readInt();
                yield new NbtIntArray(arr);
            }
            case TAG_LONG_ARRAY -> {
                int n = in.readInt();
                checkLen(n, "long array");
                long[] arr = new long[n];
                for (int i = 0; i < n; i++) arr[i] = in.readLong();
                yield new NbtLongArray(arr);
            }
            default -> throw new IOException("Unknown NBT tag id: " + id);
        };
    }

    private static void checkLen(int n, String what) throws IOException {
        if (n < 0) throw new IOException("Negative length for " + what + ": " + n);
    }

    private static DataInputStream wrapForRead(InputStream in) throws IOException {
        InputStream buffered = in.markSupported() ? in : new java.io.BufferedInputStream(in);
        buffered.mark(2);
        int b0 = buffered.read();
        int b1 = buffered.read();
        buffered.reset();
        if (b0 == 0x1f && b1 == 0x8b) {
            return new DataInputStream(new java.util.zip.GZIPInputStream(buffered));
        }
        return new DataInputStream(buffered);
    }

    // ---------------------------------------------------------------- Writing

    /**
     * Writes the named tag as gzipped NBT. This is the canonical
     * {@code .litematic} on-disk form.
     */
    public static void writeGzipped(OutputStream out, NamedTag tag) throws IOException {
        try (var gz = new java.util.zip.GZIPOutputStream(out);
             var dos = new DataOutputStream(gz)) {
            writeNamedTag(dos, tag);
        }
    }

    /** Writes the named tag uncompressed. */
    public static void writeRaw(OutputStream out, NamedTag tag) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        writeNamedTag(dos, tag);
        dos.flush();
    }

    public static void writeNamedTag(DataOutput out, NamedTag named) throws IOException {
        NbtTag tag = named.tag();
        if (tag == null) {
            out.writeByte(TAG_END);
            return;
        }
        out.writeByte(tag.id());
        out.writeUTF(named.name());
        writePayload(out, tag);
    }

    private static void writePayload(DataOutput out, NbtTag tag) throws IOException {
        switch (tag) {
            case NbtByte b -> out.writeByte(b.value());
            case NbtShort s -> out.writeShort(s.value());
            case NbtInt i -> out.writeInt(i.value());
            case NbtLong l -> out.writeLong(l.value());
            case NbtFloat f -> out.writeFloat(f.value());
            case NbtDouble d -> out.writeDouble(d.value());
            case NbtByteArray a -> {
                out.writeInt(a.value().length);
                out.write(a.value());
            }
            case NbtString s -> out.writeUTF(s.value());
            case NbtList l -> {
                byte t = l.elementType();
                // canonicalise: empty list → TAG_END element type
                if (l.size() == 0) t = TAG_END;
                out.writeByte(t);
                out.writeInt(l.size());
                for (NbtTag child : l.values()) {
                    writePayload(out, child);
                }
            }
            case NbtCompound c -> {
                for (Map.Entry<String, NbtTag> e : c.entries.entrySet()) {
                    NbtTag child = e.getValue();
                    out.writeByte(child.id());
                    out.writeUTF(e.getKey());
                    writePayload(out, child);
                }
                out.writeByte(TAG_END);
            }
            case NbtIntArray a -> {
                out.writeInt(a.value().length);
                for (int v : a.value()) out.writeInt(v);
            }
            case NbtLongArray a -> {
                out.writeInt(a.value().length);
                for (long v : a.value()) out.writeLong(v);
            }
        }
    }

    // ------------------------------------------------------------- Named tag

    /** A name + payload pair representing the root of an NBT document. */
    public record NamedTag(String name, NbtTag tag) {
        public NamedTag {
            Objects.requireNonNull(name);
        }
    }
}
