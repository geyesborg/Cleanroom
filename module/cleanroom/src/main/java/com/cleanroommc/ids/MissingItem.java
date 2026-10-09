package com.cleanroommc.ids;

import com.cleanroommc.common.CleanroomVersion;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.event.RegistryEvent;

/**
 * The one internal stack a saved item with an unresolvable name reads as. It carries the whole original
 * compound verbatim under {@link #RETAINED} inside its tag and writes that compound back out, so a
 * load-save cycle while the mod is absent loses nothing. Not a per-name placeholder: two different
 * originals never merge because their tags differ.
 */
public final class MissingItem extends Item {
    public static Item INSTANCE;
    /** The tag key under the stack tag holding the original {@code ItemStack} compound, verbatim. */
    public static final String RETAINED = "CleanroomRetained";

    public MissingItem() {
        setCreativeTab(null);
        setTranslationKey("cleanroom.missing_item");
        setRegistryName(new ResourceLocation("cleanroom", "missing_item"));
    }

    @SubscribeEvent
    public static void registerMissingItem(RegistryEvent.Register<Item> event) {
        if (INSTANCE != null) {
            return;
        }
        ModContainer cleanroom = Loader.instance().getIndexedModList().get(CleanroomVersion.MOD_ID);
        if (cleanroom != null) {
            Loader.instance().setActiveModContainer(cleanroom); // the item's "cleanroom" domain is ours, not the event owner's
        }
        INSTANCE = new MissingItem();
        event.getRegistry().register(INSTANCE);
        if (cleanroom != null) {
            Loader.instance().setActiveModContainer(null);
        }
    }
}
