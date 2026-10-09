package com.cleanroommc.ids;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Missing-item retention: an unreadable stack keeps its whole compound and writes it back verbatim. */
@ForgeTestRunner.Isolated
public class MissingItemTest {
    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
        if (MissingItem.INSTANCE == null) {
            // The Register<Item> event does not fire in the test harness; register directly
            MissingItem.INSTANCE = new MissingItem();
            ForgeRegistries.ITEMS.register(MissingItem.INSTANCE);
        }
    }

    private static NBTTagCompound stack(String id) {
        NBTTagCompound compound = new NBTTagCompound();
        compound.setString("id", id);
        compound.setByte("Count", (byte) 3);
        compound.setInteger("Damage", 7);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Charge", 12);
        compound.setTag("tag", tag);
        return compound;
    }

    @Test
    public void unknownItemBecomesAMissingStackThatKeepsItsCompound() {
        NBTTagCompound compound = stack("gone_mod:wand");
        ItemStack stack = new ItemStack(compound);
        assertFalse(stack.isEmpty());
        assertSame(MissingItem.INSTANCE, stack.getItem());
        assertEquals(3, stack.getCount());
        assertEquals(compound, stack.getTagCompound().getCompoundTag(MissingItem.RETAINED));
    }

    @Test
    public void writeToNbtRestoresTheOriginalCompound() {
        NBTTagCompound compound = stack("gone_mod:dagger");
        ItemStack stack = new ItemStack(compound);
        assertEquals(compound, stack.writeToNBT(new NBTTagCompound()));
        ItemStack split = stack.copy();
        split.setCount(1);
        NBTTagCompound splitOut = split.writeToNBT(new NBTTagCompound());
        assertEquals(1, splitOut.getByte("Count"));
        splitOut.setByte("Count", (byte) 3);
        assertEquals(compound, splitOut);
    }

    @Test
    public void retainedItemReturnsWhenTheModDoes() {
        NBTTagCompound compound = stack("gone_mod:wand2");
        ItemStack stack = new ItemStack(compound);
        assertSame(MissingItem.INSTANCE, stack.getItem());
        NBTTagCompound out = stack.writeToNBT(new NBTTagCompound());

        Item wand2 = new Item();
        wand2.setRegistryName(new ResourceLocation("gone_mod", "wand2"));
        ForgeRegistries.ITEMS.register(wand2);

        ItemStack back = new ItemStack(out);
        assertSame(wand2, back.getItem());
        assertEquals(3, back.getCount());
        assertEquals(7, back.getItemDamage());
        assertEquals(12, back.getTagCompound().getInteger("Charge"));
    }

    @Test
    public void differentMissingStacksDoNotMerge() {
        ItemStack wand = new ItemStack(stack("gone_mod:wand3"));
        ItemStack dagger = new ItemStack(stack("gone_mod:dagger2"));
        ItemStack wandAgain = new ItemStack(stack("gone_mod:wand3"));
        assertFalse(ItemStack.areItemStackTagsEqual(wand, dagger));
        assertFalse(ItemStack.areItemStacksEqual(wand, dagger));
        assertTrue(ItemStack.areItemStackTagsEqual(wand, wandAgain));
        assertTrue(ItemStack.areItemStacksEqual(wand, wandAgain));
    }

    @Test
    public void airAndEmptyAreUntouched() {
        NBTTagCompound air = stack("minecraft:air");
        assertTrue(new ItemStack(air).isEmpty());
        NBTTagCompound empty = new NBTTagCompound();
        assertTrue(new ItemStack(empty).isEmpty());
        NBTTagCompound emptyId = new NBTTagCompound();
        emptyId.setString("id", "");
        assertTrue(new ItemStack(emptyId).isEmpty());
    }

    @Test
    public void missingItemIsHiddenFromCreativeTabs() {
        assertNull(MissingItem.INSTANCE.getCreativeTab());
    }
}
