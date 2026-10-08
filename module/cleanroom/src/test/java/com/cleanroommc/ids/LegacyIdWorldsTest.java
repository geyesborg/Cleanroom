package com.cleanroommc.ids;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ForgeTestRunner.Isolated
public class LegacyIdWorldsTest {
    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
    }

    @Test
    public void jeidSectionRoundTripsThroughTheNumericPalette() {
        NBTTagCompound section = new NBTTagCompound();
        section.setByte("Y", (byte) 4);
        int stone = Block.getIdFromBlock(Blocks.STONE) << 4;
        int dirt = Block.getIdFromBlock(Blocks.DIRT) << 4 | 1;
        section.setIntArray(SectionBlockFormat.PALETTE, new int[] { stone, dirt });
        byte[] blocks = new byte[4096];
        byte[] data = new byte[2048];
        NibbleArray nibbles = new NibbleArray(data);
        blocks[0] = 0;
        blocks[1] = 0;
        nibbles.set(1, 0, 0, 1);
        section.setByteArray("Blocks", blocks);
        section.setByteArray("Data", data);
        assertTrue(SectionBlockFormat.isJeidSection(section));
        assertFalse(SectionBlockFormat.isNeidSection(section));
        var container = new net.minecraft.world.chunk.BlockStateContainer();
        SectionBlockFormat.readJeid(section, container);
        assertEquals(Blocks.STONE.getDefaultState(), container.get(0, 0, 0));
        assertEquals(Blocks.DIRT.getStateFromMeta(1), container.get(1, 0, 0));
    }

    @Test
    public void neidSectionUsesBlocks16() {
        NBTTagCompound section = new NBTTagCompound();
        byte[] blocks = new byte[8192];
        int id = Block.getIdFromBlock(Blocks.OBSIDIAN);
        blocks[0] = (byte) (id >> 8);
        blocks[1] = (byte) id;
        section.setByteArray(LegacyIdWorlds.BLOCKS16, blocks);
        section.setByteArray("Data", new byte[2048]);
        assertTrue(SectionBlockFormat.isNeidSection(section));
        var container = new net.minecraft.world.chunk.BlockStateContainer();
        SectionBlockFormat.readNeid(section, container);
        assertEquals(Blocks.OBSIDIAN.getDefaultState(), container.get(0, 0, 0));
    }

    @Test
    public void cleanroomPaletteIsNotTreatedAsJeid() {
        NBTTagCompound chunk = new NBTTagCompound();
        NBTTagList palette = new NBTTagList();
        palette.appendTag(new NBTTagString("minecraft:stone"));
        chunk.setTag(SectionBlockFormat.CHUNK_PALETTE, palette);
        NBTTagCompound section = new NBTTagCompound();
        section.setIntArray(SectionBlockFormat.PALETTE, new int[] { 0 });
        assertTrue(SectionBlockFormat.isPaletted(chunk));
        assertFalse(SectionBlockFormat.isJeidSection(section));
    }
}
