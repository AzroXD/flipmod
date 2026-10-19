package dev.flipmod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;

/**
 * Every Minecraft-version-specific call lives here. If something fails to compile on a new
 * Minecraft release, this is the only file that should need changes.
 */
public final class Gui {
    private Gui() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** The open chest-like screen, or null. */
    public static AbstractContainerScreen<?> container() {
        return mc().screen instanceof AbstractContainerScreen<?> s ? s : null;
    }

    public static boolean titleHas(AbstractContainerScreen<?> scr, String part) {
        return scr.getTitle().getString().toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    /** Index of the first container slot (not the player inventory) whose item name contains any of the names. */
    public static int findSlot(AbstractContainerScreen<?> scr, String... names) {
        for (Slot slot : scr.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack st = slot.getItem();
            if (st.isEmpty()) continue;
            String n = st.getHoverName().getString().toLowerCase(Locale.ROOT);
            for (String want : names) {
                if (n.contains(want.toLowerCase(Locale.ROOT))) return slot.index;
            }
        }
        return -1;
    }

    /** Index of the first container slot whose lore contains the text. */
    public static int findSlotByLore(AbstractContainerScreen<?> scr, String text) {
        String want = text.toLowerCase(Locale.ROOT);
        for (Slot slot : scr.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack st = slot.getItem();
            if (st.isEmpty()) continue;
            ItemLore lore = st.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
            for (Component line : lore.lines()) {
                if (line.getString().toLowerCase(Locale.ROOT).contains(want)) return slot.index;
            }
        }
        return -1;
    }

    /** Left-click a slot in the open container. */
    public static void click(AbstractContainerScreen<?> scr, int slotIndex) {
        Minecraft mc = mc();
        // NOTE: if this method is renamed in 26.2 (e.g. handleContainerInput), change it here only.
        mc.gameMode.handleInventoryMouseClick(scr.getMenu().containerId, slotIndex, 0, ClickType.PICKUP, mc.player);
    }

    /** Run a command without the leading slash. */
    public static void command(String cmdWithoutSlash) {
        Minecraft mc = mc();
        if (mc.player != null) mc.player.connection.sendCommand(cmdWithoutSlash);
    }

    public static void closeScreen() {
        Minecraft mc = mc();
        if (mc.player != null && mc.screen instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
    }

    /** True when connected to a Hypixel server. */
    public static boolean onHypixel() {
        Minecraft mc = mc();
        var server = mc.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel");
    }

    public static void say(String msg) {
        Minecraft mc = mc();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), false);
    }
}
