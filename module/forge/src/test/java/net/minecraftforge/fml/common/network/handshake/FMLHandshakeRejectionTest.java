package net.minecraftforge.fml.common.network.handshake;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 13: a refused login reports mod rejections and registry hash mismatches in one reason.
 */
@ForgeTestRunner.Isolated
public class FMLHandshakeRejectionTest
{
    @Test
    public void rejectionReasonCombinesModsThenRegistries()
    {
        List<ResourceLocation> mismatches = Arrays.asList(
                new ResourceLocation("minecraft", "blocks"),
                new ResourceLocation("somemod", "things"));
        String reason = FMLHandshakeClientState.rejectionReason("Mod rejections: [somemod]", mismatches);
        assertTrue(reason.startsWith("Mod rejections: [somemod]"), reason);
        assertTrue(reason.contains("minecraft:blocks"), reason);
        assertTrue(reason.contains("somemod:things"), reason);
        assertTrue(reason.indexOf("Mod rejections") < reason.indexOf("Registry hash mismatches"), reason);
    }

    @Test
    public void rejectionReasonWithoutMismatchesIsTheModReason()
    {
        assertEquals("Mod rejections: [x]", FMLHandshakeClientState.rejectionReason("Mod rejections: [x]", Collections.emptyList()));
    }
}
