# Shop v2 — design spec

Living document. Edit anything. The "Recommendation" in each block is a proposed default, not a decision, until its **Status** says `Agreed`.

**Status legend:** `Proposed` (default suggested, not reviewed) · `Agreed` (you signed off) · `Changed` (you replaced the default; write what in Your notes) · `Open` (needs a decision)

---

## 1. Goals and principles

1. **Drop-in for players.** Same commands, permission nodes and click-then-type-in-chat flow as v1. Players should only notice that bugs are gone. New features come after parity. The only exceptions are the safety and recovery additions agreed in D4, D5, D9 and D10 (for example `/shop resign`, the range rule when typing an amount, and remove-mode expiring).
2. **Correct before fast.** Performance work is deferred unless it also fixes a correctness problem.
3. **Small, readable pieces.** Each class does one job. Game-touching code stays thin, and decision logic (identity, trade planning, storage format) is written so it can be checked without starting Minecraft.
4. **Safe to switch back.** v2 never modifies v1's files. Setting `shopVersion` back to `"v1"` always works.
5. **Money moves only through `EconomyService`.** That keeps the option open to ship shops as a separate addon later. Logging, permissions and translations still use the core helpers (D11), so an addon would need those too.

Non-goals for v2.0: changing the player experience, co-owners, editing an existing shop's price or item, several shops on one container, protecting against hoppers or explosions.

---

## 2. Decisions

### D1 — Shop identity
**Status:** Agreed
**Recommendation:** A shop is identified by its **dimension plus the position of its anchor block**. The anchor is the container block the shop was created on, and it stays fixed for the life of the shop. Every lookup (click, break, protect, sign) uses both.
**One shop per container.** A double chest counts as one container. Creating a shop checks both halves and refuses, with a message, if either half already belongs to a shop. If a chest is later merged next to an existing shop chest, the shop simply extends to cover both halves (the partner half is found from the chest's block state when the shop is used).
**Unreachable dimension:** if a dimension no longer exists (for example a datapack that added it was removed), its shops are disabled and reported, not deleted (see D8).
**Why:** v1 keys shops by coordinates only, so the same coordinates in another dimension collide: false protection, wrong-shop trades, and a sign broken in the Nether can delete an Overworld shop. Without the one-per-container rule, two shops could be created on the two halves of one double chest and share a single inventory.
**Alternatives:** Keep coordinates only and require unique coordinates across dimensions (rejected: not enforceable). Allow several shops on one container, for example a buy shop and a sell shop for different items (considered and deferred to keep v2.0 simple; can be revisited later).
**Your notes:** Agreed: one shop per container, and refuse creation on the second half of a double chest.

### D2 — Which blocks can be a shop
**Status:** Agreed
**Recommendation:** A **config-driven registry**, in the flat config key `shopAllowedContainers` (see D13). It is a list of block IDs and/or block tags (a `#` prefix marks a tag). Default:
- `minecraft:chest`
- `minecraft:trapped_chest`
- `minecraft:barrel`

Left out on purpose: shulker boxes, ender chests (an ender chest is not a real container block), and copper chests (see the copper golem note below).
Not "any Container". That includes hoppers, droppers, dispensers, furnaces, brewing stands and crafters, which move or consume items on their own.
**Tags are supported so an admin can add copper chests correctly:** copper chests are eight blocks (four oxidation stages, waxed and unwaxed), and verified in the game code, oxidizing or waxing one changes the block's ID but keeps the block entity (`CopperChestBlock.shouldChangedStateKeepBlockEntity`). An entry of only `minecraft:copper_chest` would disable a shop the day its chest oxidizes, so the tag `#minecraft:copper_chests` is the right entry. The check is made against the block that is there now, never against an ID stored with the shop.
**How an entry's inventory is found (by the block's class, not by a setting):**
1. A chest-family block (extends `ChestBlock`, which includes copper chests) can be half of a double chest, so it uses the game's own combining (D3).
2. Any other block whose block entity is a `Container` (barrels, and most modded containers) is a single inventory, used as is.
3. Anything else (no container, for example an ender chest) is ignored, and a warning is logged at startup.

**Responsibility:** entries beyond the defaults are the responsibility of whoever configures them. Some containers restrict which items or slots they accept, and v1 inserts items directly without checking. The defaults are the supported set.
**Why:** v1 lets you *create* a shop on any container but its cleanup only accepts chests, so barrel shops are deleted within 5 seconds (verified: `BarrelBlock` is not a `ChestBlock`; `TrappedChestBlock` is). GitHub PR #8 generalises to "any Container", which is the case this avoids.
**Removed-from-list behaviour:** a shop whose container type is no longer allowed is **disabled and reported, never deleted** (see D8).
**Why copper chests are not a default:** the Copper Golem (present in 26.3) moves items between containers, and its behaviour code references the copper chest tag, so a golem near a copper chest shop can move stock in or out of it, or be used to take it, like a hopper. The exact pickup and drop-off rules have not been verified. Trades re-check stock at the moment items move (D6), so this cannot duplicate items, only change stock. An admin who adds the tag accepts that risk.
**Future:** look into a clean way to handle copper golems (one candidate: a mixin that keeps golems away from shop containers), and then reconsider copper chests as a default.
**Your notes:** Default is chest, trapped chest and barrel. Copper chests, shulker boxes and ender chests are left out. Responsibility for extra entries lies with whoever configures them.

### D3 — Double chests
**Status:** Agreed
**Recommendation:** **Supported.** The shop's inventory is the combined inventory of both halves, resolved when it is used, not stored:
- The partner half is found from the chest's own block state, and the game's `ChestBlock.getContainer(..., override = true)` returns the combined inventory.
- The `override` flag makes a block on top of the chest, or a cat sitting on it, not stop the shop (verified: it swaps the "blocked" check for one that is always false). This matches v1, which reads the chest directly.
- Both halves count as part of the shop. One lookup, "is this position part of a shop?", checks the block and its partner half. It is used for protection, sign refresh and change detection, so a change in either half refreshes the sign.

**Why:** Verified in the game code: opening either half of a double chest builds a combined inventory (`CompoundContainer`). A chest block entity only holds its own half, so v1 undercounts stock and space, and does not react to changes in the other half. Because v1 only protects the one registered block, opening the neighbouring half appears to give access to the shop half (not tested in-game).
**Related:** D1 (one shop per container; creating a shop on the second half is refused) and D4 (other players cannot create the merge).
**Alternatives:** Refuse to create shops on double chests (simpler, but players use them).
**Your notes:** Agreed.

### D4 — What v2 protects
**Status:** Agreed
**Recommendation:** The same as v1, plus two additions. All of it applies to both halves of a double chest (D3).
- **Protection follows the shop record, whatever its status** (D8): a shop's container and sign stay protected in every status except deleted. Removing a container type from `shopAllowedContainers` (Disabled) or a lost sign (No sign) never removes protection. The one exception is a record too broken to read its dimension and position from (Unreadable): it cannot be protected because we do not know where it is, though the raw entry is kept (D7).
- Non-owners (and non-admins) cannot open a shop container.
- Nobody, including the owner, can break the container. The owner removes the shop by breaking its sign, or by using `/shop remove` and clicking the sign or the container (D5).
- Breaking the sign: owner or admin removes the shop, others are refused.
- **New, merge rule (option B):** a chest placed next to someone else's shop chest comes out as a normal single chest instead of merging. This is a small mixin on the chest's placement state: if the new chest would connect to a shop chest whose owner is not the player placing it, it is made single. The chest is still placed; it just does not join. The owner placing a chest next to their own shop chest merges normally, which extends the shop. The mixin must do nothing when v2 is not the active shop version. Chests that override placement (copper chests, and possibly modded chests) may not be covered by a mixin on the base chest; to be checked if copper chests ever become a default.
- **New, creation access check:** before a shop is created, fire the normal block-interaction event (Fabric `UseBlockCallback`) for the player and the aimed container. If any mod cancels it, creation is refused with a message. Limitations: only mods that use that event are consulted, and other mods' callbacks may show their own "protected" message to the player. Both need testing with the common claim mods.
- **Not protected, documented as out of scope:** hoppers pulling from a shop, explosions, copper golems, and other ways of removing items. Leave those to claim or protection mods.

**Why:**
- The merge hole comes from how v2 treats double chests, so v2 closes it.
- The access check closes a hole in v1. Reading the code, `/shop create` never checks that the player may use the container. On a server with a claim mod, someone could aim at a chest inside another player's claim and turn it into their shop. Trading through the sign moves items directly and never goes through the claim mod, so they could set their own price and buy the contents. Not tested with a claim mod.
- The rest is a scope call: the mod already does a lot.

**Alternatives:** Merge rule: (A) deny placing a chest next to a shop chest (simpler, but also blocks placements that would not have merged, for example while sneaking). Scope: (a) players only, exactly like v1; (b) also block hopper placement next to shops; (c) also handle explosions.
**Your notes:** Option B chosen for the merge rule. Creation access check: yes. The owner merging their own chests into their own shop is allowed (confirmed): it extends the shop.

### D5 — Signs
**Status:** Agreed
**Recommendation:** The shop record **stores its sign position**. Clicking a sign looks it up by dimension and sign position. Nothing searches around a container for "a sign". Only the recorded sign is a shop sign; other wall signs attached to the container are ordinary signs.
The sign is a **display**: its text is generated from the shop and can always be regenerated. If the sign is missing or destroyed, the shop still exists (see D8 for how that is surfaced).
**Why:** v1 found signs by searching the four sides of a chest and taking the first match. A neighbouring shop's sign was overwritten (fixed in v1, but the search is still the design). Storing the relationship removes the whole class of bug.
**Sign placement (agreed; differs from v1):** the sign is always a wall sign attached to a **side face** of the anchor block, never the top or bottom.
- The side is **the face the player is aiming at** when they run `/shop create`.
- If they are aiming at the top or bottom face, use the side **opposite the direction the player is facing** (v1's rule), so the sign faces the player.
- The spot must be air or a replaceable block, and a wall sign must be able to stand there.
- **If the spot is not usable, creation is refused** with a message that says why, for example "There is no room for the shop sign on that side. Clear the space in front of that face." Nothing is created. No other side is tried, and a shop is never created without its sign.
- Order of work: check the spot first, create the shop (D7's order: the item file, then `shops.json`), then place the sign. If placing fails after the record exists, remove the shop again (D7's removal order: `shops.json` first, then the item file).
- New message key: `shop.command.create_no_sign_space` (see D12).

**Difference from v1:** v1 always used the side opposite the player's facing. If that side was blocked it tried north, south, east, west in a fixed order (so the sign could face away), and it still created the shop if no side worked.
**Importing from v1 (agreed):** v1 never recorded sign positions, so the import looks for wall signs attached to the container. Exactly one: record it. More than one: record the first and log the others. None: import the shop anyway, flagged "no sign". The lookup is deferred until the shop's chunk is checked, so the import itself never loads chunks (see D7).
**Recovering a lost sign (agreed, both additive):**
- Remove-mode also accepts clicking the shop's **container**, not only its sign, so a shop can always be removed. In v1 a shop whose sign is destroyed by something other than a player cannot be removed and its container can never be broken (verified: v1's only removal paths are clicking the sign in remove-mode, breaking the sign, and the cleanup when the chest is gone).
- New **`/shop resign`** (owner or admin, aimed at the shop's container): re-places the sign using the same aimed-face rule and the same refusal messages as creation.

**Owner name on the sign:** refresh the cached owner name when the owner next joins, so a renamed player does not keep the old name.
**How a shop with no sign is flagged:** see D8 (a status that is reported once when it changes, and shown in `/shop list` and `/shop info`).
**Your notes:** Agreed: the placement rule, the import approach, both recovery options, and flagging through D8.

### D6 — Trades
**Status:** Agreed
**Rule:** **The payer pays first, the goods move, and the payee is paid last.** Every trade is one operation, run through a single trade service.

Why this order:
- The payer goes first because that is where ordinary failures happen ("not enough money"). If it fails there, nothing else has been touched.
- The goods move in one main-thread task that plans and then executes, so nothing can change in between and the move is all-or-nothing. Items are never held in memory while waiting on the economy. Money losses can be traced and repaired from the log; lost items cannot.
- The payee goes last because a refund needs money. If the owner were paid first and the trade then had to be undone, the owner might already have spent it.

| Trade | 1. Payer pays | 2. Goods move (main thread) | 3. Payee is paid |
|---|---|---|---|
| Buy from a player shop | Buyer is charged | Items go from the container to the buyer | Owner is credited |
| Sell to a player shop | Owner is charged | Items go from the seller into the container | Seller is credited |
| Buy from an admin shop | Buyer is charged | Items are given to the buyer | (nothing) |
| Sell to an admin shop | (nothing, the system pays) | Items are taken from the seller | Seller is credited |

**Steps:**
1. **Check** (main thread): the shop's status is OK (D8) and it is not busy, the player is in range (D9), stock or space is enough, the player has the items or the room, and the payer has enough money by cached balance (a quick refusal; the real check is the charge).
2. **Payer pays** (async).
3. **Goods move** (main thread), all-or-nothing: plan first (counts, the receiver's room, the container's capacity by simulation), re-check at the moment of movement (including that the shop still exists and is still OK), and execute only if the whole plan fits.
4. **Payee is paid** (async).
5. **Failures:** if step 1 or 2 fails, nothing else has happened; tell the player. If step 3 fails, refund the payer. If step 4 fails, the payee is owed: log an error and a `TRANSFER_FAILED` entry naming who is owed, and tell them if online. A refund that itself fails is handled the same way.

**Details:**
- **A player who is gone when the goods would move** (disconnected or dead): abort and refund the payer, because items given to a gone player's inventory are lost. Checked with `hasDisconnected()`, `isAlive()` and `isRemoved()` (verified to exist). The money side is safe offline, since it works by UUID.
- **A buyer whose inventory is full when the items arrive:** the leftovers are dropped at their feet in normal-sized stacks. Nothing is lost and no refund is needed.
- **One trade at a time per shop ("busy"):** starts when the amount is submitted, not when the sign is clicked (otherwise an idle clicker would lock the shop). Released on every outcome, including errors and exceptions. A watchdog (about 30 seconds) frees a stuck lock and logs an error, for example if a payment never completes. A second player sees "shop busy, try again" (new key `shop.transaction.busy`).
- **Totals:** computed once, rounded to two decimals (half up), and that one figure is used to charge, credit, log and show the player. The SQL balance column is `DECIMAL(30, 2)` and nothing in the code rounds, so an unrounded total such as `0.335 x 3` could leave the two sides with slightly different amounts.
- **Prices** are limited to two decimals at creation, so a price cannot round to zero by accident. A price of exactly 0 is still allowed (D10).
- **The owner trading with their own shop** stays allowed (v1 parity).
- **Item scan:** only the 36 main slots and hotbar; never armor or offhand.
- **Item matching:** strict, the same item and the same components.
- **Inserting into a container** respects the container's own rules (`canPlaceItem` and its stack limit) instead of writing slots directly as v1 does.
- **Testability:** the plan (counts, totals, the order of steps, and the outcome of each failure) is pure logic on plain values. Only reading inventories and moving items touch the game.

**Why:** v1 has several gaps where a step fails and earlier ones are not undone (for example, a sale can destroy items if the chest is gone, and can remove items partially). v1's handling of failed payments already follows steps 4 and 5 (refund, or log who is owed), but its item movement in sales does not (see section 6). v2 makes the full pattern the only way to trade.
**Alternatives considered:**
- Take the items first, then charge (rejected: the common failure, not enough money, would need an item put-back every time, and items would be held in memory across the async gap).
- Pay the owner before moving the goods (rejected: an undo may then be impossible because the money is spent).
- Abort and refund when the buyer's inventory is full (rejected: dropping is simpler and matches `/buy`).
- Allow several trades at once on one shop (rejected: much harder to reason about).

**Your notes:** Agreed: the ordering, dropping leftovers on a full inventory, abort and refund when the player is gone, the busy rule, rounding totals to two decimals and two-decimal prices, and the owner trading with their own shop.

### D7 — Storage
**Status:** Agreed
**Layout** (all under `config/savs-common-economy/data/`, see `DataFolder`):
- `shops.json`: the shop records, with a `formatVersion`.
- `shopitems/<shopId>.snbt`: one file per shop holding its item. The shop's own ID is the pointer, so a shop record has no item field. A shop sells exactly one item, so it is one file per shop.

**Shop record (sketch):** `id`, `dimension`, `anchor {x,y,z}`, `owner`, `ownerName`, `type`, `mode`, `price` (a string, two decimals), `sign {x,y,z}` or null, and `needsSignLookup` for imported shops (see Import).
**Not stored:** stock and status. Stock is recalculated from the container whenever it is needed. v1 saves it, but only `/shop info` reads it, which is why v1 rewrites the whole file every second while stock changes. Status (D8) is worked out at run time.

**Saving:**
- Saved **immediately** whenever a shop's record changes: create, remove (including automatic removal, D8), re-place a sign, the deferred sign lookup for an imported shop, convert to admin, or an owner's name refresh. These are rare, so there is no timer or debounce and nothing is lost on a crash.
- **Atomic:** write a temporary file, flush it to disk, then move it into place.
- **One previous copy is kept** as `shops.json.bak` on every save.
- Runs on the main thread (the events are rare and the file is small).

**Item files (SNBT):**
- The item as the game's own data (`ItemStack.CODEC` with NBT ops), written as **SNBT text**, with a `DataVersion` at the root, the same convention vanilla uses. Readable, editable, easy to inspect.
- Verified in the game code: `DataFixers.getDataFixer()` is public, there is a fixer type for items (`References.ITEM_STACK`), and `NbtUtils` provides the `DataVersion` helpers and SNBT read and write. The game's data fixer only works on NBT, so this is the path that is supported for upgrading old data.
- **Loading:** read the file, look at its `DataVersion`, run the data fixer from that version up to the current one if it is older, then decode. If the file was upgraded, rewrite it (atomically) at the current version.
- The stored item is a **single-item template** (count 1). v1 stores the held stack including its count.
- A file whose `DataVersion` is **newer** than the running game is left untouched, and its shop is Unreadable.
- **Verified (M1):** the data fixer upgrades old items correctly. Two hand-written files, one from data version 3700 (the 1.20.4 layout with `Count` and `tag`) and one from 3955 (1.21.1 components), were loaded on the current version (5023). Both came out with the right item, name, damage and enchantment, and the file was rewritten in the current format. The check ran the game's own registries and data fixer outside the game. It covers those two eras; other versions rely on Mojang's fixers.

**Consistency between the two:** item files are written once and never change, except for a version upgrade.
- Creating a shop: write the item file first, then save `shops.json`.
- Removing a shop: save `shops.json` first, then delete the item file.
- A crash between the two leaves at most an unused item file, never a broken shop.
- A shop whose item file is missing, unreadable or undecodable is **Unreadable**: kept, reported, and not used until it can be read.
- **Unused item files** (not referenced by any shop) are **reported at startup and never deleted automatically.**

**Safety, carried over from v1's fixes:**
- **A shop that cannot be read is kept exactly as it was** in `shops.json` (the raw entry is retained and written back), not dropped.
- **A file written by a newer version is never overwritten.** If `formatVersion` is higher than this version understands, v2 leaves it alone and reports it, so switching to an older jar cannot destroy data.
- A backup copy of `shops.json` is made whenever anything fails to load.

**Import from v1** (one time, one way):
- Runs the first time v2 starts and `shops.json` does not exist in the data folder. v1's file is never modified. The importer itself refuses to run if a v2 `shops.json` exists, and writes nothing if no shop could be imported, so a later start can try again. v1's shop ids are kept, so the item files are named after them.
- For each v1 shop: the dimension, position, owner, type and price carry over, and the mode comes from v1's `buying` flag. The item is decoded from v1's base64 and rewritten as an SNBT file with a count of 1, stamped with the current `DataVersion` (v1 never recorded one, so it is assumed current). A v1 item that cannot be decoded leaves that shop out of the import, reported; it stays in v1's file.
- Prices with more than two decimals are **rounded to two decimals (half up) on import**, and each change is logged as a warning, especially any price that becomes 0 (a free shop). This keeps imported prices consistent with the two-decimal rule (D6, D10), so a total can never round to zero while the sign shows a price. A price outside 0 to 1,000,000,000 leaves that shop out of the import (reported).
- **The import does not read blocks**, because that would mean loading chunks. It records the shop with no sign and marks it `needsSignLookup`. The first time its chunk is checked (D8), the sign is looked up: exactly one attached wall sign is recorded, several record the first and log the rest, none becomes "No sign".
- After the first import, v1 and v2 files do not sync. Shops made in one do not appear in the other if you switch back. This is documented. An admin command to pull in missing shops from v1 is possible later.

**Loading timing:** on server start, once the registries are available (decoding an item needs them).
**Why:** v1 rewrites the whole file on the main thread every second while stock changes, saves a stock number nobody needs, loses shops that fail to load, and stores items as an opaque blob that breaks across Minecraft updates.
**Alternatives considered:**
- The item as a base64 string inside `shops.json` (v1's way): a single file, but opaque.
- The item as SNBT text inside `shops.json`: a single file, but the quotes are escaped and it is hard to read.
- Compressed binary NBT files: smaller, but not readable.
- A separate item UUID: only useful if shops shared an item file.
- Debounced saving: not needed once stock is no longer stored.

**Your notes:** Agreed: not storing stock or status and saving immediately; one previous copy as `.bak`; item files as SNBT named by the shop ID; unused item files reported and never auto-deleted; the import is one-time and one-way.

### D8 — How a shop notices its container or sign is gone
**Status:** Agreed
**Recommendation:** A shop's parts are checked at four points, and **none of them ever forces a chunk to load**:
1. **When a player breaks it** (an event, immediate).
2. **On use:** a sign click, a trade, `/shop info`, `/shop list`, `/shop resign`, `/shop remove`.
3. **When the shop's chunk loads** (catches anything that changed while it was unloaded, such as offline edits). Fabric has a chunk-load event; to be confirmed when implementing.
4. **A periodic sweep of shops whose chunk is already loaded**, every few seconds (about 5 to start, tunable). It skips any shop in an unloaded chunk. Verified in the game code: the "is this chunk loaded" check is a pure lookup and does not load it.

A shop can only be physically broken while its chunk is loaded (offline edits are covered by point 3), so the sweep plus the chunk-load check catch every cause, including explosions, commands and other mods, without a hook for each.

**Status model.** Each shop has a status that these checks keep current:
| Status | Meaning | What happens |
|---|---|---|
| OK | Everything present | Normal |
| No sign | Container fine, recorded sign missing | The shop is inert (trading is by sign). The container stays protected. The owner or an admin can `/shop resign` or remove it. |
| Container gone | The anchor block is no longer a container (air, or any block that is not a container) | **The shop is deleted automatically.** |
| Disabled | The container type is no longer allowed by `shopAllowedContainers`, or the dimension cannot be found | **Kept**, trades refused, reported. An admin or owner can remove it. It works again if the entry is added back. |
| Unreadable | The shop could not be loaded from the file (D7) | Kept untouched in the file, reported. |

**Protection in every status:** a shop's container and sign stay protected in every status except deleted (D4).

**Deleting a shop whose container is gone.** Because it cannot be undone:
- Only when the dimension is known, the chunk is loaded, and the block at the anchor is not a container at all. A container that is merely not in the allowed list is **Disabled**, not deleted.
- It must be seen missing on **two consecutive checks** before it is deleted, as a guard against a transient state.
- The full record (owner, item, price, position) is written to the log when it is deleted, so it can be restored by hand.
- The owner is told if online.
- **Known limitation:** because the check is against the block that is there now (D2), if a destroyed container is replaced by a different allowed container within those two checks (about 10 seconds), the shop treats the new one as its container. It is a narrow window and needs a container placed in exactly that spot.

**Reporting happens once, when the status changes**, not on every check: a console warning with the owner and position, a message to the owner if online (for example "your shop lost its sign, use `/shop resign`"). `/shop list` and `/shop info` always show the current status.
**Why:** verified in the game code: v1's cleanup calls `getBlockState` for every shop every 5 seconds, and that forces the chunk to load (it adds a 1-tick ticket and blocks the main thread until loaded). The fix is not to stop checking; it is to check only chunks that are already loaded. Keeping a record for a container that no longer exists would leave an unusable shop with no way back, so it is deleted, but never silently: it is logged and the owner is told.
**Deferred:** an admin command that lists flagged shops.
**Your notes:** Agreed: the loaded-chunk sweep, and deleting shops whose container is gone. Disabled (config change or missing dimension) is kept instead.

### D9 — Player experience (parity)
**Status:** Agreed
**Recommendation:** The same flow as v1 for v2.0, plus the additions marked *new*.

**The flow (unchanged from v1):**
- Right-click the shop's sign, then type the amount in chat (or `all`). The typed message is consumed. It expires after 30 seconds, checked when the player next chats (an expired one lets that message through as normal chat).
- Any chat line while a trade is pending counts as the amount. An invalid amount cancels it with a message.
- Only the main hand triggers it. Clicking a shop sign never places a block or opens the sign editor.
- "All" for purchases is limited by what the buyer can afford, the shop's stock and the buyer's inventory space. The 2304 cap is implied by inventory space (36 slots x 64) and is kept only as a defensive limit. An admin shop refuses "all" for purchases (infinite stock).
- "All" for sales is limited by what the player has and by the shop's free space (player shops).
- Sign layout: owner (or admin header), item name, price line ("Selling/Buying: $X"), stock line ("Stock: N", "Space: N", or infinite). Change-driven refreshes are batched about once a second. The sign is a generated display (D5).

**New:**
1. **Distance and dimension.** The pending trade remembers the shop's identity, including its dimension, not the world the player happens to be in. When the amount is submitted the player must still be in that dimension and within about 16 blocks of the sign, or the trade is cancelled with a message. v1 uses whichever dimension the player is in when they type and checks no distance, so a trade can run against the wrong dimension or a far-away chunk. This also means v2 never needs to load a distant chunk for a trade (D8).
2. **Re-validation on submit.** The pending trade keeps only the shop's identity. When the amount is submitted the shop is looked up again: it must still exist, have status OK (D8), and not be busy (D6). The pending trade is also cleared when the player disconnects. (v1 keeps a reference to the shop from the click, so a shop removed within the 30 seconds can still be traded.)
3. **A shop that is not OK.** Clicking it says the shop is currently unavailable and does nothing. The sign text is left as it is.
4. **"All" for sales is also limited by the owner's funds** (player shops), so the player sells as much as the owner can pay for instead of the whole trade failing. Explicit amounts still refuse if the owner cannot afford them. The limit uses the cached balance, so the real charge can still fail (D6).

**Kept as in v1 for v2.0 (to revisit when polishing, see section 7b):**
- Asking to sell more than you have silently sells what you have, while asking to buy more than the shop has is refused.
- Admin shops refuse "all" for purchases.

**Why:** v1 keeps a reference to the shop and the player's current world from the moment they type, so a removed shop, a changed dimension or a far-away player can still produce a trade.
**Your notes:** Agreed: the range rule (same dimension, within about 16 blocks), re-validation on submit, "all" for sales limited by the owner's funds, and keeping v1's small quirks for v2.0.

### D10 — Commands and permissions (parity)
**Status:** Agreed
**Recommendation:** Identical to v1, plus `/shop resign` and the additions below.

| Command | Node | Default |
|---|---|---|
| `/shop create sell <price>`, `/shop create buy <price>` | `savscommoneconomy.shop.create` | everyone |
| `/shop info` (shows live stock and status) | `savscommoneconomy.shop.info` | everyone |
| `/shop list` (shows dimension and status) | `savscommoneconomy.shop.list` | everyone |
| `/shop remove` (then click the sign or the container) | `savscommoneconomy.shop.remove` | everyone |
| `/shop resign` (new: aim at your shop's container to re-place its sign) | `savscommoneconomy.shop.create` (reused, open to change) | everyone (owner or admin only) |
| `/shop admin` (convert the shop you look at) | `savscommoneconomy.admin` | op level 2 |

**Creation** needs an item in the main hand, a container within 5 blocks of the crosshair, a free side face for the sign (D5), and the player must be allowed to open that container (D4). Creating a shop on a container that already belongs to a shop is refused (D1). v1 has no per-player shop limit, and neither does v2.
**Price rules at creation:**
- At most **two decimals**. A price with more is refused with a message (D6). A price of exactly 0 is allowed (a free shop).
- A **maximum of 1,000,000,000 per item**, refused with a message above that. This is a fixed rule, not a config setting. Reason: the SQL balance column is `DECIMAL(30, 2)` (about 10^28) and explicit trade amounts go up to about 2.1 billion items, so an unbounded price could push a total past what the database can store, and the credit would fail and leave someone owed. With the cap, the largest possible total is about 2 x 10^18.

**Remove-mode:** cleared when the player disconnects, and expires after 30 seconds with a message. (In v1 it stays on until a shop sign is clicked, so a player who forgets can remove their own shop later by clicking its sign to trade.)
**`/shop info`** shows the live stock (calculated when asked, since stock is no longer stored, D7) and the shop's status (D8). **`/shop list`** shows each shop's item, dimension, position and status.
**`/shop admin`** is unchanged: a one-way conversion.
**Your notes:** Agreed: two-decimal prices with a fixed maximum of 1,000,000,000 per item, remove-mode clearing on disconnect and expiring after 30 seconds, and `/shop info` and `/shop list` showing live stock, status and dimension.

### D11 — Logging
**Status:** Agreed
**Recommendation:** Completed shop trades are logged (v1 logs none), following the convention `/sell` and `/buy` already use: **the source is whoever provides the items**.

| Entry | Source | Target | Reason |
|---|---|---|---|
| `SHOP_BUY` | The owner (`Server` for an admin shop) | The buyer | `Bought 5x minecraft:diamond at minecraft:overworld 10 64 10` |
| `SHOP_SELL` | The seller | The owner (`Server` for an admin shop) | `Sold 5x minecraft:diamond at minecraft:overworld 10 64 10` |

- The amount is the rounded total (D6).
- The reason uses the item ID, not the display name, and includes the shop's location so an admin can find the shop.
- Only **fully completed** trades are logged. If a payment problem leaves someone owed, it is a `TRANSFER_FAILED` entry as elsewhere. A trade that is aborted and refunded is not logged as a trade, since nothing net happened.
- `/ecolog` matches on text, so searching for a player finds their shop trades. It already colours types containing `SHOP` gold (v1 never writes one, so it is currently unused).

**Note:** logging stays a direct `TransactionLogger` call for now; moving logging into the core is a separate step.
**Your notes:** Agreed.

### D12 — Language keys
**Status:** Agreed
**Background (verified in the code):** v1 has 55 `shop.*` keys, already grouped by area (`shop.transaction.*`, `shop.protect.*`, `shop.remove.*`, `shop.interaction.*`, `shop.sign.*`, `shop.action.*`, `shop.command.*`). The language sync only ever **adds** missing keys to a server's `lang/*.json`; it never overwrites or removes anything, so admin edits and old keys stay. v1 and v2 never run at the same time, so sharing a key is safe when the message and its placeholders are identical.

**The naming rule:**
1. **Same `shop.` prefix for everything.** No `shopv2.*`. When v1 is retired, renaming every key would drop every admin's overrides, and it contradicts the plan to rename the `shopv2` package to `shop` (section 8).
2. **Reuse a v1 key when the message and its placeholders are identical.** Most transaction, protection, sign and info messages qualify, so an admin's custom wording carries over.
3. **When the meaning or the placeholders differ, add a new key with a descriptive name.** No version suffixes.
4. **v1-only keys are deleted from the jar's lang files when v1 is retired** (`shop.command.create_sign_failed`, which v2 never uses, plus `shop.command.remove_mode.enter` and `shop.command.my_shops.entry`, which are replaced below). Servers' own copies keep them, which is harmless.
5. **Every new key also gets a `zh_cn` translation.** These are drafted by me and need a native speaker's review. A missing translation falls back to English, not to a raw key.
6. `/shop resign` reuses `shop.remove.not_owner` for the wrong-owner case.

**New keys (23; `shop.command.create_failed` and `shop.command.save_failed` were added in M4, for a shop or a change that could not be saved).** English wording is a draft; the final text is settled when each is implemented.
| Key | Draft English text | From |
|---|---|---|
| `shop.command.create_no_sign_space` | `&cThere is no room for the shop sign on that side. Clear the space in front of that face.` | D5 |
| `shop.command.create_no_access` | `&cYou are not allowed to use that container.` | D4 |
| `shop.command.container_in_use` | `&cThat container already belongs to a shop.` | D1 |
| `shop.command.create_failed` | `&cThe shop could not be saved, so it was not created. Ask an admin to check the server log.` | D7 |
| `shop.command.save_failed` | `&cThe change could not be saved, so it was not made. Ask an admin to check the server log.` | D7 |
| `shop.command.price_decimals` | `&cPrices can have at most two decimal places.` | D10 |
| `shop.command.price_too_high` | `&cThe maximum price is %s per item.` | D10 |
| `shop.command.remove_mode.enter_sign_or_container` | `&6Entered REMOVE MODE. Right-click the shop's sign or its container to remove it.` | D5 |
| `shop.command.remove_mode.expired` | `&eRemove mode ended.` | D10 |
| `shop.command.resign.success` | `&aSign placed.` | D5 |
| `shop.command.resign.has_sign` | `&eThis shop already has its sign.` | D5 |
| `shop.command.info.status` | `&eStatus: &f%s` | D8, D10 |
| `shop.command.my_shops.entry_detail` | `&e%s at %s in %s (%s)` (item, position, dimension, status) | D10 |
| `shop.status.ok` | `OK` | D8 |
| `shop.status.no_sign` | `No sign` | D8 |
| `shop.status.disabled` | `Disabled` | D8 |
| `shop.status.unreadable` | `Unreadable` | D8 |
| `shop.interaction.unavailable` | `&cThis shop is currently unavailable.` | D9 |
| `shop.interaction.too_far` | `&cYou moved too far from the shop. Transaction cancelled.` | D9 |
| `shop.transaction.busy` | `&eThis shop is busy with another trade. Try again in a moment.` | D6 |
| `shop.notice.sign_missing` | `&eYour shop at %s lost its sign. Look at its container and use /shop resign.` | D8 |
| `shop.notice.removed_container_gone` | `&cYour shop at %s was removed because its container is gone.` | D8 |
| `shop.protect.no_merge` | `&eThat chest was placed on its own so it does not join someone else's shop.` | D4 |

"Container gone" is not a status name because such shops are deleted (D8). Console and log messages are plain English and are not translated.
**Your notes:** Agreed: the naming rule, and the working list above.

### D13 — Configuration
**Status:** Agreed
**Decision:** flat keys in `config.json`, matching `enableChestShops` and `shopVersion`. The allowed-container list is `shopAllowedContainers`. A nested `shops` section was considered and not chosen.
**Your notes:** Agreed: flat, `shopAllowedContainers`.

---

## 3. Data model (sketch)

A shop record (saved in `shops.json`, D7):
- `id` (UUID)
- `dimension` (for example `minecraft:overworld`) and `anchor` position
- `owner` (UUID) and cached `ownerName`
- `type`: player or admin
- `mode`: shop sells to players, or shop buys from players
- `price`: unit price, two decimals (D6, D10)
- `sign`: position of the sign, or none (D5)
- `needsSignLookup`: set on imported shops until the sign has been looked up (D7)

The item is not in the record. It is a single-item template stored in `shopitems/<id>.snbt` (D7).

Runtime only, never saved: the status (D8), the busy flag (D6), and stock.

Identity for lookups: `(dimension, position)`.

---

## 4. Proposed layout (names may change)

| Class | Job |
|---|---|
| `ShopV2Feature` | Feature hooks: registers commands and events when v2 is selected |
| `ShopRegistry` | The shops in memory; lookups by identity, sign and owner; "is this position part of a shop?" (D3) |
| `ContainerRegistry` | Allowed container types (D2), and resolving the real inventory including double chests (D3) |
| `ShopSigns` | Generates sign text; places, refreshes and re-places signs (D5) |
| `ShopCommands` | `/shop ...`, including create's checks (D1, D4, D5, D10) |
| `ShopClickHandler` | Sign and container clicks, the chat amount flow, pending trades and remove-mode (D9, D10) |
| `ShopProtection` | Open and break rules, and the creation access check (D4) |
| `ChestPlacementMixin` | The merge rule (D4) |
| `TradePlan` | The trade plan as pure logic: totals, steps, and the outcome of each failure (D6) |
| `TradeService` | Runs a trade: the busy rule, the payments, moving the goods (D6) |
| `ShopHealth` | The status model; checks on use, on chunk load and by the loaded-chunk sweep; reporting; automatic removal (D8) |
| `ShopStorage` | `shops.json` and the item files (D7) |
| `ShopImporter` | The one-time import from v1 (D7) |

Rule for pure logic: trade planning, identity, and the storage format take plain values, so they can be exercised without the game.

---

## 5. Milestones (each a small, reviewable commit series)

- [x] **M0** Scaffold: `shopv2` package, `shopVersion` selector, data folder.
- [x] **M1** Data model and storage (`shops.json` and the SNBT item files), including the import from v1 and the check that the data fixer upgrades an old-format item (no game hooks yet). Done; nothing calls it yet, that is M2 onwards.
- [x] **M2** Container registry and inventory resolving (chest, trapped chest, barrel, double chest), and the shared "is this position part of a shop" lookup (D3). Done and checked in the game; shops are loaded (and the v1 import runs) at server start. Item files are read with the shops from M3 onwards.
- [x] **M3** Sign rendering and placement. Done and checked in the game. Item files are read at startup and held in `ShopRegistry`; a shop whose item file cannot be used stays registered without an item. `ContainerStock` counts an item and the room for it (M6 reuses it). `ShopSigns` writes the sign text (same language keys as v1) and chooses and places the wall sign; nothing records the sign position yet, that is M4.
- [x] **M4** Commands: create (with the one-shop-per-container, sign-space, access and price checks), info, list, resign, admin. Done and checked in the game, except the access check against a real claim mod (still open in 7c). `/shop remove` is remove-mode, which is click handling, so it is built in M5. The status is worked out on use from the item, the container type and whether the recorded sign block is still a wall sign; the periodic checks stay in M8.
- [x] **M5** Click and chat handling: pending trades (range and dimension rule, re-validation, clearing on disconnect) and remove-mode (sign or container, expiry), including `/shop remove`. Done and checked in the game. The amount typed in chat ends at a temporary stub that only reports what M6 will be asked to do. "All" is resolved here with its limits (`Prices.affordable`, `PlayerItems.count`, `ContainerStock`).
- [x] **M6** Trade service (purchase and sale, all four trade types, the busy rule), with the trade plan as pure logic. Done. Pure logic (`TradeKind`, `TradePlan`, `TradeLocks`) and the container moves were checked outside the game (38, 12 and 29 cases, the last against the real item registry); `TradeService` was tested in the game by the user as far as one player can, with wider testing left for the polish phase. Completed trades are logged as `SHOP_BUY` and `SHOP_SELL` (D11). One deliberate deviation from D6: the quick refusal by cached balance is only made for the acting player, not for an owner who pays, because an uncached account reads as the default balance and would be refused wrongly; the real charge decides.
- [x] **M6b** Change-driven sign refresh (added after M5; D9). A mixin on `BlockEntity.setChanged()` notes every container change (chests and barrels do not override it, so one hook covers all; v1's mixin was chests only), and a once-a-second batch rewrites the sign of each shop whose container changed, either half of a double chest. Done and checked in the game.
- [x] **M7** Protection, including the merge rule (a mixin that keeps a chest placed next to someone else's shop chest single). Done and checked in the game. Opening a container is refused for everyone but the owner and admins in either hand (v1 checked the main hand only); nobody can break a container; breaking a sign removes the shop for its owner or an admin. The merge rule is `ChestPlacementMixin` on `ChestBlock.getStateForPlacement` with `ChestMergeRule`; the chest it makes single faces as a lone chest would.
- [x] **M8** Shop health checks (on use, on chunk load, and a sweep of loaded chunks), the status model and reporting, automatic removal when the container is gone, and the deferred sign lookup for imported shops. Built (`ShopChecker`, `MissingContainers`, `ReportedStatuses`, `ShopHealth.containerStateOf`, `ShopSigns.findAttached`). Checked in the game so far: a shop whose container was removed with `/setblock` is deleted after a few seconds. The rest is checked outside the game (the two-sighting guard and once-only reporting, 16 cases) or not yet tested, see the list in 7c.
- [ ] **M9** Use it, question the choices, refine and polish. Parity testing against v1, the README, and the switch-over are all held back until you consider v2 finished. There is no fixed date: gaps, edge cases and better ways to reach a goal are expected to turn up through play, and each is discussed before it changes an agreed decision.

---

## 6. v1 problems v2 must not repeat

| Problem found in v1 | Status in v1 | v2 answer |
|---|---|---|
| Shops keyed by position only (no dimension) | Open | D1 |
| Barrel and other-container shops deleted by cleanup | Open | D2, D8 |
| Double chests: only one half seen and protected | Open | D3, D4 |
| Sign lookup by searching around the chest | Fixed (picks the attached sign) | D5 |
| Sale can destroy or partly remove items | Open | D6 |
| A player who disconnects or dies mid-trade can lose the items | Open | D6 (abort and refund) |
| Trade totals are not rounded, so the two sides can end up with different amounts | Open | D6 (rounded to two decimals) |
| Purchase leftovers lost when the inventory fills | Fixed | D6 (drop, never lose) |
| Worn armor and offhand counted or removed when selling | Fixed | D6 (36-slot scan) |
| `$0` shop plus "all" threw an error; huge quotient wrapped | Fixed | D9 (free shops allowed, no crash) |
| `shops.json` written in place; corrupt file blocked startup | Fixed | D7 |
| Undecodable item loaded as empty and was erased on next save | Fixed (shop skipped, backup) | D7 (kept untouched) |
| Cleanup force-loads every shop chunk every 5 seconds | Open | D8 |
| Whole file rewritten on the main thread every second, to save a stock number nobody needs | Open | D7 (stock not stored, saved only on real changes) |
| Items stored as an opaque base64 blob that breaks across Minecraft updates | Open | D7 (SNBT item files, data fixer) |
| Remove-mode not cleared when a player leaves, and never expires | Open | D10 (cleared on disconnect, expires after 30 seconds) |
| A price can be so large that a trade total cannot be stored (or has more decimals than the balance keeps) | Open | D10 (two decimals, maximum 1,000,000,000) |
| A pending trade still works after the shop is removed | Open | D9 (re-validate) |
| A pending trade runs in whichever dimension the player is in when they type, with no distance check | Open | D9 (same dimension, within about 16 blocks) |
| Selling "all" fails entirely when the owner cannot afford the whole amount | Open | D9 (capped by the owner's funds) |
| Anyone can merge a chest into a shop chest | Open | D4 (merge rule) |
| Shop creation does not check the player may use the container (claim bypass, untested) | Open | D4 (creation access check) |
| Hoppers can drain shop chests | Open | Out of scope (D4) |
| Shop trades not in the transaction log | Open | D11 |
| A shop whose sign was destroyed cannot be removed, and its container can never be broken | Open | D5 (remove-mode on the container, `/shop resign`) |

---

## 7. Open questions

Answered during the design pass: a free shop (price 0) stays allowed (D10); there is no per-player shop limit (D10); v2.0 is parity plus the safety and recovery additions listed in D4, D5, D9 and D10.

Still open:
- ~~Should v2 become the default at M9, or only after real-world testing on your server?~~ Answered: v2 does not stay as a second option. When it replaces v1 it replaces it outright (section 8), but only once you are 100% certain, and v1 and its shops are not removed before then. Until then `shopVersion` stays as a safety net so real data can go back to v1.

---

## 7b. Polish after v2 works

Things deliberately left for later, so they do not slow v2.0 down:
- **Reorganise the flat `shopv2` root into a few subpackages** (about 24 files sit in it, next to `model`, `storage` and `mixin`). Purely cosmetic, and best done once as pure moves when the code has stopped changing, together with the rename to `shop` in section 8. The real cost is visibility: 15 of the 24 root classes are package-private, and moving related ones apart forces `public` on them and on the `ShopV2Feature` accessors they use (the two classes the mixins call, `ChestMergeRule` and `ContainerChanges`, already had to be public for that reason). A layout to consider then: `container/` (`ContainerRegistry`, `ContainerStock`, `ContainerMoves`, `ContainerChanges`, `PlayerItems`), `trade/` (`TradeService`, `TradeLocks`, `PendingTrades`, `ShopChatHandler`), `health/` (`ShopHealth`, `ShopChecker`, `MissingContainers`, `ReportedStatuses`) and `interaction/` (`ShopCommands`, `ShopClickHandler`, `ShopProtection`, `ChestMergeRule`, `RemoveMode`), with the feature, registry, changes, signs and `Positions` staying in the root.
- **Buying more than the shop has is refused, but selling more than you have sells what you have** (D9). Make them consistent, one way or the other.
- **Admin shops refuse "all" on purchases** (D9). Consider "as much as fits and can be afforded".
- **Several shops on one container**, for example a buy shop and a sell shop for different items (D1).
- **Copper golems** and a clean way to handle them, then reconsider copper chests as a default container (D2).
- **An admin command to pull in shops missing from v2 out of v1's file** (D7).
- **An admin command that lists flagged shops** (D8).
- **Notices for owners who are offline** (D8, D12): store a short notice per owner and show it at their next login, so a shop lost to an explosion overnight is not a silent surprise. D8 currently tells the owner only if they are online.
- **Hopper and explosion protection**, if you ever want it (D4).
- **Items with durability match strictly** (D6). A shop created while holding a used tool only matches tools with exactly that damage. Consider ignoring durability when matching.
- **The README entry, once v2 is considered finished.** Only `shopAllowedContainers` and the behaviour changes need documenting. `shopVersion` is temporary and goes when v1 is retired (section 8), so it is not documented as a setting. The v1 import (`ShopImporter`) reads v1's shops.json as plain JSON and does not use v1's classes, so deleting v1 does not break migration for existing servers.

---

## 7c. Still to verify

Claims in this document that were reasoned from the code or the game's bytecode but never tested, so they are not forgotten:
- ~~The data fixer upgrades an old-format item correctly~~ (D7): verified in M1, see D7.
- **The creation access check works with the common claim mods**, and what other mods' callbacks show the player (D4).
- ~~Block tags are loaded when the registry is built at server start (D2)~~: verified in M2, `#minecraft:copper_chests` resolved and produced no warning.
- **In v1, opening the neighbouring half of a shop's double chest reaches the shop's contents**, and in v2 it no longer does (D3, D4).
- **The exact pickup and drop-off rules of the Copper Golem** (D2).
- Fabric's chunk-load event exists as assumed (it compiles against `ServerChunkEvents.CHUNK_LOAD`) and the sweep's loaded-chunk check works: a shop whose container was removed with `/setblock` was deleted after a few seconds (D8). **Not yet seen in the game:** the chunk-load batch itself.
- **M8 cases not yet tested in the game (left for the polish phase):** an imported shop finding its old v1 sign (one sign, several signs, none, and a sign in an unloaded chunk); a lost sign reported once and `/shop resign` bringing the status back to OK; the two-sighting guard (put the container back within a few seconds and the shop survives); a container type removed from `shopAllowedContainers` giving Disabled without deleting; the deleted shop's logged record and SNBT being complete enough to restore by hand; the owner being told when online. The two-sighting guard and the report-once logic were checked outside the game (16 cases). **Partly covered by the user's real-data test:** they migrated a world holding v1 shop data from the latest release into the current build (shopVersion v2), the shops worked afterwards and the migrated data was all there. That covers the import against real release data and, since the shops work, most likely the sign lookup in the common case; which shops and which cases (several signs, none) were tried is not known.
- ~~Whether the merge-rule mixin on the base chest applies to chests that override placement, such as copper chests (D4)~~: `CopperChestBlock.getStateForPlacement` calls the base `ChestBlock` method first (checked in the bytecode), so the injection covers it; trapped chests use the base method as is. Not tested with a copper chest in the game.

---

## 8. Retiring v1 (reminder)

When v1 is removed: delete the `shop` package and its mixin, drop its entry from the feature list, remove `ShopVersion` and the `shopVersion` setting, rename `shopv2` to `shop`, and remove the v1-only lang keys listed in D12 (the shared keys stay).

**Also at that point (decided with the user): the import renames v1's `shops.json` to `shops.json.old`** once it has written v2's file, so a migration is visibly one and done. Until v1 is gone the file is left alone, because switching `shopVersion` back to `"v1"` needs it (v1 starts with no shops when the file is missing, and its next save would write a fresh one). The rename must only happen after a successful import, must never overwrite an existing `shops.json.old`, and a failed rename is a warning, not an error. The importer's "it stays in v1's file" wording and the D7 import text change with it: a shop the import could not convert is then only in `shops.json.old`.

---

## 9. Change log

- Initial draft with recommended defaults (all decisions `Proposed`).
- Design pass: D1 to D13 discussed and agreed one by one (see each block's "Your notes").
- Consistency pass: brought section 3 (data model), section 4 (layout), the milestones, section 7 and section 8 in line with the decisions; fixed stale wording in D4, D5, D6 and D7; added a known limitation to D8 and section 7c (still to verify). Two gaps closed: imported v1 prices are rounded to two decimals (D7), and protection applies in every status except deleted (D4, D8).
- Decisions after the real-data migration test (user): v1's shops.json is left alone while shopVersion exists and is renamed to shops.json.old at retirement (section 8); reorganising the flat shopv2 root is a polish item for when the code has settled (7b). The user is taking their time to be sure before releasing and is doing the deeper multi-user testing when they have the energy.
- Real-data migration (user): a world with v1 shop data from the latest release was migrated into the current build with shopVersion v2; the shops worked and the migrated data all existed. This is the first check of the import against a released version's data rather than the dev test folder.
- Direction check after M8: the user does not want two shop versions to stay selectable. v2 replaces v1 outright when it is considered finished, and not before they are certain; the README and parity testing wait until then. M9 was rewritten as an open-ended use-and-polish phase, the "become the default" question was answered, and the README item in 7b now says shopVersion is temporary.
- M8 done (built): `ShopChecker` (checks on use, on chunk load in one batch, and a 5-second sweep of loaded shops; deferred sign lookup for imported shops; two-sighting deletion of a shop whose container is gone with the record and item SNBT logged; once-only status reports), `MissingContainers`, `ReportedStatuses`, `ShopHealth.containerStateOf`, `ShopSigns.findAttached`. Only the `/setblock` deletion was checked in the game so far; the rest is listed in 7c.
- M7 done: `ShopProtection` (open, break container, break sign; both hands), `ChestMergeRule` and `ChestPlacementMixin`. Checked in the game. The copper chest question in 7c is answered from the bytecode.
- M6b done: `ContainerChanges` + `ContainerChangeMixin` (second mixin config `savs-common-economy.shopv2.mixins.json`) and `SignRefresh` on the once-a-second tick. Checked in the game.
- M6 done: `TradeKind` and `TradePlan` (pure decisions, 38 checks), `TradeLocks` (one trade per shop, token-guarded watchdog, 12 checks), `ContainerMoves` and `PlayerItems.remove/give` (all-or-nothing moves by the container's own rules, 29 checks against the real registry), `TradeService` (payer pays, goods move, payee is paid; refund and owed handling; lock released on every outcome), and D11 logging. Tested in the game as far as one player can; more in the polish phase.
- M5 done: `RemoveMode` and `/shop remove` (30 s expiry with a message, cleared on disconnect), removal by clicking the sign or the container, `PendingTrades`, sign click starting a trade, `ShopChatHandler` (amount, "all", revalidation, range). The trade is a stub until M6. Checked in the game. Not covered by any milestone yet: change-driven sign refresh (D9, batched about once a second) when a chest changes by hand or by hopper.
- M4 done: `ShopChanges` (create, update and remove in memory and on disk, D7 order, undone on a failed save), `/shop create`, `info`, `list`, `resign` and `admin`, `ShopHealth`/`ShopStatus` for the on-use status. Two language keys were added (`create_failed`, `save_failed`). Checked in the game. `/shop remove` moved to M5.
- M3 done: item cache, `ContainerStock`, `ShopSigns` (text, side choice, placement). Checked in the game with a temporary stick-click helper: the generated text on the imported shops' signs, the aimed-face and top-face side rule, and the refusal when the spot is taken.
- M2 done: `shopAllowedContainers`, `ContainerRegistry`, `ShopRegistry` and `ShopStorage`. Checked in the game with a temporary debug log: single and double chests, barrels and copper chests (by tag) resolve to the right inventory, a double chest reports its partner half, and a shop is found from either half of its double chest.
- M1 done: the data model, `shops.json` storage, the SNBT item store and the v1 import are built and checked, the import against a real v1 file. The data fixer upgrade is verified (D7, section 7c).
