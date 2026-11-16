package com.scrimmy.client.module.combat;

import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.setting.BooleanSetting;
import com.scrimmy.client.setting.ModeSetting;
import com.scrimmy.client.setting.NumberSetting;
import com.scrimmy.client.util.TotemSearch;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

public final class AutoTotem extends Module {
    public final ModeSetting mode = add(new ModeSetting("mode", "Mode", "Always", "Always", "Health threshold"));
    public final NumberSetting health = add(new NumberSetting("health", "Health threshold", 12, 1, 40, 1));
    public final NumberSetting delay = add(new NumberSetting("delay", "Delay (ticks)", 4, 0, 20, 1));
    public final BooleanSetting inventoryClosed = add(new BooleanSetting("inventoryClosed", "Inventory closed only", true));
    private int cooldown;

    public AutoTotem() {
        super("AutoTotem", "Moves a totem to the offhand", Category.COMBAT);
        mode.tooltip("Always hold a totem, or only when health is low.");
        health.tooltip("Equip a totem below this health level.");
        delay.tooltip("Wait this many game ticks between swaps.");
        inventoryClosed.tooltip("Only swap when no screen is open.");
    }
    @Override protected void onEnable() { cooldown = 0; }
    @Override protected void onDisable() { cooldown = 0; }

    @Override public void onTick(Minecraft client) {
        if (client.player == null || client.gameMode == null || client.level == null) return;
        if (cooldown > 0) { cooldown--; return; }
        if (inventoryClosed.get() && client.screen != null) return;
        if (client.player.containerMenu != client.player.inventoryMenu) return;
        if (client.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        if (mode.get().equals("Health threshold") && client.player.getHealth() > health.get()) return;

        int source = TotemSearch.find(true,
            slot -> client.player.inventoryMenu.getSlot(slot).getItem().is(Items.TOTEM_OF_UNDYING));
        if (source < 0) return;
        // SWAP with hotbar button 40 means the offhand, and sends one server click.
        client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, source, 40, ClickType.SWAP, client.player);
        int base = delay.get().intValue();
        cooldown = base;
    }
}
