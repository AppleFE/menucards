package com.sunlitvalley.menucards.integration.shipping;

import com.mojang.authlib.GameProfile;
import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import com.sunlitvalley.menucards.inventory.InventorySide;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("menucards")
@PrefixGameTestTemplate(false)
public final class MenuCardsShippingMenuGameTests {
    private MenuCardsShippingMenuGameTests() {
    }

    @GameTest(templateNamespace = "menucards", template = "menucardsgametests.empty", timeoutTicks = 20)
    public static void flatSixRowChestMapsBothPersistedSidesAndQuickMoves(GameTestHelper helper) {
        VirtualShippingSavedData data = new VirtualShippingSavedData();
        ServerPlayer player = mockServerPlayer(helper);
        UUID owner = player.getUUID();
        data.enroll(owner, 0L);
        MenuCardsShippingBinMenu menu = new MenuCardsShippingBinMenu(41, player.getInventory(), data, owner);
        try {
            helper.assertTrue(menu.getType() == MenuType.GENERIC_9x6,
                    "The smart shipping bin must use the vanilla GENERIC_9x6 menu type");
            helper.assertTrue(menu.slots.size() == 90,
                    "A six-row chest must expose 54 container slots plus 36 player slots");

            menu.getSlot(0).set(new ItemStack(Items.STONE, 2));
            menu.getSlot(27).set(new ItemStack(Items.DIRT, 4));
            menu.getSlot(53).set(new ItemStack(Items.COBBLESTONE, 6));
            helper.assertTrue(data.getStack(owner, InventorySide.INPUT, 0).is(Items.STONE)
                            && data.getStack(owner, InventorySide.INPUT, 0).getCount() == 2
                            && data.getStack(owner, InventorySide.OUTPUT, 0).is(Items.DIRT)
                            && data.getStack(owner, InventorySide.OUTPUT, 0).getCount() == 4
                            && data.getStack(owner, InventorySide.OUTPUT, 26).is(Items.COBBLESTONE)
                            && data.getStack(owner, InventorySide.OUTPUT, 26).getCount() == 6,
                    "Flat slots 0..26 must map to input and 27..53 must map to output");

            player.getInventory().setItem(9, new ItemStack(Items.STONE, 5));
            ItemStack movedIntoChest = menu.quickMoveStack(player, 54);
            helper.assertTrue(movedIntoChest.is(Items.STONE) && movedIntoChest.getCount() == 5
                            && player.getInventory().getItem(9).isEmpty()
                            && data.getStack(owner, InventorySide.INPUT, 0).getCount() == 7
                            && data.generation(owner, InventorySide.INPUT) == 2L
                            && menu.hasLease(),
                    "Player quick-move must commit through the flat chest inventory and retain its lease");

            ItemStack movedOutOfChest = menu.quickMoveStack(player, 27);
            helper.assertTrue(movedOutOfChest.is(Items.DIRT) && movedOutOfChest.getCount() == 4
                            && data.getStack(owner, InventorySide.OUTPUT, 0).isEmpty()
                            && player.getInventory().getItem(8).is(Items.DIRT)
                            && player.getInventory().getItem(8).getCount() == 4
                            && data.generation(owner, InventorySide.OUTPUT) == 3L
                            && menu.hasLease(),
                    "Chest quick-move must remove from flat output slot 27 into the player inventory");

            menu.removed(player);
            helper.assertTrue(!menu.hasLease() && !data.hasLease(owner),
                    "Closing the menu must release the virtual shipping lease");
            helper.succeed();
        } finally {
            menu.removed(player);
        }
    }

    private static ServerPlayer mockServerPlayer(GameTestHelper helper) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "menucards-test"));
    }
}
