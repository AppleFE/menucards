package com.sunlitvalley.menucards;

import com.sunlitvalley.menucards.network.ModNetwork;
import com.sunlitvalley.menucards.server.MenuCardsServerEvents;
import com.sunlitvalley.menucards.server.BalanceSync;
import com.sunlitvalley.menucards.server.TeleportCountdown;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MenuCardsMod.MOD_ID)
public class MenuCardsMod {
    public static final String MOD_ID = "menucards";

    public MenuCardsMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModSounds.SOUND_EVENTS.register(modBus);
        MinecraftForge.EVENT_BUS.addListener(MenuCardsServerEvents::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(MenuCardsServerEvents::onPlayerLogout);
        MinecraftForge.EVENT_BUS.addListener(MenuCardsServerEvents::onPlayerDeath);
        MinecraftForge.EVENT_BUS.addListener(MenuCardsServerEvents::onPlayerClone);
        MinecraftForge.EVENT_BUS.addListener(MenuCardsServerEvents::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(TeleportCountdown::onPlayerTick);
        MinecraftForge.EVENT_BUS.addListener(BalanceSync::onPlayerTick);
        modBus.addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
    }
}
