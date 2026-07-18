package com.sunlitvalley.menucards;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, MenuCardsMod.MOD_ID);

    public static final RegistryObject<SoundEvent> MENU_CARD_INTERACTION = SOUND_EVENTS.register(
            "menu_card_interaction",
            () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(MenuCardsMod.MOD_ID, "menu_card_interaction")));

    private ModSounds() {
    }
}
