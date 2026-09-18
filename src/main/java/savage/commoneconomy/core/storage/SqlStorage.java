package savage.commoneconomy.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import savage.commoneconomy.SavsCommonEconomy;
import savage.commoneconomy.core.config.ConfigManager;
import savage.commoneconomy.core.model.AccountData;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * SQL-based storage implementation using HikariCP.
 * Handles MariaDB/MySQL storage for cross-server synchronization.
 */
public class SqlStorage implements EconomyStorage {

    private HikariDataSource dataSource;
    private final ExecutorService executor;

    public SqlStorage(ExecutorService executor) {
        this.executor = executor;
        initConnection();
    }

    private void initConnection() {
        var config = ConfigManager.getConfig().storage;
        HikariConfig hikariConfig = new HikariConfig();
        
        String jdbcUrl;
        if ("POSTGRESQL".equalsIgnoreCase(config.type)) {
            jdbcUrl = "jdbc:postgresql://" + config.host + ":" + config.port + "/" + config.database;
            hikariConfig.setDriverClassName("org.postgresql.Driver");
        } else if ("SQLITE".equalsIgnoreCase(config.type)) {
            // SQLite stored in the config directory alongside config.json
            jdbcUrl = "jdbc:sqlite:config/savs-common-economy/economy.db";
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
            hikariConfig.setPoolName("SavsEconomyLitePool");
            hikariConfig.setMaximumPoolSize(1); // SQLite is best with a single write connection
        } else {
            jdbcUrl = "jdbc:mariadb://" + config.host + ":" + config.port + "/" + config.database;
            hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            hikariConfig.setMaximumPoolSize(config.poolSize);
        }
        
        hikariConfig.setJdbcUrl(jdbcUrl);
        if (!"SQLITE".equalsIgnoreCase(config.type)) {
            hikariConfig.setUsername(config.user);
            hikariConfig.setPassword(config.password);
            hikariConfig.setConnectionTimeout(config.connectionTimeout);
            hikariConfig.setIdleTimeout(config.idleTimeout);
        }

        this.dataSource = new HikariDataSource(hikariConfig);
        setupTable();
    }

    private void setupTable() {
        String prefix = ConfigManager.getConfig().storage.tablePrefix;
        String sql;
        if ("POSTGRESQL".equalsIgnoreCase(ConfigManager.getConfig().storage.type)) {
            sql = "CREATE TABLE IF NOT EXISTS " + prefix + "balances (" +
                  "uuid UUID PRIMARY KEY, " +
                  "name VARCHAR(255), " +
                  "balance DECIMAL(30, 2), " +
                  "version BIGINT DEFAULT 0)";
        } else {
            // MariaDB and SQLite both use VARCHAR/TEXT for UUIDs
            sql = "CREATE TABLE IF NOT EXISTS " + prefix + "balances (" +
                  "uuid VARCHAR(36) PRIMARY KEY, " +
                  "name VARCHAR(255), " +
                  "balance DECIMAL(30, 2), " +
                  "version BIGINT DEFAULT 0)";
        }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            SavsCommonEconomy.LOGGER.error("Failed to setup SQL tables!", e);
        }
    }

    @Override
    public CompletableFuture<AccountData> loadAccount(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String prefix = ConfigManager.getConfig().storage.tablePrefix;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT name, balance, version FROM " + prefix + "balances WHERE uuid = ?")) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        AccountData data = new AccountData(rs.getString("name"), rs.getBigDecimal("balance"));
                        data.setVersion(rs.getLong("version"));
                        return data;
                    }
                }
            } catch (SQLException e) {
                SavsCommonEconomy.LOGGER.error("Failed to load account from SQL: " + uuid, e);
            }
            return null;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> saveAccount(UUID uuid, AccountData data) {
        return CompletableFuture.runAsync(() -> {
            String prefix = ConfigManager.getConfig().storage.tablePrefix;
            String query;
            if ("POSTGRESQL".equalsIgnoreCase(ConfigManager.getConfig().storage.type) || 
                "SQLITE".equalsIgnoreCase(ConfigManager.getConfig().storage.type)) {
                query = "INSERT INTO " + prefix + "balances (uuid, name, balance, version) VALUES (?, ?, ?, ?) " +
                         "ON CONFLICT (uuid) DO UPDATE SET name = EXCLUDED.name, balance = EXCLUDED.balance, version = EXCLUDED.version";
            } else {
                query = "INSERT INTO " + prefix + "balances (uuid, name, balance, version) VALUES (?, ?, ?, ?) " +
                         "ON DUPLICATE KEY UPDATE name = ?, balance = ?, version = ?";
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, data.getName());
                stmt.setBigDecimal(3, data.getBalance());
                stmt.setLong(4, data.getVersion());
                
                if (!"POSTGRESQL".equalsIgnoreCase(ConfigManager.getConfig().storage.type) && 
                    !"SQLITE".equalsIgnoreCase(ConfigManager.getConfig().storage.type)) {
                    stmt.setString(5, data.getName());
                    stmt.setBigDecimal(6, data.getBalance());
                    stmt.setLong(7, data.getVersion());
                }
                
                stmt.executeUpdate();
            } catch (SQLException e) {
                SavsCommonEconomy.LOGGER.error("Failed to save account to SQL: " + uuid, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> saveAccountIfVersionMatches(UUID uuid, AccountData data, long expectedVersion) {
        return CompletableFuture.supplyAsync(() -> {
            String prefix = ConfigManager.getConfig().storage.tablePrefix;
            // Use UPDATE with WHERE version = ? for optimistic locking (CAS)
            String query = "UPDATE " + prefix + "balances SET name = ?, balance = ?, version = ? WHERE uuid = ? AND version = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, data.getName());
                stmt.setBigDecimal(2, data.getBalance());
                stmt.setLong(3, data.getVersion());
                stmt.setString(4, uuid.toString());
                stmt.setLong(5, expectedVersion);

                int rowsAffected = stmt.executeUpdate();
                if (rowsAffected == 0) {
                    // Either version conflict or account doesn't exist yet — try insert for new accounts
                    return false;
                }
                return true;
            } catch (SQLException e) {
                SavsCommonEconomy.LOGGER.error("Failed to CAS-save account to SQL: " + uuid, e);
                return false;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Map<UUID, AccountData>> loadAllAccounts() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, AccountData> accounts = new HashMap<>();
            String prefix = ConfigManager.getConfig().storage.tablePrefix;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT uuid, name, balance, version FROM " + prefix + "balances")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        AccountData data = new AccountData(rs.getString("name"), rs.getBigDecimal("balance"));
                        data.setVersion(rs.getLong("version"));
                        accounts.put(uuid, data);
                    }
                }
            } catch (SQLException e) {
                SavsCommonEconomy.LOGGER.error("Failed to load all accounts from SQL", e);
            }
            return accounts;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> deleteAccount(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            String prefix = ConfigManager.getConfig().storage.tablePrefix;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + prefix + "balances WHERE uuid = ?")) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                SavsCommonEconomy.LOGGER.error("Failed to delete account from SQL: " + uuid, e);
            }
        }, executor);
    }

    @Override
    public void shutdown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
