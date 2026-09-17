# Savs Common Economy

A lightweight, **server-side only** economy mod for Minecraft 26.3 (Fabric), designed for SMP servers and multi-server networks. It provides a robust, modern economy system with support for JSON, SQLite, MySQL, and PostgreSQL storage, offline player support, leaderboards, physical bank notes, player chest shops, and cross-server network synchronization. No client installation required!

## Core Features

*   **Server-Side Only**: No client modifications needed. Works perfectly for vanilla clients joining your Fabric server.
*   **Database Support**: Start simple with JSON (default), or scale up to SQLite, MySQL, or PostgreSQL for high-traffic environments.
*   **Redis Network Sync**: Built-in support for Redis Pub/Sub. Synchronize balances and transaction notifications across proxy setups and multi-server networks. 
*   **Common Economy API**: Full support for the [Common Economy API v2](https://github.com/Patbox/common-economy-api), integrating with Universal Shops, Mob Money, and other compliant economy mods.
*   **Offline Transactions**: Perform seamless administrative actions and payments to players even when they are offline!
*   **Chest Shops**: Player shops! Create buy and sell chest shops using vanilla signs.
*   **Buy & Sell System**: Quickly configure official server prices and empower your players to directly trade with the server using `/buy` and `/sell` commands! (Customizable via `worth.json`).
*   **Bank Notes**: Players can withdraw their digital balance into physical vanilla paper items to trade or stash away.
*   **Transaction Logging**: Searchable in-game transaction ledger for server admins (`/ecolog`).
*   **Vanilla Permissions Setup**: Easily configure command access levels using the `permissions.json` config using vanilla OP clearance levels.

## Commands

### Player Commands
*   `/bal` or `/balance`: Check your own balance.
*   `/bal <player>`: Check another player's balance.
*   `/pay <player> <amount>`: Pay an amount to another player.
*   `/baltop` or `/balancetop`: View the richest players on the server.
*   `/withdraw <amount>`: Withdraw money as a physical bank note.
*   `/worth`: Check the value of the item in your hand.
*   `/worth all`: Check the value of all items in your inventory matching the one in your hand.
*   `/worth list`: View an organized, colored table of all server buy and sell prices.
*   `/worth <item>`: Check the value of a specific item.
*   `/buy <item> [amount]`: Purchase items directly from the server.
*   `/sell`: Sell the item stack currently in your hand.
*   `/sell all`: Sell all identical items in your inventory.

### Shop Commands
*   `/shop create sell <price>`: Create a selling shop (sells items TO players).
*   `/shop create buy <price>`: Create a buying shop (buys items FROM players).
*   `/shop remove`: Enter remove-mode to delete a shop by clicking on its sign.
*   `/shop info`: Display information about the shop you are looking at.
*   `/shop list`: List all active shops you own.

### Admin Commands (Level 2+ / Configurable)
*   `/givemoney <player> <amount>`: Add money to a player's account.
*   `/takemoney <player> <amount>`: Remove money from a player's account.
*   `/setmoney <player> <amount>`: Set a player's balance to a specific amount.
*   `/resetmoney <player>`: Reset a player's balance to the default starting value.
*   `/shop admin`: Toggle Admin Shop creation (infinite stock/funds).
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

#### Storage Options
Controls how player balances are saved.
*   `storage.type`: Choose how to save player balances. `JSON` is the default and is perfect for standard servers. Change this to `SQLITE`, `MYSQL`, or `POSTGRESQL` if you run a large network.
*   `storage.host` / `port` / `database` / `user` / `password`: Fill these in only if you are using `MYSQL` or `POSTGRESQL`.
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
*   `savscommoneconomy.shop.create`: Access to create both buy and sell chest shops.
*   `savscommoneconomy.shop.info`: Access to check shop details.
*   `savscommoneconomy.shop.list`: Access to see all your active shops.
*   `savscommoneconomy.shop.remove`: Access to safely break your own shops.

### Admin Command Nodes (Default: Level 2)
All powerful administrative features are grouped securely under one single node constraint!
*   `savscommoneconomy.admin`: Controls access to **all** admin features including `/givemoney`, `/takemoney`, `/setmoney`, `/resetmoney`, `/shop admin`, and `/ecolog`.

## Chest Shops Setup
Chest shops allow players or admins to buy and sell items directly from chests.

**How to create a Shop:**
1. Place any chest container and insert items (if making a `sell` shop).
2. Look precisely at the chest while holding the precise matching item you wish to market.
3. Run `/shop create sell 100` to create a shop that sells items to players for $100 per interaction unit.
4. Or, run `/shop create buy 50` to create a shop that purchases items from players for $50 per interaction unit.

**How to trade with a Shop:**
1. **Right-click** any physical shop sign to begin a transaction.
2. Type in the chat the amount you want to buy or sell (e.g. type `10` or type `all`) and press enter.
3. Receive your items or money.
