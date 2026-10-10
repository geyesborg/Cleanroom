package com.cleanroommc.ids;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stand-in-terrain index, its persistence in {@link RetentionFile}, and the Regenerate/Keep choice. */
@ForgeTestRunner.Isolated
public class StandInTerrainTest {

    @BeforeAll
    public static void setup() throws Exception {
        MinecraftTestHarness.bootstrap();
        // StartupQuery.choice -> FMLCommonHandler.getSide() needs a sided delegate; a proxy answering
        // SERVER takes the dedicated-server branch (no GUI; cleanroom.missingContent picks the option).
        Object handler = java.lang.reflect.Proxy.newProxyInstance(
                StandInTerrainTest.class.getClassLoader(),
                new Class<?>[] { net.minecraftforge.fml.common.IFMLSidedHandler.class },
                (proxy, method, args) -> "getSide".equals(method.getName()) ? Side.SERVER
                        : method.getReturnType() == boolean.class ? false : null);
        java.lang.reflect.Field delegate = net.minecraftforge.fml.common.FMLCommonHandler.class.getDeclaredField("sidedDelegate");
        delegate.setAccessible(true);
        delegate.set(net.minecraftforge.fml.common.FMLCommonHandler.instance(), handler);
    }

    @AfterEach
    public void tearDown() {
        RetainedContent.clearWorldState();
        System.clearProperty("cleanroom.standInTerrain");
        System.clearProperty("fml.doNotBackup");
    }

    @Test
    public void indexTracksMarkedAndUnmarkedWrites() {
        ChunkPos pos = new ChunkPos(3, 4);
        RetainedContent.indexStandInChunk("biomesop", 0, pos);
        RetainedContent.indexStandInChunk("biomesop", -1, new ChunkPos(9, 9));
        assertTrue(RetainedContent.standInTerrain().get("biomesop").get(0).contains(pos.asLong(pos.x, pos.z)));

        // the same position written without a mark drops out of every type and dimension
        RetainedContent.indexStandInChunk(null, 0, pos);
        assertFalse(RetainedContent.standInTerrain().get("biomesop").get(0).contains(pos.asLong(pos.x, pos.z)));
        assertTrue(RetainedContent.standInTerrain().get("biomesop").get(-1).contains(new ChunkPos(9, 9).asLong(9, 9)));
    }

    @Test
    public void fileRoundTripsTheIndex(@TempDir Path dir) {
        RetentionFile out = new RetentionFile();
        Map<Integer, it.unimi.dsi.fastutil.longs.LongSet> dims = new TreeMap<>();
        dims.put(0, new LongOpenHashSet(new long[] { ChunkPos.asLong(1, 2), ChunkPos.asLong(5, 6) }));
        dims.put(-1, new LongOpenHashSet(new long[] { ChunkPos.asLong(7, 8) }));
        out.standInTerrain.put("biomesop", dims);
        out.write(dir.toFile());

        RetentionFile in = RetentionFile.read(dir.toFile());
        assertNotNull(in);
        assertEquals(2, in.standInTerrain.get("biomesop").get(0).size());
        assertTrue(in.standInTerrain.get("biomesop").get(0).contains(ChunkPos.asLong(1, 2)));
        assertTrue(in.standInTerrain.get("biomesop").get(-1).contains(ChunkPos.asLong(7, 8)));
    }

    @Test
    public void eraserRemovesOneChunkKeepingTheOther(@TempDir Path dir) throws Exception {
        File regionDir = Files.createDirectories(dir.resolve("region")).toFile();
        writeChunks(regionDir, new int[][] { {3, 4}, {5, 6} });

        RegionChunkEraser.erase(regionDir, new ChunkPos(3, 4));

        RegionFile region = new RegionFile(new File(regionDir, "r.0.0.mca"));
        try {
            assertNull(region.getChunkDataInputStream(3, 4), "erased chunk reads as absent");
            assertNotNull(region.getChunkDataInputStream(5, 6), "the untouched chunk still reads");
        } finally {
            region.close();
        }
        // a missing region file is nothing to do
        RegionChunkEraser.erase(regionDir, new ChunkPos(40, 40));
    }

    @Test
    public void missingTypeLeavesTheIndexAlone(@TempDir Path dir) {
        RetentionFile out = fileWithIndex("NO_SUCH_TYPE", 0, ChunkPos.asLong(1, 2));
        out.write(dir.toFile());

        RetentionFile.onWorldLoad(dir.toFile(), infoWithType(WorldType.DEFAULT));

        assertTrue(RetainedContent.standInTerrain().get("NO_SUCH_TYPE").get(0).contains(ChunkPos.asLong(1, 2)),
                "a still-missing type keeps its marked chunks indexed");
    }

    @Test
    public void differentCurrentTypeLeavesTheIndexAlone(@TempDir Path dir) {
        RetentionFile out = fileWithIndex("default", 0, ChunkPos.asLong(1, 2));
        out.write(dir.toFile());

        RetentionFile.onWorldLoad(dir.toFile(), infoWithType(WorldType.FLAT));

        assertTrue(RetainedContent.standInTerrain().get("default").get(0).contains(ChunkPos.asLong(1, 2)),
                "the world runs a different type, so the marked chunks stay indexed");
    }

    @Test
    public void rememberedKeepClearsTheSetSilently(@TempDir Path dir) {
        RetentionFile out = fileWithIndex("default", 0, ChunkPos.asLong(1, 2));
        out.decisions.put("regen:default", "keep");
        out.write(dir.toFile());

        RetentionFile.onWorldLoad(dir.toFile(), infoWithType(WorldType.DEFAULT));

        assertTrue(RetainedContent.standInTerrain().get("default").get(0).isEmpty(),
                "a recorded Keep clears the set without asking");
    }

    @Test
    public void keepAnswerRecordsTheDecisionAndClears(@TempDir Path dir) {
        RetentionFile out = fileWithIndex("default", 0, ChunkPos.asLong(1, 2));
        out.write(dir.toFile());
        System.setProperty("cleanroom.standInTerrain", "keep"); // dedicated-server choice -> "Keep"

        RetentionFile.onWorldLoad(dir.toFile(), infoWithType(WorldType.DEFAULT));

        assertEquals("keep", RetainedContent.retentionDecisions().get("regen:default"));
        assertTrue(RetainedContent.standInTerrain().get("default").get(0).isEmpty());
    }

    @Test
    public void serverDefaultIsKeep(@TempDir Path dir) {
        RetentionFile out = fileWithIndex("default", 0, ChunkPos.asLong(1, 2));
        out.write(dir.toFile());
        // cleanroom.standInTerrain unset: a dedicated server must not erase chunks unasked

        RetentionFile.onWorldLoad(dir.toFile(), infoWithType(WorldType.DEFAULT));

        assertEquals("keep", RetainedContent.retentionDecisions().get("regen:default"));
        assertTrue(RetainedContent.standInTerrain().get("default").get(0).isEmpty());
    }

    @Test
    public void regenerateBacksUpAndErasesTheMarkedChunks(@TempDir Path dir) throws Exception {
        File worldDir = dir.toFile();
        File regionDir = Files.createDirectories(dir.resolve("region")).toFile();
        writeChunks(regionDir, new int[][] { {3, 4}, {5, 6} });
        RetentionFile out = fileWithIndex("default", 0, ChunkPos.asLong(3, 4));
        out.write(dir.toFile());
        // a server only regenerates when explicitly asked: the default answer is Keep
        System.setProperty("cleanroom.standInTerrain", "regenerate");

        RetentionFile.onWorldLoad(worldDir, infoWithType(WorldType.DEFAULT));

        RegionFile region = new RegionFile(new File(regionDir, "r.0.0.mca"));
        try {
            assertNull(region.getChunkDataInputStream(3, 4), "the marked chunk was erased");
            assertNotNull(region.getChunkDataInputStream(5, 6), "an unmarked chunk was not");
        } finally {
            region.close();
        }
        assertTrue(RetainedContent.standInTerrain().get("default").get(0).isEmpty());
        try (var zips = Files.list(dir.getParent())) {
            assertTrue(zips.anyMatch(p -> p.getFileName().toString().endsWith(".zip")
                    && p.getFileName().toString().startsWith(worldDir.getName())), "a world backup zip was made");
        }
    }

    private static RetentionFile fileWithIndex(String type, int dim, long... positions) {
        RetentionFile file = new RetentionFile();
        Map<Integer, it.unimi.dsi.fastutil.longs.LongSet> dims = new TreeMap<>();
        dims.put(dim, new LongOpenHashSet(positions));
        file.standInTerrain.put(type, dims);
        return file;
    }

    private static WorldInfo infoWithType(WorldType type) {
        return new WorldInfo(new WorldSettings(0L, GameType.CREATIVE, false, false, type), "test");
    }

    private static void writeChunks(File regionDir, int[][] positions) throws Exception {
        RegionFile region = new RegionFile(new File(regionDir, "r.0.0.mca"));
        try {
            for (int[] pos : positions) {
                try (DataOutputStream out = region.getChunkDataOutputStream(pos[0], pos[1])) {
                    out.writeInt(0xdead);
                    out.writeInt(pos[0] * 100 + pos[1]);
                }
            }
        } finally {
            region.close();
        }
    }
}
