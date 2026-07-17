package com.sunlitvalley.menucards.integration.shipping;

import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkHooks;

/** Server-only authenticated entry point for the v4 Shipping Bin UI. */
public final class ShippingBinMenuIntegration {
    private static final Map<UUID, MenuCardsShippingBinMenu> ACTIVE_MENUS = new HashMap<>();
    private ShippingBinMenuIntegration() { }

    /** Opens only the caller's UUID-addressed virtual record; UUIDs are never client-selected. */
    public static boolean open(ServerPlayer caller) {
        if (caller == null) return false;
        UUID authenticatedOwner = caller.getUUID();
        VirtualShippingSavedData data = VirtualShippingSavedData.get(caller.serverLevel().getServer().overworld());
        data.enroll(authenticatedOwner, data.gameTime(caller.serverLevel().getServer().overworld()));
        release(caller);
        if (data.hasLease(authenticatedOwner)) return false;
        NetworkHooks.openScreen(caller, new MenuProvider() {
            @Override public Component getDisplayName() { return Component.translatable("container.shippingbin.shipping_bin"); }
            @Override public AbstractContainerMenu createMenu(int containerId, Inventory inventory, net.minecraft.world.entity.player.Player player) {
                return new MenuCardsShippingBinMenu(containerId, inventory, data, authenticatedOwner);
            }
        });
        return caller.containerMenu instanceof MenuCardsShippingBinMenu menu && menu.hasLease();
    }

    /** Cleanup hook for disconnect/death/replacement paths where vanilla did not call removed. */
    public static void release(ServerPlayer player) {
        MenuCardsShippingBinMenu menu = ACTIVE_MENUS.get(player.getUUID());
        if (menu == null && player.containerMenu instanceof MenuCardsShippingBinMenu current) {
            menu = current;
        }
        if (menu != null) menu.removed(player);
    }

    static void register(MenuCardsShippingBinMenu menu) {
        MenuCardsShippingBinMenu previous = ACTIVE_MENUS.put(menu.owner(), menu);
        if (previous != null && previous != menu) {
            ACTIVE_MENUS.put(menu.owner(), previous);
            throw new IllegalStateException("ACTIVE_MENU_EXISTS");
        }
    }

    static void unregister(MenuCardsShippingBinMenu menu) {
        ACTIVE_MENUS.remove(menu.owner(), menu);
    }

    public static void clearActiveMenus() {
        ACTIVE_MENUS.clear();
    }
}
