# Savs Common Economy

A lightweight, **server-side only** economy mod for Minecraft 26.3 (Fabric), designed for SMP servers and multi-server networks. It provides a robust, modern economy system with support for JSON, SQLite, MySQL, and PostgreSQL storage, offline player support, leaderboards, physical bank notes, player chest shops, and cross-server network synchronization. No client installation required!

## Core Features

*   **Server-Side Only**: No client modifications needed. Works perfectly for vanilla clients joining your Fabric server.
*   **Database Support**: Start simple with JSON (default), or scale up to SQLite, MySQL, or PostgreSQL for high-traffic environments.
*   **Redis Network Sync**: Built-in support for Redis Pub/Sub. Synchronize balances and transaction notifications across proxy setups and multi-server networks. 
*   **Common Economy API**: Full support for the [Common Economy API v2](https://github.com/Patbox/common-economy-api), integrating with Universal Shops, Mob Money, and other compliant economy mods.
*   **Offline Transactions**: Perform seamless administrative actions and payments to players even when they are offline!
*   **Chest Shops**: Player shops on chests, trapped chests and barrels, traded through a sign that shows the price and live stock. See [Chest Shops Setup](#chest-shops-setup).
*   **Buy & Sell System**: Quickly configure official server prices and empower your players to directly trade with the server using `/buy` and `/sell` commands! (Customizable via `worth.json`).
*   **Bank Notes**: Players can withdraw their digital balance into physical vanilla paper items to trade or stash away.
*   **Transaction Logging**: Searchable in-game transaction ledger for server admins (`/ecolog`). A `TRANSFER_FAILED` entry means a payment could not be completed; its reason says whether the player was refunded or is still owed money (the server console also logs an error when someone is owed).
*   **Vanilla Permissions Setup**: Easily configure command access levels using the `permissions.json` config using vanilla OP clearance levels.

## Commands

### Player Commands
*   `/bal` or `/balance`: Check your own balance.
*   `/bal <player>`: Check another player's balance.
*   `/pay <player> <amount>`: Pay an amount to another player.
*   `/baltop` or `/balancetop`: View the richest players on the server.
*   `/withdraw <amount>`: Withdraw money as a physical bank note.
*   `/worth`: Check the value of the item in your hand.
*   `/worth all`: Check the value of all items in your main inventory and hotbar matching the one in your hand.
*   `/worth list`: View an organized, colored table of all server buy and sell prices.
*   `/worth <item>`: Check the value of a specific item.
*   `/buy <item> [amount]`: Purchase items directly from the server. It fails without charging you if your inventory cannot hold the items.
*   `/sell`: Sell the item stack currently in your hand.
*   `/sell all`: Sell all identical items in your main inventory and hotbar. Worn armor and your offhand are never sold.
*   Items that hold contents or custom data are never sold or counted by `/sell` and `/worth`: bank notes, and shulker boxes or bundles with items inside.

### Shop Commands
*   `/shop create sell <price>`: Create a selling shop (sells items TO players) on the container you are looking at.
*   `/shop create buy <price>`: Create a buying shop (buys items FROM players) on the container you are looking at.
*   `/shop info`: Show the owner, item, price, stock and status of the shop you are looking at.
*   `/shop list`: List all your shops with their location and status.
*   `/shop remove`: Enter remove-mode, then right-click a shop's sign or container to remove it. Run it again to leave; it also ends after 30 seconds.
*   `/shop resign`: Look at your shop's container to place a new sign after its sign was destroyed.

### Admin Commands (Level 2+ / Configurable)
*   `/givemoney <player> <amount>`: Add money to a player's account.
*   `/takemoney <player> <amount>`: Remove money from a player's account.
*   `/setmoney <player> <amount>`: Set a player's balance to a specific amount.
*   `/resetmoney <player>`: Reset a player's balance to the default starting value.
*   `/shop admin`: Turn the shop you are looking at into an admin shop with unlimited stock and funds. There is no command to turn it back.
*   `/ecolog <target> <time> <unit> [page]`: Search transaction logs (e.g., `/ecolog * 1 h`).

---

## Configuration Guide

The main configuration file is automatically created at `config/savs-common-economy/config.json`.
From here, you can change the default starting balances, currency symbols, and database details.

```json
{
  "defaultBalance": 1000.0,
  "currencySymbol": "$",
  "symbolBeforeAmount": true,
  "enableSellCommands": false,
  "enableChestShops": true,
  "enableBankNotes": true,
  "shopAllowedContainers": [
    "minecraft:chest",
    "minecraft:trapped_chest",
    "minecraft:barrel"
  ],
  "storage": {
    "type": "JSON",
    "host": "localhost",
    "port": 3306,
    "database": "savs_economy",
    "user": "root",
    "password": "password",
    "tablePrefix": "savs_eco_",
    "poolSize": 10,
    "connectionTimeout": 30000,
    "idleTimeout": 600000
  },
  "redis": {
    "enabled": false,
    "host": "localhost",
    "port": 6379,
    "password": "",
    "channel": "savs-economy-updates",
    "debugLogging": false,
    "timeout_ms": 5000,
    "client_name": "Savs-Economy-Node"
  },
  "apiNotificationMode": "ACTION_BAR",
  "commandNotificationMode": "CHAT"
}
```

### Config Settings Explained

#### General
*   `defaultBalance`: How much money new players start with when they first join.
*   `currencySymbol`: The symbol shown next to money amounts (e.g. "$", "€", "Coins").
*   `symbolBeforeAmount`: If `true`, output shows `$100`. If `false`, output shows `100$`.
*   `enableSellCommands`: Turns the `/worth`, `/buy`, and `/sell` server market on or off. 
*   `enableChestShops`: Turns the player chest shop sign system on or off.
*   `enableBankNotes`: Turns `/withdraw` and bank note redemption on or off. While this is off, existing bank notes cannot be redeemed.
*   `shopAllowedContainers`: Which blocks can hold a chest shop. Each entry is a block id, or a block tag starting with `#`. The default is chests, trapped chests and barrels. Copper chests can be added with the tag `#minecraft:copper_chests`, which includes every oxidation stage; they are not in the default list because copper golems may move items in and out of them. Removing a type disables its existing shops (they are kept, and work again once you add it back). Entries that don't match a block or tag are skipped with a warning in the server log.

#### Storage Options
Controls how player balances are saved.
*   `storage.type`: Choose how to save player balances. `JSON` is the default and is perfect for standard servers. Change this to `SQLITE`, `MYSQL` (or `MARIADB`), or `POSTGRESQL` if you run a large network. Any other value falls back to `JSON`.
*   `storage.host` / `port` / `database` / `user` / `password`: Fill these in only if you are using `MYSQL`, `MARIADB` or `POSTGRESQL`. `SQLITE` ignores them and saves to `config/savs-common-economy/economy.db`.
*   `storage.tablePrefix`: The prefix used for the database tables (default `savs_eco_`).

#### Redis Network Sync (Optional)
If you run a multi-server network, turn this on to instantly sync balances between your servers!
*   `redis.enabled`: Turn this to `true` to enable sharing balances across servers.
*   `redis.host` / `port` / `password`: Your Redis server login details (Port is usually `6379`).
*   `redis.channel`: Make sure every server in your network has the EXACT same text here so they can talk to each other.

#### Chat Notifications
Controls how transaction messages appear to players! 
Set these to `ACTION_BAR` (shows briefly above the hotbar), `CHAT` (standard chat message), or `NONE`.
*   `apiNotificationMode`: Messages triggered automatically by other plugins.
*   `commandNotificationMode`: Messages from player commands like `/pay`.

---

## Permissions & OP Levels
The mod supports LuckPerms and the Fabric Permissions API.

If you aren't using a permissions manager, we gracefully fall back to standard Vanilla OP Levels. Open the `config/savs-common-economy/permissions.json` file to easily change what OP level is required for each command. Most standard player commands default to OP level 0 (everyone), while admin commands default to OP level 2.

Here is a list of all current permission nodes you can change in the config:

### Player Command Nodes (Default: Level 0)
*   `savscommoneconomy.command.bal`: Access to `/bal` for checking your own balance.
*   `savscommoneconomy.command.bal.others`: Access to `/bal <player>` for checking someone else's balance.
*   `savscommoneconomy.command.pay`: Access to `/pay`.
*   `savscommoneconomy.command.withdraw`: Access to `/withdraw`.
*   `savscommoneconomy.command.baltop`: Access to `/baltop`.
*   `savscommoneconomy.command.worth`: Access to `/worth` and `/worth all`.
*   `savscommoneconomy.command.sell`: Access to `/sell` and `/sell all`.
*   `savscommoneconomy.command.buy`: Access to `/buy`.

### Shop Command Nodes (Default: Level 0)
*   `savscommoneconomy.shop.create`: Access to create both buy and sell chest shops, and to `/shop resign`.
*   `savscommoneconomy.shop.info`: Access to `/shop info`.
*   `savscommoneconomy.shop.list`: Access to `/shop list`.
*   `savscommoneconomy.shop.remove`: Access to `/shop remove`. Owners can also remove a shop by breaking its sign, without this node.

### Admin Command Nodes (Default: Level 2)
All powerful administrative features are grouped securely under one single node constraint!
*   `savscommoneconomy.admin`: Controls access to **all** admin features including `/givemoney`, `/takemoney`, `/setmoney`, `/resetmoney`, `/shop admin`, and `/ecolog`. It also lets admins open, remove and re-sign any player's shop.

## Chest Shops Setup
Chest shops let players buy from and sell to each other through a container and a sign.

**How to create a Shop:**
1. Place a chest, trapped chest or barrel (see `shopAllowedContainers`). A double chest counts as one shop. For a `sell` shop, fill it with the item you want to sell.
2. Hold the item you want to trade in your main hand. Shops match items exactly, including their name, enchantments and damage.
3. Look at the container from within 5 blocks and run `/shop create sell <price>` to sell to players, or `/shop create buy <price>` to buy from them. The price is per item.
4. A sign is placed on the side of the container you are looking at (or, if you look at its top or bottom, on the side facing you). That spot must be free, or the shop is not created.

Prices can have up to two decimal places and go up to 1,000,000,000 per item; a price of 0 makes a free shop. A container can only belong to one shop. If a claim or protection mod stops you from using the container, you can't make it a shop (this works with mods that use Fabric's standard block-use event).

**How to trade with a Shop:**
1. **Right-click** the shop's sign.
2. Within 30 seconds, type in chat how many you want to buy or sell, or `all`. Your message is not shown in chat. Stay within 16 blocks of the sign.
3. Whoever pays is charged first; if the items then can't be moved, they are refunded. If your inventory fills up before your items arrive, the extra items drop at your feet.

Buying `all` takes as many as you can afford and carry, up to what the shop has. Selling `all` sells everything you carry, up to what the shop has room for and its owner can pay. Admin shops don't accept `all` when buying. Only one player can trade with a shop at a time; others are asked to try again in a moment. Items only come from and go to your main inventory and hotbar, never your armor or offhand.

The sign shows the owner, the item, the price, and the stock (or, for a buying shop, the space left), and updates as the container changes. Completed shop trades are logged in `/ecolog` as `SHOP_BUY` and `SHOP_SELL`.

**Managing your Shops:**
*   Only you and admins can open your shop's container, and nobody can break it, not even you.
*   To remove a shop, break its sign, or run `/shop remove` and right-click its sign or container.
*   If the sign is destroyed, the shop stops trading until you look at its container and run `/shop resign`.
*   If the container is destroyed (for example by an explosion), the shop is removed a few seconds later. You are told if you are online, and the server log keeps a full record so an admin can restore it by hand.
*   A chest placed next to another player's shop chest stays a single chest. Placing one next to your own shop chest makes it a double chest.
*   `/shop info` and `/shop list` show each shop's status: `OK`, `No sign`, `Disabled` (its container type was removed from `shopAllowedContainers`), or `Unreadable` (its item file could not be read).
*   Hoppers and explosions are not blocked. Use a claim or protection mod if you need that.

**Admin Shops:** an admin can run `/shop admin` while looking at a shop to give it unlimited stock and funds. Buyers pay the server, sellers are paid by the server, and the container's contents are not used.

**Where shops are saved:** each shop is its own file in `config/savs-common-economy/data/shops/`, with its item in `data/shopitems/`. Changes are saved straight away, and the previous copy of each shop file is kept as a `.bak`.

**Upgrading from an older version:** the first time the server starts with this version, the shops in `config/savs-common-economy/shops.json` are converted, and that file is renamed to `shops.json.old`. Each converted shop finds its sign the first time its area is loaded. Prices with more than two decimals are rounded, and any shop that can't be converted is listed in the server log (it stays in `shops.json.old`). To go back to an older version, rename `shops.json.old` back to `shops.json`; shops created or changed since the upgrade won't carry over.
