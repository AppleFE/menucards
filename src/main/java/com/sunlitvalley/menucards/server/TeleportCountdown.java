package com.sunlitvalley.menucards.server;

import com.sunlitvalley.menucards.CardIds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Delays menu teleports until the player has remained in place for five seconds. */
public final class TeleportCountdown {
    static final long WAIT_TICKS = 100L;
    private static final double MOVEMENT_TOLERANCE_SQUARED = 1.0E-8D;
    private static final Map<UUID, PendingTeleport> PENDING = new HashMap<>();

    private TeleportCountdown() {
    }

    public static void begin(ServerPlayer player, String cardId) {
        if (!CardIds.TOWN_HOME.equals(cardId) && !CardIds.SPAWN.equals(cardId)) {
            throw new IllegalArgumentException("Unsupported delayed teleport card: " + cardId);
        }

        long now = player.getServer().overworld().getGameTime();
        PendingTeleport pending = new PendingTeleport(
                cardId, player.serverLevel().dimension(), player.position(), now, 5);
        PENDING.put(player.getUUID(), pending);
        showCountdown(player, pending, 5);
    }

    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }

        PendingTeleport pending = PENDING.get(player.getUUID());
        if (pending == null) {
            return;
        }
        if (player.getServer().getPlayerList().getPlayer(player.getUUID()) != player || !player.isAlive()) {
            PENDING.remove(player.getUUID());
            return;
        }
        if (positionChanged(pending.dimension, pending.position, player)) {
            PENDING.remove(player.getUUID());
            player.displayClientMessage(
                    Component.translatable("menucards.teleport.cancelled_moved")
                            .withStyle(ChatFormatting.RED),
                    true);
            return;
        }

        long elapsed = player.getServer().overworld().getGameTime() - pending.startedAt;
        if (elapsed >= WAIT_TICKS) {
            PENDING.remove(player.getUUID());
            complete(player, pending.cardId);
            return;
        }

        int remainingSeconds = remainingSeconds(elapsed);
        if (remainingSeconds != pending.lastShownSeconds) {
            pending.lastShownSeconds = remainingSeconds;
            showCountdown(player, pending, remainingSeconds);
        }
    }

    static boolean positionChanged(ResourceKey<Level> startDimension, Vec3 startPosition, ServerPlayer player) {
        return !player.serverLevel().dimension().equals(startDimension)
                || player.position().distanceToSqr(startPosition) > MOVEMENT_TOLERANCE_SQUARED;
    }

    static int remainingSeconds(long elapsedTicks) {
        long remainingTicks = Math.max(1L, WAIT_TICKS - elapsedTicks);
        return (int) ((remainingTicks + 19L) / 20L);
    }

    public static void clear(UUID playerId) {
        PENDING.remove(playerId);
    }

    public static void clear() {
        PENDING.clear();
    }

    private static void showCountdown(ServerPlayer player, PendingTeleport pending, int remainingSeconds) {
        Component destination = Component.translatable("menucards.destination." + pending.cardId);
        player.displayClientMessage(
                Component.translatable("menucards.teleport.countdown", destination, remainingSeconds)
                        .withStyle(ChatFormatting.YELLOW),
                true);
    }

    private static void complete(ServerPlayer player, String cardId) {
        boolean success = switch (cardId) {
            case CardIds.TOWN_HOME -> MenuCardActions.townHome(player);
            case CardIds.SPAWN -> MenuCardActions.spawn(player);
            default -> throw new IllegalStateException("Unsupported delayed teleport card: " + cardId);
        };
        String result = success ? "success" : "failure";
        player.displayClientMessage(
                Component.translatable("menucards.teleport." + result)
                        .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED),
                true);
    }

    private static final class PendingTeleport {
        private final String cardId;
        private final ResourceKey<Level> dimension;
        private final Vec3 position;
        private final long startedAt;
        private int lastShownSeconds;

        private PendingTeleport(String cardId, ResourceKey<Level> dimension, Vec3 position,
                long startedAt, int lastShownSeconds) {
            this.cardId = cardId;
            this.dimension = dimension;
            this.position = position;
            this.startedAt = startedAt;
            this.lastShownSeconds = lastShownSeconds;
        }
    }
}
