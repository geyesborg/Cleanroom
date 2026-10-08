package com.cleanroommc.ids;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Ids and damage are saved as ints. Byte, short and int tags compare equal by value ({@code NBTPrimitive}), so a short
 * from a mod or an older save is the same tag as ours.
 */
public final class WideNbt {
    private WideNbt() {
    }

    /**
     * Older saves wrote potion {@code Id} as a signed byte, so ids 128-255 are negative there: a byte tag is read as
     * unsigned, anything else as the int it is.
     */
    public static int getUnsignedByteOrInt(NBTTagCompound tag, String key) {
        return tag.getTagId(key) == 1 ? tag.getByte(key) & 0xFF : tag.getInteger(key);
    }
}
