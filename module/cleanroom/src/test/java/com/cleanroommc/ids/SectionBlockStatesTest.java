package com.cleanroommc.ids;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The name format and section storage round-trip every vanilla block state. */
@ForgeTestRunner.Isolated
public class SectionBlockStatesTest {
    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
    }

    private static List<IBlockState> allStates() {
        List<IBlockState> states = new ArrayList<>();
        for (Block block : Block.REGISTRY) {
            states.addAll(block.getBlockState().getValidStates());
        }
        return states;
    }

    @Test
    public void uniformAirClearsAReusedContainer() {
        BlockStateContainer data = new BlockStateContainer();
        data.set(3, 2, 1, Blocks.STONE.getDefaultState());
        NBTTagCompound section = new NBTTagCompound();
        section.setIntArray(SectionBlockFormat.PALETTE, new int[] { 0 });
        SectionBlockFormat.read(section, data, new IBlockState[] { Blocks.AIR.getDefaultState() });
        for (int i = 0; i < 4096; i++) {
            assertSame(Blocks.AIR.getDefaultState(), data.get(i & 15, i >> 8, i >> 4 & 15));
        }
    }

    @Test
    public void badIndicesDoNotClearTheExistingContainer() {
        BlockStateContainer data = new BlockStateContainer();
        data.set(0, 0, 0, Blocks.STONE.getDefaultState());
        NBTTagCompound section = new NBTTagCompound();
        section.setIntArray(SectionBlockFormat.PALETTE, new int[] { 0, 1 });
        long[] packed = new long[SectionBlockFormat.longCount(4)];
        packed[0] = 2;
        section.setTag(SectionBlockFormat.STATES, new NBTTagLongArray(packed));
        assertThrows(IllegalStateException.class, () -> SectionBlockFormat.read(section, data,
                new IBlockState[] { Blocks.AIR.getDefaultState(), Blocks.STONE.getDefaultState() }));
        assertSame(Blocks.STONE.getDefaultState(), data.get(0, 0, 0));
    }

    @Test
    public void malformedPaletteTagsAreNotTreatedAsLegacy() {
        NBTTagCompound chunk = new NBTTagCompound();
        assertFalse(SectionBlockFormat.isPaletted(chunk));
        chunk.setInteger(SectionBlockFormat.CHUNK_PALETTE, 1);
        assertThrows(IllegalStateException.class, () -> SectionBlockFormat.isPaletted(chunk));
        assertThrows(IllegalStateException.class, () -> SectionBlockFormat.readChunkPalette(chunk));
        NBTTagList wrongEntries = new NBTTagList();
        wrongEntries.appendTag(new NBTTagInt(0));
        chunk.setTag(SectionBlockFormat.CHUNK_PALETTE, wrongEntries);
        assertThrows(IllegalStateException.class, () -> SectionBlockFormat.readChunkPalette(chunk));
    }

    @Test
    public void uniformWriteRemovesOldPackedStates() {
        NBTTagCompound section = new NBTTagCompound();
        section.setTag(SectionBlockFormat.STATES, new NBTTagLongArray(new long[SectionBlockFormat.longCount(4)]));
        SectionBlockFormat.write(new BlockStateContainer(), section, new SectionBlockFormat.ChunkPalette());
        assertFalse(section.hasKey(SectionBlockFormat.STATES));
    }

    @Test
    public void namesRoundTripEveryState() {
        for (IBlockState state : allStates()) {
            assertSame(state, SectionBlockFormat.parse(SectionBlockFormat.toString(state)), SectionBlockFormat.toString(state));
        }
        assertEquals("minecraft:stone[variant=granite]", SectionBlockFormat.toString(Blocks.STONE.getStateFromMeta(1)));
        assertSame(Blocks.AIR.getDefaultState(), SectionBlockFormat.parse("nomod:gone[x=1]"));
        assertSame(Blocks.STONE.getDefaultState(), SectionBlockFormat.parse("minecraft:stone[variant=nope,unknown=1]"));
    }

    /** Sections with 1, 2, 16, 17, 256 and 300 distinct states (4-bit copy, 5..8-bit local palettes, global ids). */
    @Test
    public void sectionsRoundTripAtEveryPaletteWidth() {
        List<IBlockState> states = allStates();
        for (int distinct : new int[] { 1, 2, 16, 17, 100, 256, 300 }) {
            BlockStateContainer data = new BlockStateContainer();
            for (int i = 0; i < 4096; i++) {
                data.set(i & 15, i >> 8, i >> 4 & 15, states.get(1 + i % distinct));
            }
            SectionBlockFormat.ChunkPalette palette = new SectionBlockFormat.ChunkPalette();
            NBTTagCompound section = new NBTTagCompound();
            SectionBlockFormat.write(data, section, palette);
            NBTTagCompound chunk = new NBTTagCompound();
            chunk.setTag(SectionBlockFormat.CHUNK_PALETTE, palette.toNBT());
            BlockStateContainer back = new BlockStateContainer();
            SectionBlockFormat.read(section, back, SectionBlockFormat.readChunkPalette(chunk));
            for (int i = 0; i < 4096; i++) {
                assertSame(data.get(i & 15, i >> 8, i >> 4 & 15), back.get(i & 15, i >> 8, i >> 4 & 15), distinct + " states, index " + i);
            }
        }
    }
}
