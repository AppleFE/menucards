package com.sunlitvalley.menucards.server;

import com.sunlitvalley.menucards.network.BalanceSyncS2CPacket;
import com.sunlitvalley.menucards.network.ModNetwork;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import io.github.chakyl.numismaticsutils.utils.CurioUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps each client supplied with the balance that a bank meter would display. */
public final class BalanceSync {
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final Map<UUID, Integer> LAST_SENT_BALANCES = new HashMap<>();

    private BalanceSync() {
    }

    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || !(event.player instanceof ServerPlayer player)
                || player.tickCount % SYNC_INTERVAL_TICKS != 0) {
            return;
        }

        BankAccount account = CurioUtils.getPersonalOrCurioAccount(player.level(), player);
        if (account == null) {
            account = Numismatics.BANK.getOrCreateAccount(player.getUUID(), BankAccount.Type.PLAYER);
        }

        int balance = account.getBalance();
        Integer previous = LAST_SENT_BALANCES.put(player.getUUID(), balance);
        if (previous == null || previous != balance) {
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new BalanceSyncS2CPacket(balance));
        }
    }

    public static void clear(ServerPlayer player) {
        LAST_SENT_BALANCES.remove(player.getUUID());
    }
}
