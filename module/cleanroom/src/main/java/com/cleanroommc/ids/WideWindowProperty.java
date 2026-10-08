package com.cleanroommc.ids;

/**
 * Container properties ({@code SPacketWindowProperty}) are sent as shorts. Ids wider than 16 bits travel as two
 * properties: the high half first, then the low half, which keeps the vanilla value for ids that fit in 15 bits.
 */
public final class WideWindowProperty {
    private WideWindowProperty() {
    }

    public static int high(int value) {
        return value >> 16;
    }

    public static int low(int value) {
        return value & 0xFFFF;
    }

    /** {@code high} is {@link Integer#MIN_VALUE} when no high half was sent: {@code lowData} is then the vanilla value. */
    public static int combine(int high, int lowData) {
        return high == Integer.MIN_VALUE ? lowData : high << 16 | lowData & 0xFFFF;
    }
}
