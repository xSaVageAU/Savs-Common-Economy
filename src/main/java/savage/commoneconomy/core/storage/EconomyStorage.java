package savage.commoneconomy.core.storage;

import savage.commoneconomy.core.model.AccountData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for economy storage handlers.
 */
public interface EconomyStorage {
    /**
     * Loads an account from storage asynchronously.
     */
    CompletableFuture<AccountData> loadAccount(UUID uuid);

    /**
     * Saves an account to storage asynchronously.
     */
    CompletableFuture<Void> saveAccount(UUID uuid, AccountData data);

    /**
     * Saves an account to storage with optimistic locking (CAS).
     * Only succeeds if the stored version matches expectedVersion.
     * @return true if the save succeeded, false if a version conflict occurred.
     */
    CompletableFuture<Boolean> saveAccountIfVersionMatches(UUID uuid, AccountData data, long expectedVersion);

    /**
     * Deletes an account from storage asynchronously.
     */
    CompletableFuture<Void> deleteAccount(UUID uuid);

    /**
     * Performs a graceful shutdown of the storage handler.
     */
    void shutdown();

    /**
     * @return All stored accounts asynchronously (used for baltop/migrations).
     */
    CompletableFuture<Map<UUID, AccountData>> loadAllAccounts();
}
