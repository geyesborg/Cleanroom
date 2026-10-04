package net.minecraftforge.registries;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runtime block state numbers ({@link BlockStateIdMap}): consecutive, one per valid state, in block registry id
 * order, and identical after a rebuild.
 */
@ForgeTestRunner.Isolated
public class BlockStateIdMapTest
{
    @BeforeAll
    public static void setup()
    {
        System.setProperty("fml.queryResult", "confirm");
        System.setProperty("fml.doNotBackup", "true");
        Loader.instance();
        Bootstrap.register();
        Loader.instance().setupTestHarness(new DummyModContainer(new ModMetadata()
        {{
            modId = "test";
        }}));
    }

    private static List<IBlockState> expectedOrder()
    {
        List<IBlockState> states = new ArrayList<>();
        for (Block block : RegistryManager.ACTIVE.getRegistry(Block.class))
            states.addAll(block.getBlockState().getValidStates());
        return states;
    }

    @Test
    public void numbersEveryValidStateConsecutivelyInRegistryOrder()
    {
        List<IBlockState> expected = expectedOrder();
        assertEquals(expected.size(), Block.BLOCK_STATE_IDS.size());
        for (int i = 0; i < expected.size(); i++)
        {
            IBlockState state = expected.get(i);
            assertSame(state, Block.BLOCK_STATE_IDS.getByValue(i), "state at " + i);
            assertEquals(i, Block.BLOCK_STATE_IDS.get(state), "number of " + state);
        }
        assertEquals(0, Block.BLOCK_STATE_IDS.get(Blocks.AIR.getDefaultState()), "air keeps 0, the value of an empty ChunkPrimer");
    }

    @Test
    public void stateIdsRoundTripAndKeepTheOldEncodingWhereItFits()
    {
        for (IBlockState state : expectedOrder())
            assertSame(state, Block.getStateById(Block.getStateId(state)), String.valueOf(state));
        // Mods send the block id alone as a meta-0 state (world event 2001)
        assertEquals(Block.getIdFromBlock(Blocks.GRASS), Block.getStateId(Blocks.GRASS.getDefaultState()));
        assertSame(Blocks.WATER.getDefaultState(), Block.getStateById(Block.getIdFromBlock(Blocks.WATER)));
        assertEquals(Block.getIdFromBlock(Blocks.WOOL) + (14 << 12), Block.getStateId(Blocks.WOOL.getStateFromMeta(14)));
    }

    @Test
    public void storesTheNumberInTheState()
    {
        Block.BLOCK_STATE_IDS.get(Blocks.STONE.getDefaultState());
        for (IBlockState state : Block.BLOCK_STATE_IDS)
        {
            if (state instanceof BlockStateContainer.StateImplementation impl)
                assertEquals(Block.BLOCK_STATE_IDS.get(state), impl.runtimeStateId, String.valueOf(state));
        }
    }

    @Test
    public void rebuildGivesTheSameNumbers()
    {
        List<IBlockState> before = new ArrayList<>();
        Block.BLOCK_STATE_IDS.forEach(before::add);
        ((BlockStateIdMap) Block.BLOCK_STATE_IDS).invalidate();
        List<IBlockState> after = new ArrayList<>();
        Block.BLOCK_STATE_IDS.forEach(after::add);
        assertEquals(before, after);
    }

    @Test
    public void unknownNumbersAreAirAndOutOfRangeIsNull()
    {
        int size = Block.BLOCK_STATE_IDS.size();
        assertEquals(null, Block.BLOCK_STATE_IDS.getByValue(size));
        assertEquals(null, Block.BLOCK_STATE_IDS.getByValue(-1));
        assertSame(Blocks.AIR.getDefaultState(), Block.getStateById(size));
    }

    @Test
    public void addingStatesByHandIsRejected()
    {
        assertThrows(UnsupportedOperationException.class, () -> Block.BLOCK_STATE_IDS.put(Blocks.STONE.getDefaultState(), 5));
    }
}
