package com.cleanroommc.common;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.LoadController;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * The ID format both sides of a connection must share: runtime block state numbers, and later the chunk, biome,
 * item and potion formats. The version is the format number; the FML handshake compares it in both directions,
 * so a client and server with different formats (or without this container) refuse each other at login instead of
 * misreading chunk and packet data.
 */
public class IdFormatContainer extends DummyModContainer {
    public static final String MOD_ID = "cleanroom_ids";
    public static final String FORMAT = "1";
    private static final Logger LOGGER = LogManager.getLogger("Cleanroom");

    public IdFormatContainer() {
        super(new ModMetadata());
        ModMetadata meta = this.getMetadata();
        meta.modId = MOD_ID;
        meta.name = "Cleanroom ID format";
        meta.description = "Runtime and saved ID formats (block states, chunks, biomes, items, potions). Both sides of a connection need the same format.";
        meta.version = FORMAT;
        meta.parent = CleanroomVersion.MOD_ID;
    }

    @Override
    public boolean registerBus(EventBus bus, LoadController controller) {
        bus.register(this);
        return true;
    }

    @Subscribe
    public void construct(FMLConstructionEvent event) {
        NetworkRegistry.INSTANCE.register(this, this.getClass(), null, event.getASMHarvestedData());
    }

    @NetworkCheckHandler
    public static boolean checkRemote(Map<String, String> remoteMods, Side remoteSide) {
        String remote = remoteMods.get(MOD_ID);
        if (FORMAT.equals(remote)) {
            return true;
        }
        // Empty: FML's startup probe for vanilla peers, or a vanilla peer (FML reports that itself)
        if (remoteMods.isEmpty()) {
            return false;
        }
        LOGGER.error("Cleanroom ID format mismatch: this side uses format {}, the {} uses {}.", FORMAT,
                remoteSide == Side.SERVER ? "server" : "client", remote == null ? "none (vanilla, Forge or an older Cleanroom)" : "format " + remote);
        return false;
    }
}
