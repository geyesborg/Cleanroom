package com.cleanroommc.ids;

import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@ForgeTestRunner.Isolated
public class StandInsTest {
    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
        StandIns.load(null);
    }

    @AfterAll
    public static void cleanup() {
        StandIns.load(null);
    }

    @Test
    public void defaultTableCoversGroundBlocksAndSkipsPlants() {
        IBlockState leaves = StandIns.forMissing("gone:leaves_2[decayable=true,variant=redwood]");
        assertEquals(Blocks.LEAVES, leaves.getBlock());
        assertEquals(false, leaves.getValue(BlockLeaves.DECAYABLE));
        assertEquals(false, leaves.getValue(BlockLeaves.CHECK_DECAY));

        assertEquals(Blocks.LOG.getDefaultState().withProperty(BlockLog.LOG_AXIS, BlockLog.EnumAxis.Z),
                StandIns.forMissing("gone:log_0[axis=z,variant=cherry]"));
        assertEquals(Blocks.PLANKS.getDefaultState(), StandIns.forMissing("gone:planks_0[variant=willow]"));
        assertEquals(Blocks.OAK_STAIRS.getDefaultState()
                        .withProperty(BlockStairs.FACING, EnumFacing.EAST)
                        .withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.TOP),
                StandIns.forMissing("gone:stairs[facing=east,half=top,shape=straight]"));
        assertEquals(Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.TOP),
                StandIns.forMissing("gone:slab[half=top]"));
        assertEquals(Blocks.DOUBLE_WOODEN_SLAB.getDefaultState(), StandIns.forMissing("gone:double_slab[]"));
        assertEquals(Blocks.OAK_FENCE.getDefaultState(), StandIns.forMissing("gone:fence_0[]"));
        IBlockState fenceGate = StandIns.forMissing("gone:fence_gate[facing=east,open=true]");
        assertEquals(Blocks.OAK_FENCE_GATE, fenceGate.getBlock());
        assertEquals(Blocks.GRASS.getDefaultState(), StandIns.forMissing("gone:grass[snowy=false,variant=loamy]"));
        assertEquals(Blocks.DIRT.getDefaultState().withProperty(BlockDirt.VARIANT, BlockDirt.DirtType.COARSE_DIRT),
                StandIns.forMissing("gone:dirt[coarse=true,variant=loamy]"));
        assertEquals(Blocks.DIRT.getDefaultState(), StandIns.forMissing("gone:mud[]"));
        assertEquals(Blocks.SAND.getDefaultState(), StandIns.forMissing("gone:sand_0[variant=white]"));
        assertEquals(Blocks.SANDSTONE.getDefaultState(), StandIns.forMissing("gone:sandstone[type=smooth]"));
        assertEquals(Blocks.GRAVEL.getDefaultState(), StandIns.forMissing("gone:gravel[]"));
        assertEquals(Blocks.STONE.getDefaultState(), StandIns.forMissing("gone:gem_ore[variant=sapphire]"));
        assertEquals(Blocks.STONE.getDefaultState(), StandIns.forMissing("gone:rock_0[variant=shale]"));
    }

    @Test
    public void plantsFluidsAndUnmatchedReadAsAir() {
        assertNull(StandIns.forMissing("gone:double_plant[half=lower,variant=sea_oats]"));
        assertNull(StandIns.forMissing("gone:flower_1[variant=x]"));
        assertNull(StandIns.forMissing("gone:poison[level=0]"));
        assertNull(StandIns.forMissing("gone:willow_vine[north=true]"));
        assertNull(StandIns.forMissing("gone:mushroom[variant=brown]"));
        assertNull(StandIns.forMissing("gone:tall_grass[type=x]"));
        assertNull(StandIns.forMissing("gone:sapling[variant=palm]"));
    }

    @Test
    public void configOverridesApplyBeforeTheDefaults() throws Exception {
        Path file = Files.createTempFile("retained_standins", ".cfg");
        Files.write(file, List.of("# comment", "sapphire = minecraft:lapis_ore", "leaves = air", "not a line"));
        StandIns.load(file);
        assertEquals(Blocks.LAPIS_ORE.getDefaultState(), StandIns.forMissing("gone:sapphire_ore[variant=x]"));
        assertNull(StandIns.forMissing("gone:leaves_0[variant=x]"));
        // the defaults still apply to paths no override matched
        assertEquals(Blocks.STONE.getDefaultState(), StandIns.forMissing("gone:gem_ore[]"));
        StandIns.load(null);
        assertEquals(Blocks.LEAVES.getDefaultState().getBlock(), StandIns.forMissing("gone:leaves_0[]").getBlock());
        Files.deleteIfExists(file);
    }

    @Test
    public void defaultBiomeTable() {
        assertSame(Biomes.ROOFED_FOREST, StandIns.forMissingBiome("biomesoplenty:ominous_woods"));
        assertSame(Biomes.SWAMPLAND, StandIns.forMissingBiome("biomesoplenty:bayou"));
        assertSame(Biomes.JUNGLE, StandIns.forMissingBiome("traverse:mini_jungle"));
        assertSame(Biomes.ICE_PLAINS, StandIns.forMissingBiome("byg:alps"));
        assertSame(Biomes.MESA, StandIns.forMissingBiome("biomesoplenty:outback"));
        assertSame(Biomes.FOREST, StandIns.forMissingBiome("biomesoplenty:cherry_blossom_grove"));
        assertSame(Biomes.PLAINS, StandIns.forMissingBiome("biomesoplenty:orchard"));
        assertSame(Biomes.TAIGA, StandIns.forMissingBiome("biomesoplenty:redwood_forest"));
        assertNull(StandIns.forMissingBiome("biomesoplenty:xyzzy"));
        assertSame(Biomes.OCEAN, StandIns.forMissingBiome("biomesoplenty:coral_reef"));
        assertSame(Biomes.ICE_PLAINS, StandIns.forMissingBiome("biomesoplenty:snowy_coniferous_forest"));
        assertSame(Biomes.ROOFED_FOREST, StandIns.forMissingBiome("twilightforest:dark_forest"));
        // "sea" matches (ocean group) before "dead" (roofed group) — a dead sea is still an ocean column
        assertSame(Biomes.OCEAN, StandIns.forMissingBiome("byg:dead_sea"));
    }

    @Test
    public void biomeConfigOverridesApplyBeforeTheDefaults() throws Exception {
        Path file = Files.createTempFile("retained_standins", ".cfg");
        Files.write(file, List.of("biome:ominous = minecraft:swampland", "biome:bayou = none"));
        StandIns.load(file);
        assertSame(Biomes.SWAMPLAND, StandIns.forMissingBiome("biomesoplenty:ominous_woods"));
        assertNull(StandIns.forMissingBiome("biomesoplenty:bayou"));
        StandIns.load(null);
        assertSame(Biomes.SWAMPLAND, StandIns.forMissingBiome("biomesoplenty:bayou"));
        Files.deleteIfExists(file);
    }
}
