package com.cleanroommc.ids;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The name format and section storage round-trip every vanilla block state. */
@ForgeTestRunner.Isolated
public class SectionBlockStatesTest {
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

    private static List<IBlockState> allStates() {
        List<IBlockState> states = new ArrayList<>();
        for (Block block : Block.REGISTRY) {
            states.addAll(block.getBlockState().getValidStates());
        }
        return states;
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
