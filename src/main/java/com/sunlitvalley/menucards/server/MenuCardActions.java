package com.sunlitvalley.menucards.server;

import com.sunlitvalley.chunkyclaim.service.ClaimService;
import com.sunlitvalley.menucards.data.MenuCardsSpawnSavedData;
import com.sunlitvalley.menucards.integration.shipping.ShippingBinMenuIntegration;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-side implementations for Menu Card actions. */
public final class MenuCardActions {
    private static final Logger LOGGER = LoggerFactory.getLogger(MenuCardActions.class);
    private static final long HELP_COOLDOWN_TICKS = 1200L;
    private static final int MAX_HELP_REQUESTS_PER_WINDOW = 5;
    private static final Map<UUID, Long> LAST_HELP_REQUEST = new HashMap<>();
    private static final ArrayDeque<Long> HELP_REQUESTS = new ArrayDeque<>();
    private static final double TELEPORT_POSITION_TOLERANCE = 1.0E-4D;
    private static final float TELEPORT_ROTATION_TOLERANCE = 1.0E-4F;

    private MenuCardActions() {
    }

    public static boolean townHome(ServerPlayer player) {
        TeleportEffects.Source source = TeleportEffects.captureSource(player);
        ClaimService.ActionResult result = ClaimService.teleportHome(player);
        if (!result.success()) {
            return false;
        }
        TeleportEffects.play(player, source);
        return true;
    }

    public static boolean spawn(ServerPlayer player) {
        Optional<MenuCardsSpawnSavedData.SpawnPose> spawn = MenuCardsSpawnSavedData.get(player.getServer()).getSpawn();
        if (spawn.isEmpty()) {
            return false;
        }
        MenuCardsSpawnSavedData.SpawnPose pose = spawn.get();
        ServerLevel level = player.getServer().getLevel(pose.dimension());
        if (level == null) {
            return false;
        }
        Vec3 destination = new Vec3(pose.x(), pose.y(), pose.z());
        AABB bounds = TeleportSafety.destinationBounds(player, destination);
        try {
            if (!TeleportSafety.loadChunks(level, destination, bounds)) return false;
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to load configured MenuCards spawn chunks", exception);
            return false;
        }
        if (!TeleportSafety.isSafe(player, level, destination, bounds)) {
            return false;
        }
        TeleportEffects.Source source = TeleportEffects.captureSource(player);
        player.teleportTo(level, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
        if (!teleportCompleted(player, level, destination, pose.yaw(), pose.pitch())) {
            return false;
        }
        TeleportEffects.play(player, source);
        return true;
    }

    public static void shipping(ServerPlayer player) {
        ShippingBinMenuIntegration.open(player);
    }

    public static boolean help(ServerPlayer player) {
        long now = player.getServer().overworld().getGameTime();
        Long previous = LAST_HELP_REQUEST.get(player.getUUID());
        if (previous != null && now - previous < HELP_COOLDOWN_TICKS) {
            player.sendSystemMessage(Component.translatable("menucards.action.help.cooldown"));
            return false;
        }
        while (!HELP_REQUESTS.isEmpty() && now - HELP_REQUESTS.peekFirst() >= HELP_COOLDOWN_TICKS) {
            HELP_REQUESTS.removeFirst();
        }
        LAST_HELP_REQUEST.entrySet().removeIf(
                entry -> now - entry.getValue() >= HELP_COOLDOWN_TICKS);
        if (HELP_REQUESTS.size() >= MAX_HELP_REQUESTS_PER_WINDOW) {
            player.sendSystemMessage(Component.translatable("menucards.action.help.rate_limited"));
            return false;
        }
        LAST_HELP_REQUEST.put(player.getUUID(), now);
        HELP_REQUESTS.addLast(now);

        player.sendSystemMessage(Component.literal("운영자를 호출하였습니다. 잠시만 기다려 주세요."));
        Component alert = player.getDisplayName().copy().append(Component.literal(" 님이 도움을 요청하였습니다."));
        for (ServerPlayer recipient : player.getServer().getPlayerList().getPlayers()) {
            if (player.getServer().getPlayerList().isOp(recipient.getGameProfile())) {
                recipient.sendSystemMessage(alert);
                recipient.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.0F);
            }
        }
        return true;
    }

    public static boolean skullCave(ServerPlayer player) {
        long enterDay = Math.floorDiv(player.serverLevel().getDayTime(), 24000L) + 1L;
        Optional<SkullCavernDestinationResolver.Destination> destination =
                SkullCavernDestinationResolver.resolve(player.getServer(), player);
        if (destination.isEmpty()) {
            return false;
        }
        SkullCavernDestinationResolver.Destination target = destination.get();
        float targetYaw = player.getYRot();
        float targetPitch = player.getXRot();
        TeleportEffects.Source source = TeleportEffects.captureSource(player);
        player.teleportTo(target.level(), target.position().x, target.position().y, target.position().z,
                targetYaw, targetPitch);
        if (!teleportCompleted(player, target.level(), target.position(), targetYaw, targetPitch)) {
            return false;
        }
        player.getPersistentData().putLong("skullCavernEnterDay", enterDay);
        TeleportEffects.play(player, source);
        return true;
    }

    static boolean teleportCompleted(ServerPlayer player, ServerLevel targetLevel, Vec3 targetPosition,
            float targetYaw, float targetPitch) {
        return player.serverLevel() == targetLevel
                && Math.abs(player.getX() - targetPosition.x) <= TELEPORT_POSITION_TOLERANCE
                && Math.abs(player.getY() - targetPosition.y) <= TELEPORT_POSITION_TOLERANCE
                && Math.abs(player.getZ() - targetPosition.z) <= TELEPORT_POSITION_TOLERANCE
                && Math.abs(Mth.wrapDegrees(player.getYRot() - targetYaw)) <= TELEPORT_ROTATION_TOLERANCE
                && Math.abs(player.getXRot() - targetPitch) <= TELEPORT_ROTATION_TOLERANCE;
    }

    public static void clearRuntimeState() {
        LAST_HELP_REQUEST.clear();
        HELP_REQUESTS.clear();
        SkullCavernDestinationResolver.clearCache();
    }

}
