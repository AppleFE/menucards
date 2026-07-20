package com.sunlitvalley.menucards.network;

import com.sunlitvalley.menucards.client.ClientBalanceState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Sends the server-authoritative Numismatics balance to the local HUD. */
public record BalanceSyncS2CPacket(int balance) {
    public static void encode(BalanceSyncS2CPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.balance);
    }

    public static BalanceSyncS2CPacket decode(FriendlyByteBuf buffer) {
        return new BalanceSyncS2CPacket(buffer.readVarInt());
    }

    public static void handle(BalanceSyncS2CPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientBalanceState.update(packet.balance)));
        context.setPacketHandled(true);
    }
}
