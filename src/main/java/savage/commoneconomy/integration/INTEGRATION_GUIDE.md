# Common Economy API Integration Guide

This guide documents how to integrate the **Common Economy API** into a Fabric mod, specifically targeting Minecraft 1.21. It covers dependency setup, implementation, and a critical workaround for version compatibility.

## 1. Dependency Setup
Add the Common Economy API to your `build.gradle`.

```gradle
dependencies {
    // Common Economy API
    modImplementation "eu.pb4:common-economy-api:1.1.1"
    include "eu.pb4:common-economy-api:1.1.1"
}
```

## 2. Implementation
You need to implement three core interfaces:

### 1. EconomyProvider
The main entry point. It manages currencies and accounts.
- **Key Methods:** `getCurrencies()`, `getAccounts(GameProfile)`, `getAccount(GameProfile, currencyId)`.
- **Singleton:** It's best to implement this as a singleton (e.g., `SavsEconomyProvider.INSTANCE`).

### 2. EconomyCurrency
Represents a specific currency (e.g., "Dollars").
- **Key Methods:** `formatValue(long)`, `parseValue(String)`.
- **ID:** Must be unique (e.g., `savs_common_economy:dollar`).

### 3. EconomyAccount
Represents a player's account for a specific currency.
- **Key Methods:** `balance()`, `setBalance(long)`, `canDecreaseBalance(long)`.
- **Transaction Safety:** Use `EconomyTransaction` to report success/failure.

## 3. Registration
Register your provider in your mod's initializer (`onInitialize` or `onInitializeServer`).

```java
import eu.pb4.common.economy.api.CommonEconomy;

public void onInitialize() {
    CommonEconomy.register("savs_common_economy", SavsEconomyProvider.INSTANCE);
}
```

## 4. Integration Notes: Version Compatibility

### Supporting the Ecosystem (v1.1.1)
During integration, we observed that many popular mods in the ecosystem (such as Universal Shops) currently bundle version `1.1.1` of the Common Economy API. To ensure seamless compatibility and avoid version conflicts for users, we decided to align our integration with this version.

### Technical Implementation Details
Targeting version `1.1.1` on Minecraft 1.21 required a small technical workaround due to changes in the underlying game code.

**Observation:**
Version `1.1.1` attempts to access the `server` field in `ServerPlayerEntity`. In Minecraft 1.21, this field's visibility is restricted, which would normally prevent interaction.

**Implementation:**
To bridge this gap, we included a standard Fabric **Access Widener**. This allows the API to interact with the game field as intended, ensuring that version `1.1.1` functions perfectly within the 1.21 environment.

1.  **Access Widener (`src/main/resources/savs-common-economy.accesswidener`):**
    ```accesswidener
    accessWidener	v1	named
    accessible field net/minecraft/server/network/ServerPlayerEntity server Lnet/minecraft/server/MinecraftServer;
    ```

2.  **Configuration (`fabric.mod.json`):**
    ```json
    {
      "accessWidener": "savs-common-economy.accesswidener",
      ...
    }
    ```

This approach allows us to maintain full compatibility with the broader mod ecosystem while running on the latest Minecraft version.
