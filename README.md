# Savs Common Economy

A lightweight, **server-side only** economy mod for Minecraft 1.21.10 (Fabric), designed for SMP servers and multi-server networks. It provides a robust and modern economy system with support for JSON, SQLite, MySQL, and PostgreSQL storage, offline player support, leaderboards, physical bank notes, and player chest shops. No client installation required!

## Features

*   **Economy System**: Tracks player balances with flexible storage options (JSON, SQLite, MySQL, PostgreSQL).
*   **Server-Side Only**: No client installation required - fully compatible with vanilla clients.
*   **Offline Support**: Supports payments and administrative actions for offline players who have joined the server at least once.
*   **Common Economy API**: Full support for the [Common Economy API](https://github.com/Patbox/common-economy-api), allowing seamless integration with other mods like [Universal Shops](https://modrinth.com/mod/universal-shops), and other mods using the API.
*   **Configuration**: Customizable default starting balance and currency formatting (symbol, position).
*   **Autocompletion**: Smart tab completion for both online and offline player names.
*   **Leaderboard**: View the top 10 richest players with `/baltop`.
*   **Bank Notes**: Withdraw physical currency as vanilla paper items that can be traded or redeemed.
*   **Sell System**: Configurable system to allow players to check item values and sell them (optional, disabled by default).
*   **Chest Shops**: Player-owned shops using chests and signs with dynamic stock detection (optional, enabled by default).
*   **Transaction Logging**: Comprehensive logging of all economy transactions with a searchable in-game command.
*   **Database Support**: Choose between JSON (default), SQLite, MySQL, or PostgreSQL for data storage.
*   **Multi-Server Ready**: Optimistic locking prevents race conditions, connection pooling for high-traffic networks.
*   **Performance Caching**: Caffeine-based caching for instant balance lookups and reduced database load.
*   **Redis Pub/Sub** (Optional): Real-time cross-server cache synchronization and transaction notifications.


## Confirmed compatability:
[Universal Shops](https://modrinth.com/mod/universal-shops)

## Commands

### Player Commands
*   `/bal` or `/balance`: Check your own balance.
*   `/bal <player>`: Check another player's balance (Online or Offline).
*   `/pay <player> <amount>`: Pay a specific amount to another player.
*   `/baltop` or `/balancetop`: View the top 10 richest players on the server.
*   `/withdraw <amount>`: Withdraw money as a physical bank note (vanilla paper item).
*   `/worth`: Check the value of the item in your hand.
*   `/worth all`: Check the value of all items in your inventory matching the one in your hand.
*   `/worth list`: List all sellable items and their prices.
*   `/worth <item>`: Check the value of a specific item (e.g., `minecraft:apple`).
*   `/sell`: Sell the item stack currently in your hand.
*   `/sell all`: Sell all items in your inventory matching the one in your hand.

### Shop Commands
*   `/shop create sell <price>`: Create a selling shop for the item in your hand (requires looking at a chest).
*   `/shop create buy <price>`: Create a buying shop for the item in your hand.
*   `/shop remove`: Enter "remove mode" - click your shop sign to remove it.
*   `/shop list`: List all shops you own.

### Admin Commands (Level 2+)
*   `/givemoney <player> <amount>`: Add money to a player's account.
*   `/takemoney <player> <amount>`: Remove money from a player's account.
*   `/setmoney <player> <amount>`: Set a player's balance to a specific amount.
*   `/resetmoney <player>`: Reset a player's balance to the default starting value.
*   `/shop create sell <price>` (then `/shop admin`): Create an Admin Shop (infinite stock).
*   `/ecolog <target> <time> <unit> [page]`: Search transaction logs (e.g., `/ecolog * 1 h`).
    *   `target`: Player name or `*` for all.
    *   `time`: Number of time units (e.g., `1`, `30`).
    *   `unit`: Time unit (`s`=seconds, `m`=minutes, `h`=hours, `d`=days).
    *   `page`: Optional page number for pagination.
*   `/ecodebug verify`: Test database connection and transaction safety (creates a temporary test account).
*   `/ecodebug cleanup`: Remove the test account created by `/ecodebug verify`.

## Configuration

The configuration file is located at `config/savs-common-economy/config.json`.

```json
{
  "defaultBalance": 1000,
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
    "tablePrefix": "savs_eco_"
  }
}
```

*   `defaultBalance`: The amount of money new players start with (default: 1000).
*   `currencySymbol`: The symbol used for currency (e.g., "$", "€", "Coins").
*   `symbolBeforeAmount`: If true, shows "$100"; if false, shows "100$".
*   `enableSellCommands`: Set to `true` to enable `/worth` and `/sell` commands.
*   `enableChestShops`: Set to `true` to enable the chest shop system.
*   `storage.type`: Storage backend to use (`JSON`, `SQLITE`, `MYSQL`, `POSTGRESQL`).
*   `storage.host`: Database host (for MySQL/PostgreSQL).
*   `storage.port`: Database port (for MySQL/PostgreSQL).
*   `storage.database`: Database name (for MySQL/PostgreSQL).
*   `storage.user`: Database username (for MySQL/PostgreSQL).
*   `storage.password`: Database password (for MySQL/PostgreSQL).
*   `storage.tablePrefix`: Prefix for database tables (for SQL backends).
*   `storage.poolSize`: Connection pool size (default: 10, for SQL backends).
*   `storage.connectionTimeout`: Connection timeout in milliseconds (default: 30000).
*   `storage.idleTimeout`: Idle connection timeout in milliseconds (default: 600000).

### Redis Configuration (Optional)

For multi-server networks, you can enable Redis Pub/Sub for real-time cache synchronization:

```json
"redis": {
  "enabled": false,
  "host": "localhost",
  "port": 6379,
  "password": "",
  "channel": "savs-economy-updates",
  "debugLogging": false
}
```

*   `redis.enabled`: Set to `true` to enable Redis Pub/Sub (default: false).
*   `redis.host`: Redis server hostname.
*   `redis.port`: Redis server port (default: 6379).
*   `redis.password`: Redis password (leave empty if no auth).
*   `redis.channel`: Pub/Sub channel name (default: "savs-economy-updates").
*   `redis.debugLogging`: Enable verbose Redis logging for debugging (default: false).

## Database Support

The mod supports multiple storage backends for economy data:

### JSON (Default)
- **File**: `config/savs-common-economy/balances.json`
- **Use Case**: Single servers, easy setup
- **No additional setup required**

### SQLite
- **File**: `config/savs-common-economy/economy_data.sqlite`
- **Use Case**: Single servers with better performance than JSON
- **Setup**: Just change `"type": "SQLITE"` in config

### MySQL / MariaDB
- **Use Case**: Multi-server networks, shared economy across servers
- **Setup**:
  1. Install MySQL/MariaDB on your server
  2. Create database: `CREATE DATABASE savs_economy;`
  3. Create user (optional): `CREATE USER 'minecraft'@'%' IDENTIFIED BY 'password';`
  4. Grant permissions: `GRANT ALL PRIVILEGES ON savs_economy.* TO 'minecraft'@'%';`
  5. Update config with connection details

### PostgreSQL
- **Use Case**: Advanced multi-server setups
- **Setup**: Similar to MySQL, but use PostgreSQL commands

**Example MySQL/MariaDB Config:**
```json
"storage": {
  "type": "MYSQL",
  "host": "your-database-server.com",
  "port": 3306,
  "database": "savs_economy",
  "user": "minecraft",
  "password": "your_secure_password",
  "tablePrefix": "savs_eco_"
}
```

**Note**: For multi-server networks:
- All servers should point to the same database with identical configuration
- Enable Redis Pub/Sub for instant cache synchronization across servers
- Transaction safety is ensured via optimistic locking (version-based concurrency control)
- Connection pooling is automatically configured for high-traffic environments

### Multi-Server Setup (Velocity/BungeeCord)

For networks with multiple Minecraft servers sharing the same economy:

1. **Database**: Use MySQL or PostgreSQL (not JSON/SQLite)
2. **Redis** (Recommended): Install Redis and enable it in config for real-time sync
3. **Configuration**: Ensure all servers have identical database and Redis settings

**With Redis enabled:**
- Players receive transaction notifications instantly across servers
- Balance changes are synchronized in real-time
- Cache invalidation happens automatically

**Without Redis:**
- Balance changes are still safe (optimistic locking prevents conflicts)
- Players see updated balances when they check `/bal`
- Slightly higher database load (no caching between servers)

### Worth Configuration

If `enableSellCommands` is true, a `worth.json` file will be created in the same directory. Use this to define item prices:

```json
{
  "itemPrices": {
    "minecraft:apple": 10.0,
    "minecraft:diamond": 100.0
  }
}
```

## Chest Shops

Chest shops allow players to buy and sell items using chests and signs.

**How to create a Shop:**
1. Place a chest and put items in it (if selling).
2. Hold the item you want to trade in your main hand.
3. Look at the chest.
4. Run `/shop create sell <price>` to create a shop that **sells** to players.
5. Run `/shop create buy <price>` to create a shop that **buys** from players.

**Interaction:**
- **Right-click** the sign to interact.
- Type the amount you want to buy/sell in chat (or type `all`).
- Signs automatically update to show current stock or available space!

## Bank Notes

Bank notes are physical representations of currency that can be traded between players or used with chest shop mods. They appear as vanilla paper items with a custom name showing their value.

**How to use:**
1. Use `/withdraw <amount>` to convert your balance into a bank note
2. The bank note appears as a paper item in your inventory
3. Trade it with other players or use it in chest shops
4. Right-click the bank note to redeem it back into your balance

**Features:**
*   Fully vanilla-compatible (appears as paper to clients)
*   Shows value in the item name (e.g., "Bank Note: $100.0")
*   Can be stacked if same value
*   Server-side validation prevents duplication

## Permissions
This mod supports the [Fabric Permissions API](https://github.com/lucko/fabric-permissions-api). To manage these permissions, you will need a permissions management mod such as **[LuckPerms](https://luckperms.net/)** (recommended) or any other mod that implements the Fabric Permissions API.

If no permissions mod is installed, the mod falls back to vanilla OP levels (Level 2 for admin commands).

### Player Permissions (Default: true)
*   `savscommoneconomy.command.bal`: Access to `/bal` (self).
*   `savscommoneconomy.command.bal.others`: Access to `/bal <player>`.
*   `savscommoneconomy.command.pay`: Access to `/pay`.
*   `savscommoneconomy.command.withdraw`: Access to `/withdraw`.
*   `savscommoneconomy.command.baltop`: Access to `/baltop`.
*   `savscommoneconomy.command.worth`: Access to `/worth`.
*   `savscommoneconomy.command.sell`: Access to `/sell`.
*   `savscommoneconomy.shop.create`: Access to `/shop create`.
*   `savscommoneconomy.shop.remove`: Access to `/shop remove` (own shops).
*   `savscommoneconomy.shop.info`: Access to `/shop info`.
*   `savscommoneconomy.shop.list`: Access to `/shop list`.

### Admin Permissions (Default: OP Level 2)
*   `savscommoneconomy.admin`: Grants access to all admin features:
    *   `/givemoney`, `/takemoney`, `/setmoney`, `/resetmoney`
    *   `/ecolog` (view transaction logs)
    *   `/ecodebug verify` and `/ecodebug cleanup` (database testing)
    *   `/shop admin` (create admin shops)
    *   **Shop Removal Override**: Ability to remove ANY player's shop.
