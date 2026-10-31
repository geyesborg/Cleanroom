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

import com.cleanroommc.client.GuiIncompatibleIdMods;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IDisplayableError;
import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown when mods that raise vanilla's ID limits themselves (JEID, REID, NEID, HadEnoughIDs) are installed, since
 * they conflict with Cleanroom's wide IDs.
 */
public class IncompatibleIdModsException extends LoaderException implements IDisplayableError {

    private static final long serialVersionUID = 1L;

    private final Map<String, File> mods;

    /**
     * Creates the exception.
     *
     * @param mods the conflicting mods' loading plugin class names mapped to their files
     */
    public IncompatibleIdModsException(Map<String, File> mods) {
        super("Mods incompatible with Cleanroom wide IDs are installed: " + mods.keySet());
        this.mods = Collections.unmodifiableMap(new LinkedHashMap<>(mods));
    }

    /**
     * Returns the conflicting mods.
     *
     * @return the conflicting mods' loading plugin class names mapped to their files
     */
    public Map<String, File> mods() {
        return mods;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public GuiScreen createGui() {
        return new GuiIncompatibleIdMods(this);
    }

    @Override
    protected void printStackTrace(WrappedPrintStream stream) {
        stream.println("Mods incompatible with Cleanroom wide IDs (Cleanroom already extends the ID limits):");
        mods.forEach((plugin, file) -> stream.println(String.format("\t%s : %s", file.getName(), plugin)));
        stream.println("");
    }

}
