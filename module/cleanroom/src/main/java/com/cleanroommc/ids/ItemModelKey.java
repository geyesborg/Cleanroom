package com.cleanroommc.ids;

/**
 * ItemModelMesher model-map keys: the vanilla {@code id << 16 | meta} packs collides once item ids pass 16 bits,
 * so the key keeps the full item id in the high 32 bits and the metadata in the low 32.
 */
public final class ItemModelKey {
    private ItemModelKey() {
    }

    public static long of(int itemId, int meta) {
        return ((long) itemId << 32) | (meta & 0xFFFFFFFFL);
    }
}
