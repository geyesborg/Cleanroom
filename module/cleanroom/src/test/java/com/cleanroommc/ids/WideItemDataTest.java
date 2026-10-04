package com.cleanroommc.ids;

import io.netty.buffer.Unpooled;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.potion.PotionEffect;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ForgeTestRunner.Isolated
public class WideItemDataTest {
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

    @Test
    public void numbersKeepTheirVanillaTypeWhileTheyFit() {
        NBTTagCompound tag = new NBTTagCompound();
        WideNbt.setShortOrInt(tag, "Damage", 300);
        assertEquals(2, tag.getTagId("Damage"), "short");
        WideNbt.setShortOrInt(tag, "Damage", 70000);
        assertEquals(3, tag.getTagId("Damage"), "int");
        assertEquals(70000, tag.getInteger("Damage"));
        WideNbt.setUnsignedByteOrInt(tag, "Id", 200);
        assertEquals(1, tag.getTagId("Id"));
        assertEquals(200, WideNbt.getUnsignedByteOrInt(tag, "Id"));
        WideNbt.setUnsignedByteOrInt(tag, "Id", 300);
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
        assertEquals(2, tag.getCompoundTag("tag").getTagList("ench", 10).getCompoundTagAt(0).getTagId("id"), "still a short for vanilla readers");
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
        ItemStack wide = PackedItems.unpack(PackedItems.pack(Items.DYE, 70000));
        assertSame(Items.DYE, wide.getItem());
        assertEquals(70000, wide.getMetadata());
        assertEquals(PackedItems.pack(Items.DYE, 70000), PackedItems.pack(Items.DYE, 70000), "same pair, same number");
        assertTrue(PackedItems.unpack(0).isEmpty());
    }
}
