package com.cleanroommc.ids;

import net.minecraft.init.Biomes;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ForgeTestRunner.Isolated
public class ChunkBiomesTest {
    @BeforeAll
    public static void setup() {
        System.setProperty("fml.queryResult", "confirm");
        System.setProperty("fml.doNotBackup", "true");
        Loader.instance();
        Bootstrap.register();
        Loader.instance().setupTestHarness(new DummyModContainer(new ModMetadata() {{
            modId = "test";
        }}));
    }

    private static Chunk chunk() {
        return new Chunk(null, 3, -2);
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
            view[i] = (byte) jungle; // what chunk generators and biome-changing mods do
        }
        for (int i = 0; i < 256; i++) {
            assertEquals(jungle, chunk.getBiomeIds().get(i));
        }
        view[17] = (byte) 0xFF;
        assertEquals(ChunkBiomes.UNSET, chunk.getBiomeIds().get(17));
        chunk.setBiomeArray(new byte[256]);
        assertEquals(Biome.getIdForBiome(Biomes.OCEAN), chunk.getBiomeIds().get(17));
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
