package com.sunlitvalley.menucards.inventory;

import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import tfar.shippingbin.inventory.CommonHandler;

/** Callback-aware adapter for one side of a Menu Cards virtual shipping record. */
public final class MenuCardsCommonHandler implements CommonHandler {
    private final VirtualShippingSavedData data;
    private final UUID owner;
    private final InventorySide side;
    private Predicate<ItemStack> predicate = stack -> true;
    private final SimpleContainer slotBacking = new SimpleContainer(VirtualShippingSavedData.SLOTS);

    public MenuCardsCommonHandler(VirtualShippingSavedData data, UUID owner, InventorySide side) {
        this.data = data;
        this.owner = owner;
        this.side = side;
    }

    @Override public int $getSlotCount() { return VirtualShippingSavedData.SLOTS; }
    @Override public ItemStack $getStack(int slot) {
        return slot >= 0 && slot < $getSlotCount() ? data.getStack(owner, side, slot) : ItemStack.EMPTY;
    }
    @Override public void $setStack(int slot, ItemStack stack) {
        if (slot < 0 || slot >= $getSlotCount()) return;
        ItemStack replacement = stack == null ? ItemStack.EMPTY : stack.copy();
        if (ItemStack.matches($getStack(slot), replacement)) return;
        if (!data.setStack(owner, side, slot, replacement)) {
            throw new IllegalStateException("Virtual inventory slot is not writable");
        }
    }

    @Override public CompoundTag $serialize() {
        CompoundTag tag = new CompoundTag();
        ListTag entries = new ListTag();
        for (int slot = 0; slot < $getSlotCount(); slot++) {
            ItemStack stack = $getStack(slot);
            if (!stack.isEmpty()) { CompoundTag entry = new CompoundTag(); entry.putByte("Slot", (byte) slot); entry.put("Stack", stack.save(new CompoundTag())); entries.add(entry); }
        }
        tag.put("Items", entries);
        return tag;
    }

    /** Deserialization atomically replaces a complete, strictly validated side. */
    @Override public void $deserialize(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        Tag rawItems = tag.get("Items");
        if (!(rawItems instanceof ListTag entries)
                || entries.getElementType() != Tag.TAG_END
                && entries.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Items must be a compound list");
        }
        ItemStack[] replacement = new ItemStack[$getSlotCount()];
        java.util.Arrays.fill(replacement, ItemStack.EMPTY);
        boolean[] occupied = new boolean[replacement.length];
        for (Tag value : entries) {
            if (!(value instanceof CompoundTag entry)) {
                throw new IllegalArgumentException("Invalid inventory entry");
            }
            if (!entry.contains("Slot", Tag.TAG_BYTE)) {
                throw new IllegalArgumentException("Inventory slot must be a byte");
            }
            int slot = entry.getByte("Slot") & 255;
            if (slot >= replacement.length || occupied[slot]
                    || !entry.contains("Stack", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Invalid or duplicate inventory slot");
            }
            ItemStack stack = ItemStack.of(entry.getCompound("Stack"));
            if (stack.isEmpty()) throw new IllegalArgumentException("Invalid inventory stack");
            replacement[slot] = stack;
            occupied[slot] = true;
        }
        if (!data.replaceSide(owner, side, replacement)) {
            throw new IllegalStateException("Virtual inventory side is not writable");
        }
    }

    @Override public Slot addInvSlot(int slot, int x, int y) {
        if (slot < 0 || slot >= $getSlotCount()) throw new IndexOutOfBoundsException("slot " + slot);
        return new Slot(slotBacking, slot, x, y) {
            private ItemStack view = $getStack(slot);
            private long viewGeneration = data.slotGeneration(owner, side, slot);

            private void refreshIfChanged() {
                long currentGeneration = data.slotGeneration(owner, side, slot);
                if (currentGeneration != viewGeneration) {
                    view = $getStack(slot);
                    viewGeneration = currentGeneration;
                }
            }

            @Override public ItemStack getItem() {
                refreshIfChanged();
                return view;
            }

            @Override public void set(ItemStack stack) {
                try {
                    $setStack(slot, stack == null ? ItemStack.EMPTY : stack.copy());
                } catch (RuntimeException | Error exception) {
                    view = $getStack(slot);
                    viewGeneration = data.slotGeneration(owner, side, slot);
                    throw exception;
                }
                view = $getStack(slot);
                viewGeneration = data.slotGeneration(owner, side, slot);
            }

            @Override public ItemStack remove(int amount) {
                ItemStack extracted = $extractStack(slot, amount, false);
                view = $getStack(slot);
                viewGeneration = data.slotGeneration(owner, side, slot);
                return extracted;
            }

            @Override public boolean hasItem() { return !getItem().isEmpty(); }

            @Override public void setChanged() {
                if (data.slotGeneration(owner, side, slot) != viewGeneration) {
                    refreshIfChanged();
                    return;
                }
                try {
                    $setStack(slot, view);
                } catch (RuntimeException | Error exception) {
                    view = $getStack(slot);
                    viewGeneration = data.slotGeneration(owner, side, slot);
                    throw exception;
                }
                view = $getStack(slot);
                viewGeneration = data.slotGeneration(owner, side, slot);
            }

            @Override public boolean mayPlace(ItemStack stack) { return $isValid(stack); }
            @Override public int getMaxStackSize() { return $getMaxStackSize(slot); }
            @Override public int getMaxStackSize(ItemStack stack) { return Math.min(getMaxStackSize(), stack.getMaxStackSize()); }
        };
    }

    @Override public int $getMaxStackSize(int slot) { return slot >= 0 && slot < $getSlotCount() ? 64 : 0; }

    @Override public ItemStack $insertStack(int slot, ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        if (slot < 0 || slot >= $getSlotCount() || !$isValid(stack)) return stack.copy();

        ItemStack current = $getStack(slot);
        if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, stack)) return stack.copy();

        int currentCount = current.isEmpty() ? 0 : current.getCount();
        int limit = Math.min($getMaxStackSize(slot), stack.getMaxStackSize());
        if (!current.isEmpty()) limit = Math.min(limit, current.getMaxStackSize());
        int accepted = Math.min(limit - currentCount, stack.getCount());
        if (accepted <= 0) return stack.copy();

        if (!simulate) {
            ItemStack replacement = current.isEmpty() ? stack.copy() : current.copy();
            replacement.setCount(currentCount + accepted);
            if (!data.setStack(owner, side, slot, replacement)) return stack.copy();
        }

        ItemStack remainder = stack.copy();
        remainder.shrink(accepted);
        return remainder;
    }

    @Override public ItemStack $slotlessInsertStack(ItemStack stack, int amount, boolean simulate) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        if (amount <= 0 || !$isValid(stack)) return stack.copy();

        ItemStack offered = stack.copy();
        int requested = Math.min(amount, offered.getCount());
        offered.setCount(requested);
        for (int slot = 0; slot < $getSlotCount() && !offered.isEmpty(); slot++) {
            offered = $insertStack(slot, offered, simulate);
        }

        ItemStack remainder = stack.copy();
        remainder.shrink(requested - offered.getCount());
        return remainder;
    }

    @Override public ItemStack $extractStack(int slot, int amount, boolean simulate) {
        if (slot < 0 || slot >= $getSlotCount() || amount <= 0) return ItemStack.EMPTY;
        return data.extract(owner, side, slot, amount, simulate);
    }

    @Override public boolean $isValid(ItemStack stack) { return stack != null && !stack.isEmpty() && predicate.test(stack.copy()); }
    @Override public void $setPredicate(Predicate<ItemStack> predicate) { this.predicate = Objects.requireNonNull(predicate, "predicate"); }

    public UUID owner() { return owner; }
    public InventorySide side() { return side; }
}
