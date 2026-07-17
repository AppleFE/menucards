package com.sunlitvalley.menucards.data;

import com.sunlitvalley.menucards.integration.shipping.BridgeResult;
import com.sunlitvalley.menucards.integration.shipping.CanonicalInputFingerprint;
import com.sunlitvalley.menucards.integration.shipping.LeaseView;
import com.sunlitvalley.menucards.integration.shipping.VirtualSalePlan;
import com.sunlitvalley.menucards.integration.shipping.VirtualShippingBridge;
import com.sunlitvalley.menucards.inventory.InventorySide;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/** Private, UUID-addressed 27-input/27-output shipping store and durable sale state machine. */
public final class VirtualShippingSavedData extends SavedData {
    public static final String DATA_ID = "menucards_virtual_shipping";
    public static final int DATA_VERSION = 3;
    public static final int SLOTS = CanonicalInputFingerprint.SLOT_COUNT;
    public static final long SALE_INTERVAL = 6000L;
    private static final Set<String> ROOT_KEYS = Set.of(
            "DataVersion", "Records", "QuarantinedRawRecords");
    private static final Set<String> RECORD_KEYS = Set.of(
            "Owner", "Input", "Output", "InputGeneration", "OutputGeneration",
            "NextSaleGameTime", "LastObservedGameTime", "State", "Reason",
            "RecoveryCount", "LastRecoveryActor", "LastRecoveryToken",
            "LastRecoveryGameTime", "RecoveryAuditPending", "Token",
            "LastAbortedToken", "SnapshotDigest", "InputSnapshot",
            "PlanInputGeneration", "PolicyMask", "OutputApplied", "Plan");
    public enum State { IDLE, PLANNING, APPLYING, QUARANTINED }
    public record Status(UUID owner, State state, long nextSaleGameTime, String reason,
            int recoveryCount, @Nullable UUID token, boolean recoveryAuditPending,
            @Nullable EconomyIntent economyIntent) { }
    public record EconomyIntent(String phase, long debt, long debtPaid, String accountId,
            long expectedAccountBalance, long bankDeposit) { }
    public enum RecoveryResult {
        SUCCESS, NOT_FOUND, NOT_QUARANTINED, TOKEN_MISMATCH, LEASE_HELD, AUDIT_NOT_PERSISTED
    }

    private final Map<UUID, Record> records = new HashMap<>();
    private final ListTag quarantinedRawRecords = new ListTag();

    public static VirtualShippingSavedData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(VirtualShippingSavedData::load, VirtualShippingSavedData::new, DATA_ID);
    }
    public long gameTime(ServerLevel overworld) { return overworld.getGameTime(); }
    public synchronized void enroll(UUID owner, long now) {
        if (records.containsKey(owner)) return;
        Record record = new Record(owner, deadlineAfter(now));
        record.lastObservedGameTime = Math.max(0L, now);
        records.put(owner, record);
        setDirty();
    }
    public synchronized boolean isEnrolled(UUID owner) { return records.containsKey(owner); }
    public synchronized ItemStack getStack(UUID owner, InventorySide side, int slot) { Record r = records.get(owner); return r == null || !validSlot(slot) ? ItemStack.EMPTY : copy(r.stacks(side)[slot]); }
    public synchronized ItemStack[] snapshot(UUID owner, InventorySide side) { Record r = records.get(owner); ItemStack[] result = emptyStacks(); if (r != null) copyInto(r.stacks(side), result); return result; }
    public synchronized long generation(UUID owner, InventorySide side) { Record r = records.get(owner); return r == null ? 0 : r.generation(side); }
    public synchronized long slotGeneration(UUID owner, InventorySide side, int slot) {
        Record record = records.get(owner);
        return record == null || !validSlot(slot) ? 0L : record.slotGeneration(side, slot);
    }
    public synchronized boolean setStack(UUID owner, InventorySide side, int slot, ItemStack stack) {
        Record r = records.get(owner); if (!menuWritable(r) || !validSlot(slot)) return false;
        ItemStack replacement = copy(stack); if (ItemStack.matches(r.stacks(side)[slot], replacement)) return false;
        r.stacks(side)[slot] = replacement; r.increment(side, slot); setDirty(); return true;
    }

    public synchronized boolean replaceSide(UUID owner, InventorySide side, ItemStack[] replacement) {
        Record record = records.get(owner);
        if (!menuWritable(record) || replacement == null || replacement.length != SLOTS) return false;
        ItemStack[] validated = emptyStacks();
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = replacement[slot];
            if (stack == null || stack.getCount() < 0 || stack.getCount() > stack.getMaxStackSize()) {
                throw new IllegalArgumentException("Invalid replacement stack at slot " + slot);
            }
            validated[slot] = copy(stack);
        }
        boolean changed = false;
        ItemStack[] current = record.stacks(side);
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!ItemStack.matches(current[slot], validated[slot])) {
                current[slot] = validated[slot];
                record.increment(side, slot);
                changed = true;
            }
        }
        if (changed) setDirty();
        return true;
    }
    public synchronized ItemStack extract(UUID owner, InventorySide side, int slot, int amount, boolean simulate) {
        if (!validSlot(slot) || amount <= 0) return ItemStack.EMPTY; Record r = records.get(owner); if (!menuWritable(r)) return ItemStack.EMPTY;
        ItemStack current = r.stacks(side)[slot]; if (current.isEmpty()) return ItemStack.EMPTY;
        ItemStack extracted = current.copy(); extracted.setCount(Math.min(amount, current.getCount()));
        if (!simulate) { ItemStack replacement = current.copy(); replacement.shrink(extracted.getCount()); setStack(owner, side, slot, replacement); }
        return extracted;
    }
    public synchronized ItemStack insert(UUID owner, InventorySide side, int slot, ItemStack offered, boolean simulate) {
        if (!validSlot(slot) || offered == null || offered.isEmpty()) return ItemStack.EMPTY; Record r = records.get(owner); if (!menuWritable(r)) return copy(offered);
        ItemStack current = r.stacks(side)[slot]; if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, offered)) return copy(offered);
        int limit = Math.min(offered.getMaxStackSize(), current.isEmpty() ? offered.getMaxStackSize() : current.getMaxStackSize());
        int accepted = Math.min(limit - (current.isEmpty() ? 0 : current.getCount()), offered.getCount()); if (accepted <= 0) return copy(offered);
        if (!simulate) { ItemStack replacement = current.isEmpty() ? offered.copy() : current.copy(); replacement.setCount((current.isEmpty() ? 0 : current.getCount()) + accepted); setStack(owner, side, slot, replacement); }
        ItemStack remainder = offered.copy(); remainder.shrink(accepted); return remainder;
    }
    public synchronized ItemStack insertAny(UUID owner, InventorySide side, ItemStack offered, int start, boolean simulate) {
        ItemStack remainder = copy(offered); for (int slot = Math.max(0, start); slot < SLOTS && !remainder.isEmpty(); slot++) remainder = insert(owner, side, slot, remainder, simulate); return remainder;
    }
    public synchronized boolean acquireLease(UUID owner, int containerId) { Record r = records.get(owner); if (r == null || r.state != State.IDLE || r.leaseContainer != null && r.leaseContainer != containerId) return false; if (r.leaseContainer == null) { r.leaseContainer = containerId; setDirty(); } return true; }
    public synchronized void releaseLease(UUID owner, int containerId) { Record r = records.get(owner); if (r != null && r.leaseContainer != null && r.leaseContainer == containerId) { r.leaseContainer = null; setDirty(); } }
    public synchronized void clearTransientLeases() {
        boolean changed = false;
        for (Record record : records.values()) {
            if (record.leaseContainer != null) {
                record.leaseContainer = null;
                changed = true;
            }
        }
        if (changed) setDirty();
    }
    public synchronized boolean hasLease(UUID owner) { Record r = records.get(owner); return r != null && r.leaseContainer != null; }
    public synchronized boolean hasLease(UUID owner, int containerId) {
        Record record = records.get(owner);
        return record != null && record.leaseContainer != null
                && record.leaseContainer == containerId;
    }
    public synchronized @Nullable UUID pollDue(long now) { return records.values().stream().filter(r -> r.state == State.IDLE && r.leaseContainer == null && r.nextSaleGameTime <= now).min(Comparator.comparingLong((Record r) -> r.nextSaleGameTime).thenComparing(r -> r.owner)).map(r -> r.owner).orElse(null); }
    public synchronized State state(UUID owner) { Record r = records.get(owner); return r == null ? State.QUARANTINED : r.state; }
    public synchronized long nextSaleGameTime(UUID owner) { Record r = records.get(owner); return r == null ? Long.MAX_VALUE : r.nextSaleGameTime; }
    public synchronized Collection<UUID> owners() { return List.copyOf(records.keySet()); }
    public synchronized @Nullable Status status(UUID owner) {
        Record r = records.get(owner);
        return r == null ? null : new Status(owner, r.state, r.nextSaleGameTime,
                r.reason == null ? "OK" : r.reason, r.recoveryCount, r.token,
                r.recoveryAuditPending, economyIntent(r));
    }

    /** Validates recovery and records the proceeds-reconciliation audit while the record remains quarantined. */
    public synchronized RecoveryResult prepareRecovery(UUID owner, UUID token, UUID actor, long now) {
        Record r = records.get(owner);
        if (r == null) return RecoveryResult.NOT_FOUND;
        if (r.state != State.QUARANTINED) return RecoveryResult.NOT_QUARANTINED;
        if (r.token == null || !r.token.equals(token)) return RecoveryResult.TOKEN_MISMATCH;
        if (r.leaseContainer != null) return RecoveryResult.LEASE_HELD;
        r.recoveryAuditPending = true;
        r.lastRecoveryActor = actor;
        r.lastRecoveryToken = token;
        r.lastRecoveryGameTime = now;
        setDirty();
        return RecoveryResult.SUCCESS;
    }

    /** Clears only a quarantine whose reconciliation audit has already been force-saved. */
    public synchronized RecoveryResult completeRecovery(UUID owner, UUID token, UUID actor, long now) {
        Record r = records.get(owner);
        if (r == null) return RecoveryResult.NOT_FOUND;
        if (r.state != State.QUARANTINED) return RecoveryResult.NOT_QUARANTINED;
        if (r.token == null || !r.token.equals(token)) return RecoveryResult.TOKEN_MISMATCH;
        if (r.leaseContainer != null) return RecoveryResult.LEASE_HELD;
        if (!r.recoveryAuditPending || !actor.equals(r.lastRecoveryActor)
                || !token.equals(r.lastRecoveryToken)) return RecoveryResult.AUDIT_NOT_PERSISTED;
        r.recoveryRollback = RecoveryRollback.capture(r);
        clearPlan(r);
        r.state = State.IDLE;
        r.reason = null;
        r.nextSaleGameTime = deadlineAfter(now);
        r.lastObservedGameTime = Math.max(r.lastObservedGameTime, now);
        r.recoveryCount++;
        r.recoveryAuditPending = false;
        setDirty();
        return RecoveryResult.SUCCESS;
    }

    public synchronized void finishRecovery(UUID owner, UUID token, UUID actor) {
        Record record = records.get(owner);
        if (record != null && record.state == State.IDLE && token.equals(record.lastRecoveryToken)
                && actor.equals(record.lastRecoveryActor)) {
            record.recoveryRollback = null;
        }
    }

    public synchronized void rollbackRecoveryClear(UUID owner, UUID token, UUID actor) {
        Record record = records.get(owner);
        if (record == null || record.state != State.IDLE || !token.equals(record.lastRecoveryToken)
                || !actor.equals(record.lastRecoveryActor) || record.recoveryRollback == null) return;
        record.recoveryRollback.restore(record);
        record.recoveryRollback = null;
        setDirty();
    }

    public synchronized @Nullable LeaseView pollDue(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        boolean backwards = records.values().stream().anyMatch(r -> now < r.lastObservedGameTime);
        if (backwards) return null; // do not sell while the persistent game clock is behind its high-water mark
        boolean observedAdvance = false;
        for (Record r : records.values()) if (r.lastObservedGameTime < now) { r.lastObservedGameTime = now; observedAdvance = true; }
        if (observedAdvance) setDirty();
        Record due = records.values().stream().filter(r -> r.state == State.IDLE && r.leaseContainer == null && r.nextSaleGameTime <= now && server.getPlayerList().getPlayer(r.owner) != null).min(Comparator.comparingLong((Record r) -> r.nextSaleGameTime).thenComparing(r -> r.owner)).orElse(null);
        if (due == null) return null;
        ServerPlayer player = server.getPlayerList().getPlayer(due.owner);
        due.state = State.PLANNING; due.token = UUID.randomUUID(); due.inputGenerationAtPlan = due.inputGeneration; due.inputSnapshot = copySlots(due.input); due.snapshotDigest = CanonicalInputFingerprint.sha256(due.inputSnapshot); due.plan = null; due.policyMask = 0; due.outputApplied = false; due.reason = null; due.lastObservedGameTime = now; setDirty();
        return new LeaseView(due.token, player, due.inputGeneration, due.input, due.output);
    }
    public synchronized BridgeResult submitPlan(UUID token, UUID owner, long generation, byte[] fingerprint, VirtualSalePlan plan) {
        Record r = recordFor(token, owner, State.PLANNING);
        if (r == null) return BridgeResult.requeue("STALE_TOKEN");
        if (generation != r.inputGenerationAtPlan || !Arrays.equals(fingerprint, r.snapshotDigest)) return BridgeResult.requeue("SNAPSHOT_CHANGED");
        try {
            if (plan == null) return BridgeResult.requeue("PLAN_MISSING");
            plan.validateForSnapshot(r.inputSnapshot);
        } catch (VirtualSalePlan.PlanValidationException exception) {
            return BridgeResult.requeue(exception.reason());
        }
        if (!canInsertAll(r.output, plan)) return BridgeResult.requeue("OUTPUT_CAPACITY");
        r.plan = plan; setDirty(); return BridgeResult.success();
    }
    public synchronized @Nullable VirtualSalePlan.EconomyTerms acceptedEconomyTerms(
            UUID token, UUID owner) {
        Record r = recordFor(token, owner, State.APPLYING);
        return r == null || r.plan == null ? null : r.plan.economy();
    }

    public synchronized BridgeResult beginApplying(UUID token, UUID owner) {
        Record r = recordFor(token, owner, State.PLANNING); if (r == null || r.plan == null) return BridgeResult.requeue("PLAN_MISSING");
        if (!snapshotCurrent(r)) return BridgeResult.requeue("SNAPSHOT_CHANGED");
        r.state = State.APPLYING; r.outputApplied = false; setDirty(); // SavedData is dirty before policy code is allowed to mutate anything else.
        return BridgeResult.success();
    }

    public synchronized boolean rollbackApplyingBeforePolicy(UUID token, UUID owner) {
        Record r = recordFor(token, owner, State.APPLYING);
        if (r == null || r.policyMask != 0) return false;
        r.state = State.PLANNING;
        setDirty();
        return true;
    }
    public synchronized BridgeResult applyPlannedOutput(UUID token, UUID owner) {
        Record r = recordFor(token, owner, State.APPLYING);
        if (r == null || r.plan == null) return BridgeResult.quarantine("STALE_TOKEN");
        if (r.outputApplied) return BridgeResult.success();
        if (!canInsertAll(r.output, r.plan)) return quarantineRecord(r, "OUTPUT_CAPACITY_CHANGED");
        try {
            ItemStack[] staged = copySlots(r.output);
            for (VirtualSalePlan.StackEntry entry : r.plan.outputs()) {
                if (!insertStack(staged, r.plan.stack(entry))) {
                    return quarantineRecord(r, "OUTPUT_CAPACITY_CHANGED");
                }
            }
            for (VirtualSalePlan.StackEntry entry : r.plan.receipts()) {
                if (!insertStack(staged, r.plan.stack(entry))) {
                    return quarantineRecord(r, "OUTPUT_CAPACITY_CHANGED");
                }
            }
            commitSlots(r, InventorySide.OUTPUT, staged);
            r.outputApplied = true;
            setDirty();
            return BridgeResult.success();
        } catch (RuntimeException exception) {
            return quarantineRecord(r, "OUTPUT_EXCEPTION");
        }
    }

    public synchronized BridgeResult reportPolicyStep(
            UUID token, UUID owner, VirtualShippingBridge.PolicyStep step) {
        Record r = recordFor(token, owner, State.APPLYING);
        if (r == null || r.plan == null) return BridgeResult.quarantine("STALE_TOKEN");
        if (step == VirtualShippingBridge.PolicyStep.OUTPUT && !r.outputApplied) {
            return quarantineRecord(r, "OUTPUT_NOT_APPLIED");
        }
        int bit = 1 << step.ordinal();
        if ((r.policyMask & bit) != 0) return BridgeResult.success();
        int expected = r.policyMask == 0 ? 0 : r.policyMask == 1 ? 1 : 2;
        if (step.ordinal() != expected) return quarantineRecord(r, "POLICY_ORDER");
        r.policyMask |= bit;
        setDirty();
        return BridgeResult.success();
    }

    public synchronized BridgeResult removeInputAndComplete(UUID token, UUID owner) {
        Record r = recordFor(token, owner, State.APPLYING);
        if (r == null || r.plan == null) return BridgeResult.quarantine("STALE_TOKEN");
        if (r.policyMask != 7) return quarantineRecord(r, "POLICY_INCOMPLETE");
        if (!snapshotCurrent(r)) return quarantineRecord(r, "SNAPSHOT_CHANGED");
        try {
            ItemStack[] staged = copySlots(r.input);
            for (VirtualSalePlan.Removal removal : r.plan.removals()) {
                ItemStack stack = staged[removal.slot()].copy();
                if (stack.getCount() < removal.count()) {
                    return quarantineRecord(r, "REMOVAL_CHANGED");
                }
                stack.shrink(removal.count());
                staged[removal.slot()] = stack;
            }
            commitSlots(r, InventorySide.INPUT, staged);
            long now = r.lastObservedGameTime;
            clearPlan(r);
            r.state = State.IDLE;
            r.nextSaleGameTime = deadlineAfter(now);
            r.reason = null;
            setDirty();
            return BridgeResult.success();
        } catch (RuntimeException exception) {
            return quarantineRecord(r, "INPUT_REMOVAL_EXCEPTION");
        }
    }

    public synchronized BridgeResult quarantine(UUID token, UUID owner, String reason) {
        Record r = recordForToken(token, owner);
        return r == null ? BridgeResult.quarantine("STALE_TOKEN")
                : quarantineRecord(r, bounded(reason, "QUARANTINE"));
    }
    public synchronized BridgeResult.PlanningAbortCode abortPlanning(UUID token, String reason) {
        for (Record r : records.values()) {
            if (token.equals(r.lastAbortedToken)) return BridgeResult.PlanningAbortCode.REQUEUED;
            if (token.equals(r.token) && r.state == State.PLANNING) {
                r.lastAbortedToken = token;
                clearPlan(r);
                r.state = State.IDLE;
                r.reason = bounded(reason, "PLAN_INVALID");
                r.nextSaleGameTime = deadlineAfter(r.lastObservedGameTime);
                setDirty();
                return BridgeResult.PlanningAbortCode.REQUEUED;
            }
        }
        return BridgeResult.PlanningAbortCode.STALE_TOKEN;
    }

    @Override public synchronized CompoundTag save(CompoundTag tag) {
        tag.putInt("DataVersion", DATA_VERSION);
        ListTag list = new ListTag();
        for (Record record : records.values()) list.add(record.save());
        tag.put("Records", list);
        tag.put("QuarantinedRawRecords", quarantinedRawRecords.copy());
        return tag;
    }

    public static VirtualShippingSavedData load(CompoundTag tag) {
        VirtualShippingSavedData data = new VirtualShippingSavedData();
        if (!ROOT_KEYS.equals(tag.getAllKeys()) || !tag.contains("DataVersion", Tag.TAG_INT)
                || !(tag.get("Records") instanceof ListTag records) || !isCompoundList(records)
                || !(tag.get("QuarantinedRawRecords") instanceof ListTag rawQuarantine)
                || !isCompoundList(rawQuarantine)) {
            preserveMalformed(data, tag);
            return data;
        }
        data.quarantinedRawRecords.addAll(rawQuarantine);
        boolean unsupportedVersion = tag.getInt("DataVersion") != DATA_VERSION;
        Map<UUID, CompoundTag> firstRecordTags = new HashMap<>();
        Map<UUID, Boolean> firstRawPreserved = new HashMap<>();
        Set<UUID> duplicateOwners = new java.util.HashSet<>();
        for (Tag entry : records) {
            if (!(entry instanceof CompoundTag recordTag) || !recordTag.contains("Owner", Tag.TAG_INT_ARRAY)
                    || !recordTag.hasUUID("Owner")) {
                preserveMalformed(data, entry);
                continue;
            }
            UUID owner = recordTag.getUUID("Owner");
            firstRecordTags.putIfAbsent(owner, recordTag.copy());
            boolean rawPreserved = false;
            Record record;
            if (unsupportedVersion) {
                data.quarantinedRawRecords.add(recordTag.copy());
                rawPreserved = true;
                record = Record.loadInventoryOnly(recordTag);
                record.state = State.QUARANTINED;
                record.reason = "unsupported_data_version_" + tag.getInt("DataVersion");
            } else {
                try {
                    record = Record.load(recordTag);
                } catch (RuntimeException exception) {
                    data.quarantinedRawRecords.add(recordTag.copy());
                    rawPreserved = true;
                    record = Record.loadInventoryOnly(recordTag);
                    record.state = State.QUARANTINED;
                    record.reason = "malformed_record";
                }
            }
            Record duplicate = data.records.putIfAbsent(owner, record);
            if (duplicate != null) {
                if (duplicateOwners.add(owner) && !firstRawPreserved.getOrDefault(owner, false)) {
                    data.quarantinedRawRecords.add(firstRecordTags.get(owner).copy());
                }
                if (!rawPreserved) data.quarantinedRawRecords.add(recordTag.copy());
                duplicate.state = State.QUARANTINED;
                duplicate.reason = "duplicate_owner";
                data.setDirty();
            }
            firstRawPreserved.putIfAbsent(owner, rawPreserved);
            if (rawPreserved || "planning_restart".equals(record.reason)
                    || "durable_applying_restart".equals(record.reason)) {
                data.setDirty();
            }
        }
        return data;
    }

    private static void preserveMalformed(VirtualShippingSavedData data, Tag entry) {
        if (entry instanceof CompoundTag compoundTag) {
            data.quarantinedRawRecords.add(compoundTag.copy());
        } else {
            CompoundTag wrapper = new CompoundTag();
            wrapper.put("Raw", entry.copy());
            data.quarantinedRawRecords.add(wrapper);
        }
        data.setDirty();
    }

    private static boolean isCompoundList(ListTag list) {
        return list.getElementType() == Tag.TAG_END
                || list.getElementType() == Tag.TAG_COMPOUND;
    }

    private static ListTag requireCompoundList(CompoundTag tag, String key) {
        Tag raw = tag.get(key);
        if (!(raw instanceof ListTag list) || !isCompoundList(list)) {
            throw new IllegalArgumentException(key + " must be a compound list");
        }
        return list;
    }
    private static void requireTag(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException(key + " has wrong type");
    }

    private static UUID requireUuid(CompoundTag tag, String key) {
        requireTag(tag, key, Tag.TAG_INT_ARRAY);
        if (!tag.hasUUID(key)) throw new IllegalArgumentException(key + " is not a UUID");
        return tag.getUUID(key);
    }

    private static boolean requireBoolean(CompoundTag tag, String key) {
        requireTag(tag, key, Tag.TAG_BYTE);
        byte value = tag.getByte(key);
        if (value != 0 && value != 1) throw new IllegalArgumentException(key + " is not boolean");
        return value != 0;
    }

    private static void requireOptionalUuid(CompoundTag tag, String key) {
        if (tag.get(key) != null) requireUuid(tag, key);
    }

    private static ListTag compoundListOrEmpty(CompoundTag tag, String key) {
        Tag raw = tag.get(key);
        return raw instanceof ListTag list && isCompoundList(list) ? list : new ListTag();
    }

    private Record recordFor(UUID token, UUID owner, State state) { Record r = recordForToken(token, owner); return r != null && r.state == state ? r : null; }
    private Record recordForToken(UUID token, UUID owner) { Record r = records.get(owner); return r != null && token != null && token.equals(r.token) ? r : null; }
    private static boolean menuWritable(@Nullable Record record) {
        return record != null && record.state == State.IDLE && record.leaseContainer != null;
    }

    private BridgeResult quarantineRecord(Record record, String reason) {
        if (record.state == State.QUARANTINED) {
            return BridgeResult.quarantine(bounded(record.reason, "QUARANTINE"));
        }
        if (record.plan == null) clearPlan(record);
        record.state = State.QUARANTINED;
        record.reason = bounded(reason, "QUARANTINE");
        setDirty();
        return BridgeResult.quarantine(record.reason);
    }
    private static boolean snapshotCurrent(Record r) { return r.inputGeneration == r.inputGenerationAtPlan && Arrays.equals(r.snapshotDigest, CanonicalInputFingerprint.sha256(r.input)); }
    private static boolean canInsertAll(ItemStack[] output, VirtualSalePlan plan) { ItemStack[] simulated = copySlots(output); for (VirtualSalePlan.StackEntry e : plan.outputs()) if (!insertStack(simulated, plan.stack(e))) return false; for (VirtualSalePlan.StackEntry e : plan.receipts()) if (!insertStack(simulated, plan.stack(e))) return false; return true; }
    private static boolean insertStack(ItemStack[] slots, ItemStack offered) { ItemStack remaining = offered.copy(); for (int i = 0; i < SLOTS && !remaining.isEmpty(); i++) { ItemStack current = slots[i]; if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, remaining)) continue; int room = (current.isEmpty() ? remaining.getMaxStackSize() : current.getMaxStackSize() - current.getCount()); if (room <= 0) continue; int accepted = Math.min(room, remaining.getCount()); if (current.isEmpty()) { slots[i] = remaining.copy(); slots[i].setCount(accepted); } else current.grow(accepted); remaining.shrink(accepted); } return remaining.isEmpty(); }
    private static void clearPlan(Record r) { r.token = null; r.snapshotDigest = null; r.inputSnapshot = null; r.plan = null; r.policyMask = 0; r.outputApplied = false; }
    private static boolean validSlot(int slot) { return slot >= 0 && slot < SLOTS; }
    private static ItemStack copy(ItemStack stack) { return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy(); }
    private static ItemStack[] emptyStacks() { ItemStack[] result = new ItemStack[SLOTS]; Arrays.fill(result, ItemStack.EMPTY); return result; }
    private static ItemStack[] copySlots(ItemStack[] source) { ItemStack[] result = emptyStacks(); copyInto(source, result); return result; }
    private static void copyInto(ItemStack[] from, ItemStack[] to) { for (int i = 0; i < SLOTS; i++) to[i] = copy(from[i]); }
    private static void validateState(Record record) {
        boolean hasPlanningSnapshot = record.token != null && record.inputSnapshot != null
                && record.snapshotDigest != null && record.snapshotDigest.length == 32
                && Arrays.equals(record.snapshotDigest, CanonicalInputFingerprint.sha256(record.inputSnapshot))
                && record.inputGenerationAtPlan == record.inputGeneration;
        if (record.state == State.IDLE) {
            if (record.token != null || record.snapshotDigest != null || record.inputSnapshot != null
                    || record.plan != null || record.policyMask != 0 || record.outputApplied) {
                throw new IllegalArgumentException("Invalid idle state");
            }
            return;
        }
        if (record.state == State.PLANNING) {
            if (!hasPlanningSnapshot || record.policyMask != 0 || record.outputApplied) {
                throw new IllegalArgumentException("Invalid planning state");
            }
        } else if (record.state == State.APPLYING) {
            if (!hasPlanningSnapshot || record.plan == null || !isPolicyPrefix(record.policyMask)
                    || record.policyMask != 0 && !record.outputApplied) {
                throw new IllegalArgumentException("Invalid applying state");
            }
        } else if (record.state == State.QUARANTINED) {
            boolean hasActiveArtifacts = record.token != null || record.snapshotDigest != null
                    || record.inputSnapshot != null || record.plan != null;
            if (hasActiveArtifacts && (!hasPlanningSnapshot || record.plan == null
                    || !isPolicyPrefix(record.policyMask)
                    || record.policyMask != 0 && !record.outputApplied)) {
                throw new IllegalArgumentException("Invalid quarantined state");
            }
            if (!hasActiveArtifacts && (record.policyMask != 0 || record.outputApplied)) {
                throw new IllegalArgumentException("Invalid salvaged quarantine state");
            }
        }
        if ((record.state == State.PLANNING || record.state == State.APPLYING)
                && record.plan != null) {
            try {
                record.plan.validateForSnapshot(record.inputSnapshot);
            } catch (VirtualSalePlan.PlanValidationException exception) {
                throw new IllegalArgumentException("Invalid plan", exception);
            }
        } else if (record.state == State.QUARANTINED && record.plan != null) {
            try {
                record.plan.validateRemovalsForSnapshot(record.inputSnapshot);
            } catch (VirtualSalePlan.PlanValidationException exception) {
                throw new IllegalArgumentException("Invalid quarantined plan", exception);
            }
        }
    }
    private static boolean isPolicyPrefix(int policyMask) {
        return policyMask == 0 || policyMask == 1 || policyMask == 3 || policyMask == 7;
    }
    private static @Nullable EconomyIntent economyIntent(Record record) {
        if (record.plan == null || (record.state != State.APPLYING && record.state != State.QUARANTINED)) {
            return null;
        }
        VirtualSalePlan.EconomyTerms terms = record.plan.economy();
        String phase = record.policyMask == 0 ? "OUTPUT"
                : record.policyMask == 1 ? "DEBT"
                : record.policyMask == 3 ? "BANK" : "INPUT";
        return new EconomyIntent(phase, terms.debt(), terms.debtPaid(), terms.accountId(),
                terms.expectedAccountBalance(), terms.bankDeposit());
    }
    private static String bounded(String value, String fallback) {
        return !isBounded(value) ? fallback : value;
    }
    private static boolean isBounded(String value) {
        return value != null && value.length() <= VirtualSalePlan.MAX_STRING
                && value.getBytes(StandardCharsets.UTF_8).length <= VirtualSalePlan.MAX_STRING;
    }

    private static void commitSlots(Record record, InventorySide side, ItemStack[] staged) {
        ItemStack[] current = record.stacks(side);
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!ItemStack.matches(current[slot], staged[slot])) {
                current[slot] = copy(staged[slot]);
                record.increment(side, slot);
            }
        }
    }

    private static long deadlineAfter(long now) {
        long normalized = Math.max(0L, now);
        return normalized > Long.MAX_VALUE - SALE_INTERVAL
                ? Long.MAX_VALUE : normalized + SALE_INTERVAL;
    }

    private static final class Record {
        private final UUID owner; private final ItemStack[] input = emptyStacks(); private final ItemStack[] output = emptyStacks();
        private long inputGeneration, outputGeneration, nextSaleGameTime, lastObservedGameTime, inputGenerationAtPlan;
        private final long[] inputSlotGenerations = new long[SLOTS];
        private final long[] outputSlotGenerations = new long[SLOTS];
        private State state = State.IDLE; @Nullable private UUID token; @Nullable private UUID lastAbortedToken; @Nullable private byte[] snapshotDigest; @Nullable private ItemStack[] inputSnapshot; @Nullable private VirtualSalePlan plan; @Nullable private String reason; @Nullable private Integer leaseContainer; private int policyMask, recoveryCount; @Nullable private UUID lastRecoveryActor; @Nullable private UUID lastRecoveryToken; private long lastRecoveryGameTime; private boolean recoveryAuditPending, outputApplied; @Nullable private RecoveryRollback recoveryRollback;
        private Record(UUID owner, long deadline) { this.owner = owner; nextSaleGameTime = deadline; }
        private ItemStack[] stacks(InventorySide side) { return side == InventorySide.INPUT ? input : output; }
        private long generation(InventorySide side) { return side == InventorySide.INPUT ? inputGeneration : outputGeneration; }
        private void increment(InventorySide side, int slot) {
            if (side == InventorySide.INPUT) inputGeneration++;
            else outputGeneration++;
            slotGenerations(side)[slot]++;
        }
        private long slotGeneration(InventorySide side, int slot) {
            return slotGenerations(side)[slot];
        }
        private long[] slotGenerations(InventorySide side) {
            return side == InventorySide.INPUT ? inputSlotGenerations : outputSlotGenerations;
        }
        private CompoundTag save() { CompoundTag tag = new CompoundTag(); tag.putUUID("Owner", owner); tag.put("Input", saveStacks(input)); tag.put("Output", saveStacks(output)); tag.putLong("InputGeneration", inputGeneration); tag.putLong("OutputGeneration", outputGeneration); tag.putLong("NextSaleGameTime", nextSaleGameTime); tag.putLong("LastObservedGameTime", lastObservedGameTime); tag.putString("State", state.name()); if (reason != null) tag.putString("Reason", reason); tag.putInt("RecoveryCount", recoveryCount); if (lastRecoveryActor != null) tag.putUUID("LastRecoveryActor", lastRecoveryActor); if (lastRecoveryToken != null) tag.putUUID("LastRecoveryToken", lastRecoveryToken); tag.putLong("LastRecoveryGameTime", lastRecoveryGameTime); tag.putBoolean("RecoveryAuditPending", recoveryAuditPending); if (token != null) tag.putUUID("Token", token); if (lastAbortedToken != null) tag.putUUID("LastAbortedToken", lastAbortedToken); if (snapshotDigest != null) tag.putByteArray("SnapshotDigest", snapshotDigest); if (inputSnapshot != null) tag.put("InputSnapshot", saveStacks(inputSnapshot)); tag.putLong("PlanInputGeneration", inputGenerationAtPlan); tag.putInt("PolicyMask", policyMask); tag.putBoolean("OutputApplied", outputApplied); if (plan != null) tag.put("Plan", plan.save()); return tag; }
        private static Record load(CompoundTag tag) {
            UUID owner = requireUuid(tag, "Owner");
            if (!RECORD_KEYS.containsAll(tag.getAllKeys())) {
                throw new IllegalArgumentException("Unknown record field");
            }
            requireTag(tag, "InputGeneration", Tag.TAG_LONG);
            requireTag(tag, "OutputGeneration", Tag.TAG_LONG);
            requireTag(tag, "NextSaleGameTime", Tag.TAG_LONG);
            requireTag(tag, "LastObservedGameTime", Tag.TAG_LONG);
            requireTag(tag, "State", Tag.TAG_STRING);
            requireTag(tag, "RecoveryCount", Tag.TAG_INT);
            requireTag(tag, "LastRecoveryGameTime", Tag.TAG_LONG);
            requireTag(tag, "PlanInputGeneration", Tag.TAG_LONG);
            requireTag(tag, "PolicyMask", Tag.TAG_INT);
            requireTag(tag, "OutputApplied", Tag.TAG_BYTE);
            Record r = new Record(owner, tag.getLong("NextSaleGameTime"));
            loadStacks(requireCompoundList(tag, "Input"), r.input);
            loadStacks(requireCompoundList(tag, "Output"), r.output);
            r.inputGeneration = tag.getLong("InputGeneration");
            r.outputGeneration = tag.getLong("OutputGeneration");
            r.lastObservedGameTime = tag.getLong("LastObservedGameTime");
            r.state = State.valueOf(tag.getString("State"));
            r.recoveryCount = tag.getInt("RecoveryCount");
            r.lastRecoveryGameTime = tag.getLong("LastRecoveryGameTime");
            r.inputGenerationAtPlan = tag.getLong("PlanInputGeneration");
            r.policyMask = tag.getInt("PolicyMask");
            r.outputApplied = requireBoolean(tag, "OutputApplied");
            if (r.inputGeneration < 0 || r.outputGeneration < 0 || r.nextSaleGameTime < 0
                    || r.lastObservedGameTime < 0 || r.recoveryCount < 0
                    || r.lastRecoveryGameTime < 0 || r.inputGenerationAtPlan < 0) {
                throw new IllegalArgumentException("Negative persisted value");
            }
            if (tag.get("Reason") != null) {
                requireTag(tag, "Reason", Tag.TAG_STRING);
                r.reason = tag.getString("Reason");
                if (!isBounded(r.reason)) throw new IllegalArgumentException("Reason exceeds persisted bounds");
            }
            requireOptionalUuid(tag, "LastRecoveryActor");
            requireOptionalUuid(tag, "LastRecoveryToken");
            if (tag.hasUUID("LastRecoveryActor")) r.lastRecoveryActor = tag.getUUID("LastRecoveryActor");
            if (tag.hasUUID("LastRecoveryToken")) r.lastRecoveryToken = tag.getUUID("LastRecoveryToken");
            r.recoveryAuditPending = requireBoolean(tag, "RecoveryAuditPending");
            requireOptionalUuid(tag, "Token");
            requireOptionalUuid(tag, "LastAbortedToken");
            if (tag.hasUUID("Token")) r.token = tag.getUUID("Token");
            if (tag.hasUUID("LastAbortedToken")) r.lastAbortedToken = tag.getUUID("LastAbortedToken");
            if (tag.get("SnapshotDigest") != null) {
                requireTag(tag, "SnapshotDigest", Tag.TAG_BYTE_ARRAY);
                r.snapshotDigest = tag.getByteArray("SnapshotDigest");
            }
            if (tag.get("InputSnapshot") != null) {
                r.inputSnapshot = emptyStacks();
                loadStacks(requireCompoundList(tag, "InputSnapshot"), r.inputSnapshot);
            }
            if (tag.get("Plan") != null) {
                requireTag(tag, "Plan", Tag.TAG_COMPOUND);
                r.plan = VirtualSalePlan.load(tag.getCompound("Plan"));
                if (r.plan == null) throw new IllegalArgumentException("Invalid sale plan");
            }
            validateState(r);
            if (r.state == State.PLANNING) {
                r.lastAbortedToken = r.token;
                clearPlan(r);
                r.state = State.IDLE;
                r.reason = "planning_restart";
                r.nextSaleGameTime = deadlineAfter(r.lastObservedGameTime);
            } else if (r.state == State.APPLYING) {
                r.state = State.QUARANTINED;
                r.reason = "durable_applying_restart";
            }
            return r;
        }
        private static Record loadInventoryOnly(CompoundTag tag) {
            Record record = new Record(tag.getUUID("Owner"), Long.MAX_VALUE);
            loadStacksLenient(compoundListOrEmpty(tag, "Input"), record.input);
            loadStacksLenient(compoundListOrEmpty(tag, "Output"), record.output);
            return record;
        }
        private static ListTag saveStacks(ItemStack[] stacks) { ListTag result = new ListTag(); for (int slot = 0; slot < SLOTS; slot++) if (!stacks[slot].isEmpty()) { CompoundTag entry = new CompoundTag(); entry.putByte("Slot", (byte) slot); entry.put("Stack", stacks[slot].save(new CompoundTag())); result.add(entry); } return result; }
        private static void loadStacks(ListTag source, ItemStack[] target) {
            boolean[] occupied = new boolean[SLOTS];
            for (Tag entry : source) {
                if (!(entry instanceof CompoundTag stackTag)
                        || !stackTag.getAllKeys().equals(Set.of("Slot", "Stack"))
                        || !stackTag.contains("Slot", Tag.TAG_BYTE)
                        || !stackTag.contains("Stack", Tag.TAG_COMPOUND)) {
                    throw new IllegalArgumentException("Invalid stack entry");
                }
                int slot = stackTag.getByte("Slot") & 255;
                if (!validSlot(slot) || occupied[slot]) {
                    throw new IllegalArgumentException("Invalid or duplicate stack slot");
                }
                ItemStack stack = ItemStack.of(stackTag.getCompound("Stack"));
                if (stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > stack.getMaxStackSize()) {
                    throw new IllegalArgumentException("Invalid stack");
                }
                target[slot] = stack;
                occupied[slot] = true;
            }
        }
        private static void loadStacksLenient(ListTag source, ItemStack[] target) {
            for (Tag entry : source) {
                if (!(entry instanceof CompoundTag stackTag) || !stackTag.contains("Slot", Tag.TAG_BYTE)) continue;
                int slot = stackTag.getByte("Slot") & 255;
                if (!validSlot(slot) || !target[slot].isEmpty()
                        || !stackTag.contains("Stack", Tag.TAG_COMPOUND)) continue;
                target[slot] = ItemStack.of(stackTag.getCompound("Stack"));
            }
        }
    }

    private record RecoveryRollback(
            @Nullable UUID token, @Nullable byte[] snapshotDigest,
            @Nullable ItemStack[] inputSnapshot, @Nullable VirtualSalePlan plan, int policyMask,
            boolean outputApplied, State state, @Nullable String reason, long nextSaleGameTime,
            long lastObservedGameTime, int recoveryCount, boolean recoveryAuditPending) {
        private static RecoveryRollback capture(Record record) {
            return new RecoveryRollback(record.token,
                    record.snapshotDigest == null ? null : record.snapshotDigest.clone(),
                    record.inputSnapshot == null ? null : copySlots(record.inputSnapshot),
                    record.plan, record.policyMask, record.outputApplied, record.state, record.reason,
                    record.nextSaleGameTime, record.lastObservedGameTime, record.recoveryCount,
                    record.recoveryAuditPending);
        }

        private void restore(Record record) {
            record.token = token;
            record.snapshotDigest = snapshotDigest == null ? null : snapshotDigest.clone();
            record.inputSnapshot = inputSnapshot == null ? null : copySlots(inputSnapshot);
            record.plan = plan;
            record.policyMask = policyMask;
            record.outputApplied = outputApplied;
            record.state = state;
            record.reason = reason;
            record.nextSaleGameTime = nextSaleGameTime;
            record.lastObservedGameTime = lastObservedGameTime;
            record.recoveryCount = recoveryCount;
            record.recoveryAuditPending = recoveryAuditPending;
        }
    }
}
