package io.nexstudios.economy.economy.persistence;

import io.nexstudios.economy.economy.persistence.model.DbAccountSnapshot;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction over Nexus datasource for Economy persistence.
 * Must support both MySQL/MariaDB and SQLite.
 * Implementations should use dialect-specific upsert statements.
 */
public interface EcoPersistencePort {

    /**
     * Loads all accounts of a player in a single query.
     */
    CompletableFuture<Map<String, DbAccountSnapshot>> loadAllAccountsForPlayer(UUID playerId);

    /**
     * Loads versions (and optionally balances) for a player to compare staleness (cheap metadata query).
     * If an implementation prefers, it may return the same as loadAllAccountsForPlayer.
     */
    CompletableFuture<Map<String, DbAccountSnapshot>> loadVersionsForPlayer(UUID playerId);

    /**
     * Loads all accounts for all players (full scan).
     * Return map: playerId -> (currencyKey -> snapshot).
     */
    CompletableFuture<Map<UUID, Map<String, DbAccountSnapshot>>> loadAllAccountsAllPlayers();

    /**
     * Upserts a batch of accounts across multiple players and currencies.
     * Implementation must use:
     * - MySQL/MariaDB: INSERT ... ON DUPLICATE KEY UPDATE
     * - SQLite: INSERT ... ON CONFLICT(player_uuid, currency_key) DO UPDATE
     */
    CompletableFuture<Void> upsertBatch(Map<UUID, List<DbAccountSnapshot>> batchByPlayer);
}