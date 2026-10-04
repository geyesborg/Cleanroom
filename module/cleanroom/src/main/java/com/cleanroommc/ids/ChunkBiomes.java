package com.cleanroommc.ids;

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
import java.util.HashMap;
import java.util.Map;

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
    private static boolean warnedAmbiguous;

    private final Chunk chunk;
    private final byte[] view;
    private final byte[] seen = new byte[SIZE];
    private final int[] ids = new int[SIZE];

    public ChunkBiomes(Chunk chunk, byte[] view) {
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
        int[] newIds = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            newIds[i] = bytes[i] == (byte) 0xFF ? UNSET : bytes[i] & 0xFF;
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

    private int resolve(int lowByte, int index) {
        if (lowByte == 0xFF) {
            return UNSET;
        }
        int[] candidates = GameData.getBiomeIdsWithLowByte(lowByte);
        if (candidates.length <= 1) {
            return candidates.length == 0 ? UNSET : candidates[0];
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
        if (!warnedAmbiguous) {
            warnedAmbiguous = true;
            LOGGER.warn("A biome was written into Chunk.getBiomeArray() as byte {}, which {} biomes share; using id {}. Mods that set biomes should use Chunk.getBiomeIds() (logged once).",
                    lowByte, candidates.length, candidates[0]);
        }
        return candidates[0];
    }

    public void writeNBT(NBTTagCompound chunkTag) {
        sync();
        Map<Integer, Integer> indexOf = new HashMap<>();
        NBTTagList palette = new NBTTagList();
        byte[] indices = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) {
            Integer index = indexOf.get(ids[i]);
            if (index == null) {
                index = indexOf.size();
                indexOf.put(ids[i], index);
                Biome biome = ids[i] == UNSET ? null : Biome.getBiome(ids[i]);
                ResourceLocation name = biome == null ? null : biome.getRegistryName();
                palette.appendTag(new NBTTagString(name == null ? "" : name.toString()));
            }
            indices[i] = (byte) (int) index;
        }
        chunkTag.setTag(PALETTE, palette);
        if (palette.tagCount() > 1) {
            chunkTag.setByteArray(INDICES, indices);
        }
    }

    /** Reads either format; returns false when the chunk has no biome data. */
    public boolean readNBT(NBTTagCompound chunkTag) {
        if (chunkTag.hasKey(PALETTE, 9)) {
            NBTTagList palette = chunkTag.getTagList(PALETTE, 8);
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
        if (chunkTag.hasKey("Biomes", 7)) {
            byte[] legacy = chunkTag.getByteArray("Biomes");
            if (legacy.length == SIZE) {
                setLegacy(legacy);
            }
            return true;
        }
        return false;
    }

    private IllegalStateException corrupt(String problem) {
        return new IllegalStateException("Chunk " + chunk.x + ", " + chunk.z + " " + problem + ".");
    }
}
