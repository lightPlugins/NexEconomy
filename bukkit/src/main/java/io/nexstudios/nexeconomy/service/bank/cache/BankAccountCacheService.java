package io.nexstudios.nexeconomy.service.bank.cache;

import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankAccountEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankWithdrawUsageEntity;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Dependencies({
    BankRepositoryService.class,
    FileReaderService.class
})
public final class BankAccountCacheService implements Service {

  public record Key(String bankIdLower, UUID ownerUuid) {}

  public record View(
      BankAccountEntity account,
      MantissaAmount balance,
      List<BankMemberEntity> members
  ) {}

  private record WithdrawUsageKey(
      UUID bankAccountId,
      UUID memberUuid,
      BankWithdrawUsageEntity.WindowType windowType,
      long windowStartEpochSeconds
  ) {}

  private record WithdrawUsageEntry(MantissaAmount usage, long loadedAtMs) {}

  private record Entry(View view, long loadedAtMs, AtomicBoolean refreshing, AtomicLong lastAccessMs) {
    static Entry of(View view) {
      long now = System.currentTimeMillis();
      return new Entry(view, now, new AtomicBoolean(false), new AtomicLong(now));
    }
  }

  private static final int DEFAULT_TTL_SECONDS = 30;
  private static final int MIN_TTL_SECONDS = 0;
  private static final int MAX_TTL_SECONDS = 300;

  private static final int DEFAULT_MAX_ENTRIES = 10_000;
  private static final int MIN_MAX_ENTRIES = 0;
  private static final int MAX_MAX_ENTRIES = 250_000;

  private static final int DEFAULT_CLEANUP_BATCH = 512;
  private static final int MIN_CLEANUP_BATCH = 64;
  private static final int MAX_CLEANUP_BATCH = 10_000;

  private static final long DEFAULT_WITHDRAW_USAGE_TTL_MS = 5_000L;

  private final BankRepositoryService repo;
  private final FileConfiguration settings;

  private volatile long ttlMs = DEFAULT_TTL_SECONDS * 1000L;
  private volatile int maxEntries = DEFAULT_MAX_ENTRIES;
  private volatile int cleanupBatch = DEFAULT_CLEANUP_BATCH;

  private final ConcurrentHashMap<Key, Entry> byKey = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Entry> byAccountId = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<Key, CompletableFuture<View>> inFlight = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<WithdrawUsageKey, WithdrawUsageEntry> withdrawUsageByKey = new ConcurrentHashMap<>();

  private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);

  public BankAccountCacheService(ServiceAccessor accessor) {
    this.repo = accessor.getService(BankRepositoryService.class);

    FileReaderService fileReader = accessor.getService(FileReaderService.class);
    this.settings = fileReader.load(Path.of("settings.yml"), "settings.yml", true);

    reload();
  }

  public void reload() {
    int seconds = settings == null
        ? DEFAULT_TTL_SECONDS
        : settings.getInt("bank-cache.ttl-seconds", DEFAULT_TTL_SECONDS);

    seconds = clamp(seconds);
    this.ttlMs = seconds <= 0 ? 0L : seconds * 1000L;

    int max = settings == null
        ? DEFAULT_MAX_ENTRIES
        : settings.getInt("bank-cache.max-entries", DEFAULT_MAX_ENTRIES);
    this.maxEntries = clampMaxEntries(max);

    int batch = settings == null
        ? DEFAULT_CLEANUP_BATCH
        : settings.getInt("bank-cache.cleanup-batch", DEFAULT_CLEANUP_BATCH);
    this.cleanupBatch = clampCleanupBatch(batch);

    // If maxEntries got lowered, ensure we start shrinking soon.
    maybeCleanupAsync();
  }

  public View get(String bankIdLower, UUID ownerUuid) {
    if (bankIdLower == null || bankIdLower.isBlank() || ownerUuid == null) return null;

    Key key = new Key(normalize(bankIdLower), ownerUuid);
    Entry e = byKey.get(key);
    if (e == null) return null;

    touch(e);

    // refresh in background if expired (serve stale)
    if (isExpired(e) && e.refreshing().compareAndSet(false, true)) {
      refreshAsync(key, ownerUuid);
    }

    maybeCleanupAsync();
    return e.view();
  }

  public View get(UUID bankAccountId) {
    if (bankAccountId == null) return null;

    Entry e = byAccountId.get(bankAccountId);
    if (e == null) return null;

    touch(e);

    // refresh in background if expired (serve stale)
    View v = e.view();
    if (v != null && v.account() != null && v.account().getBankIdLower() != null && v.account().getOwnerUuid() != null) {
      Key key = new Key(normalize(v.account().getBankIdLower()), v.account().getOwnerUuid());
      if (isExpired(e) && e.refreshing().compareAndSet(false, true)) {
        refreshAsync(key, v.account().getOwnerUuid());
      }
    }

    maybeCleanupAsync();
    return v;
  }

  public void invalidate(UUID bankAccountId) {
    if (bankAccountId == null) return;

    Entry e = byAccountId.remove(bankAccountId);
    if (e == null || e.view() == null || e.view().account() == null) return;

    String bank = e.view().account().getBankIdLower();
    UUID owner = e.view().account().getOwnerUuid();
    if (bank == null || owner == null) return;

    Key key = new Key(normalize(bank), owner);
    byKey.remove(key, e);
    inFlight.remove(key);
    invalidateWithdrawUsage(bankAccountId);
  }

  public void invalidate(String bankIdLower, UUID ownerUuid) {
    if (bankIdLower == null || bankIdLower.isBlank() || ownerUuid == null) return;

    Key key = new Key(normalize(bankIdLower), ownerUuid);
    Entry e = byKey.remove(key);
    inFlight.remove(key);

    if (e != null && e.view() != null && e.view().account() != null && e.view().account().getId() != null) {
      UUID accountId = e.view().account().getId();
      byAccountId.remove(accountId, e);
      invalidateWithdrawUsage(accountId);
    }
  }

  public void invalidateWithdrawUsage(UUID bankAccountId) {
    if (bankAccountId == null) return;
    withdrawUsageByKey.keySet().removeIf(key -> key != null && bankAccountId.equals(key.bankAccountId()));
  }

  public CompletableFuture<MantissaAmount> loadWithdrawUsage(
      UUID bankAccountId,
      UUID memberUuid,
      BankWithdrawUsageEntity.WindowType windowType,
      long windowStartEpochSeconds
  ) {
    if (bankAccountId == null) return CompletableFuture.completedFuture(MantissaAmount.zero());
    if (memberUuid == null) return CompletableFuture.completedFuture(MantissaAmount.zero());
    if (windowType == null) return CompletableFuture.completedFuture(MantissaAmount.zero());
    if (windowStartEpochSeconds <= 0) return CompletableFuture.completedFuture(MantissaAmount.zero());

    WithdrawUsageKey key = new WithdrawUsageKey(bankAccountId, memberUuid, windowType, windowStartEpochSeconds);
    WithdrawUsageEntry cached = withdrawUsageByKey.get(key);
    long now = System.currentTimeMillis();
    if (cached != null && (now - cached.loadedAtMs()) <= DEFAULT_WITHDRAW_USAGE_TTL_MS) {
      MantissaAmount usage = cached.usage();
      return CompletableFuture.completedFuture(usage == null ? MantissaAmount.zero() : usage);
    }

    return repo.loadWithdrawUsage(bankAccountId, memberUuid, windowType, windowStartEpochSeconds)
        .thenApply(usage -> {
          MantissaAmount safe = usage == null ? MantissaAmount.zero() : usage;
          withdrawUsageByKey.put(key, new WithdrawUsageEntry(safe, System.currentTimeMillis()));
          return safe;
        });
  }

  public CompletableFuture<View> loadOrCreate(String bankIdLower, UUID ownerUuid) {
    String bank = normalize(bankIdLower);
    if (bank.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankIdLower is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));

    Key key = new Key(bank, ownerUuid);

    Entry existing = byKey.get(key);
    if (existing != null) {
      touch(existing);

      if (isExpired(existing) && existing.refreshing().compareAndSet(false, true)) {
        refreshAsync(key, ownerUuid);
      }

      maybeCleanupAsync();
      return CompletableFuture.completedFuture(existing.view());
    }

    CompletableFuture<View> inflight = inFlight.get(key);
    if (inflight != null) return inflight;

    return inFlight.computeIfAbsent(key, ignored -> {
      CompletableFuture<View> f = loadFresh(bank, ownerUuid).thenApply(view -> {
        put(key, view);
        return view;
      });

      // Ensure inFlight future is removed even on exception to prevent deadlocks
      return f.whenComplete((r, e) -> {
        inFlight.remove(key);
        if (e != null) {
          System.err.println("Failed to load bank account " + key + ": " + e.getMessage());
        }
      });
    });
  }

  private CompletableFuture<View> loadFresh(String bankIdLower, UUID ownerUuid) {
    return repo.createAccountIfMissing(bankIdLower, ownerUuid).thenCompose(acc -> {
      if (acc == null || acc.getId() == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("bank account not available"));
      }

      UUID realOwner = acc.getOwnerUuid();
      if (realOwner != null) {
        return repo.upsertMember(acc.getId(), realOwner, realOwner, "owner")
            .thenCompose(ignored -> loadBalanceAndMembers(acc));
      }

      return loadBalanceAndMembers(acc);
    });
  }

  private CompletableFuture<View> loadBalanceAndMembers(BankAccountEntity acc) {
    CompletableFuture<MantissaAmount> balF = repo.loadBalance(acc.getId());
    CompletableFuture<List<BankMemberEntity>> memF = repo.listMembers(acc.getId());

    return CompletableFuture.allOf(balF, memF).thenApply(v -> {
      MantissaAmount bal = balF.getNow(MantissaAmount.zero());
      List<BankMemberEntity> mem = memF.getNow(List.of());

      return new View(
          acc,
          bal == null ? MantissaAmount.zero() : bal,
          mem == null ? List.of() : List.copyOf(mem)
      );
    });
  }

  private void refreshAsync(Key key, UUID ownerUuid) {
    // Properly handle concurrent invalidate() calls during refresh
    inFlight.computeIfAbsent(key, ignored ->
        loadFresh(key.bankIdLower(), ownerUuid).thenApply(view -> {
          put(key, view);
          return view;
        }).whenComplete((r, e) -> {
          inFlight.remove(key);

          if (e == null) {
            // Only set refreshing to false on success
            Entry entry = byKey.get(key);
            if (entry != null) {
              entry.refreshing().set(false);
            }
          } else {
            // On error, reset refreshing flag to allow retry
            Entry entry = byKey.get(key);
            if (entry != null) {
              entry.refreshing().set(false);
            }
            System.err.println("Failed to refresh bank account " + key + ": " + e.getMessage());
          }
        })
    );
  }

  private void put(Key key, View view) {
    if (key == null || view == null || view.account() == null || view.account().getId() == null) return;

    Entry entry = Entry.of(view);
    byKey.put(key, entry);
    byAccountId.put(view.account().getId(), entry);

    maybeCleanupAsync();
  }

  private void maybeCleanupAsync() {
    int max = this.maxEntries;
    if (max <= 0) return; // disabled
    if (byKey.size() <= max) return;

    if (!cleanupRunning.compareAndSet(false, true)) return;

    // Run on same async chain as callers (DB layer is async anyway); cleanup is CPU-only + limited batch.
    CompletableFuture.runAsync(() -> {
      try {
        cleanupNow();
      } finally {
        cleanupRunning.set(false);
      }
    });
  }

  private void cleanupNow() {
    int max = this.maxEntries;
    if (max <= 0) return;

    int size = byKey.size();
    int over = size - max;
    if (over <= 0) return;

    int batch = Math.min(this.cleanupBatch, size);
    if (batch <= 0) return;

    // Sample up to "batch" entries (iteration over CHM is weakly consistent).
    ArrayList<java.util.Map.Entry<Key, Entry>> sample = new ArrayList<>(batch);
    int taken = 0;
    for (var e : byKey.entrySet()) {
      sample.add(e);
      taken++;
      if (taken >= batch) break;
    }

    // Sort by lastAccess (oldest first)
    sample.sort(Comparator.comparingLong(a -> a.getValue() == null ? 0L : a.getValue().lastAccessMs().get()));

    // Remove enough oldest to go below limit (up to sample size)
    int toRemove = Math.min(over, sample.size());
    for (int i = 0; i < toRemove; i++) {
      var ent = sample.get(i);
      Key key = ent.getKey();
      Entry entry = ent.getValue();
      if (key == null || entry == null) continue;

      // remove only if same instance (avoid racing with refresh/replace)
      boolean removed = byKey.remove(key, entry);
      if (!removed) continue;

      View v = entry.view();
      UUID accountId = v == null || v.account() == null ? null : v.account().getId();
      if (accountId != null) {
        byAccountId.remove(accountId, entry);
      }

      inFlight.remove(key);
    }
  }

  private static void touch(Entry e) {
    if (e == null) return;
    e.lastAccessMs().set(System.currentTimeMillis());
  }

  private boolean isExpired(Entry e) {
    if (e == null) return true;
    long ttl = this.ttlMs;
    if (ttl <= 0L) return false; // TTL disabled
    return (System.currentTimeMillis() - e.loadedAtMs()) > ttl;
  }

  private static int clamp(int value) {
    if (value < MIN_TTL_SECONDS) return MIN_TTL_SECONDS;
    return Math.min(value, MAX_TTL_SECONDS);
  }

  private static int clampMaxEntries(int value) {
    if (value < MIN_MAX_ENTRIES) return MIN_MAX_ENTRIES;
    return Math.min(value, MAX_MAX_ENTRIES);
  }

  private static int clampCleanupBatch(int value) {
    if (value < MIN_CLEANUP_BATCH) return MIN_CLEANUP_BATCH;
    return Math.min(value, MAX_CLEANUP_BATCH);
  }

  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
  }
}