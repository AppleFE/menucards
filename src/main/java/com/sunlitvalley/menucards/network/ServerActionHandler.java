package com.sunlitvalley.menucards.network;

import com.sunlitvalley.menucards.CardIds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ServerActionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("MenuCards");
    private static final long COOLDOWN_MS = 500;
    private static final Map<UUID, Long> lastActionTime = new HashMap<>();

    public static void handle(ServerPlayer player, String cardId) {
        if (!CardIds.ALL.contains(cardId)) {
            LOGGER.debug("Rejected invalid cardId '{}' from {}", cardId, player.getUUID());
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastActionTime.get(player.getUUID());
        if (last != null && (now - last) < COOLDOWN_MS) {
            return;
        }
        lastActionTime.put(player.getUUID(), now);

        // v1: send translatable chat message stub
        player.sendSystemMessage(Component.translatable("menucards.action." + cardId));
    }
}
