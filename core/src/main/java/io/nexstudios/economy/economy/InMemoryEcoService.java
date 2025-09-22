package io.nexstudios.economy.economy;

import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.economy.model.AccountKey;
import io.nexstudios.economy.economy.model.PlayerAccount;
import io.nexstudios.economy.economy.persistence.EcoPersistencePort;
import io.nexstudios.economy.economy.persistence.model.DbAccountSnapshot;
import io.nexstudios.economy.economy.support.EcoLocks;
import io.nexstudios.economy.economy.support.EcoMath;
import io.nexstudios.economy.economy.support.TransactionLogger;
import io.nexstudios.economy.NexEconomy;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

import static io.nexstudios.economy.economy.NexEcoResponse.ResponseType.*;

/**
 * Cache-first economy service with dirty tracking and batch DB flush.
 * Deposits/withdrawals are applied only in memory; DB is updated periodically and on player quit.
 */
public class InMemoryEcoService implements NexEcoService {

    private final Map<AccountKey, PlayerAccount> cache = new ConcurrentHashMap<>();
    private final CurrencyLookup currencyLookup;
    private final CurrencyKeyResolver keyResolver;
    private final EcoPersistencePort persistence;
    private final EcoLocks locks = new EcoLocks();
    private final TransactionLogger txLogger;

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
                              TransactionLogger txLogger) {
        this.currencyLookup = currencyLookup;
        this.keyResolver = keyResolver;
        this.persistence = persistence;
        this.txLogger = txLogger;
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
    public boolean has(UUID playerId, String currencyKey, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return true;
        AccountKey ak = new AccountKey(playerId, currencyKey);
        PlayerAccount acc = cache.get(ak);
        if (acc == null) return false;
        BigDecimal scaled = EcoMath.scale(acc.getCurrency(), amount);
        return acc.getBalance().compareTo(scaled) >= 0;
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
            return new NexEcoResponse(scaled, newBal, SUCCESS, null);
        } finally {
            lock.unlock();
        }
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