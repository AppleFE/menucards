package com.sunlitvalley.menucards.integration.shipping;

import com.mojang.authlib.GameProfile;
import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import com.sunlitvalley.menucards.inventory.InventorySide;
import com.sunlitvalley.menucards.inventory.MenuCardsCommonHandler;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("menucards")
@PrefixGameTestTemplate(false)
public final class MenuCardsShippingMenuGameTests {
    private MenuCardsShippingMenuGameTests() {
    }

    @GameTest(templateNamespace = "menucards", template = "menucardsgametests.empty", timeoutTicks = 20)
    public static void inheritedQuickMoveCommitsAndMenuGuardStaysValid(GameTestHelper helper) {
        VirtualShippingSavedData data = new VirtualShippingSavedData();
        ServerPlayer player = mockServerPlayer(helper);
        UUID owner = player.getUUID();
        data.enroll(owner, 0L);
        MenuCardsShippingBinMenu menu = new MenuCardsShippingBinMenu(41, player.getInventory(), data, owner);
        try {
            player.getInventory().setItem(9, new ItemStack(Items.STONE, 5));

            ItemStack moved = menu.quickMoveStack(player, 54);
            helper.assertTrue(moved.is(Items.STONE) && moved.getCount() == 5,
                    "Quick-move must return the stack from the first player inventory menu slot");
            helper.assertTrue(player.getInventory().getItem(9).isEmpty(),
                    "Quick-move must clear backing player inventory slot 9");
            helper.assertTrue(data.getStack(owner, InventorySide.INPUT, 0).is(Items.STONE)
                            && data.getStack(owner, InventorySide.INPUT, 0).getCount() == 5,
                    "Quick-move must persist the transferred stack in virtual input");
            helper.assertTrue(data.generation(owner, InventorySide.INPUT) == 1L,
                    "Quick-move must advance virtual input generation");
            helper.assertTrue(data.generation(owner, InventorySide.OUTPUT) == 0L,
                    "Quick-move must not mutate virtual output");
            helper.assertTrue(menu.hasLease(),
                    "The menu guard must accept the committed inherited quick-move mutation");

            player.getInventory().setItem(10, new ItemStack(Items.STONE, 3));
            long mergeGeneration = data.generation(owner, InventorySide.INPUT);
            ItemStack merged = menu.quickMoveStack(player, 55);
            helper.assertTrue(merged.is(Items.STONE) && merged.getCount() == 3
                            && player.getInventory().getItem(10).isEmpty()
                            && data.getStack(owner, InventorySide.INPUT, 0).getCount() == 8
                            && data.generation(owner, InventorySide.INPUT) == mergeGeneration + 1,
                    "Compatible quick-move must commit one replacement-copy merge and clear its source");
            helper.assertTrue(menu.hasLease(),
                    "A compatible quick-move merge must retain the menu lease");

            long inputGeneration = data.generation(owner, InventorySide.INPUT);
            long outputGeneration = data.generation(owner, InventorySide.OUTPUT);
            ItemStack[] inputSnapshot = data.snapshot(owner, InventorySide.INPUT);
            ItemStack[] outputSnapshot = data.snapshot(owner, InventorySide.OUTPUT);
            helper.assertTrue(!menu.clickMenuButton(player, 0),
                    "The Shipping Bin tab/menu button must remain a no-op");
            helper.assertTrue(data.generation(owner, InventorySide.INPUT) == inputGeneration
                            && data.generation(owner, InventorySide.OUTPUT) == outputGeneration
                            && sameStacks(inputSnapshot, data.snapshot(owner, InventorySide.INPUT))
                            && sameStacks(outputSnapshot, data.snapshot(owner, InventorySide.OUTPUT)),
                    "A tab-only action must leave both virtual sides and generations unchanged");
            helper.assertTrue(menu.hasLease(), "A tab-only action must retain the menu lease");

            MenuCardsCommonHandler input = new MenuCardsCommonHandler(data, owner, InventorySide.INPUT);
            ItemStack offered = new ItemStack(Items.STONE, 10);
            ItemStack simulatedRemainder = input.$slotlessInsertStack(offered, 3, true);
            helper.assertTrue(simulatedRemainder.getCount() == 7
                            && data.getStack(owner, InventorySide.INPUT, 0).getCount() == 8
                            && data.generation(owner, InventorySide.INPUT) == inputGeneration,
                    "Simulated slotless insertion must honor amount without persisting a mutation");

            ItemStack realRemainder = input.$slotlessInsertStack(offered, 3, false);
            helper.assertTrue(realRemainder.getCount() == 7
                            && data.getStack(owner, InventorySide.INPUT, 0).getCount() == 11
                            && data.generation(owner, InventorySide.INPUT) == inputGeneration + 1,
                    "Real slotless insertion must persist exactly the requested amount");

            long extractionGeneration = data.generation(owner, InventorySide.INPUT);
            ItemStack simulatedExtracted = input.$extractStack(0, 2, true);
            helper.assertTrue(simulatedExtracted.is(Items.STONE) && simulatedExtracted.getCount() == 2
                            && data.getStack(owner, InventorySide.INPUT, 0).getCount() == 11
                            && data.generation(owner, InventorySide.INPUT) == extractionGeneration,
                    "Simulated extraction must not persist or advance generation");
            ItemStack extracted = input.$extractStack(0, 2, false);
            helper.assertTrue(extracted.is(Items.STONE) && extracted.getCount() == 2
                            && data.getStack(owner, InventorySide.INPUT, 0).getCount() == 9
                            && data.generation(owner, InventorySide.INPUT) == extractionGeneration + 1,
                    "Real extraction must persist the extracted amount and advance generation");

            assertRejectedWritePreservesAuthority(helper, data);

            menu.removed(player);
            helper.assertTrue(!menu.hasLease() && !data.hasLease(owner),
                    "Closing the menu must release the virtual shipping lease");
            helper.succeed();
        } finally {
            menu.removed(player);
        }
    }

    private static void assertRejectedWritePreservesAuthority(
            GameTestHelper helper, VirtualShippingSavedData data) {
        UUID owner = UUID.randomUUID();
        data.enroll(owner, 0L);
        helper.assertTrue(data.acquireLease(owner, 42),
                "Rejected-write fixture must acquire its initial virtual inventory lease");

        MenuCardsCommonHandler handler = new MenuCardsCommonHandler(data, owner, InventorySide.INPUT);
        handler.$setStack(0, new ItemStack(Items.STONE, 6));
        long committedGeneration = data.generation(owner, InventorySide.INPUT);
        handler.$setStack(0, new ItemStack(Items.STONE, 6));
        helper.assertTrue(data.generation(owner, InventorySide.INPUT) == committedGeneration,
                "Writing the authoritative stack again must remain a successful no-op");
        Slot slot = handler.addInvSlot(0, 0, 0);
        data.releaseLease(owner, 42);

        ItemStack rejectedSource = new ItemStack(Items.DIRT, 4);
        boolean rejected = false;
        try {
            slot.set(rejectedSource);
        } catch (IllegalStateException exception) {
            rejected = true;
        }

        ItemStack authoritative = data.getStack(owner, InventorySide.INPUT, 0);
        helper.assertTrue(rejected
                        && rejectedSource.is(Items.DIRT) && rejectedSource.getCount() == 4
                        && authoritative.is(Items.STONE) && authoritative.getCount() == 6
                        && slot.getItem().is(Items.STONE) && slot.getItem().getCount() == 6,
                "A rejected handler write must preserve its source and authoritative slot view");
        ItemStack rejectedView = slot.getItem();
        rejectedView.shrink(1);
        boolean dirtyRejected = false;
        try {
            slot.setChanged();
        } catch (IllegalStateException exception) {
            dirtyRejected = true;
        }
        helper.assertTrue(dirtyRejected
                        && data.getStack(owner, InventorySide.INPUT, 0).getCount() == 6
                        && slot.getItem().getCount() == 6,
                "A rejected dirty mutable view must be restored from authoritative storage");
    }
    private static ServerPlayer mockServerPlayer(GameTestHelper helper) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "menucards-test"));
    }
    private static boolean sameStacks(ItemStack[] first, ItemStack[] second) {
        if (first.length != second.length) return false;
        for (int slot = 0; slot < first.length; slot++) {
            if (!ItemStack.matches(first[slot], second[slot])) return false;
        }
        return true;
    }
}
