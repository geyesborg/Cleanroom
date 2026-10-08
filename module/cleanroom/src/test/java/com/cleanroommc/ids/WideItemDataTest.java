package com.cleanroommc.ids;

import io.netty.buffer.Unpooled;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.network.PacketBuffer;
import net.minecraft.potion.PotionEffect;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ForgeTestRunner.Isolated
public class WideItemDataTest {
    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
    }

    @Test
    public void idsAreSavedAsIntsWhateverTheirSize() {
        PotionEffect small = new PotionEffect(MobEffects.HASTE, 200, 1);
        assertEquals(3, small.writeCustomPotionEffectToNBT(new NBTTagCompound()).getTagId("Id"));
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD, 1, 0);
        stack.setItemDamage(300);
        assertEquals(3, stack.writeToNBT(new NBTTagCompound()).getTagId("Damage"));
        stack.setItemDamage(70000);
        assertEquals(70000, stack.writeToNBT(new NBTTagCompound()).getInteger("Damage"));
    }

    @Test
    public void byteShortAndIntTagsCompareByValue() {
        assertEquals(new NBTTagInt(34), new NBTTagShort((short) 34));
        assertEquals(new NBTTagShort((short) 34), new NBTTagInt(34));
        assertEquals(new NBTTagByte((byte) 34), new NBTTagInt(34));
        assertEquals(new NBTTagInt(34).hashCode(), new NBTTagShort((short) 34).hashCode());
        assertEquals(new NBTTagInt(34).hashCode(), new NBTTagByte((byte) 34).hashCode());
        assertNotEquals(new NBTTagInt(35), new NBTTagShort((short) 34));
        assertNotEquals(new NBTTagInt(34), new NBTTagLong(34));
        assertNotEquals(new NBTTagInt(34), new NBTTagFloat(34));
        NBTTagCompound a = new NBTTagCompound();
        a.setShort("id", (short) 34);
        NBTTagCompound b = new NBTTagCompound();
        b.setInteger("id", 34);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        NBTTagList shorts = new NBTTagList();
        shorts.appendTag(new NBTTagShort((short) 7));
        NBTTagList ints = new NBTTagList();
        ints.appendTag(new NBTTagInt(7));
        assertEquals(shorts, ints);
        assertEquals(shorts.hashCode(), ints.hashCode());
    }

    @Test
    public void aStackWithAShortEnchantmentIdEqualsOneWithAnInt() {
        ItemStack ours = new ItemStack(Items.DIAMOND_SWORD);
        ours.addEnchantment(Enchantments.SHARPNESS, 3);
        NBTTagCompound modTag = new NBTTagCompound();
        NBTTagList ench = new NBTTagList();
        NBTTagCompound entry = new NBTTagCompound();
        entry.setShort("id", (short) Enchantment.getEnchantmentID(Enchantments.SHARPNESS));
        entry.setString("name", Enchantments.SHARPNESS.getRegistryName().toString());
        entry.setShort("lvl", (short) 3);
        ench.appendTag(entry);
        modTag.setTag("ench", ench);
        ItemStack theirs = new ItemStack(Items.DIAMOND_SWORD);
        theirs.setTagCompound(modTag);
        assertTrue(ItemStack.areItemStackTagsEqual(ours, theirs));
        assertTrue(ItemStack.areItemStacksEqual(ours, theirs));
    }

    @Test
    public void potionIdsFromOlderSavesAreReadAsUnsignedBytes() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte("Id", (byte) 200);
        assertEquals(200, WideNbt.getUnsignedByteOrInt(tag, "Id"));
        tag.setInteger("Id", 300);
        assertEquals(300, WideNbt.getUnsignedByteOrInt(tag, "Id"));
    }

    @Test
    public void itemStacksKeepWideDamageAndEnchantmentsInNbt() {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD, 1, 0);
        stack.setItemDamage(40000);
        stack.addEnchantment(Enchantments.SHARPNESS, 3);
        NBTTagCompound tag = stack.writeToNBT(new NBTTagCompound());
        ItemStack back = new ItemStack(tag);
        assertEquals(40000, back.getItemDamage());
        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(back);
        assertEquals(3, (int) enchantments.get(Enchantments.SHARPNESS));
        assertEquals(3, tag.getCompoundTag("tag").getTagList("ench", 10).getCompoundTagAt(0).getTagId("id"), "an int");
    }

    @Test
    public void potionEffectsRoundTrip() {
        PotionEffect effect = new PotionEffect(MobEffects.HASTE, 200, 1);
        PotionEffect back = PotionEffect.readCustomPotionEffectFromNBT(effect.writeCustomPotionEffectToNBT(new NBTTagCompound()));
        assertSame(MobEffects.HASTE, back.getPotion());
        assertEquals(1, back.getAmplifier());
    }

    @Test
    public void itemStackPacketsUseVarints() throws IOException {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        ItemStack big = new ItemStack(Items.STONE_PICKAXE, 200, 0);
        big.setItemDamage(50000);
        buf.writeItemStack(ItemStack.EMPTY);
        buf.writeItemStack(big);
        assertTrue(buf.readItemStack().isEmpty());
        ItemStack back = buf.readItemStack();
        assertSame(Items.STONE_PICKAXE, back.getItem());
        assertEquals(200, back.getCount(), "counts past a byte");
        assertEquals(50000, back.getItemDamage());
    }

    @Test
    public void recipeBookPackingKeepsVanillaValuesAndPacksTheRest() {
        int vanilla = PackedItems.pack(Items.DYE, 4);
        assertEquals(net.minecraft.item.Item.getIdFromItem(Items.DYE) << 16 | 4, vanilla);
        int wide = PackedItems.pack(Items.DYE, 70000);
        assertTrue(wide < 0, "wide pairs are not vanilla packing");
        assertEquals(wide, PackedItems.pack(Items.DYE, 70000), "same pair, same number");
        assertNotEquals(wide, PackedItems.pack(Items.DYE, 70001), "different damage, different number");
        ItemStack back = PackedItems.unpack(wide);
        assertSame(Items.DYE, back.getItem());
        assertEquals(70000, back.getMetadata());
        assertTrue(PackedItems.unpack(0).isEmpty());
    }
}
