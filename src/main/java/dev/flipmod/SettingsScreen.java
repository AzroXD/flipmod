package dev.flipmod;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * Tabbed settings screen built only from stock widgets (buttons, sliders, a text box, labels), and
 * with no custom drawing code, so it depends on as little Minecraft rendering API as possible.
 * Open with /flipmod.
 */
public class SettingsScreen extends Screen {
    private static final String[] TABS = {"Sniper", "Alerts", "Auto-Buy", "Ignore", "Display"};
    private static int tab = 0;

    private static final long[] MONEY = {100_000L, 250_000L, 500_000L, 850_000L, 1_000_000L, 2_000_000L, 3_000_000L,
            5_000_000L, 10_000_000L, 20_000_000L, 50_000_000L, 100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L};
    private static final long[] ZERO_MONEY = {0L, 100_000L, 250_000L, 500_000L, 850_000L, 1_000_000L, 2_000_000L,
            3_000_000L, 5_000_000L, 10_000_000L, 25_000_000L};

    private EditBox ignoreBox;

    public SettingsScreen() {
        super(Component.literal("FlipMod"));
    }

    // ---------- layout helpers ----------

    private int left() { return this.width / 2 - 150; }
    private int rowY(int row) { return 62 + row * 24; }

    private void label(int row, String text) {
        addRenderableWidget(new StringWidget(left(), rowY(row) + 6, 150, 12, Component.literal(text), this.font));
    }

    private void note(int row, String text) {
        addRenderableWidget(new StringWidget(left(), rowY(row) + 6, 300, 12, Component.literal(text), this.font));
    }

    private void toggle(int row, String name, BooleanSupplier get, Consumer<Boolean> set) {
        label(row, name);
        Button[] holder = new Button[1];
        holder[0] = Button.builder(onOff(get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            FlipMod.config.save();
            holder[0].setMessage(onOff(get.getAsBoolean()));
        }).bounds(left() + 170, rowY(row), 130, 20).build();
        addRenderableWidget(holder[0]);
    }

    private void slider(int row, String name, long[] options, LongSupplier get, LongConsumer set, LongFunction<String> fmt) {
        label(row, name);
        addRenderableWidget(new StepSlider(left() + 170, rowY(row), 130, 20, options, get.getAsLong(), v -> {
            set.accept(v);
            FlipMod.config.save();
        }, fmt));
    }

    private static Component onOff(boolean on) {
        return Component.literal(on ? "§aON" : "§cOFF");
    }

    private static long[] range(int lo, int hi) {
        long[] a = new long[hi - lo + 1];
        for (int i = 0; i < a.length; i++) a[i] = lo + i;
        return a;
    }

    private static final LongFunction<String> COINS = Fmt::coins;

    // ---------- screen ----------

    @Override
    protected void init() {
        FlipConfig c = FlipMod.config;

        // tab row
        int tw = 62;
        int tx = this.width / 2 - (TABS.length * (tw + 2)) / 2;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            Button b = Button.builder(Component.literal(i == tab ? "§e" + TABS[i] : TABS[i]), x -> {
                tab = idx;
                this.rebuildWidgets();
            }).bounds(tx + i * (tw + 2), 30, tw, 20).build();
            addRenderableWidget(b);
        }

        switch (tab) {
            case 0 -> {
                toggle(0, "Master switch", () -> c.enabled, v -> { c.enabled = v; FlipMod.applySources(); });
                toggle(1, "Coflnet feed", () -> c.useCofl, v -> { c.useCofl = v; FlipMod.applySources(); });
                toggle(2, "Own price database", () -> c.useIndex, v -> { c.useIndex = v; FlipMod.applySources(); });
                slider(3, "Undervalue (min % below ref)", range(5, 60), () -> c.undervaluePct, v -> c.undervaluePct = (int) v, v -> v + "%");
                slider(4, "Min listings per item", range(2, 20), () -> c.minSamples, v -> c.minSamples = (int) v, v -> v + "");
                slider(5, "Min profit", ZERO_MONEY, () -> c.minProfit, v -> c.minProfit = v, COINS);
                slider(6, "Max price", MONEY, () -> c.maxPrice, v -> c.maxPrice = v, COINS);
                var idx = FlipMod.feed.index;
                long age = idx.updatedAtMs == 0 ? -1 : (System.currentTimeMillis() - idx.updatedAtMs) / 1000;
                note(8, "Database: " + FlipMod.feed.status + " · " + idx.binCount + " BINs · " + idx.keyCount
                        + " items" + (age >= 0 ? " · data " + age + "s old" : ""));
            }
            case 1 -> {
                toggle(0, "Show flips in chat", () -> c.chatFlips, v -> c.chatFlips = v);
                slider(1, "Only if profit at least", ZERO_MONEY, () -> c.alertMinProfit, v -> c.alertMinProfit = v, COINS);
                note(3, "Alerts are chat lines only. Buying is controlled on the Auto-Buy tab.");
            }
            case 2 -> {
                note(0, "§cAutomated buying is against Hypixel's rules and can get your account banned.");
                toggle(1, "Auto-buy", () -> c.autoBuy, v -> c.autoBuy = v);
                slider(2, "Max buys per hour", range(1, 120), () -> c.maxBuysPerHour, v -> c.maxBuysPerHour = (int) v, v -> v + "");
                slider(3, "Click delay min (ticks)", range(0, 20), () -> c.minDelayTicks, v -> {
                    c.minDelayTicks = (int) v;
                    if (c.maxDelayTicks < v) c.maxDelayTicks = (int) v;
                }, v -> v + "");
                slider(4, "Click delay max (ticks)", range(0, 20), () -> c.maxDelayTicks, v -> {
                    c.maxDelayTicks = (int) v;
                    if (c.minDelayTicks > v) c.minDelayTicks = (int) v;
                }, v -> v + "");
                toggle(5, "Auto-claim purchases", () -> c.autoClaim, v -> c.autoClaim = v);
                note(7, "Bought this session: " + FlipMod.buy.bought + " (spent " + Fmt.coins(FlipMod.buy.spent)
                        + ", est. profit " + Fmt.coins(FlipMod.buy.expectedProfit) + ")");
                note(8, "Claimed: " + FlipMod.claim.claimed + " · buys in last hour: " + FlipMod.buy.buysLastHour());
            }
            case 3 -> {
                note(0, "Items containing any of these words are never bought:");
                ignoreBox = new EditBox(this.font, left(), rowY(1), 220, 20, Component.literal("Item name"));
                ignoreBox.setMaxLength(40);
                addRenderableWidget(ignoreBox);
                addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
                    String t = ignoreBox.getValue().trim();
                    if (!t.isEmpty() && !c.ignored.contains(t)) {
                        c.ignored.add(t);
                        c.save();
                    }
                    this.rebuildWidgets();
                }).bounds(left() + 230, rowY(1), 70, 20).build());
                List<String> list = c.ignored;
                for (int i = 0; i < Math.min(list.size(), 7); i++) {
                    final String entry = list.get(i);
                    addRenderableWidget(Button.builder(Component.literal("§c✖ §f" + entry), b -> {
                        c.ignored.remove(entry);
                        c.save();
                        this.rebuildWidgets();
                    }).bounds(left(), rowY(2 + i), 300, 20).build());
                }
                if (list.size() > 7) note(9, "+" + (list.size() - 7) + " more (edit config/flipmod.json)");
            }
            case 4 -> {
                toggle(0, "Show Coflnet chat messages", () -> c.showCoflChat, v -> c.showCoflChat = v);
                note(2, "Commands: /flipmod status, /flipmod stop, /cofl <anything>");
                note(3, "Config file: config/flipmod.json (contains your Coflnet id, keep private)");
            }
            default -> { }
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(this.width / 2 - 50, this.height - 30, 100, 20).build());
    }

    @Override
    public void onClose() {
        FlipMod.config.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------- slider over a fixed list of values ----------

    private static final class StepSlider extends AbstractSliderButton {
        private final long[] options;
        private final LongConsumer onChange;
        private final LongFunction<String> fmt;

        StepSlider(int x, int y, int w, int h, long[] options, long current, LongConsumer onChange, LongFunction<String> fmt) {
            super(x, y, w, h, Component.empty(), norm(options, current));
            this.options = options;
            this.onChange = onChange;
            this.fmt = fmt;
            updateMessage();
        }

        private static double norm(long[] opts, long cur) {
            int best = 0;
            long bestDiff = Long.MAX_VALUE;
            for (int i = 0; i < opts.length; i++) {
                long d = Math.abs(opts[i] - cur);
                if (d < bestDiff) { bestDiff = d; best = i; }
            }
            return opts.length <= 1 ? 0 : (double) best / (opts.length - 1);
        }

        private long value() {
            int i = (int) Math.round(this.value * (options.length - 1));
            return options[Math.max(0, Math.min(options.length - 1, i))];
        }

        @Override
        protected void updateMessage() {
            if (options == null || fmt == null) return; // called from the super constructor
            setMessage(Component.literal(fmt.apply(value())));
        }

        @Override
        protected void applyValue() {
            onChange.accept(value());
        }
    }
}
