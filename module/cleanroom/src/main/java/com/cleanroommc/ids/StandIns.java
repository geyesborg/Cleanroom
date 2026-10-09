package com.cleanroommc.ids;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The vanilla state a retained (unregistered) block reads as while its mod is absent. It is a reading only:
 * the saved name is what goes back out (see {@link RetainedContent}), so the real block returns when the mod
 * does. Ground blocks get solid stand-ins so terrain has no holes; plants, fluids and anything unmatched stay
 * air — a vanilla tallgrass pops off on the next update and a fluid flows, and either write would erase the
 * retained name.
 * <p>
 * {@code config/cleanroom/retained_standins.cfg} overrides the built-in rules: lines of
 * {@code substring = namespace:block[property=value,...]} or {@code substring = air}, first match on the
 * lowercase block path wins, {@code #} comments.
 */
public final class StandIns {
    private static final Logger LOGGER = LogManager.getLogger("Cleanroom");
    private static final List<String> HEADER = List.of(
            "# Stand-ins for retained (unregistered) block states.",
            "# Lines: substring = namespace:block[property=value,...] or substring = air",
            "# The first match on the lowercase block path wins, before the built-in defaults.",
            "# Example: sapphire_ore = minecraft:lapis_ore");

    private record Override(String substring, @Nullable IBlockState state) {
    }

    private static volatile List<Override> overrides = List.of();
    private static volatile boolean loaded;

    private StandIns() {
    }

    /** The stand-in for a saved {@code namespace:block[props]} string, or null when it should read as air. */
    @Nullable
    public static IBlockState forMissing(String savedState) {
        int open = savedState.indexOf('[');
        String block = open < 0 ? savedState : savedState.substring(0, open);
        int colon = block.indexOf(':');
        String path = (colon < 0 ? block : block.substring(colon + 1)).toLowerCase(Locale.ROOT);
        for (Override override : overrides()) {
            if (path.contains(override.substring())) {
                return override.state();
            }
        }
        IBlockState standIn = defaultFor(path, savedState);
        if (standIn == null) {
            return null;
        }
        // leaves pinned non-decaying: a saved decayable=true could break on a block update and lose the name
        return copyProperties(standIn, savedState,
                standIn.getBlock() == Blocks.LEAVES ? LEAF_PINNED : Set.of());
    }

    private static final Set<String> LEAF_PINNED = Set.of("decayable", "check_decay");

    /** The built-in rules: first match on the lowercase block path. */
    @Nullable
    private static IBlockState defaultFor(String path, String savedState) {
        if (path.contains("leaves")) {
            return Blocks.LEAVES.getDefaultState()
                    .withProperty(BlockLeaves.DECAYABLE, false)
                    .withProperty(BlockLeaves.CHECK_DECAY, false);
        }
        if ((path.contains("log") || path.contains("wood")) && !path.contains("planks")) {
            return Blocks.LOG.getDefaultState();
        }
        if (path.contains("planks")) {
            return Blocks.PLANKS.getDefaultState();
        }
        if (path.contains("stairs")) {
            return Blocks.OAK_STAIRS.getDefaultState();
        }
        if (path.contains("slab")) {
            return path.contains("double") ? Blocks.DOUBLE_WOODEN_SLAB.getDefaultState() : Blocks.WOODEN_SLAB.getDefaultState();
        }
        if (path.contains("fence_gate")) {
            return Blocks.OAK_FENCE_GATE.getDefaultState();
        }
        if (path.contains("fence")) {
            return Blocks.OAK_FENCE.getDefaultState();
        }
        if (path.contains("grass") && !path.contains("tall") && !path.contains("plant") && !path.contains("flower")
                && !path.contains("double")) {
            return Blocks.GRASS.getDefaultState();
        }
        if (path.contains("dirt") || path.contains("mud")) {
            IBlockState dirt = Blocks.DIRT.getDefaultState();
            return "true".equals(savedProperty(savedState, "coarse"))
                    ? dirt.withProperty(BlockDirt.VARIANT, BlockDirt.DirtType.COARSE_DIRT)
                    : dirt;
        }
        if (path.contains("sandstone")) {
            return Blocks.SANDSTONE.getDefaultState();
        }
        if (path.contains("sand")) {
            return Blocks.SAND.getDefaultState();
        }
        if (path.contains("gravel")) {
            return Blocks.GRAVEL.getDefaultState();
        }
        if (path.contains("ore") || path.contains("stone") || path.contains("rock")) {
            return Blocks.STONE.getDefaultState();
        }
        return null;
    }

    /** Same-named properties whose saved value parses on the stand-in block carry over; pinned names and the rest keep defaults. */
    private static IBlockState copyProperties(IBlockState state, String savedState, Set<String> pinned) {
        int open = savedState.indexOf('[');
        if (open < 0) {
            return state;
        }
        net.minecraft.block.state.BlockStateContainer definition = state.getBlock().getBlockState();
        for (String pair : savedState.substring(open + 1, savedState.length() - 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String name = pair.substring(0, eq);
                IProperty<?> property = pinned.contains(name) ? null : definition.getProperty(name);
                if (property != null) {
                    state = withValue(state, property, pair.substring(eq + 1));
                }
            }
        }
        return state;
    }

    @Nullable
    private static String savedProperty(String savedState, String name) {
        int open = savedState.indexOf('[');
        if (open < 0) {
            return null;
        }
        for (String pair : savedState.substring(open + 1, savedState.length() - 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static <T extends Comparable<T>> IBlockState withValue(IBlockState state, IProperty<T> property, String value) {
        return property.parseValue(value).toJavaUtil().map(v -> state.withProperty(property, v)).orElse(state);
    }

    private static List<Override> overrides() {
        if (!loaded) {
            synchronized (StandIns.class) {
                if (!loaded) {
                    File configDir = Loader.instance().getConfigDir();
                    Path file = configDir != null ? configDir.toPath().resolve("cleanroom/retained_standins.cfg") : null;
                    if (file != null && !Files.exists(file)) {
                        try {
                            Files.createDirectories(file.getParent());
                            Files.write(file, HEADER);
                        } catch (IOException e) {
                            LOGGER.warn("Could not write default retained stand-ins config {}", file, e);
                        }
                    }
                    load(file);
                }
            }
        }
        return overrides;
    }

    /** Replaces the override table from a config file; null gives none. A bad line is warned about and skipped. */
    static synchronized void load(@Nullable Path file) {
        List<Override> list = new ArrayList<>();
        if (file != null && Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq < 0) {
                        LOGGER.warn("Ignoring bad retained stand-in line: {}", trimmed);
                        continue;
                    }
                    String substring = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String state = trimmed.substring(eq + 1).trim();
                    if (substring.isEmpty() || state.isEmpty()) {
                        LOGGER.warn("Ignoring bad retained stand-in line: {}", trimmed);
                        continue;
                    }
                    IBlockState parsed = SectionBlockFormat.parse(state);
                    if (parsed.getBlock() == Blocks.AIR && !state.equals("air") && !state.equals("minecraft:air")) {
                        LOGGER.warn("Ignoring retained stand-in line with an unregistered block: {}", trimmed);
                        continue;
                    }
                    list.add(new Override(substring, parsed.getBlock() == Blocks.AIR ? null : parsed));
                }
            } catch (IOException e) {
                LOGGER.warn("Could not read retained stand-ins config {}", file, e);
            }
        }
        overrides = list;
        loaded = true;
    }
}
