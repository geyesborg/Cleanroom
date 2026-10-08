package net.minecraftforge.registries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ObjectIntIdentityMap;
import net.minecraftforge.common.property.IExtendedBlockState;

/**
 * Runtime numbering of block states ({@code Block.BLOCK_STATE_IDS}). Every valid state of every block gets a
 * consecutive number: blocks in registry id order, each block's states in {@code getValidStates()} order. Both
 * sides of a connection share the block registry ids, so they compute the same numbers. The numbers are only used
 * at runtime and on the network; saves use {@link GameData#getLegacyBlockStateIDMap()} or names.
 * <p>
 * The table is rebuilt lazily after the block registry changes. The active registry's map also stores each
 * state's number in {@link BlockStateContainer.StateImplementation#runtimeStateId}, which turns the lookup into a
 * field read.
 */
final class BlockStateIdMap extends ObjectIntIdentityMap<IBlockState>
{
    private record Table(int generation, IdentityHashMap<IBlockState, Integer> ids, IBlockState[] states) {}

    private final ForgeRegistry<Block> registry;
    private final boolean storeInStates;
    @Nullable
    private volatile Table table;

    BlockStateIdMap(ForgeRegistry<Block> registry, boolean storeInStates)
    {
        super(0);
        this.registry = registry;
        this.storeInStates = storeInStates;
    }

    /** Synchronized with {@link #rebuild()}: a rebuild that read the registry before a change can't publish after it. */
    synchronized void invalidate()
    {
        table = null;
    }

    private Table table()
    {
        Table t = table;
        return t != null && t.generation == registry.generation() ? t : rebuild();
    }

    private synchronized Table rebuild()
    {
        Table t = table;
        int generation = registry.generation();
        if (t != null && t.generation == generation)
            return t;
        IdentityHashMap<IBlockState, Integer> ids = new IdentityHashMap<>();
        List<IBlockState> states = new ArrayList<>();
        for (Block block : registry)
        {
            for (IBlockState state : block.getBlockState().getValidStates())
            {
                if (ids.putIfAbsent(state, states.size()) == null)
                    states.add(state);
            }
        }
        IBlockState[] byId = states.toArray(new IBlockState[0]);
        if (storeInStates)
        {
            for (int i = 0; i < byId.length; i++)
            {
                if (byId[i] instanceof BlockStateContainer.StateImplementation impl)
                    impl.runtimeStateId = i;
            }
        }
        t = new Table(generation, ids, byId);
        table = t;
        return t;
    }

    @Override
    public void put(IBlockState key, int value)
    {
        throw new UnsupportedOperationException("Block state ids are assigned from the block registry; register the block instead of adding states to Block.BLOCK_STATE_IDS.");
    }

    @SuppressWarnings("deprecation")
    @Override
    public int get(IBlockState key)
    {
        Table t = table();
        if (key == null)
            return -1;
        if (storeInStates && key instanceof BlockStateContainer.StateImplementation impl)
        {
            int id = impl.runtimeStateId;
            // A replaced block's states keep the number from before the registry changed
            if (id >= 0 && id < t.states.length && t.states[id] == key)
                return id;
        }
        Integer id = t.ids.get(key);
        if (id != null)
            return id;
        // States outside getValidStates(): extended states wrap a listed one, and some mods build actual states
        // by hand; their metadata state is the closest listed one (Forge's fallback before numbering)
        if (key instanceof IExtendedBlockState extended && extended.getClean() != key)
            return get(extended.getClean());
        Block block = key.getBlock();
        id = t.ids.get(block.getStateFromMeta(block.getMetaFromState(key)));
        return id == null ? -1 : id;
    }

    @Nullable
    @Override
    public IBlockState getByValue(int value)
    {
        IBlockState[] states = table().states;
        return value >= 0 && value < states.length ? states[value] : null;
    }

    @Override
    public Iterator<IBlockState> iterator()
    {
        return Collections.unmodifiableList(Arrays.asList(table().states)).iterator();
    }

    @Override
    public int size()
    {
        return table().states.length;
    }
}
