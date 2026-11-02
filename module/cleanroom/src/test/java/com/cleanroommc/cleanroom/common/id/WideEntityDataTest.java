/*
 * Copyright (c) 2026 CleanroomMC contributors
 *
 * This file is licensed under the CleanroomMC License Version 1.0.
 * See the applicable LICENSE file in this directory or a parent directory
 * for the full licence terms.
 *
 * This is visible-source software and is not open-source software.
 */

package com.cleanroommc.cleanroom.common.id;

import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraftforge.fml.common.registry.ForgeTestRunner;
import net.minecraftforge.fml.common.registry.MinecraftTestHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ForgeTestRunner.Isolated
public class WideEntityDataTest {

    @BeforeAll
    public static void setup() {
        MinecraftTestHarness.bootstrap();
    }

    @Test
    public void entityDataGrowsPastAnyIdAndKeepsIdOrder() throws Exception {
        EntityDataManager manager = new EntityDataManager(null);
        DataParameter<Integer> high = DataSerializers.VARINT.createKey(300);
        DataParameter<Integer> low = DataSerializers.VARINT.createKey(2);
        DataParameter<Integer> unregistered = DataSerializers.VARINT.createKey(1000);
        manager.register(high, 7);
        manager.register(low, 3);

        assertThat(manager.get(high)).isEqualTo(7);
        assertThat(manager.get(low)).isEqualTo(3);
        assertThatThrownBy(() -> manager.register(high, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.get(unregistered)).isInstanceOf(NullPointerException.class);

        List<EntityDataManager.DataEntry<?>> all = manager.getAll();
        assertThat(all).extracting(e -> e.getKey().getId()).containsExactly(2, 300);

        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        manager.writeEntries(buf);
        List<EntityDataManager.DataEntry<?>> read = EntityDataManager.readEntries(buf);
        assertThat(read).extracting(e -> e.getKey().getId()).containsExactly(2, 300);
        assertThat(read).extracting(e -> (Object) e.getValue()).containsExactly(3, 7);
        buf.release();
    }

    @Test
    public void endermanSavesCarriedBlockByName() {
        IBlockState spruce = Blocks.LOG.getDefaultState()
            .withProperty(BlockLog.LOG_AXIS, BlockLog.EnumAxis.Y)
            .withProperty(net.minecraft.block.BlockOldLog.VARIANT, BlockPlanks.EnumType.SPRUCE);
        EntityEnderman enderman = new EntityEnderman(null);
        enderman.setHeldBlockState(spruce);
        NBTTagCompound tag = new NBTTagCompound();
        enderman.writeEntityToNBT(tag);

        assertThat(tag.hasKey("carried", 8)).isTrue();
        assertThat(tag.getString("carried")).isEqualTo("minecraft:log");

        EntityEnderman read = new EntityEnderman(null);
        read.readEntityFromNBT(tag);
        assertThat(read.getHeldBlockState()).isEqualTo(spruce);
    }

    @Test
    public void endermanCarryingARemovedModBlockLoadsEmptyHanded() {
        EntityEnderman enderman = new EntityEnderman(null);
        NBTTagCompound tag = new NBTTagCompound();
        enderman.writeEntityToNBT(tag);
        tag.setString("carried", "removedmod:gone");
        tag.setShort("carriedData", (short) 0);

        EntityEnderman read = new EntityEnderman(null);
        read.readEntityFromNBT(tag);
        assertThat(read.getHeldBlockState()).isNull();
    }

}
