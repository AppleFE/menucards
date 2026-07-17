package com.sunlitvalley.menucards.gametest;

import com.sunlitvalley.menucards.MenuCardsMod;
import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import com.sunlitvalley.menucards.inventory.InventorySide;
import com.sunlitvalley.menucards.inventory.MenuCardsCommonHandler;
import com.sunlitvalley.menucards.integration.shipping.CanonicalInputFingerprint;
import com.sunlitvalley.menucards.integration.shipping.VirtualSalePlan;
import com.sunlitvalley.menucards.integration.shipping.VirtualShippingBridge;
import com.sunlitvalley.menucards.server.SkullCavernDestinationResolver;
import com.sunlitvalley.menucards.server.TeleportSafety;
import java.util.Arrays;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.gametest.GameTestHolder;
import tfar.shippingbin.inventory.CommonHandler;

@GameTestHolder(MenuCardsMod.MOD_ID)
public final class MenuCardsGameTests {
    private MenuCardsGameTests() {
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void shippingAbiMatchesPinnedDependency(GameTestHelper helper) {
        helper.assertTrue(CommonHandler.SLOTS == 27, "Shipping Bin v4 must expose 27 slots per side");
        helper.assertTrue(VirtualShippingSavedData.SLOTS == CommonHandler.SLOTS,
                "Persisted v3 side arrays must match the pinned Shipping Bin side size");
        helper.assertTrue(CanonicalInputFingerprint.SLOT_COUNT == VirtualShippingSavedData.SLOTS * 2,
                "Planning fingerprints must cover both persisted storage halves");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void fingerprintCanonicalizesCompoundKeyOrder(GameTestHelper helper) {
        ItemStack first = new ItemStack(Items.STONE, 2);
        CompoundTag firstTag = new CompoundTag();
        firstTag.putInt("z", 9);
        firstTag.putString("a", "값");
        first.setTag(firstTag);

        ItemStack second = new ItemStack(Items.STONE, 2);
        CompoundTag secondTag = new CompoundTag();
        secondTag.putString("a", "값");
        secondTag.putInt("z", 9);
        second.setTag(secondTag);

        ItemStack[] firstSlots = emptySlots();
        ItemStack[] secondSlots = emptySlots();
        firstSlots[3] = first;
        secondSlots[3] = second;
        helper.assertTrue(Arrays.equals(
                        CanonicalInputFingerprint.sha256(firstSlots),
                        CanonicalInputFingerprint.sha256(secondSlots)),
                "Compound insertion order must not change the canonical fingerprint");

        second.setCount(3);
        secondSlots[3] = second;
        helper.assertTrue(!Arrays.equals(
                        CanonicalInputFingerprint.sha256(firstSlots),
                        CanonicalInputFingerprint.sha256(secondSlots)),
                "Stack count changes must change the canonical fingerprint");

        second.setCount(2);
        secondSlots[3] = first.copy();
        secondSlots[VirtualShippingSavedData.SLOTS] = new ItemStack(Items.APPLE, 1);
        helper.assertTrue(!Arrays.equals(
                        CanonicalInputFingerprint.sha256(firstSlots),
                        CanonicalInputFingerprint.sha256(secondSlots)),
                "A change in the persisted output half must change the unified fingerprint");

        ItemStack untagged = new ItemStack(Items.STONE, 2);
        ItemStack taggedEmpty = new ItemStack(Items.STONE, 2);
        taggedEmpty.setTag(new CompoundTag());
        firstSlots[3] = untagged;
        secondSlots[3] = taggedEmpty;
        helper.assertTrue(!Arrays.equals(
                        CanonicalInputFingerprint.sha256(firstSlots),
                        CanonicalInputFingerprint.sha256(secondSlots)),
                "A null tag and an empty compound tag must remain distinct");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void virtualInventoryRequiresLeaseAndTracksSides(GameTestHelper helper) {
        VirtualShippingSavedData data = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        data.enroll(owner, 100L);

        ItemStack stone = new ItemStack(Items.STONE, 4);
        helper.assertTrue(!data.setStack(owner, InventorySide.INPUT, 0, stone),
                "Mutations without an active menu lease must be rejected");
        helper.assertTrue(data.acquireLease(owner, 7), "The idle owner must acquire a menu lease");
        helper.assertTrue(data.setStack(owner, InventorySide.INPUT, 0, stone),
                "A leased input write must commit");
        helper.assertTrue(data.generation(owner, InventorySide.INPUT) == 1L,
                "Input write must increment only the input generation");
        helper.assertTrue(data.generation(owner, InventorySide.OUTPUT) == 0L,
                "Input write must not increment the output generation");
        data.releaseLease(owner, 7);
        helper.assertTrue(!data.setStack(owner, InventorySide.OUTPUT, 0, stone),
                "Writes after lease release must be rejected");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void shippingSlotCommitsInheritedMutableStackChanges(GameTestHelper helper) {
        VirtualShippingSavedData data = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        data.enroll(owner, 0L);
        helper.assertTrue(data.acquireLease(owner, 9), "Test owner must acquire a lease");

        MenuCardsCommonHandler handler =
                new MenuCardsCommonHandler(data, owner, InventorySide.INPUT);
        Slot slot = handler.addInvSlot(0, 0, 0);
        slot.set(new ItemStack(Items.STONE, 4));
        ItemStack mutableView = slot.getItem();
        mutableView.shrink(1);
        slot.setChanged();

        helper.assertTrue(data.getStack(owner, InventorySide.INPUT, 0).getCount() == 3,
                "setChanged must commit mutations made through the inherited Slot view");
        handler.$extractStack(0, 1, false);
        helper.assertTrue(slot.getItem().getCount() == 2,
                "Slot view must refresh after handler-side generation changes");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void sameSideTargetCommitDoesNotDiscardSourceMutation(GameTestHelper helper) {
        VirtualShippingSavedData data = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        data.enroll(owner, 0L);
        helper.assertTrue(data.acquireLease(owner, 11), "Test owner must acquire a lease");

        MenuCardsCommonHandler handler =
                new MenuCardsCommonHandler(data, owner, InventorySide.INPUT);
        Slot source = handler.addInvSlot(0, 0, 0);
        Slot target = handler.addInvSlot(1, 0, 0);
        source.set(new ItemStack(Items.STONE, 4));

        ItemStack sourceView = source.getItem();
        sourceView.shrink(1);
        target.set(new ItemStack(Items.STONE, 1));
        source.setChanged();

        int total = data.getStack(owner, InventorySide.INPUT, 0).getCount()
                + data.getStack(owner, InventorySide.INPUT, 1).getCount();
        helper.assertTrue(total == 4,
                "An interleaved same-side target commit must preserve the source decrement");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void malformedStateQuarantinesWithoutDiscardingInventory(GameTestHelper helper) {
        VirtualShippingSavedData source = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        source.enroll(owner, 0L);
        helper.assertTrue(source.acquireLease(owner, 13), "Test owner must acquire a lease");
        helper.assertTrue(source.setStack(owner, InventorySide.INPUT, 2,
                new ItemStack(Items.DIAMOND, 3)), "Test inventory must be populated");

        CompoundTag serialized = source.save(new CompoundTag());
        CompoundTag record = serialized.getList("Records", CompoundTag.TAG_COMPOUND)
                .getCompound(0);
        record.putString("State", "INVALID_STATE");

        VirtualShippingSavedData loaded = VirtualShippingSavedData.load(serialized);
        helper.assertTrue(loaded.state(owner) == VirtualShippingSavedData.State.QUARANTINED,
                "Malformed state must fail closed into quarantine");
        helper.assertTrue(loaded.getStack(owner, InventorySide.INPUT, 2).getCount() == 3,
                "Fail-closed loading must preserve the player's inventory");
        CompoundTag normalized = loaded.save(new CompoundTag());
        helper.assertTrue(normalized.getList(
                        "QuarantinedRawRecords", CompoundTag.TAG_COMPOUND).size() == 1,
                "Malformed raw data must remain preserved for recovery");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void futureSchemaQuarantinesWithoutDiscardingInventory(GameTestHelper helper) {
        VirtualShippingSavedData source = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        source.enroll(owner, 0L);
        helper.assertTrue(source.acquireLease(owner, 17), "Test owner must acquire a lease");
        helper.assertTrue(source.setStack(owner, InventorySide.OUTPUT, 4,
                new ItemStack(Items.EMERALD, 5)), "Test inventory must be populated");

        CompoundTag serialized = source.save(new CompoundTag());
        serialized.putInt("DataVersion", VirtualShippingSavedData.DATA_VERSION + 1);
        VirtualShippingSavedData loaded = VirtualShippingSavedData.load(serialized);

        helper.assertTrue(loaded.state(owner) == VirtualShippingSavedData.State.QUARANTINED,
                "Unknown future schemas must fail closed into quarantine");
        helper.assertTrue(loaded.getStack(owner, InventorySide.OUTPUT, 4).getCount() == 5,
                "Future-schema loading must preserve recoverable inventory");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void malformedHandlerSnapshotCannotEraseInventory(GameTestHelper helper) {
        VirtualShippingSavedData data = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        data.enroll(owner, 0L);
        helper.assertTrue(data.acquireLease(owner, 19), "Test owner must acquire a lease");
        MenuCardsCommonHandler handler =
                new MenuCardsCommonHandler(data, owner, InventorySide.INPUT);
        handler.$setStack(0, new ItemStack(Items.GOLD_INGOT, 7));

        CompoundTag malformed = new CompoundTag();
        net.minecraft.nbt.ListTag entries = new net.minecraft.nbt.ListTag();
        CompoundTag first = new CompoundTag();
        first.putByte("Slot", (byte) 0);
        first.put("Stack", new ItemStack(Items.STONE, 1).save(new CompoundTag()));
        entries.add(first);
        entries.add(first.copy());
        malformed.put("Items", entries);

        boolean rejected = false;
        try {
            handler.$deserialize(malformed);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "Duplicate snapshot slots must be rejected");
        helper.assertTrue(data.getStack(owner, InventorySide.INPUT, 0).getCount() == 7,
                "Rejected snapshots must leave the prior inventory untouched");

        CompoundTag missingSlot = new CompoundTag();
        net.minecraft.nbt.ListTag missingSlotEntries = new net.minecraft.nbt.ListTag();
        CompoundTag missingSlotEntry = new CompoundTag();
        missingSlotEntry.put("Stack", new ItemStack(Items.DIAMOND, 1).save(new CompoundTag()));
        missingSlotEntries.add(missingSlotEntry);
        missingSlot.put("Items", missingSlotEntries);
        rejected = false;
        try {
            handler.$deserialize(missingSlot);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "Snapshots without a Slot tag must be rejected");
        helper.assertTrue(data.getStack(owner, InventorySide.INPUT, 0).getCount() == 7,
                "Missing Slot tags must not alter the prior inventory");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void virtualInventoriesRoundTripPrivatelyPerOwner(GameTestHelper helper) {
        VirtualShippingSavedData source = new VirtualShippingSavedData();
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        source.enroll(firstOwner, 0L);
        source.enroll(secondOwner, 0L);
        helper.assertTrue(source.acquireLease(firstOwner, 23), "First owner must acquire a lease");
        helper.assertTrue(source.acquireLease(secondOwner, 29), "Second owner must acquire a lease");
        helper.assertTrue(source.setStack(firstOwner, InventorySide.INPUT, 0,
                new ItemStack(Items.APPLE, 2)), "First owner input write must succeed");
        helper.assertTrue(source.setStack(secondOwner, InventorySide.INPUT, 0,
                new ItemStack(Items.CARROT, 4)), "Second owner input write must succeed");
        helper.assertTrue(source.setStack(firstOwner, InventorySide.OUTPUT, 1,
                new ItemStack(Items.DIAMOND, 1)), "First owner output write must succeed");

        VirtualShippingSavedData loaded =
                VirtualShippingSavedData.load(source.save(new CompoundTag()));
        helper.assertTrue(loaded.getStack(firstOwner, InventorySide.INPUT, 0).is(Items.APPLE)
                        && loaded.getStack(firstOwner, InventorySide.INPUT, 0).getCount() == 2,
                "First owner input item and count must round-trip privately");
        helper.assertTrue(loaded.getStack(secondOwner, InventorySide.INPUT, 0).is(Items.CARROT)
                        && loaded.getStack(secondOwner, InventorySide.INPUT, 0).getCount() == 4,
                "Second owner input item and count must round-trip privately");
        helper.assertTrue(loaded.getStack(firstOwner, InventorySide.OUTPUT, 1).is(Items.DIAMOND)
                        && loaded.getStack(firstOwner, InventorySide.OUTPUT, 1).getCount() == 1,
                "First owner output item and count must round-trip privately");
        helper.assertTrue(loaded.getStack(secondOwner, InventorySide.OUTPUT, 1).isEmpty(),
                "One owner's output must not leak to another owner");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void wrongTypedRecordListIsPreservedFailClosed(GameTestHelper helper) {
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("DataVersion", VirtualShippingSavedData.DATA_VERSION);
        net.minecraft.nbt.ListTag wrongType = new net.minecraft.nbt.ListTag();
        wrongType.add(net.minecraft.nbt.StringTag.valueOf("inventory evidence"));
        malformed.put("Records", wrongType);

        VirtualShippingSavedData loaded = VirtualShippingSavedData.load(malformed);
        CompoundTag normalized = loaded.save(new CompoundTag());
        helper.assertTrue(normalized.getList(
                        "QuarantinedRawRecords", CompoundTag.TAG_COMPOUND).size() == 1,
                "Wrong-typed record lists must be preserved as quarantine evidence");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void malformedPersistedPlanPreservesRawRecord(GameTestHelper helper) {
        VirtualShippingSavedData source = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        source.enroll(owner, 0L);
        helper.assertTrue(source.acquireLease(owner, 31), "Test owner must acquire a lease");
        source.setStack(owner, InventorySide.INPUT, 3, new ItemStack(Items.IRON_INGOT, 6));

        CompoundTag serialized = source.save(new CompoundTag());
        CompoundTag record = serialized.getList("Records", CompoundTag.TAG_COMPOUND)
                .getCompound(0);
        record.putString("State", "APPLYING");
        record.putUUID("Token", UUID.randomUUID());
        CompoundTag malformedPlan = new CompoundTag();
        malformedPlan.putString("Removals", "wrong type");
        malformedPlan.put("Outputs", new net.minecraft.nbt.ListTag());
        malformedPlan.put("Receipts", new net.minecraft.nbt.ListTag());
        record.put("Plan", malformedPlan);

        VirtualShippingSavedData loaded = VirtualShippingSavedData.load(serialized);
        helper.assertTrue(loaded.state(owner) == VirtualShippingSavedData.State.QUARANTINED,
                "Malformed persisted plans must fail closed");
        helper.assertTrue(loaded.getStack(owner, InventorySide.INPUT, 3).getCount() == 6,
                "Malformed plans must not discard the preserved inventory");
        helper.assertTrue(loaded.save(new CompoundTag()).getList(
                        "QuarantinedRawRecords", CompoundTag.TAG_COMPOUND).size() == 1,
                "Malformed plan records must retain exact raw evidence");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void persistedSlotTypeAndPlanJsonDuplicatesFailClosed(GameTestHelper helper) {
        VirtualShippingSavedData source = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        source.enroll(owner, 0L);
        helper.assertTrue(source.acquireLease(owner, 37), "Test owner must acquire a lease");
        source.setStack(owner, InventorySide.INPUT, 0, new ItemStack(Items.STONE, 2));
        CompoundTag serialized = source.save(new CompoundTag());
        CompoundTag record = serialized.getList("Records", CompoundTag.TAG_COMPOUND).getCompound(0);
        record.getList("Input", CompoundTag.TAG_COMPOUND).getCompound(0).putInt("Slot", 0);

        VirtualShippingSavedData loaded = VirtualShippingSavedData.load(serialized);
        helper.assertTrue(loaded.state(owner) == VirtualShippingSavedData.State.QUARANTINED,
                "A non-byte persisted slot must quarantine the record");
        helper.assertTrue(loaded.save(new CompoundTag()).getList(
                        "QuarantinedRawRecords", CompoundTag.TAG_COMPOUND).size() == 1,
                "Malformed slot evidence must remain preserved");

        boolean rejected = false;
        try {
            VirtualSalePlan.parse("{\"version\":2,\"removals\":[],\"outputs\":["
                    + "{\"item\":\"minecraft:stone\",\"item\":\"minecraft:air\",\"snbt\":\"{}\",\"count\":1}],"
                    + "\"receipts\":[],\"economy\":" + validEconomyJson()
                    + ",\"steps\":[\"OUTPUT\",\"DEBT\",\"BANK\"]}", emptySlots());
        } catch (VirtualSalePlan.PlanValidationException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "Duplicate JSON members must be rejected before plan tree validation");
        rejected = false;
        try {
            VirtualSalePlan.parse("{\"version\":2,\"removals\":[],\"outputs\":["
                    + "{\"item\":\"minecraft:air\",\"snbt\":\"{}\",\"count\":1}],"
                    + "\"receipts\":[],\"economy\":" + validEconomyJson()
                    + ",\"steps\":[\"OUTPUT\",\"DEBT\",\"BANK\"]}", emptySlots());
        } catch (VirtualSalePlan.PlanValidationException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "AIR plan stacks must be rejected");
        helper.succeed();
    }
    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void persistedSchemaBoundariesFailClosed(GameTestHelper helper) {
        VirtualShippingSavedData source = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        source.enroll(owner, 0L);
        helper.assertTrue(source.acquireLease(owner, 41), "Test owner must acquire a lease");
        source.setStack(owner, InventorySide.INPUT, 0, new ItemStack(Items.STONE, 2));

        CompoundTag missingRecords = source.save(new CompoundTag());
        missingRecords.remove("Records");
        VirtualShippingSavedData loaded = VirtualShippingSavedData.load(missingRecords);
        helper.assertTrue(loaded.owners().isEmpty() && loaded.save(new CompoundTag()).getList(
                        "QuarantinedRawRecords", CompoundTag.TAG_COMPOUND).size() == 1,
                "Missing required root fields must preserve the whole root fail closed");

        CompoundTag extraWrapper = source.save(new CompoundTag());
        extraWrapper.getList("Records", CompoundTag.TAG_COMPOUND).getCompound(0)
                .getList("Input", CompoundTag.TAG_COMPOUND).getCompound(0).putInt("Extra", 1);
        loaded = VirtualShippingSavedData.load(extraWrapper);
        helper.assertTrue(loaded.state(owner) == VirtualShippingSavedData.State.QUARANTINED,
                "Persisted inventory wrappers with extra fields must quarantine");

        CompoundTag overstacked = source.save(new CompoundTag());
        overstacked.getList("Records", CompoundTag.TAG_COMPOUND).getCompound(0)
                .getList("Input", CompoundTag.TAG_COMPOUND).getCompound(0)
                .getCompound("Stack").putByte("Count", (byte) 65);
        loaded = VirtualShippingSavedData.load(overstacked);
        helper.assertTrue(loaded.state(owner) == VirtualShippingSavedData.State.QUARANTINED,
                "Overstacked persisted stacks must quarantine");

        CompoundTag oversizedReason = source.save(new CompoundTag());
        oversizedReason.getList("Records", CompoundTag.TAG_COMPOUND).getCompound(0)
                .putString("Reason", "x".repeat(VirtualSalePlan.MAX_STRING + 1));
        loaded = VirtualShippingSavedData.load(oversizedReason);
        helper.assertTrue(loaded.state(owner) == VirtualShippingSavedData.State.QUARANTINED,
                "Oversized persisted reasons must quarantine");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void quarantinedArtifactsAndExactJsonIntegersFailClosed(GameTestHelper helper) {
        VirtualShippingSavedData source = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        source.enroll(owner, 0L);
        helper.assertTrue(source.acquireLease(owner, 43), "Test owner must acquire a lease");
        source.setStack(owner, InventorySide.INPUT, 0, new ItemStack(Items.STONE, 2));

        CompoundTag invalidMask = source.save(new CompoundTag());
        configureActiveQuarantine(invalidMask.getList("Records", CompoundTag.TAG_COMPOUND).getCompound(0), 1);
        invalidMask.getList("Records", CompoundTag.TAG_COMPOUND).getCompound(0).putInt("PolicyMask", 2);
        VirtualShippingSavedData loaded = VirtualShippingSavedData.load(invalidMask);
        helper.assertTrue(loaded.state(owner) == VirtualShippingSavedData.State.QUARANTINED
                        && loaded.save(new CompoundTag()).getList(
                        "QuarantinedRawRecords", CompoundTag.TAG_COMPOUND).size() == 1,
                "Quarantined non-prefix policy masks must preserve raw evidence");

        CompoundTag excessiveRemoval = source.save(new CompoundTag());
        configureActiveQuarantine(
                excessiveRemoval.getList("Records", CompoundTag.TAG_COMPOUND).getCompound(0), 3);
        loaded = VirtualShippingSavedData.load(excessiveRemoval);
        helper.assertTrue(loaded.state(owner) == VirtualShippingSavedData.State.QUARANTINED
                        && loaded.save(new CompoundTag()).getList(
                        "QuarantinedRawRecords", CompoundTag.TAG_COMPOUND).size() == 1,
                "Quarantined removals exceeding the retained snapshot must preserve raw evidence");

        boolean rejected = false;
        try {
            VirtualSalePlan.parse("{\"version\":2,\"removals\":[],\"outputs\":["
                    + "{\"item\":\"minecraft:stone\",\"snbt\":\"{}\",\"count\":1.00000000000000000001}],"
                    + "\"receipts\":[],\"economy\":" + validEconomyJson()
                    + ",\"steps\":[\"OUTPUT\",\"DEBT\",\"BANK\"]}", emptySlots());
        } catch (VirtualSalePlan.PlanValidationException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "High-precision fractional JSON numbers must not round to integers");
        rejected = false;
        try {
            VirtualSalePlan.parse("{\"version\":2,\"removals\":[],\"outputs\":["
                    + "{\"item\":\"minecraft:stone\",\"snbt\":\"{}\",\"count\":1e500000000}],"
                    + "\"receipts\":[],\"economy\":" + validEconomyJson()
                    + ",\"steps\":[\"OUTPUT\",\"DEBT\",\"BANK\"]}", emptySlots());
        } catch (VirtualSalePlan.PlanValidationException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "Extreme positive-exponent JSON integers must reject without expansion");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void duplicateOwnersPreserveEveryOriginalRecordOnce(GameTestHelper helper) {
        VirtualShippingSavedData source = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        source.enroll(owner, 0L);
        CompoundTag serialized = source.save(new CompoundTag());
        net.minecraft.nbt.ListTag records = serialized.getList("Records", CompoundTag.TAG_COMPOUND);
        records.add(records.getCompound(0).copy());
        records.add(records.getCompound(0).copy());

        VirtualShippingSavedData loaded = VirtualShippingSavedData.load(serialized);
        helper.assertTrue(loaded.state(owner) == VirtualShippingSavedData.State.QUARANTINED
                        && loaded.save(new CompoundTag()).getList(
                        "QuarantinedRawRecords", CompoundTag.TAG_COMPOUND).size() == 3,
                "Duplicate owners must retain one raw copy of each original record");
        helper.succeed();
    }

    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void skullCavernCachedLoadFailuresDoNotRetry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "menucards-test"));
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int cachedFloor = minY + 16;
        BlockState[] originalColumn = new BlockState[maxY - minY];
        for (int y = minY; y < maxY; y++) {
            originalColumn[y - minY] = level.getBlockState(new BlockPos(0, y, 0));
        }

        try {
            SkullCavernDestinationResolver.clearCache();
            for (int y = minY; y < maxY; y++) {
                level.setBlockAndUpdate(new BlockPos(0, y, 0), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(new BlockPos(0, cachedFloor, 0), Blocks.STONE.defaultBlockState());
            helper.assertTrue(SkullCavernDestinationResolver.resolve(level, player).isPresent(),
                    "The initial resolution must populate the cache");

            int[] falseCalls = {0};
            SkullCavernDestinationResolver.setChunkLoaderForTesting((loadLevel, position, bounds) -> {
                falseCalls[0]++;
                return falseCalls[0] != 1 && TeleportSafety.loadChunks(player, loadLevel, position);
            });
            helper.assertTrue(SkullCavernDestinationResolver.resolve(level, player).isEmpty(),
                    "A cached loader false result must abort the action");
            helper.assertTrue(falseCalls[0] == 1,
                    "A cached loader false result must not retry through a lower search");

            SkullCavernDestinationResolver.clearCache();
            SkullCavernDestinationResolver.resetChunkLoaderForTesting();
            helper.assertTrue(SkullCavernDestinationResolver.resolve(level, player).isPresent(),
                    "The cache must be repopulated before the throw case");

            int[] throwCalls = {0};
            SkullCavernDestinationResolver.setChunkLoaderForTesting((loadLevel, position, bounds) -> {
                throwCalls[0]++;
                if (throwCalls[0] == 1) {
                    throw new IllegalStateException("forced cached load failure");
                }
                return TeleportSafety.loadChunks(player, loadLevel, position);
            });
            helper.assertTrue(SkullCavernDestinationResolver.resolve(level, player).isEmpty(),
                    "A cached loader exception must abort the action");
            helper.assertTrue(throwCalls[0] == 1,
                    "A cached loader exception must not retry through a lower search");
            helper.succeed();
        } finally {
            SkullCavernDestinationResolver.resetChunkLoaderForTesting();
            SkullCavernDestinationResolver.clearCache();
            for (int y = minY; y < maxY; y++) {
                level.setBlockAndUpdate(new BlockPos(0, y, 0), originalColumn[y - minY]);
            }
        }
    }
    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void completionSaveRollbackRestoresApplyingState(GameTestHelper helper) {
        VirtualShippingSavedData data = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        data.enroll(owner, 0L);
        helper.assertTrue(data.acquireLease(owner, 73), "Completion rollback fixture must acquire its menu lease");
        data.setStack(owner, InventorySide.INPUT, 0, new ItemStack(Items.STONE, 2));
        data.releaseLease(owner, 73);
        UUID token = UUID.randomUUID();
        ItemStack[] snapshot = emptySlots();
        snapshot[0] = new ItemStack(Items.STONE, 2);
        try {
            configurePlanningState(data, owner, token, snapshot);
        } catch (ReflectiveOperationException exception) {
            helper.fail("Completion rollback fixture must enter PLANNING: " + exception.getMessage());
            return;
        }
        VirtualSalePlan plan;
        try {
            plan = VirtualSalePlan.parse(
                    "{\"version\":2,\"removals\":[{\"slot\":0,\"count\":1}],"
                            + "\"outputs\":[],\"receipts\":[],\"economy\":" + validEconomyJson()
                            + ",\"steps\":[\"OUTPUT\",\"DEBT\",\"BANK\"]}",
                    snapshot);
        } catch (VirtualSalePlan.PlanValidationException exception) {
            helper.fail("Completion rollback fixture plan must be valid: " + exception.reason());
            return;
        }
        var submit = data.submitPlan(token, owner,
                data.generation(owner, InventorySide.INPUT) + data.generation(owner, InventorySide.OUTPUT),
                CanonicalInputFingerprint.sha256(snapshot), plan);
        helper.assertTrue(submit.succeeded(),
                "Completion rollback fixture must accept its plan: " + submit.reason());
        helper.assertTrue(data.beginApplying(token, owner).succeeded(),
                "Completion rollback fixture must enter APPLYING");
        helper.assertTrue(data.applyPlannedOutput(token, owner).succeeded(),
                "Completion rollback fixture must apply output");
        helper.assertTrue(data.reportPolicyStep(token, owner, VirtualShippingBridge.PolicyStep.OUTPUT)
                        .succeeded()
                        && data.reportPolicyStep(token, owner, VirtualShippingBridge.PolicyStep.DEBT)
                        .succeeded()
                        && data.reportPolicyStep(token, owner, VirtualShippingBridge.PolicyStep.BANK)
                        .succeeded(),
                "Completion rollback fixture must report every policy step");

        CompoundTag applying = data.save(new CompoundTag());
        helper.assertTrue(data.removeInputAndComplete(token, owner).succeeded(),
                "Completion fixture must remove input before its simulated save failure");
        helper.assertTrue(data.getStack(owner, InventorySide.INPUT, 0).getCount() == 1,
                "Completion must mutate inventory before durable-save compensation");
        data.rollbackCompletion(token, owner);

        helper.assertTrue(applying.equals(data.save(new CompoundTag())),
                "Completion save failure must restore the exact APPLYING record and inventory");
        helper.succeed();
    }
    @GameTest(templateNamespace = MenuCardsMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void repeatedQuarantinePreservesFirstTerminalReason(GameTestHelper helper) {
        VirtualShippingSavedData source = new VirtualShippingSavedData();
        UUID owner = UUID.randomUUID();
        source.enroll(owner, 0L);
        helper.assertTrue(source.acquireLease(owner, 47), "Quarantine fixture must acquire a lease");
        source.setStack(owner, InventorySide.INPUT, 0, new ItemStack(Items.STONE, 2));

        CompoundTag serialized = source.save(new CompoundTag());
        CompoundTag record = serialized.getList("Records", CompoundTag.TAG_COMPOUND).getCompound(0);
        configureActiveQuarantine(record, 1);
        record.putString("Reason", "FIRST_TERMINAL");
        UUID token = record.getUUID("Token");

        VirtualShippingSavedData loaded = VirtualShippingSavedData.load(serialized);
        var repeated = loaded.quarantine(token, owner, "SECOND_TERMINAL");
        helper.assertTrue(repeated.reason().equals("FIRST_TERMINAL")
                        && loaded.status(owner).reason().equals("FIRST_TERMINAL"),
                "Repeated quarantine must preserve the first terminal reason");
        helper.succeed();
    }
    private static void configurePlanningState(VirtualShippingSavedData data, UUID owner, UUID token,
                                               ItemStack[] snapshot) throws ReflectiveOperationException {
        var recordsField = VirtualShippingSavedData.class.getDeclaredField("records");
        recordsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var records = (java.util.Map<UUID, Object>) recordsField.get(data);
        Object record = records.get(owner);
        if (record == null) {
            throw new IllegalStateException("Missing fixture record");
        }
        setRecordField(record, "state", VirtualShippingSavedData.State.PLANNING);
        setRecordField(record, "token", token);
        setRecordField(record, "inputGenerationAtPlan",
                data.generation(owner, InventorySide.INPUT) + data.generation(owner, InventorySide.OUTPUT));
        setRecordField(record, "inputSnapshot", Arrays.stream(snapshot)
                .map(stack -> stack.isEmpty() ? ItemStack.EMPTY : stack.copy())
                .toArray(ItemStack[]::new));
        setRecordField(record, "snapshotDigest", CanonicalInputFingerprint.sha256(snapshot));
    }

    private static void setRecordField(Object record, String name, Object value)
            throws ReflectiveOperationException {
        var field = record.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(record, value);
    }
    private static void configureActiveQuarantine(CompoundTag record, int removalCount) {
        ItemStack[] snapshot = emptySlots();
        snapshot[0] = new ItemStack(Items.STONE, 2);
        record.putString("State", VirtualShippingSavedData.State.QUARANTINED.name());
        record.putUUID("Token", UUID.randomUUID());
        record.put("InputSnapshot", record.getList("Input", CompoundTag.TAG_COMPOUND).copy());
        record.putByteArray("SnapshotDigest", CanonicalInputFingerprint.sha256(snapshot));
        record.putLong("PlanInputGeneration", record.getLong("InputGeneration"));
        record.putInt("PolicyMask", 0);
        record.putBoolean("OutputApplied", false);
        CompoundTag plan = new CompoundTag();
        net.minecraft.nbt.ListTag removals = new net.minecraft.nbt.ListTag();
        CompoundTag removal = new CompoundTag();
        removal.putInt("Slot", 0);
        removal.putInt("Count", removalCount);
        removals.add(removal);
        plan.put("Removals", removals);
        plan.put("Outputs", new net.minecraft.nbt.ListTag());
        plan.put("Receipts", new net.minecraft.nbt.ListTag());
        CompoundTag economy = new CompoundTag();
        economy.putLong("Debt", 0L);
        economy.putLong("DebtPaid", 0L);
        economy.putString("AccountId", "123e4567-e89b-12d3-a456-426614174000");
        economy.putLong("ExpectedBalance", 0L);
        economy.putLong("BankDeposit", 0L);
        plan.put("Economy", economy);
        record.put("Plan", plan);
    }
    private static ItemStack[] emptySlots() {
        ItemStack[] slots = new ItemStack[CanonicalInputFingerprint.SLOT_COUNT];
        Arrays.fill(slots, ItemStack.EMPTY);
        return slots;
    }
    private static String validEconomyJson() {
        return "{\"debt\":0,\"debtPaid\":0,"
                + "\"accountId\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"expectedBalance\":0,\"bankDeposit\":0}";
    }
}
