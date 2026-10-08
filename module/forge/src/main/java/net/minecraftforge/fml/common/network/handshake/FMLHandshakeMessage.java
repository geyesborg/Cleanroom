/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.common.network.handshake;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.registries.RegistrySync;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

public abstract class FMLHandshakeMessage {
    public static FMLProxyPacket makeCustomChannelRegistration(Set<String> channels)
    {
        String salutation = Joiner.on('\0').join(Iterables.concat(Arrays.asList("FML|HS","FML", "FML|MP"),channels));
        FMLProxyPacket proxy = new FMLProxyPacket(new PacketBuffer(Unpooled.wrappedBuffer(salutation.getBytes(StandardCharsets.UTF_8))), "REGISTER");
        return proxy;
    }
    public static class ServerHello extends FMLHandshakeMessage {
        private byte serverProtocolVersion;
        private int overrideDimension;
        public ServerHello()
        {
            // noargs for the proto
        }
        public ServerHello(int overrideDim)
        {
            this.overrideDimension = overrideDim;
        }

        @Override
        public void toBytes(ByteBuf buffer)
        {
            buffer.writeByte(NetworkRegistry.FML_PROTOCOL);
            buffer.writeInt(overrideDimension);
        }

        @Override
        public void fromBytes(ByteBuf buffer)
        {
            serverProtocolVersion = buffer.readByte();
            // Extended dimension support during login
            if (serverProtocolVersion > 1)
            {
                overrideDimension = buffer.readInt();
                FMLLog.log.debug("Server FML protocol version {}, 4 byte dimension received {}", serverProtocolVersion, overrideDimension);
            }
            else
            {
                FMLLog.log.info("Server FML protocol version {}, no additional data received", serverProtocolVersion);
            }
        }

        public byte protocolVersion()
        {
            return serverProtocolVersion;
        }

        public int overrideDim() {
            return overrideDimension;
        }
    }
    public static class ClientHello extends FMLHandshakeMessage {
        private byte serverProtocolVersion;
        @Override
        public void toBytes(ByteBuf buffer)
        {
            buffer.writeByte(NetworkRegistry.FML_PROTOCOL);
        }

        @Override
        public void fromBytes(ByteBuf buffer)
        {
            serverProtocolVersion = buffer.readByte();
        }

        public byte protocolVersion()
        {
            return serverProtocolVersion;
        }
    }
    public static class ModList extends FMLHandshakeMessage {
        public ModList()
        {

        }
        public ModList(List<ModContainer> modList)
        {
            for (ModContainer mod : modList)
            {
                modTags.put(mod.getModId(), mod.getVersion());
            }
        }
        private Map<String,String> modTags = Maps.newHashMap();

        @Override
        public void toBytes(ByteBuf buffer)
        {
            super.toBytes(buffer);
            ByteBufUtils.writeVarInt(buffer, modTags.size(), 2);
            for (Map.Entry<String,String> modTag: modTags.entrySet())
            {
                ByteBufUtils.writeUTF8String(buffer, modTag.getKey());
                ByteBufUtils.writeUTF8String(buffer, modTag.getValue());
            }
        }

        @Override
        public void fromBytes(ByteBuf buffer)
        {
            super.fromBytes(buffer);
            int modCount = ByteBufUtils.readVarInt(buffer, 2);
            for (int i = 0; i < modCount; i++)
            {
                modTags.put(ByteBufUtils.readUTF8String(buffer), ByteBufUtils.readUTF8String(buffer));
            }
        }

        public String modListAsString()
        {
            return Joiner.on(',').withKeyValueSeparator("@").join(modTags);
        }

        public int modListSize()
        {
            return modTags.size();
        }
        public Map<String, String> modList()
        {
            return modTags;
        }

        @Override
        public String toString(Class<? extends Enum<?>> side)
        {
            return super.toString(side)+":"+modTags.size()+" mods";
        }
    }

    public static class RegistryHashes extends FMLHandshakeMessage
    {
        public RegistryHashes()
        {

        }

        public RegistryHashes(List<RegistrySync.Entry> entries)
        {
            this.entries = entries;
        }

        private List<RegistrySync.Entry> entries = new ArrayList<>();

        @Override
        public void fromBytes(ByteBuf buffer)
        {
            int length = ByteBufUtils.readVarInt(buffer, 5);
            entries = new ArrayList<>(length);

            for (int i = 0; i < length; i++)
            {
                ResourceLocation name = new ResourceLocation(ByteBufUtils.readUTF8String(buffer));
                int count = ByteBufUtils.readVarInt(buffer, 5);
                long hash = buffer.readLong();
                entries.add(new RegistrySync.Entry(name, count, hash));
            }
        }

        @Override
        public void toBytes(ByteBuf buffer)
        {
            ByteBufUtils.writeVarInt(buffer, entries.size(), 5);
            for (RegistrySync.Entry entry : entries)
            {
                ByteBufUtils.writeUTF8String(buffer, entry.registry().toString());
                ByteBufUtils.writeVarInt(buffer, entry.count(), 5);
                buffer.writeLong(entry.hash());
            }
        }

        public List<RegistrySync.Entry> entries()
        {
            return entries;
        }

        @Override
        public String toString(Class<? extends Enum<?>> side)
        {
            return super.toString(side) + ":"+entries.size()+" registries";
        }
    }
    public static class HandshakeAck extends FMLHandshakeMessage {
        int phase;
        public HandshakeAck() {}
        HandshakeAck(int phase)
        {
            this.phase = phase;
        }
        @Override
        public void fromBytes(ByteBuf buffer)
        {
            phase = buffer.readByte();
        }

        @Override
        public void toBytes(ByteBuf buffer)
        {
            buffer.writeByte(phase);
        }
        @Override
        public String toString(Class<? extends Enum<?>> side)
        {
            return super.toString(side) + ":{"+phase+"}";
        }
    }
    public static class HandshakeReset extends FMLHandshakeMessage {
        public HandshakeReset() {}
    }
    public void fromBytes(ByteBuf buffer)
    {
    }

    public void toBytes(ByteBuf buffer)
    {
    }

    public String toString(Class<? extends Enum<?>> side)
    {
        return getClass().getName().substring(getClass().getName().lastIndexOf('$'));
    }
}
