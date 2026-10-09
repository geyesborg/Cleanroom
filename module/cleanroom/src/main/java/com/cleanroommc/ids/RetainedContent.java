package com.cleanroommc.ids;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.entity.EntityList;
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

    /** A section's cells still holding a name no block answered: storage index (y &lt;&lt; 8 | z &lt;&lt; 4 | x) → the saved state string. */
    public static final class Section {
        private final Int2ObjectMap<String> cells = new Int2ObjectOpenHashMap<>();

        public void retain(int index, String name) {
            cells.put(index, name);
        }

        public void clear(int index) {
            cells.remove(index);
        }

        @Nullable
        public String name(int index) {
            return cells.get(index);
        }

        public boolean isEmpty() {
            return cells.isEmpty();
        }

        public Int2ObjectMap<String> cells() {
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

    /** Once per session per name, so a removed mod logs one line instead of one per chunk. */
    public static void noteMissing(String name, @Nullable ChunkPos pos) {
        if (LOGGED.add(name)) {
            LOGGER.info("Retaining content of {} (not registered; first seen in chunk {},{}). Reinstall the mod to restore it.",
                    name, pos == null ? "?" : pos.x, pos == null ? "?" : pos.z);
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
