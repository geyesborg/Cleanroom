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

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.handshake.FMLHandshakeMessage.ServerHello;
import net.minecraftforge.fml.common.network.internal.FMLMessage;
import net.minecraftforge.fml.common.network.internal.FMLNetworkHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;
import net.minecraftforge.registries.RegistrySync;

/**
 * Packet handshake sequence manager- client side (responding to remote server)
 *
 * Flow:
 * 1. Wait for server hello. (START). Move to HELLO state.
 * 2. Receive Server Hello. Send customchannel registration. Send Client Hello. Send our modlist. Move to WAITINGFORSERVERDATA state.
 * 3. Receive server modlist. Send ack if acceptable, else send nack and exit error. Receive server IDs. Move to COMPLETE state. Send ack.
 *
 * @author cpw
 *
 */
enum FMLHandshakeClientState implements IHandshakeState<FMLHandshakeClientState>
{
    START
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            cons.accept(HELLO);
            NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
            dispatcher.clientListenForServerHandshake();
        }
    },
    HELLO
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            boolean isVanilla = msg == null;
            if (isVanilla)
            {
                cons.accept(DONE);
            }
            else
            {
                cons.accept(WAITINGSERVERDATA);
            }
            // write our custom packet registration, always
            ctx.writeAndFlush(FMLHandshakeMessage.makeCustomChannelRegistration(NetworkRegistry.INSTANCE.channelNamesFor(Side.CLIENT)));
            if (isVanilla)
            {
                NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
                dispatcher.abortClientHandshake("VANILLA");
                // VANILLA login
                return;
            }

            ServerHello serverHelloPacket = (FMLHandshakeMessage.ServerHello)msg;
            FMLLog.log.info("Server protocol version {}", Integer.toHexString(serverHelloPacket.protocolVersion()));
            if (serverHelloPacket.protocolVersion() > 1)
            {
                // Server sent us an extra dimension for the logging in player - stash it for retrieval later
                NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
                dispatcher.setOverrideDimension(serverHelloPacket.overrideDim());
            }
            ctx.writeAndFlush(new FMLHandshakeMessage.ClientHello()).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            ctx.writeAndFlush(new FMLHandshakeMessage.ModList(Loader.instance().getActiveModList())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    },

    WAITINGSERVERDATA
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            String modRejections = FMLNetworkHandler.checkModList((FMLHandshakeMessage.ModList) msg, Side.SERVER);
            if (modRejections != null)
            {
                cons.accept(ERROR);
                NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
                dispatcher.rejectHandshake(modRejections);
                return;
            }
            if (!ctx.channel().attr(NetworkDispatcher.IS_LOCAL).get())
            {
                cons.accept(WAITINGSERVERCOMPLETE);
            }
            else
            {
                cons.accept(PENDINGCOMPLETE);
            }
            ctx.writeAndFlush(new FMLHandshakeMessage.HandshakeAck(ordinal())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);

        }
    },
    WAITINGSERVERCOMPLETE
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            FMLHandshakeMessage.RegistryHashes pkt = (FMLHandshakeMessage.RegistryHashes)msg;
            List<ResourceLocation> mismatches = RegistrySync.mismatches(pkt.entries(), RegistryManager.ACTIVE);
            if (!mismatches.isEmpty())
            {
                // The server's numbering is authoritative: ask for its ordered name list per
                // mismatched registry so we can adopt it, keeping our own extra entries after
                // the server's block.
                ctx.channel().attr(PENDING_REGISTRY_NAMES).set(new PendingRegistryNames(mismatches));
                cons.accept(WAITINGREGISTRYNAMES);
                ctx.writeAndFlush(new FMLHandshakeMessage.RegistryNamesRequest(mismatches)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                return;
            }
            cons.accept(PENDINGCOMPLETE);
            ctx.writeAndFlush(new FMLHandshakeMessage.HandshakeAck(ordinal())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    },
    WAITINGREGISTRYNAMES
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
            PendingRegistryNames pending = ctx.channel().attr(PENDING_REGISTRY_NAMES).get();
            if (!(msg instanceof FMLHandshakeMessage.RegistryNames) || pending == null || !pending.names.containsKey(((FMLHandshakeMessage.RegistryNames)msg).registry()))
            {
                cons.accept(ERROR);
                dispatcher.rejectHandshake("Expected registry name lists, received " + msg);
                return;
            }
            FMLHandshakeMessage.RegistryNames pkt = (FMLHandshakeMessage.RegistryNames)msg;
            pending.names.get(pkt.registry()).addAll(pkt.names());
            if (pkt.hasMore())
            {
                return; // this registry's name list continues in the next message
            }
            pending.finished.add(pkt.registry());
            ForgeRegistry<?> registry = RegistryManager.ACTIVE.getRegistry(pkt.registry());
            List<ResourceLocation> order = new ArrayList<>(pending.names.get(pkt.registry()));
            if (registry == null)
            {
                // The whole registry is absent on this client: every server entry is missing.
                pending.missing.put(pkt.registry(), order);
            }
            else
            {
                // Renumbering touches registries the client thread iterates; run it there like world snapshot loading did.
                RegistrySync.Reconciliation result = Futures.getUnchecked(Minecraft.getMinecraft().addScheduledTask(() -> RegistrySync.reconcile(registry, order)));
                if (!result.missingLocally().isEmpty())
                {
                    pending.missing.put(pkt.registry(), result.missingLocally());
                }
            }
            if (pending.finished.size() < pending.names.size())
            {
                return;
            }
            if (!pending.missing.isEmpty())
            {
                String problem = missingEntriesMessage(pending);
                cons.accept(ERROR);
                dispatcher.rejectHandshake(problem);
                FMLLog.log.fatal("Failed to connect to server: registry mismatch\n{}", problem);
                return;
            }
            Map<ResourceLocation, Map<ResourceLocation, Integer[]>> remaps = Maps.newHashMap();
            for (ResourceLocation name : pending.names.keySet())
            {
                ForgeRegistry<?> adopted = RegistryManager.ACTIVE.getRegistry(name);
                Map<ResourceLocation, Integer[]> remap = adopted == null ? Maps.newHashMap() : RegistrySync.lastRemap(adopted);
                if (!remap.isEmpty())
                {
                    remaps.put(name, remap);
                }
            }
            // Same remap event fired after adopting remote ids: not a freeze, the mappings revert on disconnect.
            Futures.getUnchecked(Minecraft.getMinecraft().addScheduledTask(() ->
            {
                Loader.instance().fireRemapEvent(remaps, false);
                return null;
            }));
            cons.accept(PENDINGCOMPLETE);
            ctx.writeAndFlush(new FMLHandshakeMessage.HandshakeAck(ordinal())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    },
    PENDINGCOMPLETE
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            cons.accept(COMPLETE);
            ctx.writeAndFlush(new FMLHandshakeMessage.HandshakeAck(ordinal())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    },
    COMPLETE
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            cons.accept(DONE);
            NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
            dispatcher.completeClientHandshake();
            FMLMessage.CompleteHandshake complete = new FMLMessage.CompleteHandshake(Side.CLIENT);
            ctx.fireChannelRead(complete);
            ctx.writeAndFlush(new FMLHandshakeMessage.HandshakeAck(ordinal())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    },
    DONE
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            if (msg instanceof FMLHandshakeMessage.HandshakeReset)
            {
                cons.accept(HELLO);
                //Run the revert on the client thread in case things are currently running to prevent race conditions while rebuilding the registries.
                Minecraft.getMinecraft().addScheduledTask(GameData::restoreLocalNumbering);
            }
        }
    },
    ERROR
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
        }
    };

    private static final AttributeKey<PendingRegistryNames> PENDING_REGISTRY_NAMES = AttributeKey.valueOf("fml:pendingRegistryNames");

    /** Per-connection accumulation of a server's registry name lists while the client reconciles. */
    private static final class PendingRegistryNames
    {
        private final Map<ResourceLocation, List<ResourceLocation>> names = new LinkedHashMap<>();
        private final Set<ResourceLocation> finished = new HashSet<>();
        private final Map<ResourceLocation, List<ResourceLocation>> missing = new LinkedHashMap<>();

        private PendingRegistryNames(List<ResourceLocation> registries)
        {
            for (ResourceLocation name : registries)
            {
                names.put(name, new ArrayList<ResourceLocation>());
            }
        }
    }

    private static String missingEntriesMessage(PendingRegistryNames pending)
    {
        StringBuilder sb = new StringBuilder("This client is missing registry entries the server requires:");
        pending.missing.forEach((registry, names) ->
        {
            sb.append("\nRegistry ").append(registry).append(": missing ");
            int shown = Math.min(names.size(), 20);
            for (int i = 0; i < shown; i++)
            {
                if (i > 0)
                {
                    sb.append(", ");
                }
                sb.append(names.get(i));
            }
            if (names.size() > shown)
            {
                sb.append(" ... and ").append(names.size() - shown).append(" more");
            }
        });
        return sb.toString();
    }
}
