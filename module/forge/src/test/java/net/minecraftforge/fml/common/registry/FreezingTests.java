/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.common.registry;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.IForgeRegistryEntry;
import net.minecraftforge.registries.RegistryBuilder;
import net.minecraftforge.registries.RegistryManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Maps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

/**
 * Created by cpw on 04/07/16.
 */
@ForgeTestRunner.Isolated
public class FreezingTests
{

    private static RTest r1;
    private static RTest r2;
    private static RTest r3;
    private static RTest r4;
    private static RTest r5;
    private static RTest r6;
    private static Map<ResourceLocation, ForgeRegistry.Snapshot> ss;

    static class RTest extends IForgeRegistryEntry.Impl<RTest>
    {
        public RTest(String name)
        {
            setRegistryName(name);
        }
        @Override
        public String toString()
        {
            return this.getRegistryName().toString();
        }
    }

    public static ResourceLocation resloc = new ResourceLocation("fmltest:test");

    @BeforeAll
    public static void setup()
    {
        Loader.instance();
        System.setProperty("fml.queryResult", "confirm");
        System.setProperty("fml.doNotBackup", "true");
        System.setProperty("forge.disableVanillaGameData", "true");

        new RegistryBuilder<RTest>().setName(resloc).setType(RTest.class).setIDRange(0, 255).create();
        new RegistryBuilder<Block>().setName(GameData.BLOCKS).setType(Block.class).setIDRange(0, 255).create();
        new RegistryBuilder<Item>().setName(GameData.ITEMS).setType(Item.class).setIDRange(0, 255).create();

        r1 = new RTest("test1");
        r2 = new RTest("test2");
        r3 = new RTest("test3");
        r4 = new RTest("test4");
        r5 = new RTest("test5");
        r6 = new RTest("test6");

        ss = Maps.newHashMap();
        ss.put(GameData.BLOCKS, new ForgeRegistry.Snapshot());
        ss.put(GameData.ITEMS, new ForgeRegistry.Snapshot());

        RegistryManager.ACTIVE.getRegistry(RTest.class).register(r1);
        RegistryManager.ACTIVE.getRegistry(RTest.class).register(r2);
        RegistryManager.ACTIVE.getRegistry(RTest.class).register(r3);
        RegistryManager.ACTIVE.getRegistry(RTest.class).register(r4);
        ss.put(resloc, ((ForgeRegistry<?>)RegistryManager.ACTIVE.getRegistry(RTest.class)).makeSnapshot());

        RegistryManager.ACTIVE.clean();
        RegistryManager.FROZEN.clean();
        new RegistryBuilder<RTest>().setName(resloc).setType(RTest.class).setIDRange(0, 255).create();
        new RegistryBuilder<Block>().setName(GameData.BLOCKS).setType(Block.class).setIDRange(0, 255).create();
        new RegistryBuilder<Item>().setName(GameData.ITEMS).setType(Item.class).setIDRange(0, 255).create();
    }

    @Test
    public void testFreezeCycle()
    {
        ResourceLocation name = new ResourceLocation("test3");
        ForgeRegistry<RTest> active = (ForgeRegistry<RTest>)RegistryManager.ACTIVE.getRegistry(RTest.class);
        active.register(r6);
        active.register(r5);
        active.register(r4);
        active.register(r3);

        GameData.freezeData();

        //Numbering is derived at freeze: dense, sorted by name, registration order irrelevant
        ForgeRegistry<RTest> frozen = (ForgeRegistry<RTest>)RegistryManager.ACTIVE.getRegistry(RTest.class);
        assertEquals(0, active.getID(r3), "test3 is the first name");
        assertEquals(1, active.getID(r4));
        assertEquals(2, active.getID(r5));
        assertEquals(3, active.getID(r6));
        assertEquals(r3, frozen.getValue(name), "Frozen object not the same");
        assertEquals(r3, active.getValue(name), "Active object not the same");

        // The snapshot holds test1..4; only test3 and test4 exist locally, so the registry reloads
        // as just those two and renumbers dense by name
        GameData.injectSnapshot(ss, false, true); //Unlike the old system we die on missing mappings for custom registries. So we need to tell it to continue loading as if we're local
        assertEquals(0, active.getID(r3));
        assertEquals(1, active.getID(r4));
        assertEquals(-1, active.getID(r5), "not in the snapshot, so not loaded");
        assertEquals(r3, frozen.getValue(name), "Frozen object not the same");
        assertEquals(r3, active.getValue(name), "Active object not the same");

        // Reverting brings back the frozen set and its derived numbering
        GameData.revertToFrozen();
        assertEquals(0, active.getID(r3));
        assertEquals(3, active.getID(r6));
        assertEquals(r3, frozen.getValue(name), "Frozen object not the same");
        assertEquals(r3, active.getValue(name), "Active object not the same");

        // Frozen entries are merged into the snapshot data; all six names renumber to name order
        GameData.injectSnapshot(ss, true, true);
        assertEquals(0, active.getID(r3));
        assertEquals(3, active.getID(r6));
        assertEquals(r3, frozen.getValue(name), "Frozen object not the same");
        assertEquals(r3, active.getValue(name), "Active object not the same");

        //And back to the frozen set
        GameData.revertToFrozen();
        assertEquals(0, active.getID(r3));
        assertEquals(3, active.getID(r6));
        assertEquals(r3, frozen.getValue(name), "Frozen object not the same");
        assertEquals(r3, active.getValue(name), "Active object not the same");
    }
}
