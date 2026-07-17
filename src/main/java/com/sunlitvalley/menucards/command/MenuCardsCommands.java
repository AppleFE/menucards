package com.sunlitvalley.menucards.command;

import com.mojang.brigadier.CommandDispatcher;
import com.sunlitvalley.menucards.data.MenuCardsSpawnSavedData;
import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import com.sunlitvalley.menucards.integration.shipping.VirtualShippingBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Commands whose administration is deliberately restricted to online ops.json profiles. */
public final class MenuCardsCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger(MenuCardsCommands.class);

    private MenuCardsCommands() {
    }

    public static boolean isExplicitOnlineOperator(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null
                && source.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && source.getServer().getPlayerList().isOp(player.getGameProfile());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("스폰설정")
                .requires(MenuCardsCommands::isExplicitOnlineOperator)
                .executes(context -> setSpawn(context.getSource())));
        var recoveryPlayer = Commands.argument("player", EntityArgument.player());
        recoveryPlayer.then(Commands.literal("상태")
                .executes(context -> shippingStatus(
                        context.getSource(), EntityArgument.getPlayer(context, "player"))));
        recoveryPlayer.then(Commands.literal("해제")
                .then(Commands.argument("token", UuidArgument.uuid())
                        .then(Commands.literal("확인")
                                .executes(context -> recoverShipping(
                                        context.getSource(),
                                        EntityArgument.getPlayer(context, "player"),
                                        UuidArgument.getUuid(context, "token"))))));
        dispatcher.register(Commands.literal("메뉴카드")
                .requires(MenuCardsCommands::isExplicitOnlineOperator)
                .then(Commands.literal("배송복구").then(recoveryPlayer)));
    }

    private static int setSpawn(CommandSourceStack source) {
        // Recheck at mutation time so a de-op between Brigadier's check and execution cannot write.
        if (!isExplicitOnlineOperator(source)) {
            LOGGER.warn("Denied MenuCards spawn configuration at execution time.");
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        MenuCardsSpawnSavedData.get(source.getServer()).setSpawn(new MenuCardsSpawnSavedData.SpawnPose(
                player.level().dimension(), player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()));
        player.sendSystemMessage(Component.literal("현재 위치를 스폰 지점으로 설정했습니다."));
        return 1;
    }
    private static int shippingStatus(CommandSourceStack source, ServerPlayer owner) {
        if (!isExplicitOnlineOperator(source)) {
            LOGGER.warn("Denied MenuCards shipping status access at execution time.");
            return 0;
        }
        ServerPlayer actor = source.getPlayer();
        if (actor == null) {
            return 0;
        }
        VirtualShippingSavedData.Status status = VirtualShippingBridge.instance().status(
                source.getServer(), actor, owner.getUUID());
        if (status == null) {
            source.sendSuccess(() -> Component.literal("배송 상태: 기록 없음."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("배송 상태: " + status.state()
                + ", 사유: " + status.reason() + ", 토큰: "
                + (status.token() == null ? "없음" : status.token())
                + ", 복구 감사 대기: " + status.recoveryAuditPending()
                + ", 복구 횟수: " + status.recoveryCount()), false);
        return 1;
    }

    private static int recoverShipping(CommandSourceStack source, ServerPlayer owner, java.util.UUID token) {
        if (!isExplicitOnlineOperator(source)) {
            LOGGER.warn("Denied MenuCards shipping recovery at execution time.");
            return 0;
        }
        ServerPlayer actor = source.getPlayer();
        if (actor == null) {
            return 0;
        }
        VirtualShippingSavedData.RecoveryResult result = VirtualShippingBridge.instance().recover(
                source.getServer(), actor, owner.getUUID(), token);
        if (result != VirtualShippingSavedData.RecoveryResult.SUCCESS) {
            source.sendFailure(Component.literal("배송 복구 실패: " + result));
            return 0;
        }
        VirtualShippingSavedData.Status status = VirtualShippingBridge.instance().status(
                source.getServer(), actor, owner.getUUID());
        source.sendSuccess(() -> Component.literal("배송 복구 완료: 상태: " + status.state()
                + ", 복구 횟수: " + status.recoveryCount()), false);
        return 1;
    }
}
