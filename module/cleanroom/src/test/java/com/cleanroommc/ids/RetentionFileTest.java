package com.cleanroommc.ids;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.init.Blocks;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RetentionFile}: the record of a world's content and the Retain/Remove answers it keeps. */
@ForgeTestRunner.Isolated
public class RetentionFileTest {

    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
    }

    @AfterEach
    public void tearDown() {
        RetainedContent.clearWorldState();
        System.clearProperty("cleanroom.missingContent");
    }

    @Test
    public void roundTripKeepsModdedNamesModsDecisionsAndGenerator(@TempDir Path dir) throws Exception {
        RetentionFile out = new RetentionFile();
        out.registries.put("blocks", List.of("gone_mod:crystal", "gone_mod:opal"));
        out.registries.put("items", List.of("gone_mod:wand"));
        out.mods.put("gonemod", "Gone Mod 1.2.3");
        out.decisions.put("gonemod", RetentionFile.RETAIN);
        out.generator = "GONETYPE";
        out.write(dir.toFile());

        RetentionFile in = RetentionFile.read(dir.toFile());
        assertNotNull(in);
        assertEquals(List.of("gone_mod:crystal", "gone_mod:opal"), in.registries.get("blocks"));
        assertEquals(List.of("gone_mod:wand"), in.registries.get("items"));
        assertEquals("Gone Mod 1.2.3", in.mods.get("gonemod"));
        assertEquals(RetentionFile.RETAIN, in.decisions.get("gonemod"));
        assertEquals("GONETYPE", in.generator);
    }

    @Test
    public void captureExcludesVanillaNames() {
        WorldInfo info = new WorldInfo(new WorldSettings(0L, GameType.CREATIVE, false, false, WorldType.DEFAULT), "test");
        RetentionFile file = RetentionFile.capture(info);
        for (List<String> names : file.registries.values()) {
            for (String name : names) {
                assertFalse(name.startsWith("minecraft:"), name);
            }
        }
        assertEquals("default", file.generator);
    }

    @Test
    public void diffGroupsMissingNamesByNamespaceWithCounts() {
        RetentionFile file = new RetentionFile();
        file.registries.put("blocks", List.of("gone_mod:crystal", "gone_mod:opal", "minecraft:stone"));
        file.registries.put("items", List.of("gone_mod:wand"));
        file.registries.put("biomes", List.of("gone_mod:bog", "other_mod:fen"));

        Map<String, Map<String, Integer>> missing = file.missingByNamespace();
        assertEquals(2, missing.get("gone_mod").get("blocks").intValue());
        assertEquals(1, missing.get("gone_mod").get("items").intValue());
        assertEquals(1, missing.get("gone_mod").get("biomes").intValue());
        assertEquals(1, missing.get("other_mod").get("biomes").intValue());
        assertFalse(missing.containsKey("minecraft"), "minecraft:stone resolves, so the namespace is not missing");
    }

    @Test
    public void returnedNamespaceDropsItsDecision(@TempDir Path dir) {
        // the file remembers a "remove" answer for gonemod, but gonemod:stone resolves now (stone is registered)
        RetentionFile out = new RetentionFile();
        out.registries.put("blocks", List.of("minecraft:stone")); // stands in for a name that resolves again
        out.decisions.put("minecraft", RetentionFile.REMOVE);
        out.write(dir.toFile());

        RetentionFile.onWorldLoad(dir.toFile(), plainInfo());

        assertFalse(RetainedContent.retentionDecisions().containsKey("minecraft"),
                "a namespace whose names all resolve again loses its decision");
        assertTrue(RetainedContent.removedNamespaces().isEmpty());
    }

    @Test
    public void decidedRemoveAppliesSilently(@TempDir Path dir) {
        RetentionFile out = new RetentionFile();
        out.registries.put("blocks", List.of("gone_mod:crystal"));
        out.decisions.put("gone_mod", RetentionFile.REMOVE);
        out.write(dir.toFile());

        RetentionFile.onWorldLoad(dir.toFile(), plainInfo());

        assertTrue(RetainedContent.isRemoved("gone_mod:crystal[glow=true]"));
        assertEquals(RetentionFile.REMOVE, RetainedContent.retentionDecisions().get("gone_mod"));
    }

    @Test
    public void removedNamespaceMakesBlocksAirWithoutRetention(@TempDir Path dir) {
        RetentionFile out = new RetentionFile();
        out.registries.put("blocks", List.of("gone_mod:crystal"));
        out.decisions.put("gone_mod", RetentionFile.REMOVE);
        out.write(dir.toFile());
        RetentionFile.onWorldLoad(dir.toFile(), plainInfo());

        // a section whose whole palette is the missing block
        NBTTagCompound chunk = new NBTTagCompound();
        NBTTagList palette = new NBTTagList();
        palette.appendTag(new NBTTagString("gone_mod:crystal[glow=true]"));
        chunk.setTag(SectionBlockFormat.CHUNK_PALETTE, palette);
        SectionBlockFormat.ChunkPaletteRead read = SectionBlockFormat.readChunkPalette(chunk);
        assertNull(read.unresolved(), "removed namespaces leave nothing to retain");

        BlockStateContainer data = new BlockStateContainer();
        NBTTagCompound section = new NBTTagCompound();
        section.setIntArray(SectionBlockFormat.PALETTE, new int[] { 0 });
        RetainedContent.Section retained = new RetainedContent.Section();
        SectionBlockFormat.read(section, data, read, retained, null);
        assertTrue(retained.isEmpty(), "no retained cell");
        assertEquals(Blocks.AIR.getDefaultState(), data.get(0, 0, 0), "removed content reads as air");
    }

    @Test
    public void removedNamespaceMakesItemsEmpty() {
        RetainedContent.removedNamespaces().add("gone_mod");
        com.cleanroommc.ids.MissingItem.INSTANCE = new MissingItem();
        NBTTagCompound stack = new NBTTagCompound();
        stack.setString("id", "gone_mod:wand");
        stack.setByte("Count", (byte) 1);
        ItemStack s = new ItemStack(stack);
        assertTrue(s.isEmpty(), "a removed namespace's items read as EMPTY");
        RetainedContent.removedNamespaces().clear();
    }

    @Test
    public void removedNamespaceBiomeFallsToTheProvider(@TempDir Path dir) {
        RetentionFile out = new RetentionFile();
        out.registries.put("biomes", List.of("gone_mod:bog"));
        out.decisions.put("gone_mod", RetentionFile.REMOVE);
        out.write(dir.toFile());
        RetentionFile.onWorldLoad(dir.toFile(), plainInfo());

        ChunkBiomes biomes = chunkBiomesWithPalette(List.of("gone_mod:bog"));
        NBTTagCompound written = new NBTTagCompound();
        biomes.writeNBT(written);
        NBTTagList palette = written.getTagList(ChunkBiomes.PALETTE, 8);
        for (int i = 0; i < palette.tagCount(); i++) {
            assertFalse(palette.getStringTagAt(i).startsWith("gone_mod:"), "a removed biome name is not retained");
        }
    }

    @Test
    public void corruptFileIsNoInformation(@TempDir Path dir) throws Exception {
        java.nio.file.Files.write(dir.resolve(RetentionFile.FILE_NAME), new byte[] { 1, 2, 3, 4 });
        assertNull(RetentionFile.read(dir.toFile()));
        // and the load proceeds without a prompt or an exception
        RetentionFile.onWorldLoad(dir.toFile(), plainInfo());
        assertTrue(RetainedContent.removedNamespaces().isEmpty());
    }

    @Test
    public void missingFileIsNoInformation(@TempDir Path dir) {
        assertNull(RetentionFile.read(dir.toFile()));
        RetentionFile.onWorldLoad(dir.toFile(), plainInfo());
    }

    private static WorldInfo plainInfo() {
        return new WorldInfo(new WorldSettings(0L, GameType.CREATIVE, false, false, WorldType.DEFAULT), "test");
    }

    private static ChunkBiomes chunkBiomesWithPalette(List<String> names) {
        net.minecraft.world.chunk.Chunk chunk = new net.minecraft.world.chunk.Chunk(null, 0, 0);
        NBTTagCompound chunkTag = new NBTTagCompound();
        NBTTagList palette = new NBTTagList();
        for (String n : names) {
            palette.appendTag(new NBTTagString(n));
        }
        chunkTag.setTag(ChunkBiomes.PALETTE, palette);
        if (names.size() > 1) {
            chunkTag.setByteArray(ChunkBiomes.INDICES, new byte[256]);
        }
        chunk.getBiomeIds().readNBT(chunkTag);
        return chunk.getBiomeIds();
    }
}
