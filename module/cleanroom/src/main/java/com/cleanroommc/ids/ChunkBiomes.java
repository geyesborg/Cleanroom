package com.cleanroommc.ids;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.registries.GameData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;

/**
 * A chunk's biomes as full runtime ids, one per x/z column ({@code z << 4 | x}), {@link #UNSET} where not generated.
 * <p>
 * The chunk's old {@code byte[]} ({@code Chunk.getBiomeArray()}) stays a live view: it holds each id's low byte, and
 * bytes that code writes into it are picked up the next time the biomes are read, by comparing the view with the
 * bytes last seen. A written byte means the biome whose id has that low byte; when several do (more than 256
 * biomes), the one the world's biome provider places in that column wins, which is what chunk generators write.
 * Code that knows full ids uses {@link #set}, {@link #get} and {@link #copy}.
 * <p>
 * Saved as {@code BiomePalette} (biome names, {@code ""} for unset) and {@code BiomeIndices} (256 palette indices,
 * absent for a single entry).
 */
public final class ChunkBiomes {
    public static final int UNSET = -1;
    public static final String PALETTE = "BiomePalette";
    public static final String INDICES = "BiomeIndices";
    private static final int SIZE = 256;
    private static final Logger LOGGER = LogManager.getLogger("Cleanroom");

    private final Chunk chunk;
    private final byte[] view;
    private final byte[] seen = new byte[SIZE];
    private final int[] ids = new int[SIZE];

    public ChunkBiomes(Chunk chunk, byte[] view) {
        if (view.length != SIZE) {
            throw new IllegalArgumentException("Chunk biome views need " + SIZE + " bytes, got " + view.length + ".");
        }
        this.chunk = chunk;
        this.view = view;
        Arrays.fill(ids, UNSET);
        System.arraycopy(view, 0, seen, 0, SIZE);
        for (int i = 0; i < SIZE; i++) {
            if (view[i] != (byte) 0xFF) {
                ids[i] = view[i] & 0xFF;
            }
        }
    }

    public int get(int index) {
        sync();
        return ids[index];
    }

    public int get(int x, int z) {
        return get((z & 15) << 4 | x & 15);
    }

    public void set(int index, int id) {
        sync();
        ids[index] = id;
        view[index] = seen[index] = legacyByte(id);
    }

    public void set(int x, int z, int id) {
        set((z & 15) << 4 | x & 15, id);
    }

    public int[] copy() {
        sync();
        return ids.clone();
    }

    public void setAll(int[] newIds) {
        if (newIds.length != SIZE) {
            throw new IllegalArgumentException("Chunk biomes need " + SIZE + " ids, got " + newIds.length + ".");
        }
        System.arraycopy(newIds, 0, ids, 0, SIZE);
        for (int i = 0; i < SIZE; i++) {
            view[i] = seen[i] = legacyByte(ids[i]);
        }
    }

    /** Old-format bytes, which are full ids (worlds from before the format held at most 256 biomes). */
    public void setLegacy(byte[] bytes) {
        if (bytes.length != SIZE) {
            throw new IllegalArgumentException("Legacy chunk biomes need " + SIZE + " bytes, got " + bytes.length + ".");
        }
        int[] newIds = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            newIds[i] = bytes[i] == (byte) 0xFF ? UNSET : bytes[i] & 0xFF;
        }
        setAll(newIds);
    }

    /** JEID/REID save one full biome id per column. */
    public void setIds(int[] newIds) {
        if (newIds.length != SIZE) {
            throw new IllegalArgumentException("Chunk biomes need " + SIZE + " ids, got " + newIds.length + ".");
        }
        setAll(newIds);
    }

    private static byte legacyByte(int id) {
        return id == UNSET ? (byte) 0xFF : (byte) id;
    }

    private void sync() {
        int first = Arrays.mismatch(view, seen);
        if (first >= 0) {
            syncFrom(first);
        }
    }

    private synchronized void syncFrom(int first) {
        for (int i = first; i < SIZE; i++) {
            byte b = view[i];
            if (b != seen[i]) {
                ids[i] = resolve(b & 0xFF, i);
                seen[i] = b;
            }
        }
    }

    /**
     * What a byte a mod wrote into {@code getBiomeArray()} means. A mod never invents the byte: it came from
     * {@code Biome.getIdForBiome} (the low byte of a full id), most often read out of this same array a moment
     * earlier. So, in order:
     * <ol>
     * <li>Only one biome has that low byte: it. (Always the case with at most 256 biomes.)</li>
     * <li>One of the candidates is already somewhere in this chunk: that one. A read-modify-write of the array
     * (the common pattern: feature generators, decorators) resolves every unchanged cell exactly, and a cell
     * copied from a neighbour resolves to the neighbour's biome.</li>
     * <li>The world's biome provider places one of the candidates at this column: that one. Chunk generators
     * writing what they just computed from the provider land here.</li>
     * <li>Otherwise the lowest candidate, with a warning naming the mod. This is a biome past 255, new to the
     * chunk, that disagrees with the provider: a byte cannot carry it. The mod needs {@link #set}.</li>
     * </ol>
     */
    private int resolve(int lowByte, int index) {
        if (lowByte == 0xFF) {
            return UNSET;
        }
        int[] candidates = GameData.getBiomeIdsWithLowByte(lowByte);
        if (candidates.length <= 1) {
            return candidates.length == 0 ? UNSET : candidates[0];
        }
        int present = candidatePresentInChunk(candidates);
        if (present != UNSET) {
            return present;
        }
        World world = chunk.getWorld();
        if (world != null && !world.isRemote) {
            BlockPos pos = new BlockPos(chunk.x << 4 | index & 15, 64, chunk.z << 4 | index >> 4);
            int generated = Biome.getIdForBiome(world.getBiomeProvider().getBiome(pos, null));
            for (int candidate : candidates) {
                if (candidate == generated) {
                    return candidate;
                }
            }
        }
        warnAmbiguous(lowByte, candidates);
        return candidates[0];
    }

    /** The candidate already used by another column of this chunk, if exactly one of them is. */
    private int candidatePresentInChunk(int[] candidates) {
        int found = UNSET;
        for (int id : ids) {
            if (id == UNSET) {
                continue;
            }
            for (int candidate : candidates) {
                if (id == candidate) {
                    if (found != UNSET && found != id) {
                        return UNSET; // two candidates both present: the byte is ambiguous even within the chunk
                    }
                    found = id;
                }
            }
        }
        return found;
    }

    private static final java.util.Set<String> WARNED_WRITERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void warnAmbiguous(int lowByte, int[] candidates) {
        String writer = callingMod();
        if (!WARNED_WRITERS.add(writer)) {
            return;
        }
        LOGGER.warn("{} wrote biome byte {} into Chunk.getBiomeArray(); {} biomes share that low byte and none is in this chunk or placed here by the biome provider. Using {} ({}). "
                + "A byte cannot hold a biome id past 255: this mod needs Chunk.getBiomeIds().set(x, z, id). Logged once per mod.",
                writer, lowByte, candidates.length, candidates[0], Biome.getBiome(candidates[0]) == null ? "?" : Biome.getBiome(candidates[0]).getRegistryName());
    }

    /** The first stack frame outside Minecraft, Forge and Cleanroom: the mod that wrote the byte. */
    private static String callingMod() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("net.minecraft.") || cls.startsWith("net.minecraftforge.") || cls.startsWith("com.cleanroommc.")) {
                continue;
            }
            return cls;
        }
        return "an unknown caller";
    }

    public void writeNBT(NBTTagCompound chunkTag) {
        sync();
        Int2IntOpenHashMap indexOf = new Int2IntOpenHashMap();
        indexOf.defaultReturnValue(-1);
        NBTTagList palette = new NBTTagList();
        byte[] indices = null;
        for (int i = 0; i < SIZE; i++) {
            int index = indexOf.get(ids[i]);
            if (index < 0) {
                index = indexOf.size();
                indexOf.put(ids[i], index);
                Biome biome = ids[i] == UNSET ? null : Biome.getBiome(ids[i]);
                ResourceLocation name = biome == null ? null : biome.getRegistryName();
                palette.appendTag(new NBTTagString(name == null ? "" : name.toString()));
            }
            if (indices == null && index != 0) {
                indices = new byte[SIZE];
            }
            if (indices != null) {
                indices[i] = (byte) index;
            }
        }
        chunkTag.setTag(PALETTE, palette);
        if (indices != null) {
            chunkTag.setByteArray(INDICES, indices);
        } else {
            chunkTag.removeTag(INDICES);
        }
    }

    /** Reads either format; returns false when the chunk has no biome data. */
    public boolean readNBT(NBTTagCompound chunkTag) {
        if (chunkTag.hasKey(PALETTE)) {
            if (!(chunkTag.getTag(PALETTE) instanceof NBTTagList palette)
                    || palette.tagCount() > 0 && palette.getTagType() != 8) {
                throw corrupt("has an invalid " + PALETTE + "; expected a list of biome names");
            }
            if (palette.tagCount() > SIZE) {
                throw corrupt("has " + palette.tagCount() + " biome palette entries for " + SIZE + " columns");
            }
            int[] byIndex = new int[palette.tagCount()];
            for (int i = 0; i < byIndex.length; i++) {
                String name = palette.getStringTagAt(i);
                Biome biome = name.isEmpty() ? null : Biome.REGISTRY.getObject(new ResourceLocation(name));
                byIndex[i] = biome == null ? UNSET : Biome.getIdForBiome(biome);
            }
            if (byIndex.length == 0) {
                throw corrupt("has an empty biome palette");
            }
            int[] newIds = new int[SIZE];
            if (byIndex.length == 1) {
                Arrays.fill(newIds, byIndex[0]);
            } else {
                byte[] indices = chunkTag.getByteArray(INDICES);
                if (indices.length != SIZE) {
                    throw corrupt("has " + byIndex.length + " biome palette entries but no " + SIZE + "-byte " + INDICES + " array");
                }
                for (int i = 0; i < SIZE; i++) {
                    int index = indices[i] & 0xFF;
                    if (index >= byIndex.length) {
                        throw corrupt("refers to biome palette entry " + index + " of " + byIndex.length);
                    }
                    newIds[i] = byIndex[index];
                }
            }
            setAll(newIds);
            return true;
        }
        if (chunkTag.hasKey("Biomes")) {
            if (!chunkTag.hasKey("Biomes", 7)) {
                throw corrupt("has legacy Biomes data that is not a byte array");
            }
            byte[] legacy = chunkTag.getByteArray("Biomes");
            if (legacy.length != SIZE) {
                throw corrupt("has " + legacy.length + " legacy biome bytes instead of " + SIZE);
            }
            setLegacy(legacy);
            return true;
        }
        return false;
    }

    private IllegalStateException corrupt(String problem) {
        return new IllegalStateException("Chunk " + chunk.x + ", " + chunk.z + " " + problem + ".");
    }
}
