package com.sunlitvalley.menucards.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.sunlitvalley.menucards.MenuCardsMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = MenuCardsMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientModEvents {

    public static final KeyMapping KEY_OPEN_MENU = new KeyMapping(
        "key.menucards.open",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_G,
        "key.categories.menucards"
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KEY_OPEN_MENU);
    }
}
