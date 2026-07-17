package com.sunlitvalley.menucards.integration.shipping;

import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import com.sunlitvalley.menucards.inventory.InventorySide;
import java.util.UUID;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** A vanilla six-row chest menu backed solely by a Menu Cards owner record. */
public final class MenuCardsShippingBinMenu extends ChestMenu {
    private final VirtualShippingSavedData data;
    private final UUID owner;
    private final int leaseContainerId;
    private boolean leaseHeld;
    private boolean closeStarted;
    private boolean vanillaRemoved;
    private boolean leaseCleanupComplete;
    private boolean integrityFailure;

    public MenuCardsShippingBinMenu(int containerId, Inventory inventory, VirtualShippingSavedData data, UUID owner) {
        super(MenuType.GENERIC_9x6, containerId, inventory,
                new MenuCardsSmartShippingContainer(data, owner), 6);
        this.data = data;
        this.owner = owner;
        this.leaseContainerId = containerId;
        this.leaseHeld = data.acquireMenuLease(owner, containerId);
        if (leaseHeld) {
            try {
                ShippingBinMenuIntegration.register(this);
            } catch (RuntimeException | Error exception) {
                try {
                    cleanupLease();
                } catch (RuntimeException | Error cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
                throw exception;
            }
        }
    }

    /**
     * The standard move helper mutates a target slot's returned stack before calling setChanged.
     * This menu's persisted container returns defensive copies, so replacement copies must be
     * committed through Slot#set instead.
     */
    @Override
    protected boolean moveItemStackTo(ItemStack offered, int startIndex, int endIndex, boolean reverseDirection) {
        if (offered == null || offered.isEmpty() || !leaseHeld) {
            return false;
        }

        boolean moved = false;
        int index = reverseDirection ? endIndex - 1 : startIndex;
        if (offered.isStackable()) {
            while (!offered.isEmpty() && (reverseDirection ? index >= startIndex : index < endIndex)) {
                Slot slot = slots.get(index);
                ItemStack existing = slot.getItem();
                if (!existing.isEmpty()
                        && ItemStack.isSameItemSameTags(existing, offered)
                        && slot.mayPlace(offered)) {
                    int limit = Math.min(slot.getMaxStackSize(offered), offered.getMaxStackSize());
                    int transfer = Math.min(limit - existing.getCount(), offered.getCount());
                    if (transfer > 0) {
                        ItemStack replacement = existing.copy();
                        replacement.grow(transfer);
                        slot.set(replacement);
                        offered.shrink(transfer);
                        moved = true;
                    }
                }
                index += reverseDirection ? -1 : 1;
            }
        }

        index = reverseDirection ? endIndex - 1 : startIndex;
        while (!offered.isEmpty() && (reverseDirection ? index >= startIndex : index < endIndex)) {
            Slot slot = slots.get(index);
            if (!slot.hasItem() && slot.mayPlace(offered)) {
                int transfer = Math.min(slot.getMaxStackSize(offered), offered.getCount());
                ItemStack replacement = offered.copy();
                replacement.setCount(transfer);
                slot.set(replacement);
                offered.shrink(transfer);
                moved = true;
                break;
            }
            index += reverseDirection ? -1 : 1;
        }
        return moved;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!canInteract(player) || slotIndex < 0 || slotIndex >= slots.size()) return ItemStack.EMPTY;

        MenuFingerprint before = fingerprint();
        ItemStack moved;
        try {
            moved = super.quickMoveStack(player, slotIndex);
        } catch (RuntimeException | Error exception) {
            verifyAfterFailure(before, exception);
            throw exception;
        }
        verifyCommittedMutation(before);
        return moved;
    }

    @Override
    public void clicked(int slotIndex, int button, ClickType clickType, Player player) {
        if (!canInteract(player)) return;

        MenuFingerprint before = fingerprint();
        try {
            super.clicked(slotIndex, button, clickType, player);
        } catch (RuntimeException | Error exception) {
            verifyAfterFailure(before, exception);
            throw exception;
        }
        verifyCommittedMutation(before);
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (!canInteract(player)) return false;

        MenuFingerprint before = fingerprint();
        boolean handled;
        try {
            handled = super.clickMenuButton(player, buttonId);
        } catch (RuntimeException | Error exception) {
            verifyAfterFailure(before, exception);
            throw exception;
        }
        verifyCommittedMutation(before);
        return handled;
    }

    @Override
    public boolean stillValid(Player player) {
        return canInteract(player);
    }

    @Override
    public void removed(Player player) {
        closeStarted = true;
        Throwable failure = null;
        if (!vanillaRemoved) {
            vanillaRemoved = true;
            try {
                MenuFingerprint before = fingerprint();
                try {
                    super.removed(player);
                } catch (RuntimeException | Error exception) {
                    verifyAfterFailure(before, exception);
                    throw exception;
                }
                verifyCommittedMutation(before);
            } catch (RuntimeException | Error exception) {
                failure = exception;
            }
        }

        try {
            cleanupLease();
        } catch (RuntimeException | Error cleanupException) {
            if (failure == null) {
                failure = cleanupException;
            } else {
                failure.addSuppressed(cleanupException);
            }
        }
        rethrow(failure);
    }

    private boolean canInteract(Player player) {
        return !closeStarted && !integrityFailure && leaseHeld && player != null && player.getUUID().equals(owner)
                && data.hasLease(owner, leaseContainerId);
    }

    private MenuFingerprint fingerprint() {
        return new MenuFingerprint(sideFingerprint(InventorySide.INPUT), sideFingerprint(InventorySide.OUTPUT));
    }

    private SideFingerprint sideFingerprint(InventorySide side) {
        return new SideFingerprint(data.generation(owner, side), data.snapshot(owner, side));
    }

    private void verifyCommittedMutation(MenuFingerprint before) {
        MenuFingerprint after = fingerprint();
        if (!before.input().isConsistentWith(after.input())
                || !before.output().isConsistentWith(after.output())) {
            integrityFailure = true;
            throw new IllegalStateException("Virtual shipping menu mutation bypassed its committed storage path");
        }
    }

    private void verifyAfterFailure(MenuFingerprint before, Throwable primaryFailure) {
        try {
            verifyCommittedMutation(before);
        } catch (RuntimeException | Error integrityException) {
            primaryFailure.addSuppressed(integrityException);
        }
    }

    private void cleanupLease() {
        if (leaseCleanupComplete) return;

        if (leaseHeld) {
            data.releaseLease(owner, leaseContainerId);
            leaseHeld = false;
        }
        ShippingBinMenuIntegration.unregister(this);
        leaseCleanupComplete = true;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) throw runtimeException;
        if (failure instanceof Error error) throw error;
    }

    private record MenuFingerprint(SideFingerprint input, SideFingerprint output) { }

    private record SideFingerprint(long generation, ItemStack[] stacks) {
        private boolean matches(SideFingerprint other) {
            if (stacks.length != other.stacks.length) return false;
            for (int slot = 0; slot < stacks.length; slot++) {
                if (!ItemStack.matches(stacks[slot], other.stacks[slot])) return false;
            }
            return true;
        }

        private boolean isConsistentWith(SideFingerprint other) {
            boolean sameStacks = matches(other);
            boolean sameGeneration = generation == other.generation;
            return other.generation >= generation && sameStacks == sameGeneration;
        }
    }

    public UUID owner() { return owner; }
    public boolean hasLease() { return leaseHeld && !integrityFailure && data.hasLease(owner, leaseContainerId); }
}
