package com.cleanroommc.ids;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;
import net.minecraftforge.fml.common.registry.VillagerRegistry;
import net.minecraftforge.fml.common.registry.VillagerRegistry.VillagerProfession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ForgeTestRunner.Isolated
public class WideProfessionTest {
    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
    }

    @Test
    public void professionsPast1024KeepTheirIdThroughRegistryAndSync() throws IOException {
        VillagerProfession last = null;
        for (int i = 0; i < 1100; i++) {
            last = new VillagerProfession("wideids_test:profession_" + i, "minecraft:textures/entity/villager/farmer.png",
                    "minecraft:textures/entity/zombie_villager/zombie_farmer.png");
            ForgeRegistries.VILLAGER_PROFESSIONS.register(last);
        }
        int id = VillagerRegistry.getId(last);
        assertTrue(id >= 1024, "id " + id);
        assertSame(last, VillagerRegistry.getById(id));
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        DataSerializers.VARINT.write(buf, id);
        int synced = DataSerializers.VARINT.read(buf);
        assertEquals(id, synced);
        assertSame(last, ForgeRegistries.VILLAGER_PROFESSIONS.getValue(last.getRegistryName()));
    }
}
