package com.sunlitvalley.menucards.server;

import com.sunlitvalley.menucards.command.MenuCardsCommands;
import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import com.sunlitvalley.menucards.network.ServerActionHandler;
import com.sunlitvalley.menucards.integration.shipping.ShippingBinMenuIntegration;
import com.sunlitvalley.menucards.integration.shipping.VirtualShippingBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Forge lifecycle hooks for command registration and transient Menu Cards state. */
public final class MenuCardsServerEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(MenuCardsServerEvents.class);
    private MenuCardsServerEvents() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MenuCardsCommands.register(event.getDispatcher());
    }

    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            releasePlayer(player);
        }
    }

    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            runCleanup("release virtual shipping menu for " + player.getUUID(),
                    () -> ShippingBinMenuIntegration.release(player));
            runCleanup("clear action state for " + player.getUUID(),
                    () -> ServerActionHandler.clearRuntimeState(player.getUUID()));
        }
    }

    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer player) {
            runCleanup("release virtual shipping menu for " + player.getUUID(),
                    () -> ShippingBinMenuIntegration.release(player));
            runCleanup("clear action state for " + player.getUUID(),
                    () -> ServerActionHandler.clearRuntimeState(player.getUUID()));
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            runCleanup("release virtual shipping menu for " + player.getUUID(),
                    () -> ShippingBinMenuIntegration.release(player));
        }
        runCleanup("clear action throttles", ServerActionHandler::clearRuntimeState);
        runCleanup("clear transient virtual shipping leases",
                () -> VirtualShippingSavedData.get(event.getServer().overworld())
                        .clearTransientLeases());
        runCleanup("clear active virtual shipping menu registry",
                ShippingBinMenuIntegration::clearActiveMenus);
        runCleanup("reset virtual shipping bridge",
                () -> VirtualShippingBridge.instance().resetRuntimeState());
    }

    private static void runCleanup(String operation, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error exception) {
            LOGGER.error("Failed to {}", operation, exception);
        }
    }

    private static void releasePlayer(ServerPlayer player) {
        BalanceSync.clear(player);
        runCleanup("release virtual shipping menu for " + player.getUUID(),
                () -> ShippingBinMenuIntegration.release(player));
        runCleanup("clear action throttles for " + player.getUUID(),
                () -> ServerActionHandler.clearRuntimeState(player.getUUID()));
    }
}
