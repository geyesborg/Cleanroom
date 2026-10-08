package com.cleanroommc.test.ids;

import com.cleanroommc.common.IdFormatContainer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.SaveHandler;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.minecraftforge.fml.common.ZipperUtil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ForgeTestRunner.Isolated
public class WorldIdFormatTest {
    @TempDir
    Path directory;

    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
    }

    private SaveHandler handler() {
        return new SaveHandler(directory.toFile(), "world", false, null);
    }

    @Test
    public void migrationBacksUpTheSaveHandlersActualWorldDirectory() throws IOException {
        SaveHandler handler = handler();
        Path world = handler.getWorldDirectory().toPath();
        Path region = Files.createDirectories(world.resolve("region")).resolve("r.0.0.mca");
        byte[] content = { 1, 7, 3, 9 };
        Files.write(region, content);
        String previous = System.getProperty("fml.doNotBackup");
        try {
            System.clearProperty("fml.doNotBackup");
            new IdFormatContainer().readData(handler, null, Map.of(), new NBTTagCompound());
        } finally {
            if (previous == null) System.clearProperty("fml.doNotBackup");
            else System.setProperty("fml.doNotBackup", previous);
        }
        try (var files = Files.list(directory)) {
            var backups = files.filter(path -> path.getFileName().toString().endsWith(".zip")).toList();
            assertEquals(1, backups.size());
            try (ZipFile zip = new ZipFile(backups.getFirst().toFile())) {
                try (var in = zip.getInputStream(zip.getEntry("region/r.0.0.mca"))) {
                    assertArrayEquals(content, in.readAllBytes());
                }
            }
        }
        assertArrayEquals(content, Files.readAllBytes(region));
    }

    @Test
    public void badBackupSourceDoesNotOverwriteTheDestination() throws IOException {
        Path archive = directory.resolve("existing.zip");
        byte[] before = { 8, 4, 2 };
        Files.write(archive, before);
        assertThrows(IOException.class, () -> ZipperUtil.zip(directory.resolve("missing-world").toFile(), archive.toFile()));
        assertArrayEquals(before, Files.readAllBytes(archive));
    }

    @Test
    public void repeatedBackupsDoNotReplaceAnEarlierArchive() throws IOException {
        Path world = Files.createDirectory(directory.resolve("backup-world"));
        Files.write(world.resolve("data.dat"), new byte[] { 1 });
        File first = ZipperUtil.backupWorld(world.toFile());
        byte[] originalArchive = Files.readAllBytes(first.toPath());
        Files.write(world.resolve("data.dat"), new byte[] { 2 });
        File second = ZipperUtil.backupWorld(world.toFile());
        assertNotEquals(first, second);
        assertArrayEquals(originalArchive, Files.readAllBytes(first.toPath()));
        try (ZipFile zip = new ZipFile(second)) {
            try (var in = zip.getInputStream(zip.getEntry("data.dat"))) {
                assertArrayEquals(new byte[] { 2 }, in.readAllBytes());
            }
        }
    }

    @Test
    public void failedBackupsDoNotPublishOrLeavePartialArchives() throws IOException {
        Path world = Files.createDirectory(directory.resolve("unreadable-world"));
        File unreadable = new File(world.toString()) {
            @Override
            public File getCanonicalFile() { return this; }
            @Override
            public File[] listFiles() { return null; }
        };
        assertThrows(IOException.class, () -> ZipperUtil.backupWorld(unreadable));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(world), files.toList());
        }
    }

    @Test
    public void archiveInsideTheSourceIsRejectedBeforeWriting() throws IOException {
        Path world = Files.createDirectory(directory.resolve("self-backup-world"));
        Path archive = world.resolve("backup.zip");
        byte[] before = { 6, 2, 3 };
        Files.write(archive, before);
        assertThrows(IOException.class, () -> ZipperUtil.zip(world.toFile(), archive.toFile()));
        assertArrayEquals(before, Files.readAllBytes(archive));
    }

    @Test
    public void acceptsCurrentFormatAndAnUnmarkedEmptyWorld() {
        IdFormatContainer container = new IdFormatContainer();
        SaveHandler handler = handler();
        NBTTagCompound tag = new NBTTagCompound();
        assertDoesNotThrow(() -> container.readData(handler, null, Map.of(), tag));
        tag.setString("Format", IdFormatContainer.FORMAT);
        assertDoesNotThrow(() -> container.readData(handler, null, Map.of(), tag));
    }

    @Test
    public void wronglyTypedFormatMarkerIsNotAnUnmarkedWorld() {
        IdFormatContainer container = new IdFormatContainer();
        SaveHandler handler = handler();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Format", 999);
        assertThrows(IllegalStateException.class, () -> container.readData(handler, null, Map.of(), tag));
    }

    @Test
    public void rejectsInvalidAndFutureFormatNumbers() {
        IdFormatContainer container = new IdFormatContainer();
        SaveHandler handler = handler();
        for (String value : new String[] { "", "-1", "0", "invalid", "999" }) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("Format", value);
            assertThrows(IllegalStateException.class, () -> container.readData(handler, null, Map.of(), tag), value);
        }
    }
}
