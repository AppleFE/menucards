package com.sunlitvalley.menucards.integration.shipping;

import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import com.sunlitvalley.menucards.inventory.InventorySide;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** A flat 54-slot view over the two persisted virtual shipping inventory sides. */
final class MenuCardsSmartShippingContainer implements Container {
    static final int SLOT_COUNT = VirtualShippingSavedData.SLOTS * 2;

    private final VirtualShippingSavedData data;
    private final UUID owner;
    private final ItemStack[] views = new ItemStack[SLOT_COUNT];
    private final long[] viewGenerations = new long[SLOT_COUNT];

    MenuCardsSmartShippingContainer(VirtualShippingSavedData data, UUID owner) {
        this.data = data;
        this.owner = owner;
        Arrays.fill(views, ItemStack.EMPTY);
        Arrays.fill(viewGenerations, -1L);
    }

    @Override public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override public boolean isEmpty() {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (!getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    @Override public ItemStack getItem(int slot) {
        return validSlot(slot) ? refresh(slot) : ItemStack.EMPTY;
    }

    @Override public ItemStack removeItem(int slot, int amount) {
        if (!validSlot(slot)) return ItemStack.EMPTY;
        ItemStack extracted = data.extract(owner, side(slot), sideSlot(slot), amount, false);
        refresh(slot);
        return extracted;
    }

    @Override public ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = getItem(slot);
        return current.isEmpty() ? ItemStack.EMPTY : removeItem(slot, current.getCount());
    }

    @Override public void setItem(int slot, ItemStack stack) {
        if (!validSlot(slot)) return;
        ItemStack replacement = stack == null ? ItemStack.EMPTY : stack.copy();
        ItemStack authoritative = data.getStack(owner, side(slot), sideSlot(slot));
        if (ItemStack.matches(authoritative, replacement)) {
            views[slot] = authoritative;
            viewGenerations[slot] = data.slotGeneration(owner, side(slot), sideSlot(slot));
            return;
        }
        if (!data.setStack(owner, side(slot), sideSlot(slot), replacement)) {
            views[slot] = data.getStack(owner, side(slot), sideSlot(slot));
            viewGenerations[slot] = data.slotGeneration(owner, side(slot), sideSlot(slot));
            throw new IllegalStateException("Virtual inventory slot is not writable");
        }
        refresh(slot);
    }

    @Override public void setChanged() {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            commitView(slot);
        }
    }

    @Override public boolean stillValid(Player player) {
        return true;
    }

    @Override public void clearContent() {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            setItem(slot, ItemStack.EMPTY);
        }
    }

    private ItemStack refresh(int slot) {
        long generation = data.slotGeneration(owner, side(slot), sideSlot(slot));
        if (viewGenerations[slot] != generation) {
            views[slot] = data.getStack(owner, side(slot), sideSlot(slot));
            viewGenerations[slot] = generation;
        }
        return views[slot];
    }

    private void commitView(int slot) {
        long generation = data.slotGeneration(owner, side(slot), sideSlot(slot));
        if (viewGenerations[slot] != generation) {
            views[slot] = data.getStack(owner, side(slot), sideSlot(slot));
            viewGenerations[slot] = generation;
            return;
        }

        ItemStack authoritative = data.getStack(owner, side(slot), sideSlot(slot));
        if (ItemStack.matches(authoritative, views[slot])) return;
        if (!data.setStack(owner, side(slot), sideSlot(slot), views[slot])) {
            views[slot] = authoritative;
            throw new IllegalStateException("Virtual inventory slot is not writable");
        }
        refresh(slot);
    }

    private static boolean validSlot(int slot) {
        return slot >= 0 && slot < SLOT_COUNT;
    }

    private static InventorySide side(int slot) {
        return slot < VirtualShippingSavedData.SLOTS ? InventorySide.INPUT : InventorySide.OUTPUT;
    }

    private static int sideSlot(int slot) {
        return slot % VirtualShippingSavedData.SLOTS;
    }
}
