package com.sunlitvalley.menucards.integration.shipping;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Immutable, authenticated view of a single planning lease. */
public final class LeaseView {
    public static final int SLOTS = CanonicalInputFingerprint.SLOT_COUNT;
    private final UUID token;
    private final UUID playerId;
    private final ServerPlayer player;
    private final long inputGeneration;
    private final ItemStack[] input;

    public LeaseView(UUID token, ServerPlayer player, long inputGeneration, ItemStack[] input) {
        this.token = Objects.requireNonNull(token, "token");
        this.player = Objects.requireNonNull(player, "player");
        this.playerId = player.getUUID();
        this.inputGeneration = inputGeneration;
        this.input = copySlots(input);
        if (this.input.length != SLOTS) throw new IllegalArgumentException("SLOT_COUNT");
    }

    public UUID token() { return token; }
    public UUID playerId() { return playerId; }
    public ServerPlayer player() { return player; }
    public long inputGeneration() { return inputGeneration; }
    public ItemStack[] input() { return copySlots(input); }

    private static ItemStack[] copySlots(ItemStack[] source) {
        Objects.requireNonNull(source, "source");
        ItemStack[] copy = Arrays.copyOf(source, source.length);
        for (int slot = 0; slot < copy.length; slot++) copy[slot] = copy[slot] == null ? ItemStack.EMPTY : copy[slot].copy();
        return copy;
    }
}
