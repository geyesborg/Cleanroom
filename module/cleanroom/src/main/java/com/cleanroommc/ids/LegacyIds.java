package com.cleanroommc.ids;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;

/**
 * The per-world id table recorded in level.dat ({@code FML}/{@code Registries}). Live registry ids are
 * derived each session and differ from the numbers a Forge-era save used, so every numeric id read out of
 * an old world's NBT must go through this table: saved id -> saved name -> live object. Vanilla entries
 * keep their vanilla ids and resolve identically either way.
 *
 * <p>One world is open per process in 1.12 (all dimensions share level.dat), so a single static instance
 * is correct. Installed from {@code FMLContainer.readData} before {@code GameData.loadWorldSnapshot}, cleared
 * when the server stops so the next world opened in this session does not inherit it.</p>
 */
public final class LegacyIds {
    @Nullable
    private static volatile LegacyIds current;

    /** The {@code Registries} tag this table was built from, verbatim, for re-writing on save. */
    private final NBTTagCompound registries;
    /** Registry name -> saved id -> saved name. */
    private final Map<ResourceLocation, Int2ObjectMap<ResourceLocation>> tables = new HashMap<>();

    private LegacyIds(NBTTagCompound registries) {
        this.registries = registries;
        for (String key : registries.getKeySet()) {
            ForgeRegistry.Snapshot snapshot = ForgeRegistry.Snapshot.read(registries.getCompoundTag(key));
            if (snapshot.ids.isEmpty()) {
                continue;
            }
            Int2ObjectMap<ResourceLocation> table = new Int2ObjectOpenHashMap<>(snapshot.ids.size());
            snapshot.ids.forEach((name, id) -> table.put((int) id, name));
            this.tables.put(new ResourceLocation(key), table);
        }
    }

    public static void install(NBTTagCompound registriesTag) {
        LegacyIds table = new LegacyIds(registriesTag);
        current = table;
        int entries = table.tables.values().stream().mapToInt(Int2ObjectMap::size).sum();
        LogManager.getLogger("Cleanroom").info("LegacyIds: saved id table installed for {} registries ({} entries)", table.tables.size(), entries);
    }

    public static void clear() {
        current = null;
        LogManager.getLogger("Cleanroom").debug("LegacyIds: saved id table cleared");
    }

    @Nullable
    public static LegacyIds current() {
        return current;
    }

    /**
     * The verbatim {@code Registries} tag for writing back to level.dat, or null when no table was
     * installed. The saved table is preserved, never regenerated from live ids.
     */
    @Nullable
    public static NBTTagCompound registriesForWriting() {
        LegacyIds table = current;
        return table == null ? null : table.registries;
    }

    @Nullable
    private ResourceLocation lookup(ResourceLocation registry, int savedId) {
        Int2ObjectMap<ResourceLocation> table = tables.get(registry);
        return table == null ? null : table.get(savedId);
    }

    /** A saved block id (chunk sections, piston TEs, endermen). Falls back to the live id. */
    @Nullable
    public static Block block(int savedId) {
        LegacyIds table = current;
        if (table != null) {
            ResourceLocation name = table.lookup(GameData.BLOCKS, savedId);
            if (name != null) {
                return Block.REGISTRY.getObject(name);
            }
        }
        return Block.getBlockById(savedId);
    }

    /** A saved biome id (legacy biome bytes, JEID int arrays). Falls back to the live id. */
    @Nullable
    public static Biome biome(int savedId) {
        LegacyIds table = current;
        if (table != null) {
            ResourceLocation name = table.lookup(GameData.BIOMES, savedId);
            if (name != null) {
                return Biome.REGISTRY.getObject(name);
            }
        }
        return Biome.getBiome(savedId);
    }

    /** A saved potion id (PotionEffect, beacons). Falls back to the live id. */
    @Nullable
    public static Potion potion(int savedId) {
        LegacyIds table = current;
        if (table != null) {
            ResourceLocation name = table.lookup(GameData.POTIONS, savedId);
            if (name != null) {
                return Potion.REGISTRY.getObject(name);
            }
        }
        return Potion.getPotionById(savedId);
    }

    /** A saved enchantment id. Falls back to the live id. */
    @Nullable
    public static Enchantment enchantment(int savedId) {
        LegacyIds table = current;
        if (table != null) {
            ResourceLocation name = table.lookup(GameData.ENCHANTMENTS, savedId);
            if (name != null) {
                return Enchantment.REGISTRY.getObject(name);
            }
        }
        return Enchantment.getEnchantmentByID(savedId);
    }
}
