package com.sunlitvalley.menucards.integration.shipping;

import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Versioned, JVM-only canonical encoding of the unified virtual shipping snapshot. */
public final class CanonicalInputFingerprint {
    public static final int SLOT_COUNT = 54;
    private static final byte VERSION = 3;
    private static final byte[] PREFIX = "MENUCARDS-INPUT\0".getBytes(StandardCharsets.US_ASCII);

    private CanonicalInputFingerprint() { }

    public static byte[] sha256(ItemStack[] slots) {
        if (slots == null || slots.length != SLOT_COUNT) {
            throw new IllegalArgumentException("INPUT_SLOT_COUNT");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(encode(slots));
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static byte[] encode(ItemStack[] slots) {
        if (slots == null || slots.length != SLOT_COUNT) {
            throw new IllegalArgumentException("INPUT_SLOT_COUNT");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(PREFIX);
            out.writeByte(VERSION);
            out.writeInt(SLOT_COUNT);
            for (int index = 0; index < SLOT_COUNT; index++) {
                ItemStack stack = slots[index];
                out.writeInt(index);
                if (stack == null || stack.isEmpty()) {
                    writeUtf8(out, "minecraft:air");
                    out.writeInt(0);
                    out.writeInt(0);
                    continue;
                }
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (key == null) {
                    throw new IllegalArgumentException("UNREGISTERED_ITEM");
                }
                writeUtf8(out, key.toString());
                out.writeInt(stack.getCount());
                out.writeBoolean(stack.getTag() != null);
                byte[] nbt = canonicalNbt(stack.getTag());
                out.writeInt(nbt.length);
                out.write(nbt);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] canonicalNbt(CompoundTag tag) throws IOException {
        if (tag == null || tag.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeTagPayload(out, tag);
        out.flush();
        return bytes.toByteArray();
    }

    private static void writeTagPayload(DataOutputStream out, Tag tag) throws IOException {
        switch (tag.getId()) {
            case Tag.TAG_BYTE -> out.writeByte(((ByteTag) tag).getAsByte());
            case Tag.TAG_SHORT -> out.writeShort(((ShortTag) tag).getAsShort());
            case Tag.TAG_INT -> out.writeInt(((IntTag) tag).getAsInt());
            case Tag.TAG_LONG -> out.writeLong(((LongTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> out.writeInt(Float.floatToRawIntBits(((FloatTag) tag).getAsFloat()));
            case Tag.TAG_DOUBLE -> out.writeLong(Double.doubleToRawLongBits(((DoubleTag) tag).getAsDouble()));
            case Tag.TAG_BYTE_ARRAY -> { byte[] value = ((ByteArrayTag) tag).getAsByteArray(); out.writeInt(value.length); out.write(value); }
            case Tag.TAG_STRING -> writeUtf8(out, tag.getAsString());
            case Tag.TAG_LIST -> writeList(out, (ListTag) tag);
            case Tag.TAG_COMPOUND -> writeCompound(out, (CompoundTag) tag);
            case Tag.TAG_INT_ARRAY -> { int[] value = ((IntArrayTag) tag).getAsIntArray(); out.writeInt(value.length); for (int entry : value) out.writeInt(entry); }
            case Tag.TAG_LONG_ARRAY -> { long[] value = ((LongArrayTag) tag).getAsLongArray(); out.writeInt(value.length); for (long entry : value) out.writeLong(entry); }
            default -> throw new IllegalArgumentException("UNSUPPORTED_NBT_TYPE");
        }
    }

    private static void writeList(DataOutputStream out, ListTag list) throws IOException {
        out.writeByte(list.getElementType());
        out.writeInt(list.size());
        for (Tag entry : list) writeTagPayload(out, entry);
    }

    private static void writeCompound(DataOutputStream out, CompoundTag compound) throws IOException {
        List<String> keys = new ArrayList<>(compound.getAllKeys());
        keys.sort(Comparator.comparing(CanonicalInputFingerprint::utf8, CanonicalInputFingerprint::compareUnsigned));
        for (String key : keys) {
            Tag value = compound.get(key);
            out.writeByte(value.getId());
            writeUtf8(out, key);
            writeTagPayload(out, value);
        }
        out.writeByte(Tag.TAG_END);
    }

    private static void writeUtf8(DataOutputStream out, String value) throws IOException {
        byte[] bytes = utf8(value);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }
}
