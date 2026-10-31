/*
 * Copyright (c) 2026 CleanroomMC contributors
 *
 * This file is licensed under the CleanroomMC License Version 1.0.
 * See the applicable LICENSE file in this directory or a parent directory
 * for the full licence terms.
 *
 * This is visible-source software and is not open-source software.
 */

package com.cleanroommc.client;

import com.cleanroommc.cleanroom.common.id.IncompatibleIdModsException;

import net.minecraftforge.fml.client.GuiErrorBase;

import java.io.File;

/**
 * Lists the installed mods that conflict with Cleanroom's wide IDs.
 */
public class GuiIncompatibleIdMods extends GuiErrorBase {

    private final IncompatibleIdModsException exception;

    /**
     * Creates the screen.
     *
     * @param exception the exception listing the conflicting mods
     */
    public GuiIncompatibleIdMods(IncompatibleIdModsException exception) {
        this.exception = exception;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int offset = Math.max(85 - exception.mods().size() * 10, 10);
        this.drawCenteredString(
            this.fontRenderer, "Cleanroom has found mods that are incompatible with it", this.width / 2, offset,
            0xFFFFFF
        );
        offset += 10;
        this.drawCenteredString(
            this.fontRenderer, "Cleanroom already extends the block, item and biome ID limits. Please remove:",
            this.width / 2, offset, 0xFFFFFF
        );
        offset += 5;
        for (File file : exception.mods().values()) {
            offset += 10;
            this.drawCenteredString(this.fontRenderer, file.getName(), this.width / 2, offset, 0xEEEEEE);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

}
