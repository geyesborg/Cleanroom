package com.cleanroommc.ids;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The recipe book's packed item ints ({@code RecipeItemHelper.pack/unpack}, {@code Ingredient.getValidItemStacksPacked}).
 * Vanilla packs {@code item id << 16 | meta}, which only fits item ids below 65536 and 16-bit damage values. Items
 * that fit keep that value (and its sign bit is 0, as ids below 32768 were the only ones possible); any other
 * item/damage pair gets {@code Integer.MIN_VALUE | n}, numbered the first time it is packed. 0 stays empty.
 */
public final class PackedItems {
    private record Key(Item item, int meta) {}

    private static final Object2IntOpenHashMap<Key> NUMBERS = new Object2IntOpenHashMap<>();
    private static final List<Key> KEYS = new ArrayList<>();

    static {
        NUMBERS.defaultReturnValue(-1);
    }

    private PackedItems() {
    }

    public static int pack(Item item, int meta) {
        int id = Item.REGISTRY.getIDForObject(item);
        if (id >= 0 && id < 32768 && meta >= 0 && meta <= 0xFFFF) {
            return id << 16 | meta;
        }
        Key key = new Key(item, meta);
        synchronized (KEYS) {
            int n = NUMBERS.getInt(key);
            if (n < 0) {
                n = KEYS.size();
                KEYS.add(key);
                NUMBERS.put(key, n);
            }
            return Integer.MIN_VALUE | n;
        }
    }

    public static ItemStack unpack(int packed) {
        if (packed == 0) {
            return ItemStack.EMPTY;
        }
        if (packed > 0) {
            return new ItemStack(Item.getItemById(packed >> 16 & 0xFFFF), 1, packed & 0xFFFF);
        }
        Key key;
        synchronized (KEYS) {
            int n = packed & Integer.MAX_VALUE;
            key = n < KEYS.size() ? KEYS.get(n) : null;
        }
        return key == null ? ItemStack.EMPTY : new ItemStack(key.item(), 1, key.meta());
    }
}
