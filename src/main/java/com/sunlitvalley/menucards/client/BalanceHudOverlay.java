package com.sunlitvalley.menucards.client;

import com.sunlitvalley.menucards.MenuCardsMod;
import io.github.chakyl.numismaticsutils.config.NumismaticsConfigClient;
import io.github.chakyl.numismaticsutils.utils.OverlayUtils;
import io.github.chakyl.numismaticsutils.utils.StringUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.OptionalInt;

/** Draws Numismatics' normal balance HUD when no bank meter is equipped. */
@Mod.EventBusSubscriber(modid = MenuCardsMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BalanceHudOverlay {
    private BalanceHudOverlay() {
    }

    @SubscribeEvent
    public static void registerOverlay(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("balance_without_meter", BalanceHudOverlay::render);
    }

    private static void render(ForgeGui forgeGui, GuiGraphics graphics, float partialTick,
            int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!OverlayUtils.shouldRender(minecraft) || hasBankMeter(minecraft)) {
            return;
        }

        OptionalInt currentBalance = ClientBalanceState.currentBalance();
        if (currentBalance.isEmpty()) {
            return;
        }

        int x = NumismaticsConfigClient.getHudX();
        int y = NumismaticsConfigClient.getHudY();
        float scale = (float) NumismaticsConfigClient.getHudScale();
        Component formattedBalance = Component
                .literal(StringUtils.formatBalance(currentBalance.getAsInt()))
                .withStyle(ChatFormatting.GOLD);

        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(minecraft.font,
                Component.translatable("gui.numismatics_utils.bank_meter", formattedBalance),
                x, y, 0xFFFFFF);
        graphics.pose().popPose();
    }

    private static boolean hasBankMeter(Minecraft minecraft) {
        if (minecraft.player == null) {
            return false;
        }
        return CuriosApi.getCuriosInventory(minecraft.player)
                .map(handler -> !handler.findCurios("bank_meter").isEmpty())
                .orElse(false);
    }
}
