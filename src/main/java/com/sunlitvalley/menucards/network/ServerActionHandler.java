package com.sunlitvalley.menucards.network;

import com.sunlitvalley.menucards.CardIds;
import com.sunlitvalley.menucards.server.MenuCardActions;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ServerActionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("MenuCards");
    private static final long SHIPPING_COOLDOWN_TICKS = 10L;
    private static final Map<UUID, Long> LAST_SHIPPING_ACTION = new HashMap<>();

    private ServerActionHandler() {
    }

    public static void handle(ServerPlayer player, String cardId) {
        player.getServer().execute(() -> handleOnServerThread(player, cardId));
    }

    private static void handleOnServerThread(ServerPlayer player, String cardId) {
        if (player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
            return;
        }
        if (!CardIds.ALL.contains(cardId)) {
            LOGGER.debug("Rejected invalid cardId '{}' from {}", cardId, player.getUUID());
            return;
        }

        switch (cardId) {
            case CardIds.TOWN_HOME -> MenuCardActions.townHome(player);
            case CardIds.SPAWN -> MenuCardActions.spawn(player);
            case CardIds.SELL -> {
                if (allow(LAST_SHIPPING_ACTION, player, player.getServer().overworld().getGameTime(),
                        SHIPPING_COOLDOWN_TICKS)) {
                    MenuCardActions.shipping(player);
                }
            }
            case CardIds.HELP -> MenuCardActions.help(player);
            case CardIds.SKULL_CAVE -> MenuCardActions.skullCave(player);
            default -> throw new IllegalStateException("Validated card ID was not routed: " + cardId);
        }
    }

    private static boolean allow(Map<UUID, Long> actions, ServerPlayer player, long now, long cooldown) {
        Long previous = actions.get(player.getUUID());
        if (previous != null && now - previous < cooldown) {
            return false;
        }
        actions.put(player.getUUID(), now);
        return true;
    }

    public static void clearRuntimeState(UUID playerId) {
        LAST_SHIPPING_ACTION.remove(playerId);
    }
    public static void clearRuntimeState() {
        LAST_SHIPPING_ACTION.clear();
        MenuCardActions.clearRuntimeState();
    }
}
