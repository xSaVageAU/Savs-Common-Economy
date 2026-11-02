# 26.1 rewrite regressions: v1.5.1 → v1.6.0, checked against feat/26.1 (9b6d0fe)

Read-only review, 2026-11-02. Kept here as a checklist for a future core rewrite.
Paths and line numbers refer to v1.5.1 and to feat/26.1, not to this branch's layout. Not re-checked against refactor/modules.
Paths below are under src/main/java/savage/commoneconomy/.
"Old" = v1.5.1, "New" = HEAD of feat/26.1 unless a tag is named.
Every finding below is still present on feat/26.1 unless it says otherwise.

## Known items (confirmed)
- (a) PostgreSQL native UUID column: v1.6.0 storage/SqlStorage.java:70-74. Fixed on feat/26.1 by acfb030 (SqlStorage.java:66-105, VARCHAR(36) plus ALTER migration).
- (b) Table rename `<prefix>accounts` → `<prefix>balances`, no import: old storage/SqlStorage.java:31, new SqlStorage.java:69. Still present.
  The old ALTER TABLE that added a missing `version` column (old SqlStorage.java:40-50) has no equivalent. Still present.

## 1. Data compatibility

1. Storage type "POSTGRES" now silently uses JSON. Old accepted "POSTGRES" and "POSTGRESQL" (old EconomyManager.java:84-86).
   New only matches MYSQL/POSTGRESQL/MARIADB/SQLITE; anything else falls to JsonStorage (EconomyManager.java:54-57).
   A server with "POSTGRES" starts with an empty JSON economy and no error. Verified in code.
2. SQLite database file changed. Old: savdbcore SqliteStorage("savs-common-economy", "economy_data.sqlite") (old storage/SqliteStorage.java:21-26).
   New: "jdbc:sqlite:config/savs-common-economy/economy.db" (SqlStorage.java:41), and relative to the working directory, not FabricLoader's config dir.
   The file name differs, so old SQLite data is not read even before the table rename. Verified in code (old directory not checked: savdbcore source is not in this repo).
3. SQL transaction log dropped. Old wrote and searched `<prefix>transactions` when storage was SQL (old util/TransactionLogger.java:26-30, 52-57; old SqlStorage.java:52-55, 212-263).
   New always writes and reads logs/economy.log (TransactionLogger.java:31-55). /ecolog on SQL servers no longer shows any history from before the upgrade. Verified in code.
4. MYSQL now uses the MariaDB driver. Old went through savdbcore's MysqlStorage (old storage/MysqlStorage.java:21); new builds jdbc:mariadb:// with org.mariadb.jdbc.Driver for MYSQL too (SqlStorage.java:46-47).
   Whether this connects to a real MySQL 8 (caching_sha2_password etc.) needs a DB test.
5. Redis message format changed. Old published JSON {uuid, balance, type, sourcePlayer, chatMessage} (old util/RealRedisBackend.java:62-71, 105-119); new publishes "UPDATE:<uuid>" (util/RedisManager.java:72, 80).
   Old and new servers on one channel ignore each other's messages (only matters while a network upgrades one server at a time). Verified in code.
6. Redis connection settings moved. Old used Savs-Redis-Lib's shared connection (old RealRedisBackend.java:28-38); new connects itself with config.json redis.host/port/password (RedisManager.java:43-46).
   The one-time migration reads config/savs-redis.json, forces redis.enabled = true, and renames that file to .migrated (config/ConfigManager.java:65-93).
   Not verified: that the lib's file is really named savs-redis.json, and what happens to other mods still using the lib after the rename. Needs a test.
7. Redis with the default empty password: new code always calls withPassword(""), RedisManager.java:44. Whether Lettuce then sends AUTH "" and fails against a no-auth Redis needs a Redis test.

JSON balances (balances.json, same path and field names name/balance/version), shops.json (same path and fields; price double→String reads fine via Gson), and bank notes (same EconomyBankNote/Value custom data) are compatible. Verified in code.

## 2. Config
8. worth.json migration creates buy prices. Old had only itemPrices (sell). New migration copies them to sellPrices and adds buyPrices = 2× each sell price (ConfigManager.java:132-157).
   Combined with the new /buy command (SellCommands.java:50-55, permission default 0), every sellable item becomes buyable from the server at 2× after upgrade. Verified in code.
   Other config keys: all v1.5.1 keys still exist with the same defaults; old files load (missing keys keep their defaults). Verified in code.

## 3. Commands and permissions
9. "@s" as a target is gone. Old getTargetUUID/getTargetName handled "@s" (old command/EconomyCommands.java:108-110, 120-122). New lookupUUID does not (EconomyCommands.java:200, AdminEconomyCommands.java:164); "@s" is looked up as a player name and fails. Verified in code.
10. /shop setprice removed (old shop/ShopCommands.java:44-48, permission shop.create). No equivalent on feat/26.1. Verified in code.
11. /ecodebug (verify/cleanup/api, op 4) removed (old command/DebugCommands.java:23-29). Debug only. Verified in code.
Permission nodes: identical set (old 12 nodes, new adds command.buy). Defaults equivalent. Translation keys: v1.5.1 had no lang files, nothing to lose.

## 4. Behavior
12. Shop trades are no longer logged. Old logged SHOP_BUY / SHOP_SELL (old shop/ShopTransactionHandler.java:82, 152). No TransactionLogger call in the new shop code. Verified in code.
    Also /sell log type renamed COMMAND_SELL → SELL / SELL_ALL (SellCommands.java:180, 231).
13. API increase/decreaseBalance lost optimistic locking. Old used EconomyManager.add/removeBalance (CAS with retry) (old integration/SavsEconomyAccount.java:66-83).
    New reads balance() then calls setBalance(next), an unconditional write (SavsEconomyAccount.java:73-96). A concurrent change between the read and the write is lost. Verified in code; real-world impact needs a test.
14. Paying or giving to a player on another server no longer notifies them. Old sent the chat message through Redis and the other server showed it (old EconomyCommands.java:220-232, 282-294; old RealRedisBackend.java:86-93).
    New only notifies players on the same server (EconomyCommands.java:136-139, AdminEconomyCommands.java notifyTarget). Verified in code.
15. /givemoney reports success even when the add fails. Old showed "Transaction failed" (old EconomyCommands.java:256, 298-300); new ignores `success` (AdminEconomyCommands.java:86-91). Verified in code.
16. Balance lookups now create accounts. Old getBalance returned defaultBalance for an unknown UUID without writing (old EconomyManager.java:149-168).
    New getBalance → getOrCreateAccount saves a new "Unknown" account (EconomyManager.java:80-81, 188-191). API balance() for any UUID (and the internal retry path) adds rows that show in /baltop. Verified in code.
17. Money format changed: old amount.toString() (e.g. "$1000", "$12.5"), new "#,##0.00" ("$1,000.00") (old EconomyManager.java:344-350, new EconomyManager.java:232-237). Display only. Verified in code.
18. API unit changed: old 1 API unit = 1 dollar (long), new 1 unit = 1 cent (BigInteger, ×100) since 6e6fd95 (1.6.6). Intentional fix; other mods that stored API amounts would see 100× smaller values. Mention only.

Checked and unchanged: case-insensitive name lookup (EconomyManager.java:274-282), no negative balances (removeBalance refuses), permission nodes, bank note format, shop chest/sign protection, orphan-shop cleanup.
