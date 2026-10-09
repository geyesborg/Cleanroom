package com.cleanroommc.ids;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The names a chunk holds that no registry resolved, kept verbatim so a load-save cycle while a mod is
 * absent loses nothing: block-state strings per section cell, biome names per column (on
 * {@link ChunkBiomes}), and whole tile-entity and entity compounds. A real write to a cell drops its name
 * (last write wins); everything else goes back out exactly as it came in and returns to life when the mod
 * is reinstalled.
 * <p>
 * TODO items: an ItemStack with an unknown item is EMPTY everywhere vanilla looks, so retaining item NBT
 * needs a separate design and is not done here.
 */
public final class RetainedContent {
    private static final Logger LOGGER = LogManager.getLogger("Cleanroom");
    static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** A retained cell: the saved state string and the vanilla state it reads as meanwhile (null = air). */
    public record Retained(String name, @Nullable IBlockState standIn) {
    }

    /** A section's cells still holding a name no block answered: storage index (y &lt;&lt; 8 | z &lt;&lt; 4 | x) → the saved state string and its stand-in. */
    public static final class Section {
        private final Int2ObjectMap<Retained> cells = new Int2ObjectOpenHashMap<>();

        public void retain(int index, String name) {
            retain(index, name, null);
        }

        public void retain(int index, String name, @Nullable IBlockState standIn) {
            cells.put(index, new Retained(name, standIn));
        }

        public void clear(int index) {
            cells.remove(index);
        }

        @Nullable
        public String name(int index) {
            Retained cell = cells.get(index);
            return cell == null ? null : cell.name();
        }

        /** The stand-in the cell was retained with, or null when it reads as air (or the cell is not retained). */
        @Nullable
        public IBlockState standIn(int index) {
            Retained cell = cells.get(index);
            return cell == null ? null : cell.standIn();
        }

        /** The state the cell reads as while retained: the stand-in, or air. */
        public IBlockState reads(int index) {
            IBlockState standIn = standIn(index);
            return standIn == null ? Blocks.AIR.getDefaultState() : standIn;
        }

        public boolean isEmpty() {
            return cells.isEmpty();
        }

        public Int2ObjectMap<Retained> cells() {
            return cells;
        }
    }

    private final Int2ObjectMap<Section> sections = new Int2ObjectOpenHashMap<>();
    /** Tile-entity compounds that named no registered block entity, kept verbatim. */
    public final List<NBTTagCompound> tileEntities = new ArrayList<>();
    /** Entity compounds that named no registered entity, kept verbatim (riders included). */
    public final List<NBTTagCompound> entities = new ArrayList<>();

    public Section section(int y) {
        return sections.computeIfAbsent(y, k -> new Section());
    }

    @Nullable
    public Section sectionOrNull(int y) {
        return sections.get(y);
    }

    public void section(int y, @Nullable Section section) {
        if (section == null || section.isEmpty()) {
            sections.remove(y);
        } else {
            sections.put(y, section);
        }
    }

    /** Once per session per name, so a removed mod logs one line instead of one per chunk or stack. */
    public static void noteMissing(String name, @Nullable ChunkPos pos) {
        if (LOGGED.add(name)) {
            if (pos == null) {
                LOGGER.info("Retaining content of {} (not registered). Reinstall the mod to restore it.", name);
            } else {
                LOGGER.info("Retaining content of {} (not registered; first seen in chunk {},{}). Reinstall the mod to restore it.",
                        name, pos.x, pos.z);
            }
        }
    }

    /**
     * Once per session per block (not per state string — a vine has many connection states), with the
     * stand-in the retained cells read as in the message.
     */
    public static void noteMissingBlock(String stateName, @Nullable IBlockState standIn, @Nullable ChunkPos pos) {
        int open = stateName.indexOf('[');
        String block = open < 0 ? stateName : stateName.substring(0, open);
        if (LOGGED.add("block " + block)) {
            String how = standIn == null ? "reads as air" : "standing in: " + Block.REGISTRY.getNameForObject(standIn.getBlock());
            if (pos == null) {
                LOGGER.info("Retaining content of {} (not registered; {}). Reinstall the mod to restore it.", block, how);
            } else {
                LOGGER.info("Retaining content of {} (not registered; {}; first seen in chunk {},{}). Reinstall the mod to restore it.",
                        block, how, pos.x, pos.z);
            }
        }
    }

    public static void resetLogged() {
        LOGGED.clear();
    }

    /** The compound names no registered tile entity; read failures of known ids are not retainable. */
    public static boolean unknownTileEntity(NBTTagCompound tag) {
        return !TileEntity.isRegistered(tag.getString("id"));
    }

    /** The compound names no registered entity. */
    public static boolean unknownEntity(NBTTagCompound tag) {
        return EntityList.getClass(new ResourceLocation(tag.getString("id"))) == null;
    }
}
