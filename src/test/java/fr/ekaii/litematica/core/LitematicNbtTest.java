package fr.ekaii.litematica.core;

import fr.ekaii.litematica.core.LitematicNbt.NamedTag;
import fr.ekaii.litematica.core.LitematicNbt.NbtByteArray;
import fr.ekaii.litematica.core.LitematicNbt.NbtCompound;
import fr.ekaii.litematica.core.LitematicNbt.NbtDouble;
import fr.ekaii.litematica.core.LitematicNbt.NbtIntArray;
import fr.ekaii.litematica.core.LitematicNbt.NbtList;
import fr.ekaii.litematica.core.LitematicNbt.NbtLongArray;
import fr.ekaii.litematica.core.LitematicNbt.NbtString;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LitematicNbtTest {

    @Test
    void roundtripAllTagTypesGzipped() throws Exception {
        NbtCompound root = new NbtCompound();
        root.putByte("b", (byte) -7);
        root.putShort("s", (short) 31000);
        root.putInt("i", -123456);
        root.putLong("l", 9_876_543_210L);
        root.putFloat("f", 3.14159f);
        root.putDouble("d", Math.E);
        root.putString("str", "hello-éà ✓");
        root.putIntArray("ia", new int[]{1, 2, 3, -4, 5});
        root.putLongArray("la", new long[]{Long.MIN_VALUE, 0, Long.MAX_VALUE});
        root.putByteArray("ba", new byte[]{1, 2, 3, 4, 5, 6, 7});

        NbtList list = new NbtList(LitematicNbt.TAG_DOUBLE, new ArrayList<>());
        list.values().add(new NbtDouble(1.0));
        list.values().add(new NbtDouble(2.0));
        list.values().add(new NbtDouble(3.0));
        root.put("list_d", list);

        NbtList stringList = new NbtList(LitematicNbt.TAG_STRING, new ArrayList<>());
        stringList.values().add(new NbtString("a"));
        stringList.values().add(new NbtString("b"));
        root.put("list_s", stringList);

        NbtCompound nested = new NbtCompound();
        nested.putInt("inner", 42);
        nested.putString("name", "nested");
        root.put("nested", nested);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LitematicNbt.writeGzipped(baos, new NamedTag("root", root));

        NamedTag read = LitematicNbt.read(new ByteArrayInputStream(baos.toByteArray()));
        assertEquals("root", read.name());
        assertEquals(root, read.tag());
    }

    @Test
    void readDetectsUncompressedStream() throws Exception {
        NbtCompound root = new NbtCompound();
        root.putString("k", "v");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LitematicNbt.writeRaw(baos, new NamedTag("r", root));

        NamedTag read = LitematicNbt.read(new ByteArrayInputStream(baos.toByteArray()));
        assertEquals("r", read.name());
        assertEquals(root, read.tag());
    }

    @Test
    void emptyListSerialisesCleanly() throws Exception {
        NbtCompound root = new NbtCompound();
        root.put("empty", NbtList.empty());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LitematicNbt.writeGzipped(baos, new NamedTag("r", root));
        NamedTag read = LitematicNbt.read(new ByteArrayInputStream(baos.toByteArray()));
        assertEquals(root, read.tag());
    }

    @Test
    void byteArrayAndIntArrayEqualsHandleArrayContents() {
        NbtByteArray ba1 = new NbtByteArray(new byte[]{1, 2, 3});
        NbtByteArray ba2 = new NbtByteArray(new byte[]{1, 2, 3});
        assertEquals(ba1, ba2);

        NbtIntArray ia1 = new NbtIntArray(new int[]{4, 5, 6});
        NbtIntArray ia2 = new NbtIntArray(new int[]{4, 5, 6});
        assertEquals(ia1, ia2);

        NbtLongArray la1 = new NbtLongArray(new long[]{7L, 8L, 9L});
        NbtLongArray la2 = new NbtLongArray(new long[]{7L, 8L, 9L});
        assertEquals(la1, la2);
    }
}
