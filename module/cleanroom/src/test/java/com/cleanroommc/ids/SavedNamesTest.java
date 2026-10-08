package com.cleanroommc.ids;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnumEnchantmentType;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ForgeTestRunner.Isolated
public class SavedNamesTest {
    private static final ResourceLocation POTION_NAME = new ResourceLocation("wideids_test", "wide_effect");
    private static final ResourceLocation ENCH_NAME = new ResourceLocation("wideids_test", "wide_ench");

    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
    }

    private static Potion widePotion() {
        if (!ForgeRegistries.POTIONS.containsKey(POTION_NAME)) {
            Potion potion = new Potion(false, 0) {};
            potion.setRegistryName(POTION_NAME);
            ForgeRegistries.POTIONS.register(potion);
        }
        return ForgeRegistries.POTIONS.getValue(POTION_NAME);
    }

    private static Enchantment wideEnchantment() {
        if (!ForgeRegistries.ENCHANTMENTS.containsKey(ENCH_NAME)) {
            Enchantment enchantment = new Enchantment(Enchantment.Rarity.RARE, EnumEnchantmentType.ALL,
                    new EntityEquipmentSlot[] { EntityEquipmentSlot.MAINHAND }) {};
            enchantment.setRegistryName(ENCH_NAME);
            ForgeRegistries.ENCHANTMENTS.register(enchantment);
        }
        return ForgeRegistries.ENCHANTMENTS.getValue(ENCH_NAME);
    }

    @Test
    public void potionEffectRoundTripsByName() {
        Potion potion = widePotion();
        NBTTagCompound tag = new PotionEffect(potion, 100, 1).writeCustomPotionEffectToNBT(new NBTTagCompound());
        assertTrue(tag.hasKey("Name", 8));
        assertEquals("wideids_test:wide_effect", tag.getString("Name"));
        assertTrue(tag.hasKey("Id"));
        assertSame(potion, PotionEffect.readCustomPotionEffectFromNBT(tag).getPotion());

        // An id that meant the modded potion last session can mean a vanilla one now: the name wins
        tag.setInteger("Id", Potion.getIdFromPotion(MobEffects.SPEED));
        assertSame(potion, PotionEffect.readCustomPotionEffectFromNBT(tag).getPotion());
    }

    @Test
    public void potionEffectWithoutNameStillReadsById() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte("Id", (byte) Potion.getIdFromPotion(MobEffects.SPEED));
        PotionEffect effect = PotionEffect.readCustomPotionEffectFromNBT(tag);
        assertNotNull(effect);
        assertSame(MobEffects.SPEED, effect.getPotion());
    }

    @Test
    public void enchantmentRoundTripsByName() {
        Enchantment enchantment = wideEnchantment();
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.addEnchantment(enchantment, 1);
        NBTTagCompound enchTag = stack.getEnchantmentTagList().getCompoundTagAt(0);
        assertEquals("wideids_test:wide_ench", enchTag.getString("name"));
        assertTrue(EnchantmentHelper.getEnchantments(stack).containsKey(enchantment));

        enchTag.setInteger("id", Enchantment.getEnchantmentID(Enchantments.SHARPNESS));
        assertTrue(EnchantmentHelper.getEnchantments(stack).containsKey(enchantment), "the name wins over a shifted id");
        assertFalse(EnchantmentHelper.getEnchantments(stack).containsKey(Enchantments.SHARPNESS));
    }

    @Test
    public void enchantmentWithoutNameStillReadsById() {
        NBTTagList list = new NBTTagList();
        NBTTagCompound enchTag = new NBTTagCompound();
        enchTag.setShort("id", (short) Enchantment.getEnchantmentID(Enchantments.SHARPNESS));
        enchTag.setShort("lvl", (short) 3);
        list.appendTag(enchTag);
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.setTagInfo("ench", list);
        assertEquals(3, (int) EnchantmentHelper.getEnchantments(stack).get(Enchantments.SHARPNESS));
    }

    @Test
    public void beaconEffectsRoundTripByName() {
        NBTTagCompound in = new NBTTagCompound();
        in.setInteger("Primary", Potion.getIdFromPotion(MobEffects.HASTE)); // wrong on purpose: the name wins
        in.setString("PrimaryName", "minecraft:speed");
        in.setInteger("Secondary", 0);
        TileEntityBeacon beacon = new TileEntityBeacon();
        beacon.readFromNBT(in);

        NBTTagCompound out = beacon.writeToNBT(new NBTTagCompound());
        assertEquals("minecraft:speed", out.getString("PrimaryName"));
        assertEquals(Potion.getIdFromPotion(MobEffects.SPEED), out.getInteger("Primary"), "name overrode the stale id");
        assertFalse(out.hasKey("SecondaryName"), "a null secondary writes no name");
        assertEquals(-1, out.getInteger("Secondary"), "null effect still writes -1");

        TileEntityBeacon read = new TileEntityBeacon();
        read.readFromNBT(out);
        NBTTagCompound again = read.writeToNBT(new NBTTagCompound());
        assertEquals(Potion.getIdFromPotion(MobEffects.SPEED), again.getInteger("Primary"));
        assertEquals(-1, again.getInteger("Secondary"));
    }

    @Test
    public void itemModelMesherKeysDoNotCollidePastShortIds() {
        assertNotEquals(ItemModelKey.of(65536, 0), ItemModelKey.of(0, 0));
        assertNotEquals(ItemModelKey.of(32768, 5), ItemModelKey.of(0, 5));
        assertNotEquals(ItemModelKey.of(1, 65536), ItemModelKey.of(2, 0));
        assertEquals(ItemModelKey.of(1, 2), ItemModelKey.of(1, 2));
    }
    @Test
    public void unknownNameIsMissingNotTheId() {
        // An unresolvable name means the owning mod is gone: the numeric id must NOT be used,
        // it could now belong to a different registry entry.
        NBTTagCompound enchTag = new NBTTagCompound();
        enchTag.setString("name", "wideids_test:gone");
        enchTag.setInteger("id", Enchantment.getEnchantmentID(Enchantments.SHARPNESS));
        assertNull(EnchantmentHelper.enchantmentFrom(enchTag), "unresolvable name is missing, not sharpness");

        NBTTagCompound potionTag = new NBTTagCompound();
        potionTag.setString("Name", "wideids_test:gone");
        potionTag.setInteger("Id", Potion.getIdFromPotion(MobEffects.SPEED));
        assertNull(PotionEffect.readCustomPotionEffectFromNBT(potionTag), "unresolvable name is missing, not speed");

        NBTTagCompound beaconTag = new NBTTagCompound();
        beaconTag.setString("PrimaryName", "wideids_test:gone");
        beaconTag.setInteger("Primary", Potion.getIdFromPotion(MobEffects.SPEED));
        TileEntityBeacon beacon = new TileEntityBeacon();
        beacon.readFromNBT(beaconTag);
        NBTTagCompound out = beacon.writeToNBT(new NBTTagCompound());
        assertEquals(-1, out.getInteger("Primary"), "unresolvable name left the effect unset");
        assertFalse(out.hasKey("PrimaryName"));
    }
}
