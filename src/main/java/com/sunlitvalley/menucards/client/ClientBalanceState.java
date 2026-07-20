package com.sunlitvalley.menucards.client;

import net.minecraft.client.Minecraft;

import java.util.OptionalInt;
import java.util.UUID;

/** Client-only cache, scoped to the player that received the latest balance packet. */
public final class ClientBalanceState {
    private static UUID playerId;
    private static int balance;

    private ClientBalanceState() {
    }

    public static void update(int newBalance) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        playerId = minecraft.player.getUUID();
        balance = newBalance;
    }

    public static OptionalInt currentBalance() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.getUUID().equals(playerId)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(balance);
    }

    public static void clear() {
        playerId = null;
        balance = 0;
    }
}
