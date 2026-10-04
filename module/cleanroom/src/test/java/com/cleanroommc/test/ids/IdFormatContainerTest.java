package com.cleanroommc.test.ids;

import com.cleanroommc.common.IdFormatContainer;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IdFormatContainerTest {
    @Test
    public void acceptsTheSameFormat() {
        assertTrue(IdFormatContainer.checkRemote(Map.of(IdFormatContainer.MOD_ID, IdFormatContainer.FORMAT, "forge", "14.23.5.2847"), Side.SERVER));
        assertTrue(IdFormatContainer.checkRemote(Map.of(IdFormatContainer.MOD_ID, IdFormatContainer.FORMAT), Side.CLIENT));
    }

    @Test
    public void rejectsOtherFormatsAndPeersWithoutIt() {
        assertFalse(IdFormatContainer.checkRemote(Map.of(IdFormatContainer.MOD_ID, "0"), Side.SERVER));
        assertFalse(IdFormatContainer.checkRemote(Map.of("cleanroom", "0.6.14", "forge", "14.23.5.2847"), Side.CLIENT));
        assertFalse(IdFormatContainer.checkRemote(Map.of(), Side.SERVER));
    }
}
