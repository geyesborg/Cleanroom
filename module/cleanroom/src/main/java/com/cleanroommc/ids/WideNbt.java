package com.cleanroommc.ids;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Numbers that vanilla saves as a short or byte (item damage, enchantment and potion ids): written in the vanilla
 * type while they fit, so the data stays as vanilla and mods expect it, and as an int past it. Readers use
 * {@code getInteger}, which reads any numeric tag.
 */
public final class WideNbt {
    private WideNbt() {
    }

    /** A short while the value fits one ({@code Damage}, enchantment {@code id}), otherwise an int. */
    public static void setShortOrInt(NBTTagCompound tag, String key, int value) {
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            tag.setShort(key, (short) value);
        } else {
            tag.setInteger(key, value);
        }
    }

    /** An unsigned byte while the value fits one (potion {@code Id}, read back with {@code & 0xFF}), otherwise an int. */
    public static void setUnsignedByteOrInt(NBTTagCompound tag, String key, int value) {
        if (value >= 0 && value <= 0xFF) {
            tag.setByte(key, (byte) value);
        } else {
            tag.setInteger(key, value);
        }
    }

    /** Reads what {@link #setUnsignedByteOrInt} wrote: a byte as unsigned, anything else as an int. */
    public static int getUnsignedByteOrInt(NBTTagCompound tag, String key) {
        return tag.getTagId(key) == 1 ? tag.getByte(key) & 0xFF : tag.getInteger(key);
    }
}
