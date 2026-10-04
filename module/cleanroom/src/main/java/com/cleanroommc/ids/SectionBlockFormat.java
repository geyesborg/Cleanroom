package com.cleanroommc.ids;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.BitArray;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.chunk.BlockStateContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Block storage in saved chunks, by name, so saves don't depend on runtime ids (no limit on blocks or states):
 * <ul>
 * <li>Chunk {@code BlockPalette}: list of strings, every state the chunk's sections use, written as
 * {@code namespace:name} or {@code namespace:name[property=value,...]}. One list per chunk: sections share most of
 * their states, so names are stored once rather than once per section.</li>
 * <li>Section {@code Palette}: int array of {@code BlockPalette} indices, the section's own palette.</li>
 * <li>Section {@code BlockStates}: long array of 4096 section-palette indices, absent when the section palette has
 * one entry. Each index uses {@code max(4, ceil(log2(palette size)))} bits; indices don't span longs (as in Minecraft
 * 1.16+), so a long holds {@code 64 / bits} of them, lowest bits first, in the section's {@code y << 8 | z << 4 | x}
 * order. At 4 bits this is also the in-memory layout, so most sections load and save as an array copy.</li>
 * </ul>
 * Unknown block names read as air, unknown properties or values as the block's default for them; Forge aliases and
 * missing-mapping remaps apply through the block registry.
 */
public final class SectionBlockFormat {
    public static final String CHUNK_PALETTE = "BlockPalette";
    public static final String PALETTE = "Palette";
    public static final String STATES = "BlockStates";
    private static final int SIZE = 4096;

    private SectionBlockFormat() {
    }

    /** The states one chunk's sections use, while writing it. */
    public static final class ChunkPalette {
        private final Reference2IntOpenHashMap<IBlockState> indexOf = new Reference2IntOpenHashMap<>();
        private final List<IBlockState> states = new ArrayList<>();

        public ChunkPalette() {
            indexOf.defaultReturnValue(-1);
        }

        int indexOf(IBlockState state) {
            int index = indexOf.getInt(state);
            if (index < 0) {
                index = states.size();
                indexOf.put(state, index);
                states.add(state);
            }
            return index;
        }

        public NBTTagList toNBT() {
            NBTTagList list = new NBTTagList();
            for (IBlockState state : states) {
                list.appendTag(new NBTTagString(SectionBlockFormat.toString(state)));
            }
            return list;
        }
    }

    public static boolean isPaletted(NBTTagCompound chunk) {
        return chunk.hasKey(CHUNK_PALETTE, 9);
    }

    public static IBlockState[] readChunkPalette(NBTTagCompound chunk) {
        NBTTagList list = chunk.getTagList(CHUNK_PALETTE, 8);
        IBlockState[] states = new IBlockState[list.tagCount()];
        for (int i = 0; i < states.length; i++) {
            states[i] = parse(list.getStringTagAt(i));
        }
        return states;
    }

    public static void write(BlockStateContainer data, NBTTagCompound section, ChunkPalette chunkPalette) {
        IBlockState[] local = data.getLocalPalette();
        int[] values;
        IBlockState[] entries;
        if (local != null) {
            // The section's own palette, in storage order: storage values are already palette indices
            entries = local;
            values = null;
        } else {
            // Global state ids (more than 256 states in the section): number the states the section uses
            BitArray storage = data.getRawStorage();
            Int2IntOpenHashMap localOf = new Int2IntOpenHashMap();
            localOf.defaultReturnValue(-1);
            List<IBlockState> used = new ArrayList<>();
            values = new int[SIZE];
            for (int i = 0; i < SIZE; i++) {
                int raw = storage.getAt(i);
                int index = localOf.get(raw);
                if (index < 0) {
                    index = used.size();
                    localOf.put(raw, index);
                    used.add(data.get(i & 15, i >> 8, i >> 4 & 15));
                }
                values[i] = index;
            }
            entries = used.toArray(new IBlockState[0]);
        }
        int[] palette = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            palette[i] = chunkPalette.indexOf(entries[i]);
        }
        section.setTag(PALETTE, new NBTTagIntArray(palette));
        if (entries.length == 1) {
            return;
        }
        int bits = bitsFor(entries.length);
        long[] longs;
        if (values == null && bits == 4 && data.getRawBits() == 4) {
            longs = data.getRawStorage().getBackingLongArray().clone();
        } else {
            if (values == null) {
                BitArray storage = data.getRawStorage();
                values = new int[SIZE];
                for (int i = 0; i < SIZE; i++) {
                    values[i] = storage.getAt(i);
                }
            }
            longs = pack(values, bits);
        }
        section.setTag(STATES, new NBTTagLongArray(longs));
    }

    public static void read(NBTTagCompound section, BlockStateContainer data, IBlockState[] chunkPalette) {
        int[] palette = section.getIntArray(PALETTE);
        if (palette.length == 0) {
            throw corrupt(section, "has an empty block palette");
        }
        IBlockState[] entries = new IBlockState[palette.length];
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] < 0 || palette[i] >= chunkPalette.length) {
                throw corrupt(section, "refers to chunk palette entry " + palette[i] + " of " + chunkPalette.length);
            }
            entries[i] = chunkPalette[palette[i]];
        }
        if (entries.length == 1) {
            if (entries[0] != Blocks.AIR.getDefaultState()) {
                for (int i = 0; i < SIZE; i++) {
                    data.set(i & 15, i >> 8, i >> 4 & 15, entries[0]);
                }
            }
            return;
        }
        int bits = bitsFor(entries.length);
        NBTBase tag = section.getTag(STATES);
        if (!(tag instanceof NBTTagLongArray longArray) || longArray.getLongArray().length != longCount(bits)) {
            throw corrupt(section, "has " + entries.length + " palette entries but no " + longCount(bits) + "-long " + STATES + " array");
        }
        long[] longs = longArray.getLongArray();
        // false: the palette can't take the entries in saved order (no air first, or more than 256 of them)
        boolean local = data.loadLocalPalette(entries, bits);
        if (local && bits == 4) {
            checkNibbles(longs, entries.length, section);
            System.arraycopy(longs, 0, data.getRawStorage().getBackingLongArray(), 0, longs.length);
            return;
        }
        int[] values = unpack(longs, bits);
        for (int value : values) {
            if (value >= entries.length) {
                throw corrupt(section, "refers to palette entry " + value + " of " + entries.length);
            }
        }
        BitArray storage = data.getRawStorage();
        for (int i = 0; i < SIZE; i++) {
            if (local) {
                storage.setAt(i, values[i]);
            } else {
                data.set(i & 15, i >> 8, i >> 4 & 15, entries[values[i]]);
            }
        }
    }

    /** Validates 4-bit indices in place, without unpacking them. */
    private static void checkNibbles(long[] longs, int paletteSize, NBTTagCompound section) {
        if (paletteSize >= 16) {
            return;
        }
        for (long word : longs) {
            for (int shift = 0; shift < 64; shift += 4) {
                int value = (int) (word >>> shift & 15);
                if (value >= paletteSize) {
                    throw corrupt(section, "refers to palette entry " + value + " of " + paletteSize);
                }
            }
        }
    }

    private static IllegalStateException corrupt(NBTTagCompound section, String problem) {
        return new IllegalStateException("Chunk section Y=" + section.getByte("Y") + " " + problem + ".");
    }

    static String toString(IBlockState state) {
        ResourceLocation name = Block.REGISTRY.getNameForObject(state.getBlock());
        if (state.getProperties().isEmpty()) {
            return String.valueOf(name);
        }
        StringBuilder out = new StringBuilder(String.valueOf(name)).append('[');
        boolean first = true;
        for (Map.Entry<IProperty<?>, Comparable<?>> entry : state.getProperties().entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(entry.getKey().getName()).append('=').append(valueName(entry.getKey(), entry.getValue()));
        }
        return out.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String valueName(IProperty<T> property, Comparable<?> value) {
        return property.getName((T) value);
    }

    static IBlockState parse(String s) {
        int open = s.indexOf('[');
        Block block = Block.REGISTRY.getObject(new ResourceLocation(open < 0 ? s : s.substring(0, open)));
        IBlockState state = block.getDefaultState();
        if (open < 0) {
            return state;
        }
        net.minecraft.block.state.BlockStateContainer definition = block.getBlockState();
        for (String pair : s.substring(open + 1, s.length() - 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                IProperty<?> property = definition.getProperty(pair.substring(0, eq));
                if (property != null) {
                    state = withValue(state, property, pair.substring(eq + 1));
                }
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> IBlockState withValue(IBlockState state, IProperty<T> property, String value) {
        return property.parseValue(value).toJavaUtil().map(v -> state.withProperty(property, v)).orElse(state);
    }

    static int bitsFor(int paletteSize) {
        return Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    static int longCount(int bits) {
        int perLong = 64 / bits;
        return (SIZE + perLong - 1) / perLong;
    }

    static long[] pack(int[] indices, int bits) {
        int perLong = 64 / bits;
        long[] longs = new long[longCount(bits)];
        for (int i = 0; i < indices.length; i++) {
            longs[i / perLong] |= (long) indices[i] << (i % perLong * bits);
        }
        return longs;
    }

    static int[] unpack(long[] longs, int bits) {
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        int[] indices = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            indices[i] = (int) (longs[i / perLong] >>> (i % perLong * bits) & mask);
        }
        return indices;
    }
}
