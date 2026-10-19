package dev.flipmod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Auto-collect state machine:
 *   /ah -> "Auction House" -> click "Your Bids" (or "View Bids")
 *       -> in the bids list: click "Claim All" if present, otherwise click an entry whose lore says
 *          "Click to claim", then click "Collect Auction"/"Claim" in the auction view.
 * Repeats until nothing is left to claim.
 */
public class AutoClaim {
    private static final int TIMEOUT_TICKS = 60;

    private enum State { IDLE, WAIT_AH, WAIT_BIDS, WAIT_VIEW, SETTLE }

    private State state = State.IDLE;
    private int timer;
    private int pending;          // purchases not yet confirmed as collected
    private long lastPurchaseMs;
    public int claimed;

    public boolean active() {
        return state != State.IDLE;
    }

    public int pending() {
        return pending;
    }

    public String describe() {
        return state == State.IDLE ? "idle" : state.toString();
    }

    public void notePurchase() {
        pending++;
        lastPurchaseMs = System.currentTimeMillis();
    }

    /** Force a claim cycle now. */
    public void claimNow() {
        if (state == State.IDLE) {
            pending = Math.max(pending, 1);
            lastPurchaseMs = 0;
        }
    }

    public void reset() {
        state = State.IDLE;
        timer = 0;
    }

    public void tick() {
        switch (state) {
            case IDLE -> {
                if (pending <= 0) return;
                if (!FlipMod.queue.isEmpty() || FlipMod.buy.active()) return; // sniping has priority
                long waitMs = FlipMod.config.claimDelaySeconds * 1000L;
                if (System.currentTimeMillis() - lastPurchaseMs < waitMs) return;
                Gui.command("ah");
                state = State.WAIT_AH;
                timer = 0;
            }
            case WAIT_AH -> {
                if (++timer > TIMEOUT_TICKS) { giveUp("timed out opening /ah"); return; }
                AbstractContainerScreen<?> scr = Gui.container();
                if (scr == null || !Gui.titleHas(scr, "Auction House")) return;
                int slot = Gui.findSlot(scr, "Your Bids", "View Bids");
                if (slot < 0) return;
                Gui.click(scr, slot);
                state = State.WAIT_BIDS;
                timer = 0;
            }
            case WAIT_BIDS -> {
                AbstractContainerScreen<?> scr = Gui.container();
                if (scr == null || !(Gui.titleHas(scr, "Your Bids") || Gui.titleHas(scr, "View Bids"))) {
                    if (++timer > TIMEOUT_TICKS) giveUp("timed out opening bids");
                    return;
                }
                int all = Gui.findSlot(scr, "Claim All");
                if (all >= 0) {
                    Gui.click(scr, all);
                    claimed += pending;
                    pending = 0;
                    state = State.SETTLE;
                    timer = 0;
                    return;
                }
                int one = Gui.findSlotByLore(scr, "Click to claim");
                if (one >= 0) {
                    Gui.click(scr, one);
                    state = State.WAIT_VIEW;
                    timer = 0;
                    return;
                }
                // Give the list a moment to populate, then conclude there is nothing to claim.
                if (++timer > 15) {
                    pending = 0;
                    Gui.closeScreen();
                    reset();
                }
            }
            case WAIT_VIEW -> {
                if (++timer > TIMEOUT_TICKS) { giveUp("timed out in auction view"); return; }
                AbstractContainerScreen<?> scr = Gui.container();
                if (scr == null || !Gui.titleHas(scr, "Auction View")) return;
                int slot = Gui.findSlot(scr, "Collect Auction", "Claim");
                if (slot < 0) return;
                Gui.click(scr, slot);
                if (pending > 0) pending--;
                claimed++;
                state = State.SETTLE;
                timer = 0;
            }
            case SETTLE -> {
                if (++timer >= 5) {
                    Gui.closeScreen();
                    reset(); // if pending > 0 another cycle starts and picks up the remaining items
                }
            }
        }
    }

    private void giveUp(String why) {
        FlipMod.log("claim failed: " + why);
        Gui.closeScreen();
        reset();
        // Keep pending so we retry, but back off a little.
        lastPurchaseMs = System.currentTimeMillis();
    }
}
