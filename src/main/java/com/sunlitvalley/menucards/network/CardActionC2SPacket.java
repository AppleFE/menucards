package com.sunlitvalley.menucards.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class CardActionC2SPacket {
    private final String cardId;

    public CardActionC2SPacket(String cardId) {
        this.cardId = cardId;
    }

    public String getCardId() {
        return cardId;
    }

    public static void encode(CardActionC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.cardId);
    }

    public static CardActionC2SPacket decode(FriendlyByteBuf buf) {
        return new CardActionC2SPacket(buf.readUtf(64));
    }

    public static void handle(CardActionC2SPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ServerPlayer player = ctx.getSender();
        if (player != null) {
            ServerActionHandler.handle(player, msg.cardId);
        }
        ctx.setPacketHandled(true);
    }
}
