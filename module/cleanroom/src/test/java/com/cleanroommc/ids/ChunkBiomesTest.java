package com.cleanroommc.ids;

import net.minecraft.init.Biomes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ForgeTestRunner.Isolated
public class ChunkBiomesTest {
    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
    }

    private static Chunk chunk() {
        return new Chunk(null, 3, -2);
    }

    @Test
    public void byteArraysRequireExactlyOneChunkOfColumns() {
        for (int size : new int[] { 0, 255, 257 }) {
            ChunkBiomes biomes = chunk().getBiomeIds();
            biomes.set(0, Biome.getIdForBiome(Biomes.DESERT));
            int[] before = biomes.copy();
            assertThrows(IllegalArgumentException.class, () -> biomes.setLegacy(new byte[size]));
            assertThrows(IllegalArgumentException.class, () -> biomes.setAllFromBytes(new byte[size]));
            assertArrayEquals(before, biomes.copy());
        }
    }

    @Test
    public void oversizedPaletteIsRejectedWithoutChangingBiomes() {
        ChunkBiomes biomes = chunk().getBiomeIds();
        biomes.set(0, Biome.getIdForBiome(Biomes.DESERT));
        int[] before = biomes.copy();
        NBTTagList palette = new NBTTagList();
        for (int i = 0; i < 257; i++) {
            palette.appendTag(new NBTTagString(""));
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(ChunkBiomes.PALETTE, palette);
        tag.setByteArray(ChunkBiomes.INDICES, new byte[256]);
        assertThrows(IllegalStateException.class, () -> biomes.readNBT(tag));
        assertArrayEquals(before, biomes.copy());
    }

    private static NBTTagCompound referenceNBT(int[] ids) {
        var indexOf = new java.util.HashMap<Integer, Integer>();
        NBTTagList palette = new NBTTagList();
        byte[] indices = new byte[256];
        for (int i = 0; i < ids.length; i++) {
            Integer index = indexOf.get(ids[i]);
            if (index == null) {
                index = indexOf.size();
                indexOf.put(ids[i], index);
                Biome biome = ids[i] == ChunkBiomes.UNSET ? null : Biome.getBiome(ids[i]);
                palette.appendTag(new NBTTagString(biome == null ? "" : biome.getRegistryName().toString()));
            }
            indices[i] = index.byteValue();
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(ChunkBiomes.PALETTE, palette);
        if (palette.tagCount() > 1) {
            tag.setByteArray(ChunkBiomes.INDICES, indices);
        }
        return tag;
    }

    @Test
    public void serializationMatchesThePreviousLayoutThroughAll256Indices() {
        int[] wide = new int[256];
        for (int i = 0; i < wide.length; i++) {
            Biome biome = new Biome(new Biome.BiomeProperties("palette_" + i)) {};
            biome.setRegistryName(new ResourceLocation("wideids_test", "palette_" + i));
            ForgeRegistries.BIOMES.register(biome);
            wide[i] = Biome.getIdForBiome(biome);
        }
        assertTrue(java.util.Arrays.stream(wide).anyMatch(id -> id > 255));
        ChunkBiomes biomes = chunk().getBiomeIds();
        for (int distinct : new int[] { 1, 2, 16, 127, 128, 255, 256 }) {
            int[] ids = new int[256];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = wide[i % distinct];
            }
            biomes.setAll(ids);
            NBTTagCompound actual = new NBTTagCompound();
            biomes.writeNBT(actual);
            assertEquals(referenceNBT(ids), actual, "palette size " + distinct);
            ChunkBiomes back = chunk().getBiomeIds();
            assertTrue(back.readNBT(actual));
            assertArrayEquals(ids, back.copy());
        }
        wide[0] = ChunkBiomes.UNSET;
        biomes.setAll(wide);
        NBTTagCompound mixed = new NBTTagCompound();
        biomes.writeNBT(mixed);
        assertEquals(referenceNBT(wide), mixed);
    }

    @Test
    public void corruptLegacyArrayIsRejectedWithoutReplacingBiomes() {
        ChunkBiomes biomes = chunk().getBiomeIds();
        biomes.set(0, Biome.getIdForBiome(Biomes.DESERT));
        int[] before = biomes.copy();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByteArray("Biomes", new byte[255]);
        assertThrows(IllegalStateException.class, () -> biomes.readNBT(tag));
        assertArrayEquals(before, biomes.copy());
    }

    @Test
    public void malformedPaletteDoesNotFallThroughToLegacyData() {
        ChunkBiomes biomes = chunk().getBiomeIds();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger(ChunkBiomes.PALETTE, 1);
        tag.setByteArray("Biomes", new byte[256]);
        assertThrows(IllegalStateException.class, () -> biomes.readNBT(tag));
        NBTTagList wrongEntries = new NBTTagList();
        wrongEntries.appendTag(new NBTTagInt(0));
        tag.setTag(ChunkBiomes.PALETTE, wrongEntries);
        assertThrows(IllegalStateException.class, () -> biomes.readNBT(tag));
    }

    @Test
    public void uniformWriteRemovesOldBiomeIndices() {
        ChunkBiomes biomes = chunk().getBiomeIds();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByteArray(ChunkBiomes.INDICES, new byte[256]);
        biomes.writeNBT(tag);
        assertFalse(tag.hasKey(ChunkBiomes.INDICES));
    }

    @Test
    public void startsUnsetAndKeepsTheByteViewInStep() {
        Chunk chunk = chunk();
        ChunkBiomes biomes = chunk.getBiomeIds();
        assertEquals(ChunkBiomes.UNSET, biomes.get(0));
        int desert = Biome.getIdForBiome(Biomes.DESERT);
        biomes.set(5, 7, desert);
        assertEquals(desert, biomes.get(5, 7));
        assertEquals((byte) desert, chunk.getBiomeArray()[7 << 4 | 5]);
        assertEquals((byte) 0xFF, chunk.getBiomeArray()[0]);
    }

    @Test
    public void picksUpBytesWrittenIntoTheOldArray() {
        Chunk chunk = chunk();
        int jungle = Biome.getIdForBiome(Biomes.JUNGLE);
        byte[] view = chunk.getBiomeArray();
        for (int i = 0; i < 256; i++) {
            ChunkBiomes.storeByte(view, i, jungle); // what transformed mod code calls (I2B dropped)
        }
        for (int i = 0; i < 256; i++) {
            assertEquals(jungle, chunk.getBiomeIds().get(i));
        }
        ChunkBiomes.storeByte(view, 17, (byte) 0xFF);
        assertEquals(ChunkBiomes.UNSET, chunk.getBiomeIds().get(17));
        chunk.setBiomeArray(new byte[256]);
        assertEquals(Biome.getIdForBiome(Biomes.OCEAN), chunk.getBiomeIds().get(17));
    }

    /** A biome past 255 whose low byte another biome also has, registering the extra biomes if this test runs first. */
    private static int wideBiomeSharingALowByte() {
        for (int i = 0; i < 256; i++) {
            ResourceLocation name = new ResourceLocation("wideids_test", "palette_" + i);
            if (!ForgeRegistries.BIOMES.containsKey(name)) {
                Biome biome = new Biome(new Biome.BiomeProperties("palette_" + i)) {};
                biome.setRegistryName(name);
                ForgeRegistries.BIOMES.register(biome);
            }
        }
        for (Biome biome : ForgeRegistries.BIOMES) {
            int id = Biome.getIdForBiome(biome);
            if (id > 255 && net.minecraftforge.registries.GameData.getBiomeIdsWithLowByte(id & 0xFF).length > 1) {
                return id;
            }
        }
        throw new AssertionError("no wide biome shares a low byte with another");
    }

    @Test
    public void byteWritesResolveToTheBiomeAlreadyInTheChunk() {
        int wide = wideBiomeSharingALowByte();
        int[] sharing = net.minecraftforge.registries.GameData.getBiomeIdsWithLowByte(wide & 0xFF);
        assertTrue(sharing.length > 1);
        assertTrue(sharing[0] != wide, "the lowest candidate is not the wide one, so a naive pick would be wrong");

        Chunk chunk = chunk();
        chunk.getBiomeIds().set(0, 0, wide); // the proper API put the wide biome here
        byte[] view = chunk.getBiomeArray();
        assertEquals((byte) wide, view[0]);

        // Read-modify-write, the common mod pattern, as transformed bytecode calls it
        for (int i = 1; i < 16; i++) {
            ChunkBiomes.storeByte(view, i, ChunkBiomes.loadByte(view, 0));
        }
        for (int i = 0; i < 16; i++) {
            assertEquals(wide, chunk.getBiomeIds().get(i), "column " + i + " resolves through the chunk, not to the lowest id " + sharing[0]);
        }

        // Round trip: the save holds the wide biome's name, not the lowest candidate's
        NBTTagCompound tag = new NBTTagCompound();
        chunk.getBiomeIds().writeNBT(tag);
        ChunkBiomes back = chunk().getBiomeIds();
        assertTrue(back.readNBT(tag));
        for (int i = 0; i < 16; i++) {
            assertEquals(wide, back.get(i));
        }
    }

    @Test
    public void byteWriteOfAWideBiomeNewToAnEmptyChunkFallsBackToTheLowestCandidate() {
        int wide = wideBiomeSharingALowByte();
        int[] sharing = net.minecraftforge.registries.GameData.getBiomeIdsWithLowByte(wide & 0xFF);
        Chunk chunk = chunk(); // world is null: no provider to consult either
        ChunkBiomes.storeByte(chunk.getBiomeArray(), 0, (byte) wide); // the truncated byte path
        assertEquals(sharing[0], chunk.getBiomeIds().get(0), "nothing in the chunk or the provider to disambiguate: a byte can't carry this biome");
    }

    @Test
    public void savesNamesAndReadsBothFormats() {
        Chunk chunk = chunk();
        int[] ids = new int[256];
        for (int i = 0; i < 256; i++) {
            ids[i] = i % 3 == 0 ? ChunkBiomes.UNSET : Biome.getIdForBiome(i % 3 == 1 ? Biomes.PLAINS : Biomes.SWAMPLAND);
        }
        chunk.getBiomeIds().setAll(ids);
        NBTTagCompound tag = new NBTTagCompound();
        chunk.getBiomeIds().writeNBT(tag);
        assertEquals(3, tag.getTagList(ChunkBiomes.PALETTE, 8).tagCount());
        assertTrue(tag.getTagList(ChunkBiomes.PALETTE, 8).getStringTagAt(1).startsWith("minecraft:"));
        Chunk back = chunk();
        assertTrue(back.getBiomeIds().readNBT(tag));
        assertArrayEquals(ids, back.getBiomeIds().copy());

        NBTTagCompound single = new NBTTagCompound();
        Chunk uniform = chunk();
        int[] all = new int[256];
        java.util.Arrays.fill(all, Biome.getIdForBiome(Biomes.TAIGA));
        uniform.getBiomeIds().setAll(all);
        uniform.getBiomeIds().writeNBT(single);
        assertFalse(single.hasKey(ChunkBiomes.INDICES), "one biome needs no indices");

        NBTTagCompound legacy = new NBTTagCompound();
        byte[] bytes = new byte[256];
        java.util.Arrays.fill(bytes, (byte) Biome.getIdForBiome(Biomes.BEACH));
        bytes[3] = (byte) 0xFF;
        legacy.setByteArray("Biomes", bytes);
        Chunk old = chunk();
        assertTrue(old.getBiomeIds().readNBT(legacy));
        assertEquals(Biome.getIdForBiome(Biomes.BEACH), old.getBiomeIds().get(0));
        assertEquals(ChunkBiomes.UNSET, old.getBiomeIds().get(3));
        assertFalse(chunk().getBiomeIds().readNBT(new NBTTagCompound()));
    }
}
