# Savs Common Economy

A lightweight, **server-side only** economy mod for Minecraft 1.21.10 (Fabric). This mod provides a simple yet robust economy system with JSON persistence, offline player support, leaderboards, and physical bank notes. No client installation required!

## Features

*   **Economy System**: Tracks player balances using a simple JSON file (`balances.json`).
*   **Server-Side Only**: No client installation required - fully compatible with vanilla clients.
*   **Offline Support**: Supports payments and administrative actions for offline players who have joined the server at least once.
*   **Configuration**: Customizable default starting balance and currency formatting (symbol, position).
*   **Autocompletion**: Smart tab completion for both online and offline player names.
*   **Selectors**: Basic support for the `@s` (self) selector.
*   **Leaderboard**: View the top 10 richest players with `/baltop`.
*   **Bank Notes**: Withdraw physical currency as vanilla paper items that can be traded or redeemed.
*   **Sell System**: Configurable system to allow players to check item values and sell them (optional, disabled by default).

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

### Admin Commands (Level 2+)
*   `/givemoney <player> <amount>`: Add money to a player's account.
*   `/takemoney <player> <amount>`: Remove money from a player's account.
*   `/setmoney <player> <amount>`: Set a player's balance to a specific amount.
*   `/resetmoney <player>`: Reset a player's balance to the default starting value.

## Configuration

The configuration file is located at `config/savs-common-economy/config.json`.

```json
{
  "defaultBalance": 1000,
  "currencySymbol": "$",
  "symbolBeforeAmount": true,
  "enableSellCommands": false
}
```

*   `defaultBalance`: The amount of money new players start with (default: 1000).
*   `currencySymbol`: The symbol used for currency (e.g., "$", "€", "Coins").
*   `symbolBeforeAmount`: If true, shows "$100"; if false, shows "100$".
*   `enableSellCommands`: Set to `true` to enable `/worth` and `/sell` commands.

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

## To-Do / Future Improvements

*   [ ] Full selector support (e.g., `@p`, `@a`, `@r`) for economy commands.
