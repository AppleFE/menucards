package com.sunlitvalley.menucards.integration.shipping;

import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import com.sunlitvalley.menucards.inventory.InventorySide;
import com.sunlitvalley.menucards.inventory.MenuCardsCommonHandler;
import java.util.UUID;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import tfar.shippingbin.inventory.CommonHandler;
import tfar.shippingbin.menu.ShippingBinMenu;

/** The Shipping Bin v4 screen backed solely by a Menu Cards owner record. */
public final class MenuCardsShippingBinMenu extends ShippingBinMenu<CommonHandler> {
    private final VirtualShippingSavedData data;
    private final UUID owner;
    private final int leaseContainerId;
    private boolean leaseHeld;
    private boolean closeStarted;
    private boolean vanillaRemoved;
    private boolean leaseCleanupComplete;
    private boolean integrityFailure;

    public MenuCardsShippingBinMenu(int containerId, Inventory inventory, VirtualShippingSavedData data, UUID owner) {
        this(tfar.shippingbin.init.ModMenuTypes.SHIPPING_BIN, containerId, inventory, data, owner,
                new MenuCardsCommonHandler(data, owner, InventorySide.INPUT),
                new MenuCardsCommonHandler(data, owner, InventorySide.OUTPUT));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private MenuCardsShippingBinMenu(MenuType type, int containerId, Inventory inventory,
            VirtualShippingSavedData data, UUID owner, CommonHandler input, CommonHandler output) {
        super(type, containerId, inventory, input, output);
        this.data = data;
        this.owner = owner;
        this.leaseContainerId = containerId;
        this.leaseHeld = data.acquireLease(owner, containerId);
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
     * Shipping Bin v4 mutates the stack returned by a target slot during compatible-stack merges
     * and its tab slot has a no-op {@code setChanged}. Commit replacement copies through
     * {@link Slot#set(ItemStack)} so every mutation reaches MenuCardsCommonHandler.
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
