package io.nexstudios.economy.storage;

import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.storage.model.AccountKey;
import io.nexstudios.economy.storage.model.PlayerAccount;
import io.nexstudios.economy.storage.persistence.EcoPersistencePort;
import io.nexstudios.economy.storage.persistence.model.DbAccountSnapshot;
import io.nexstudios.economy.storage.support.EcoLocks;
import io.nexstudios.economy.storage.support.EcoMath;
import io.nexstudios.economy.storage.support.TransactionLogger;
import io.nexstudios.economy.NexEconomy;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

import static io.nexstudios.economy.storage.NexEcoResponse.ResponseType.*;

/**
 * Cache-first economy service with dirty tracking, batch DB flush and optional cross-server syncing.
 * Deposits/withdrawals are applied only in memory; DB is updated periodically and on player quit.
 */
public class InMemoryEcoService implements NexEcoService {

    /**
     * Optional hook that can broadcast local balance changes to a remote system (e.g. Redis).
     * Implementations must be thread-safe.
     */
    public interface RemoteUpdateBroadcaster {
        void broadcastBalanceUpdate(PlayerAccount account, BigDecimal delta, String operationType);
    }

    private final Map<AccountKey, PlayerAccount> cache = new ConcurrentHashMap<>();
    private final CurrencyLookup currencyLookup;
    private final CurrencyKeyResolver keyResolver;
    private final EcoPersistencePort persistence;
    private final EcoLocks locks = new EcoLocks();
    private final TransactionLogger txLogger;
    private RemoteUpdateBroadcaster remoteBroadcaster;

    private final Map<UUID, Set<String>> playerCurrencies = new ConcurrentHashMap<>();

    // Lookup strategy for currencies by key
    public interface CurrencyLookup {
        Optional<NexCurrency> findByKey(String key);
    }

    // Resolves the stable currency key for a given currency instance
    public interface CurrencyKeyResolver {
        String keyOf(NexCurrency currency);
    }

    public InMemoryEcoService(CurrencyLookup currencyLookup,
                              CurrencyKeyResolver keyResolver,
                              EcoPersistencePort persistence,
                              TransactionLogger txLogger,
                              RemoteUpdateBroadcaster remoteBroadcaster) {
        this.currencyLookup = currencyLookup;
        this.keyResolver = keyResolver;
        this.persistence = persistence;
        this.txLogger = txLogger;
        this.remoteBroadcaster = remoteBroadcaster;
    }

    /**
     * Allows wiring the broadcaster after construction (e.g. once Redis sync helper is created).
     * This method is not thread-safe and should only be called during plugin startup.
     */
    public void setRemoteUpdateBroadcaster(RemoteUpdateBroadcaster broadcaster) {
        // This is intentionally not synchronized because it is only called during initialization.
        // Once set, it should not be mutated again.
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        // (Broadcaster implementations are expected to be immutable / thread-safe)
        ((InMemoryEcoService) this).remoteBroadcaster = broadcaster;
    }

    // ----- Load/Flush hooks -----

    public int loadAllForPlayerIfAbsent(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (playerCurrencies.containsKey(playerId)) return 0;

        Map<String, DbAccountSnapshot> db = persistence.loadAllAccountsForPlayer(playerId)
                .exceptionally(ex -> {
                    NexEconomy.nexusLogger.error("Eco: Failed to load accounts for " + playerId + ": " + ex.getMessage());
                    txLogger.logRaw("ERROR", "loadAllForPlayerIfAbsent failed: player=" + playerId + " msg=" + ex.getMessage());
                    return Map.of();
                }).join();

        int loaded = 0;
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, DbAccountSnapshot> e : db.entrySet()) {
            String cKey = e.getKey().toLowerCase(Locale.ROOT);
            Optional<NexCurrency> oc = currencyLookup.findByKey(cKey);
            if (oc.isEmpty()) continue;
            NexCurrency currency = oc.get();

            Lock lock = locks.lockFor(new AccountKey(playerId, cKey));
            lock.lock();
            try {
                AccountKey ak = new AccountKey(playerId, cKey);
                if (!cache.containsKey(ak)) {
                    var snap = e.getValue();
                    loaded = getImported(loaded, playerId, cKey, snap, currency, ak);
                }
            } finally {
                lock.unlock();
            }
            keys.add(cKey);
        }

        playerCurrencies.put(playerId, keys);
        return loaded;
    }

    public int ensureAccountsForPlayer(UUID playerId, Collection<NexCurrency> currencies) {
        if (playerId == null || currencies == null || currencies.isEmpty()) return 0;
        int created = 0;

        for (NexCurrency currency : currencies) {
            String cKey = keyResolver.keyOf(currency);
            AccountKey ak = new AccountKey(playerId, cKey);
            var lock = locks.lockFor(ak);
            lock.lock();
            try {
                if (!cache.containsKey(ak)) {
                    BigDecimal start = EcoMath.scale(currency, currency.getStartBalance()).max(BigDecimal.ZERO);
                    BigDecimal clamped = EcoMath.clampMax(currency, start);
                    PlayerAccount acc = new PlayerAccount(playerId, cKey, currency, clamped);
                    acc.setVersion(0L);
                    acc.setDirty(true);
                    acc.setUpdatedAtMillis(System.currentTimeMillis());
                    cache.put(ak, acc);
                    playerCurrencies.computeIfAbsent(playerId, id -> new HashSet<>()).add(cKey);
                    txLogger.log(playerId, cKey, "CREATE", "balance=" + clamped + " version=0");
                    created++;
                }
            } finally {
                lock.unlock();
            }
        }
        return created;
    }

    public int flushPlayerNow(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Set<String> keys = playerCurrencies.getOrDefault(playerId, Set.of());
        if (keys.isEmpty()) return 0;

        Map<UUID, List<DbAccountSnapshot>> batch = new HashMap<>();
        List<DbAccountSnapshot> list = new ArrayList<>();

        for (String cKey : keys) {
            AccountKey ak = new AccountKey(playerId, cKey);
            Lock lock = locks.lockFor(ak);
            lock.lock();
            try {
                PlayerAccount acc = cache.get(ak);
                if (acc == null || !acc.isDirty()) continue;

                NexEconomy.nexusLogger.info("Currency key: " + acc.getCurrencyKey());

                DbAccountSnapshot snap = new DbAccountSnapshot(
                        acc.getCurrencyKey(),
                        acc.getBalance(),
                        acc.getVersion(),
                        System.currentTimeMillis()
                );
                list.add(snap);
            } finally {
                lock.unlock();
            }
        }

        if (list.isEmpty()) return 0;
        batch.put(playerId, list);

        try {
            persistence.upsertBatch(batch).join();
            for (String cKey : keys) {
                AccountKey ak = new AccountKey(playerId, cKey);
                Lock lock = locks.lockFor(ak);
                lock.lock();
                try {
                    PlayerAccount acc = cache.get(ak);
                    if (acc == null || !acc.isDirty()) continue;
                    acc.setDirty(false);
                    acc.setUpdatedAtMillis(System.currentTimeMillis());
                    txLogger.log(playerId, cKey, "FLUSH", "balance=" + acc.getBalance() + " version=" + acc.getVersion());
                } finally {
                    lock.unlock();
                }
            }
            return list.size();
        } catch (Exception ex) {
            NexEconomy.nexusLogger.error("Eco: flushPlayerNow failed for " + playerId + ": " + ex.getMessage());
            txLogger.logRaw("ERROR", "flushPlayerNow failed: player=" + playerId + " msg=" + ex.getMessage());
            return 0;
        }
    }

    public int flushAllNow() {
        Map<UUID, List<DbAccountSnapshot>> batch = new HashMap<>();

        for (Map.Entry<AccountKey, PlayerAccount> e : cache.entrySet()) {
            AccountKey ak = e.getKey();
            PlayerAccount acc = e.getValue();
            Lock lock = locks.lockFor(ak);
            lock.lock();
            try {
                if (!acc.isDirty()) continue;
                batch.computeIfAbsent(ak.playerId(), k -> new ArrayList<>())
                        .add(new DbAccountSnapshot(
                                ak.currencyKey(),
                                acc.getBalance(),
                                acc.getVersion(),
                                System.currentTimeMillis()
                        ));
            } finally {
                lock.unlock();
            }
        }

        if (batch.isEmpty()) return 0;

        try {
            persistence.upsertBatch(batch).join();
            int cleared = 0;
            for (var entry : batch.entrySet()) {
                UUID pid = entry.getKey();
                for (DbAccountSnapshot snap : entry.getValue()) {
                    AccountKey ak = new AccountKey(pid, snap.currencyKey());
                    Lock lock = locks.lockFor(ak);
                    lock.lock();
                    try {
                        PlayerAccount acc = cache.get(ak);
                        if (acc == null) continue;
                        acc.setDirty(false);
                        acc.setUpdatedAtMillis(System.currentTimeMillis());
                        txLogger.log(pid, snap.currencyKey(), "FLUSH", "balance=" + acc.getBalance() + " version=" + acc.getVersion());
                        cleared++;
                    } finally {
                        lock.unlock();
                    }
                }
            }
            return cleared;
        } catch (Exception ex) {
            NexEconomy.nexusLogger.error("Eco: flushAllNow failed: " + ex.getMessage());
            txLogger.logRaw("ERROR", "flushAllNow failed: msg=" + ex.getMessage());
            return 0;
        }
    }

    // ----- NexEcoService implementation -----

    @Override
    public boolean hasAccount(UUID playerId, String currencyKey) {
        AccountKey ak = new AccountKey(playerId, currencyKey);
        return cache.containsKey(ak);
    }

    @Override
    public NexEcoResponse createAccount(UUID playerId, NexCurrency currency) {
        String cKey = keyResolver.keyOf(currency);
        AccountKey ak = new AccountKey(playerId, cKey);
        Lock lock = locks.lockFor(ak);
        lock.lock();
        try {
            PlayerAccount existing = cache.get(ak);
            if (existing != null) {
                return new NexEcoResponse(BigDecimal.ZERO, existing.getBalance(), FAILURE, "Account already exists");
            }
            BigDecimal start = EcoMath.scale(currency, currency.getStartBalance()).max(BigDecimal.ZERO);
            BigDecimal clamped = EcoMath.clampMax(currency, start);
            PlayerAccount acc = new PlayerAccount(playerId, cKey, currency, clamped);
            acc.setVersion(0L);
            acc.setDirty(true);
            acc.setUpdatedAtMillis(System.currentTimeMillis());
            cache.put(ak, acc);
            playerCurrencies.computeIfAbsent(playerId, id -> new HashSet<>()).add(cKey);
            txLogger.log(playerId, cKey, "CREATE", "balance=" + clamped + " version=0");
            // For newly created accounts we do not broadcast immediately; they will be visible on next balance change.
            return new NexEcoResponse(clamped, clamped, SUCCESS, null);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<AccountView> getAccount(UUID playerId, String currencyKey) {
        AccountKey ak = new AccountKey(playerId, currencyKey);
        PlayerAccount acc = cache.get(ak);
        if (acc == null) return Optional.empty();
        return Optional.of(new ViewImpl(acc));
    }

    @Override
    public BigDecimal getBalance(UUID playerId, String currencyKey) {
        AccountKey ak = new AccountKey(playerId, currencyKey);
        PlayerAccount acc = cache.get(ak);
        if (acc != null) return acc.getBalance();

        NexCurrency currency = currencyLookup.findByKey(ak.currencyKey())
                .orElseThrow(() -> new IllegalArgumentException("Unknown currency key: " + ak.currencyKey()));

        Lock lock = locks.lockFor(ak);
        lock.lock();
        try {
            PlayerAccount existing = cache.get(ak);
            if (existing != null) return existing.getBalance();

            BigDecimal start = EcoMath.scale(currency, currency.getStartBalance()).max(BigDecimal.ZERO);
            BigDecimal clamped = EcoMath.clampMax(currency, start);
            PlayerAccount created = new PlayerAccount(playerId, ak.currencyKey(), currency, clamped);
            created.setVersion(0L);
            created.setDirty(true);
            created.setUpdatedAtMillis(System.currentTimeMillis());
            cache.put(ak, created);
            playerCurrencies.computeIfAbsent(playerId, id -> new HashSet<>()).add(ak.currencyKey());
            txLogger.log(playerId, ak.currencyKey(), "CREATE", "balance=" + clamped + " version=0");
            return clamped;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<AccountView> getTopBalances(String currencyKey, int limit) {
        String cKey = currencyKey.toLowerCase(Locale.ROOT);
        return cache.entrySet().stream()
                .filter(e -> e.getKey().currencyKey().equals(cKey))
                .sorted((e1, e2) -> e2.getValue().getBalance().compareTo(e1.getValue().getBalance()))
                .limit(limit)
                .map(e -> (AccountView) new ViewImpl(e.getValue()))
                .toList();
    }

    @Override
    public boolean has(UUID playerId, String currencyKey, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return true;
        AccountKey ak = new AccountKey(playerId, currencyKey);
        PlayerAccount acc = cache.get(ak);
        if (acc == null) return false;
        BigDecimal scaled = EcoMath.scale(acc.getCurrency(), amount);
        return acc.getBalance().compareTo(scaled) >= 0;
    }

    @Override
    public NexEcoResponse setBalance(UUID playerId, String currencyKey, BigDecimal amount) {
        AccountKey ak = new AccountKey(playerId, currencyKey);
        PlayerAccount acc = cache.get(ak);
        if (acc == null) {
            getBalance(playerId, currencyKey);
            acc = cache.get(ak);
        }

        Lock lock = locks.lockFor(ak);
        lock.lock();

        try {
            if (amount == null || amount.signum() <= 0) {
                txLogger.log(playerId, currencyKey, "SET", "amount=0 result=NOT_NEGATIVE");
                return new NexEcoResponse(BigDecimal.ZERO, acc.getBalance(), NOT_NEGATIVE, "Amount must be positive");
            }
            BigDecimal scaled = EcoMath.scale(acc.getCurrency(), amount);
            BigDecimal before = acc.getBalance();

            acc.setBalance(scaled);
            acc.setVersion(acc.getVersion() + 1);
            acc.setDirty(true);
            acc.setUpdatedAtMillis(System.currentTimeMillis());
            playerCurrencies.computeIfAbsent(playerId, id -> new HashSet<>()).add(currencyKey);

            txLogger.log(playerId, currencyKey, "SET", "amount=" + scaled + " balance=" + scaled + " result=SUCCESS");

            // Broadcast cross-server update if supported
            if (remoteBroadcaster != null) {
                BigDecimal delta = scaled.subtract(before);
                remoteBroadcaster.broadcastBalanceUpdate(acc, delta, "SET");
            }

            return new NexEcoResponse(scaled, scaled, SUCCESS, null);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public NexEcoResponse deposit(UUID playerId, String currencyKey, BigDecimal amount) {
        AccountKey ak = new AccountKey(playerId, currencyKey);
        PlayerAccount acc = cache.get(ak);
        if (acc == null) {
            getBalance(playerId, currencyKey);
            acc = cache.get(ak);
        }

        Lock lock = locks.lockFor(ak);
        lock.lock();
        try {
            if (amount == null || amount.signum() <= 0) {
                txLogger.log(playerId, currencyKey, "DEPOSIT", "amount=0 result=NOT_NEGATIVE");
                return new NexEcoResponse(BigDecimal.ZERO, acc.getBalance(), NOT_NEGATIVE, "Amount must be positive");
            }
            BigDecimal scaled = EcoMath.scale(acc.getCurrency(), amount);
            BigDecimal before = acc.getBalance();
            BigDecimal tentative = before.add(scaled);
            BigDecimal clamped = EcoMath.clampMax(acc.getCurrency(), tentative);

            BigDecimal applied = clamped.subtract(before);
            if (applied.signum() <= 0) {
                txLogger.log(playerId, currencyKey, "DEPOSIT", "amount=0 balance=" + before + " result=MAX_BALANCE");
                return new NexEcoResponse(BigDecimal.ZERO, before, MAX_BALANCE, "Max balance reached");
            }

            acc.setBalance(clamped);
            acc.setVersion(acc.getVersion() + 1);
            acc.setDirty(true);
            acc.setUpdatedAtMillis(System.currentTimeMillis());
            playerCurrencies.computeIfAbsent(playerId, id -> new HashSet<>()).add(currencyKey);

            txLogger.log(playerId, currencyKey, "DEPOSIT", "amount=" + applied + " balance=" + clamped + " result=SUCCESS");

            // Broadcast cross-server update if supported
            if (remoteBroadcaster != null) {
                remoteBroadcaster.broadcastBalanceUpdate(acc, applied, "DEPOSIT");
            }

            return new NexEcoResponse(applied, clamped, SUCCESS, null);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public NexEcoResponse withdraw(UUID playerId, String currencyKey, BigDecimal amount) {
        AccountKey ak = new AccountKey(playerId, currencyKey);
        PlayerAccount acc = cache.get(ak);
        if (acc == null) {
            getBalance(playerId, currencyKey);
            acc = cache.get(ak);
        }

        Lock lock = locks.lockFor(ak);
        lock.lock();
        try {
            if (amount == null || amount.signum() <= 0) {
                txLogger.log(playerId, currencyKey, "WITHDRAW", "amount=0 result=NOT_NEGATIVE");
                return new NexEcoResponse(BigDecimal.ZERO, acc.getBalance(), NOT_NEGATIVE, "Amount must be positive");
            }
            BigDecimal scaled = EcoMath.scale(acc.getCurrency(), amount);
            BigDecimal before = acc.getBalance();
            if (before.compareTo(scaled) < 0) {
                txLogger.log(playerId, currencyKey, "WITHDRAW", "amount=" + scaled + " balance=" + before + " result=NOT_ENOUGH");
                return new NexEcoResponse(BigDecimal.ZERO, before, NOT_ENOUGH, "Not enough balance");
            }

            BigDecimal newBal = before.subtract(scaled);
            acc.setBalance(newBal);
            acc.setVersion(acc.getVersion() + 1);
            acc.setDirty(true);
            acc.setUpdatedAtMillis(System.currentTimeMillis());
            playerCurrencies.computeIfAbsent(playerId, id -> new HashSet<>()).add(currencyKey);

            txLogger.log(playerId, currencyKey, "WITHDRAW", "amount=" + scaled + " balance=" + newBal + " result=SUCCESS");

            // Broadcast cross-server update if supported
            if (remoteBroadcaster != null) {
                remoteBroadcaster.broadcastBalanceUpdate(acc, scaled, "WITHDRAW");
            }

            return new NexEcoResponse(scaled, newBal, SUCCESS, null);
        } finally {
            lock.unlock();
        }
    }

    public int refreshCurrencies() {
        int updated = 0;
        for (Map.Entry<AccountKey, PlayerAccount> entry : cache.entrySet()) {
            AccountKey ak = entry.getKey();
            PlayerAccount acc = entry.getValue();

            Optional<NexCurrency> freshCurrency = currencyLookup.findByKey(ak.currencyKey());
            if (freshCurrency.isEmpty()) continue;

            Lock lock = locks.lockFor(ak);
            lock.lock();
            try {
                // Update the currency reference in the account
                acc.setCurrency(freshCurrency.get());
                updated++;
            } finally {
                lock.unlock();
            }
        }
        return updated;
    }

    public int importAllSnapshots(Map<UUID, Map<String, DbAccountSnapshot>> all) {
        if (all == null || all.isEmpty()) return 0;
        int imported = 0;

        for (Map.Entry<UUID, Map<String, DbAccountSnapshot>> e : all.entrySet()) {
            UUID playerId = e.getKey();
            Set<String> keys = playerCurrencies.computeIfAbsent(playerId, id -> new HashSet<>());

            for (Map.Entry<String, DbAccountSnapshot> ce : e.getValue().entrySet()) {
                String cKey = ce.getKey().toLowerCase(Locale.ROOT);
                var snap = ce.getValue();

                Optional<NexCurrency> oc = currencyLookup.findByKey(cKey);
                if (oc.isEmpty()) continue;
                NexCurrency currency = oc.get();

                AccountKey ak = new AccountKey(playerId, cKey);
                var lock = locks.lockFor(ak);
                lock.lock();
                try {
                    if (!cache.containsKey(ak)) {
                        imported = getImported(imported, playerId, cKey, snap, currency, ak);
                    }
                } finally {
                    lock.unlock();
                }
                keys.add(cKey);
            }
        }
        return imported;
    }

    /**
     * Clears all cached accounts for a player and reloads them from the database.
     * This is intended for multi-server setups where the DB is the single source of truth.
     *
     * @param playerId player UUID
     * @return number of accounts loaded from DB
     */
    public int reloadAllForPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");

        // 1) Remove all cached accounts for this player.
        // We iterate over a snapshot of the keys to avoid ConcurrentModification issues.
        for (AccountKey ak : new ArrayList<>(cache.keySet())) {
            if (!ak.playerId().equals(playerId)) continue;
            Lock lock = locks.lockFor(ak);
            lock.lock();
            try {
                cache.remove(ak);
            } finally {
                lock.unlock();
            }
        }
        // 2) Remove currency tracking for this player.
        playerCurrencies.remove(playerId);

        // 3) Fresh load from DB (now behaves like first join for this player).
        return loadAllForPlayerIfAbsent(playerId);
    }

    /**
     * Deletes all accounts for a specific player (cache + DB) and recreates
     * fresh accounts with start balance for the given currencies.
     *
     * @param playerId   UUID of the player
     * @param currencies currencies for which new accounts should be created
     * @return number of newly created accounts
     */
    public int resetPlayer(UUID playerId, Collection<NexCurrency> currencies) {
        Objects.requireNonNull(playerId, "playerId");

        // 1) Clear cache and tracking for this player
        Set<String> keys = playerCurrencies.getOrDefault(playerId, Set.of());
        for (String cKey : keys) {
            AccountKey ak = new AccountKey(playerId, cKey);
            Lock lock = locks.lockFor(ak);
            lock.lock();
            try {
                cache.remove(ak);
            } finally {
                lock.unlock();
            }
        }
        playerCurrencies.remove(playerId);

        // 2) Delete all DB entries for this player
        try {
            persistence.deletePlayer(playerId).join();
        } catch (Exception ex) {
            NexEconomy.nexusLogger.error("Eco: resetPlayer DB delete failed for " + playerId + ": " + ex.getMessage());
            txLogger.logRaw("ERROR", "resetPlayer deletePlayer failed: player=" + playerId + " msg=" + ex.getMessage());
        }

        // 3) Create fresh accounts in cache
        int created = ensureAccountsForPlayer(playerId, currencies);

        // 4) Flush immediately to DB
        if (created > 0) {
            flushPlayerNow(playerId);
        }

        return created;
    }

    /**
     * Deletes all accounts of all players (cache + DB).
     * Afterwards, optionally recreates accounts for the given currencies
     * for all currently online players.
     *
     * @param currencies currencies for which online players should get fresh accounts
     * @return number of newly created accounts
     */
    public int resetAllPlayers(Collection<NexCurrency> currencies) {
        // 1) Completely clear cache and tracking
        cache.clear();
        playerCurrencies.clear();

        // 2) Delete all DB entries
        try {
            persistence.deleteAll().join();
        } catch (Exception ex) {
            NexEconomy.nexusLogger.error("Eco: resetAllPlayers DB deleteAll failed: " + ex.getMessage());
            txLogger.logRaw("ERROR", "resetAllPlayers deleteAll failed: msg=" + ex.getMessage());
        }

        // 3) For all currently online players create new accounts and flush
        int totalCreated = 0;
        var onlinePlayers = org.bukkit.Bukkit.getOnlinePlayers();
        for (org.bukkit.entity.Player p : onlinePlayers) {
            UUID playerId = p.getUniqueId();
            int created = ensureAccountsForPlayer(playerId, currencies);
            if (created > 0) {
                flushPlayerNow(playerId);
                totalCreated += created;
            }
        }
        return totalCreated;
    }

    /**
     * Applies a balance update that was received from a remote server (e.g. via Redis).
     * This method never marks accounts as dirty, so other servers will not flush these
     * values back to the database and accidentally overwrite the originating server.
     *
     * @param playerId        player UUID
     * @param currencyKey     stable currency key (lower-cased)
     * @param newBalance      new absolute balance
     * @param version         version as declared by the remote server
     * @param updatedAtMillis remote timestamp when the update was created
     */
    public void applyRemoteBalance(UUID playerId,
                                   String currencyKey,
                                   BigDecimal newBalance,
                                   long version,
                                   long updatedAtMillis) {

        if (playerId == null || currencyKey == null || newBalance == null) {
            return;
        }

        String cKey = currencyKey.toLowerCase(Locale.ROOT);
        AccountKey ak = new AccountKey(playerId, cKey);
        Lock lock = locks.lockFor(ak);
        lock.lock();
        try {
            PlayerAccount existing = cache.get(ak);

            if (existing != null) {
                // Ignore stale updates
                if (version <= existing.getVersion()) {
                    return;
                }
                existing.setBalance(newBalance);
                existing.setVersion(version);
                existing.setDirty(false);
                existing.setUpdatedAtMillis(updatedAtMillis);
                playerCurrencies.computeIfAbsent(playerId, id -> new HashSet<>()).add(cKey);
                return;
            }

            Optional<NexCurrency> oc = currencyLookup.findByKey(cKey);
            if (oc.isEmpty()) {
                // Currency does not exist on this server; nothing to apply.
                return;
            }

            NexCurrency currency = oc.get();
            PlayerAccount acc = new PlayerAccount(playerId, cKey, currency, newBalance);
            acc.setVersion(Math.max(0, version));
            acc.setDirty(false);
            acc.setUpdatedAtMillis(updatedAtMillis);
            cache.put(ak, acc);
            playerCurrencies.computeIfAbsent(playerId, id -> new HashSet<>()).add(cKey);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears all cached accounts and tracking info for a single player in response
     * to a remote reset command. This method does not touch the database.
     *
     * @param playerId player UUID whose accounts should be removed from cache
     */
    public void applyRemoteResetPlayer(UUID playerId) {
        if (playerId == null) return;

        Set<String> keys = playerCurrencies.getOrDefault(playerId, Set.of());
        for (String cKey : keys) {
            AccountKey ak = new AccountKey(playerId, cKey);
            Lock lock = locks.lockFor(ak);
            lock.lock();
            try {
                cache.remove(ak);
            } finally {
                lock.unlock();
            }
        }
        playerCurrencies.remove(playerId);
    }

    public void applyRemoteResetAll() {
        cache.clear();
        playerCurrencies.clear();
    }

    private int getImported(int imported, UUID playerId, String cKey, DbAccountSnapshot snap, NexCurrency currency, AccountKey ak) {
        BigDecimal bal = EcoMath.scale(currency, snap.balance());
        PlayerAccount acc = new PlayerAccount(playerId, cKey, currency, bal);
        acc.setVersion(Math.max(0, snap.version()));
        acc.setDirty(false);
        acc.setUpdatedAtMillis(snap.updatedAtMillis());
        cache.put(ak, acc);
        imported++;
        return imported;
    }

    private static final class ViewImpl implements AccountView {
        private final PlayerAccount acc;
        private ViewImpl(PlayerAccount acc) { this.acc = acc; }
        @Override public UUID playerId() { return acc.getPlayerId(); }
        @Override public String currencyKey() { return acc.getCurrencyKey(); }
        @Override public NexCurrency currency() { return acc.getCurrency(); }
        @Override public BigDecimal balance() { return acc.getBalance(); }
    }
}