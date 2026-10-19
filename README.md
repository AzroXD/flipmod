# FlipMod (Minecraft 26.1.2, Fabric)

Client-side Hypixel SkyBlock auction flipping helper with a settings GUI.

Open the GUI with `/flipmod`.

## Features

- **Two flip sources** (toggle each in the GUI):
  - **Coflnet feed** (`wss://sky.coflnet.com/modsocket`). `/cofl ...` and `/cl ...` forward to Coflnet.
  - **Own price database**: downloads every active BIN from Hypixel's public auction API (no key), groups
    items into comparable keys (item id + rarity + stars + recomb + skin; pets by type/rarity/level 1 or 100;
    single-enchant books), and flags new listings priced well below the market.
- **Auto-snipe:** `/viewauction` -> *Buy Item Right Now* -> *Confirm*. **Off until you enable it.**
- **Auto-claim:** opens `/ah` -> *Your Bids* and collects purchases.
- **Limits:** max price, min profit, undervalue %, min listings per item, max buys per hour, randomised click
  delay, ignore list, stale-flip dropping, no double-buying the same auction from two sources.
- **GUI tabs:** Sniper, Alerts, Auto-Buy (with session stats), Ignore, Display.
- Only runs while connected to a Hypixel server.

## How the price database decides

For each newly listed BIN it looks at the previous snapshot of the same item key and uses the
**second-lowest BIN** as the reference price (so one outlier cannot set the price). It flags the listing when:

- at least `min listings` of that item exist,
- price is at least `undervalue %` below the reference,
- profit after estimated AH tax is at least `min profit`,
- price is at most `max price`, and the item is not on the ignore list.

Limits you should know about: Hypixel updates this API about once a minute, so by the time a flip shows up it
may already be bought (Coflnet is faster). It compares **listing** prices, not sold prices or sales volume, so it
can flag items that are cheap but do not sell. Reforges, gear enchants and gems are not in the key, so a plain
item priced below the market is flagged, but a good-roll item is never compared to a worse one. Mid-level pets,
stacks and multi-enchant books are skipped. Keep `min profit` and `undervalue` conservative.

## Commands

`/flipmod` (GUI), `/flipmod status`, `/flipmod toggle`, `/flipmod snipe`, `/flipmod claim`, `/flipmod claimnow`,
`/flipmod stop`, `/flipmod maxprice 50m`, `/flipmod minprofit 500k`, `/flipmod delay 2 4`, `/flipmod debug`,
`/flipmod reconnect`.

Config: `config/flipmod.json` (contains your Coflnet session id; do not share it).

## Build

Requires Java 25.

```
gradle wrapper      # or copy gradlew + gradle/ from a template at https://fabricmc.net/develop
./gradlew build
```

Put the jar from `build/libs/` and Fabric API (26.1.2) in your `mods` folder.

## What is tested and what is not

- **Tested (outside Minecraft):** the price database: NBT decoding, item keys, page parsing, snapshot logic,
  sniping rules, ignore list, max price, no repeat flips. All pass.
- **Not compiled or run against Minecraft 26.2.** Expect to fix a few names on first build:
  1. `gradle.properties` loom / loader / Fabric API versions (generate a fresh template at fabricmc.net/develop
     and copy its versions if Gradle rejects them).
  2. `Gui.java` holds all Minecraft calls for clicking and commands (most likely rename: `handleInventoryMouseClick`).
  3. `SettingsScreen.java` uses stock widgets only (`Button`, `AbstractSliderButton`, `EditBox`, `StringWidget`).
  4. Coflnet message field names in `CoflSocket.parseFlip` (use `/flipmod debug` to see raw messages).
  5. Hypixel menu item names are matched by text in `AutoBuy` / `AutoClaim`.
- **Not included:** an on-screen HUD overlay, bazaar flips, item tooltip prices, trade/lowball tracking.

## Hypixel rules

Automated buying and claiming is against Hypixel's rules and can get an account banned. Rate limits and
randomised delays reduce how mechanical it looks but do not make it safe. Use an alt account.

## Getting the .jar (easiest first)

**A. GitHub (no installs):** make a free GitHub repo, upload the contents of this folder (keep the
`.github` folder), open the **Actions** tab, wait for "Build mod" to finish, download the `flipmod-jar` artifact.

**B. Windows:** install Temurin JDK 25 and Gradle, then double-click `build.bat`. The jar appears in `build\libs\`.

Then put `flipmod-0.1.0.jar` AND Fabric API (26.1.2) in `.minecraft\mods`.
If the build fails, copy the error text and send it back; the fixes are usually one-line name changes.
