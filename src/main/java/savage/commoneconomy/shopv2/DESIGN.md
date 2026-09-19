# Shop v2 — design spec

Living document. Edit anything. The "Recommendation" in each block is a proposed default, not a decision, until its **Status** says `Agreed`.

**Status legend:** `Proposed` (default suggested, not reviewed) · `Agreed` (you signed off) · `Changed` (you replaced the default; write what in Your notes) · `Open` (needs a decision)

---

## 1. Goals and principles

1. **Drop-in for players.** Same commands, permission nodes and click-then-type-in-chat flow as v1. Players should only notice that bugs are gone. New features come after parity.
2. **Correct before fast.** Performance work is deferred unless it also fixes a correctness problem.
3. **Small, readable pieces.** Each class does one job. Game-touching code stays thin, and decision logic (identity, trade planning, storage format) is written so it can be checked without starting Minecraft.
4. **Safe to switch back.** v2 never modifies v1's files. Setting `shopVersion` back to `"v1"` always works.
5. **Shops talk to the economy only through `EconomyService`.** That keeps the option open to ship shops as a separate addon later.

Non-goals for v2.0: changing the player experience, co-owners, editing an existing shop's price or item, protecting against hoppers or explosions.

---

## 2. Decisions

### D1 — Shop identity
**Status:** Proposed
**Recommendation:** A shop is identified by its **dimension plus the position of its anchor block**. The anchor is the container block the shop was created on. Every lookup (click, break, protect, sign) uses both.
**Why:** v1 keys shops by coordinates only, so the same coordinates in another dimension collide: false protection, wrong-shop trades, and a sign broken in the Nether can delete an Overworld shop.
**Alternatives:** Keep coordinates only and require unique coordinates across dimensions (rejected: not enforceable).
**Your notes:**

### D2 — Which blocks can be a shop
**Status:** Proposed
**Recommendation:** A **config-driven registry** of allowed container block IDs. Default: `minecraft:chest`, `minecraft:trapped_chest`, `minecraft:barrel`. Admins can add IDs (for example modded barrels). The registry also knows how to get the real inventory for a type (see D3).
Not "any Container". That includes hoppers, droppers, dispensers, furnaces, brewing stands and crafters, which move or consume items on their own.
**Why:** v1 lets you *create* a shop on any container but its cleanup only accepts chests, so barrel shops are deleted within 5 seconds (verified: `BarrelBlock` is not a `ChestBlock`; `TrappedChestBlock` is). GitHub PR #8 generalises to "any Container", which is the case this avoids.
**Removed-from-list behaviour:** a shop whose container type is no longer allowed is **disabled and reported, never deleted** (see D8).
**Config:** flat key in `config.json` to match the existing style, for example `shopAllowedContainers`. Name and shape are open.
**Your notes:**

### D3 — Double chests
**Status:** Proposed
**Recommendation:** **Supported.** The shop's inventory is the combined inventory of both halves, resolved when it is used (not stored). Both halves count as part of the shop for protection.
**Why:** Verified in the game code: opening either half of a double chest builds a combined inventory (`CompoundContainer`). A chest block entity only holds its own half, so v1 undercounts stock and space, and does not react to changes in the other half. Because v1 only protects the one registered block, opening the neighbouring half appears to give access to the shop half (not tested in-game).
**Related:** other players must not be able to create that merge (D4).
**Alternatives:** Refuse to create shops on double chests (simpler, but players use them).
**Your notes:**

### D4 — What v2 protects
**Status:** Proposed
**Recommendation:** The same as v1, plus one addition:
- Non-owners (and non-admins) cannot open a shop container.
- Nobody, including the owner, can break the container. The owner removes the shop by breaking its sign or using `/shop remove` and clicking the sign.
- Breaking the sign: owner or admin removes the shop, others are refused.
- **New:** other players cannot place a chest next to a shop chest in a way that would merge into a double chest.
- **Not protected, documented as out of scope:** hoppers pulling from a shop, explosions, and other ways of removing items. Leave those to claim or protection mods.
**Why:** the merge hole comes from how v2 treats double chests, so v2 should close it. The rest is a scope call: the mod already does a lot.
**Alternatives:** (a) players only, exactly like v1; (b) also block hopper placement next to shops; (c) also handle explosions.
**Your notes:**

### D5 — Signs
**Status:** Proposed
**Recommendation:** The shop record **stores its sign position**. Clicking a sign looks it up by dimension and sign position. Nothing searches around a container for "a sign". Only the recorded sign is a shop sign; other wall signs attached to the container are ordinary signs.
The sign is a **display**: its text is generated from the shop and can always be regenerated. If the sign is missing or destroyed, the shop still exists (see D8 for how that is surfaced).
**Why:** v1 found signs by searching the four sides of a chest and taking the first match. A neighbouring shop's sign was overwritten (fixed in v1, but the search is still the design). Storing the relationship removes the whole class of bug.
**Sign placement (parity):** placed on the side opposite the player's facing if free, otherwise north, south, east, west; only into air or a replaceable block. If no side is free, the shop is still created and the player is told the sign could not be placed.
**Nice to have, not parity:** a command to re-place a lost sign.
**Your notes:**

### D6 — Trades
**Status:** Proposed
**Recommendation:** A trade is **one operation** with a fixed order, run through a single trade service:
1. **Check** (main thread): the shop is valid and idle, stock or space is enough, the player has the items or space.
2. **Take the money** (async): from the buyer, or from the shop owner when the shop is buying.
3. **Move the items** (main thread): all-or-nothing, planned first and executed only if the whole plan fits, re-checking at the moment of movement. Anything that cannot be delivered is dropped at the player's feet, never lost.
4. **Pay the other party** (async).
5. **If any step fails:** reverse what can be reversed (refund the payer). If money cannot be moved, log an error and a `TRANSFER_FAILED` entry naming who is owed.
**One trade at a time per shop:** a shop is marked busy from step 1 until the trade finishes. A second player is told "shop busy, try again".
**Why:** v1 has several gaps where a step fails and earlier ones are not undone (for example, a sale can destroy items if the chest is gone, and can remove items partially). v1's handling of failed payments already follows steps 4 and 5 (refund, or log who is owed), but its item movement in sales does not (see section 6). v2 makes the full pattern the only way to trade.
**Item scan:** only the 36 main slots and hotbar; never armor or offhand.
**Item matching:** strict — same item and same components.
**Your notes:**

### D7 — Storage
**Status:** Proposed
**Recommendation:**
- One file: `config/savs-common-economy/data/shops.json`, with a `formatVersion`.
- Written atomically (temporary file, then move into place).
- **Saved when changed, at most every few seconds, and on shutdown.** Not on every event. (v1 rewrites the whole file on the main thread every second while any stock changes.)
- **Import from v1:** the first time v2 starts and its file does not exist, copy `config/savs-common-economy/shops.json` and convert it. v1's file is never modified.
- **A shop that cannot be loaded is kept in the file untouched and reported, not dropped.** This includes a shop whose item can no longer be decoded after a Minecraft update. It stays disabled until it can be read again.
- A backup copy is saved when anything fails to load (as v1 now does).
**Item encoding:** reuse v1's format (compressed NBT via `ItemStack.CODEC`, base64) so the import is straightforward. Open whether v2 should keep it.
**Template count:** v1 stores the held stack including its count. v2 stores a single-item template.
**Your notes:**

### D8 — How a shop notices its container or sign is gone
**Status:** Proposed
**Recommendation:** **Check lazily.** Check when a player clicks the shop's sign, when the shop's chunk loads, and when the container or sign is broken by a player. **No periodic polling.** A shop whose container is missing is **disabled and reported** (console and an admin command), not deleted.
**Why:** verified in the game code: v1's cleanup calls `getBlockState` for every shop every 5 seconds, and that forces the chunk to load (it adds a 1-tick ticket and blocks the main thread until loaded). A missing container should also not silently delete a shop record. Explosions and other removals otherwise leave a shop that is "gone" with no trace.
**Deferred:** an admin command to clean up disabled shops.
**Your notes:**

### D9 — Player experience (parity)
**Status:** Proposed
**Recommendation:** Keep v1's flow exactly for v2.0:
- Right-click the shop's sign, then type the amount in chat (or `all`). The typed message is consumed. It expires after 30 seconds.
- Invalid amounts cancel it.
- "All" for purchases is limited by what the buyer can afford, the shop's stock, and the buyer's inventory space, and capped at 2304. An admin shop refuses "all" for purchases (infinite stock).
- The pending state is cleared when the player disconnects, and the shop is re-validated when the amount is submitted (not trusted from the click).
- Sign layout: owner (or admin header), item name, price line ("Selling/Buying: $X"), stock line ("Stock: N", "Space: N", or infinite).
**Your notes:**

### D10 — Commands and permissions (parity)
**Status:** Proposed
**Recommendation:** Identical to v1.
| Command | Node | Default |
|---|---|---|
| `/shop create sell <price>`, `/shop create buy <price>` | `savscommoneconomy.shop.create` | everyone |
| `/shop info` | `savscommoneconomy.shop.info` | everyone |
| `/shop list` | `savscommoneconomy.shop.list` | everyone |
| `/shop remove` (then click the sign) | `savscommoneconomy.shop.remove` | everyone |
| `/shop admin` (convert the shop you look at) | `savscommoneconomy.admin` | op level 2 |

Creation needs an item in the main hand and a container within 5 blocks of the crosshair. Price `0` is allowed (a free shop). v1 has no per-player shop limit.
**Your notes:**

### D11 — Logging
**Status:** Proposed
**Recommendation:** Shop trades **are logged** to the transaction log (v1 does not log them), as `SHOP_BUY` and `SHOP_SELL`. `/ecolog` already colours types containing `SHOP` (v1 never writes one, so it is currently unused). Failures use `TRANSFER_FAILED` as elsewhere.
**Note:** logging is still called per feature (`TransactionLogger`); moving it into the core is a separate step.
**Your notes:**

### D12 — Language keys
**Status:** Open
**Question:** v2 could reuse v1's `shop.*` keys (admins' existing overrides keep working, and the wording stays identical), or use a fresh prefix (no risk of changing v1's messages while both exist, but overrides don't carry over).
**Leaning:** reuse existing keys for messages that stay the same; add new keys only for new messages.
**Your notes:**

### D13 — Configuration
**Status:** Open
**Question:** flat keys in `config.json` (matches `enableChestShops`, `shopVersion`) or a nested `shops` section? The allowed-container list is the only new setting so far.
**Leaning:** flat, to match the file.
**Your notes:**

---

## 3. Data model (sketch)

A shop record:
- `id` (UUID)
- `dimension` (for example `minecraft:overworld`) and `anchor` position
- `owner` (UUID) and cached `ownerName`
- `type`: player or admin
- `mode`: shop sells to players, or shop buys from players
- `item`: single-item template, with components
- `price`: unit price
- `sign`: position of the sign (may be missing, see D5)
- Runtime only, not saved: busy flag, disabled reason

Identity for lookups: `(dimension, position)`.

---

## 4. Proposed layout (names may change)

| Class | Job |
|---|---|
| `ShopV2Feature` | Feature hooks: registers commands and events when v2 is selected |
| `ShopRegistry` | The shops in memory and lookups by identity, sign, and owner |
| `ContainerRegistry` | Allowed container types, and resolving the real inventory (including double chests) |
| `ShopSigns` | Generates sign text, places and refreshes signs |
| `ShopCommands` | `/shop ...` |
| `ShopClickHandler` | Sign clicks and the chat amount flow |
| `ShopProtection` | Open and break rules, the merge rule |
| `TradeService` | The single trade operation (D6) |
| `ShopStorage` | Reading, writing, importing from v1 |

Rule for pure logic: trade planning, identity, and the storage format take plain values, so they can be exercised without the game.

---

## 5. Milestones (each a small, reviewable commit series)

- [x] **M0** Scaffold: `shopv2` package, `shopVersion` selector, data folder.
- [ ] **M1** Data model and storage, including import from v1 (no game hooks yet).
- [ ] **M2** Container registry and inventory resolving (chest, trapped chest, barrel, double chest).
- [ ] **M3** Sign rendering and placement.
- [ ] **M4** Commands: create, info, list, remove, admin.
- [ ] **M5** Click and chat handling.
- [ ] **M6** Trade service (purchase and sale).
- [ ] **M7** Protection, including the merge rule.
- [ ] **M8** Lazy orphan detection and the disabled-shop report.
- [ ] **M9** Parity testing against v1, README entry, decide when to make v2 the default.

---

## 6. v1 problems v2 must not repeat

| Problem found in v1 | Status in v1 | v2 answer |
|---|---|---|
| Shops keyed by position only (no dimension) | Open | D1 |
| Barrel and other-container shops deleted by cleanup | Open | D2, D8 |
| Double chests: only one half seen and protected | Open | D3, D4 |
| Sign lookup by searching around the chest | Fixed (picks the attached sign) | D5 |
| Sale can destroy or partly remove items | Open | D6 |
| Purchase leftovers lost when the inventory fills | Fixed | D6 (drop, never lose) |
| Worn armor and offhand counted or removed when selling | Fixed | D6 (36-slot scan) |
| `$0` shop plus "all" threw an error; huge quotient wrapped | Fixed | D9 (free shops allowed, no crash) |
| `shops.json` written in place; corrupt file blocked startup | Fixed | D7 |
| Undecodable item loaded as empty and was erased on next save | Fixed (shop skipped, backup) | D7 (kept untouched) |
| Cleanup force-loads every shop chunk every 5 seconds | Open | D8 |
| Whole file rewritten on the main thread every second | Open | D7 |
| Remove-mode not cleared when a player leaves | Open | D9 (clear on disconnect) |
| A pending trade still works after the shop is removed | Open | D9 (re-validate) |
| Anyone can merge a chest into a shop chest | Open | D4 |
| Hoppers can drain shop chests | Open | Out of scope (D4) |
| Shop trades not in the transaction log | Open | D11 |

---

## 7. Open questions

- Should a free shop (price 0) stay allowed? (v1 allows it; leaning yes.)
- A per-player shop limit? (v1 has none; leaning none, add a config option later.)
- Should v2 become the default at M9, or only after real-world testing on your server?
- Anything you want in v2.0 beyond parity?

---

## 8. Retiring v1 (reminder)

When v1 is removed: delete the `shop` package and its mixin, drop its entry from the feature list, remove `ShopVersion` and the `shopVersion` setting, rename `shopv2` to `shop`, and remove v1's lang keys.

---

## 9. Change log

- Initial draft with recommended defaults (all decisions `Proposed`).
