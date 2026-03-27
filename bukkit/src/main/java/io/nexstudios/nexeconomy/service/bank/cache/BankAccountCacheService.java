package io.nexstudios.nexeconomy.service.bank.cache;

import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankAccountEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

  private record Entry(View view, long loadedAtMs, AtomicBoolean refreshing) {
    static Entry of(View view) {
      return new Entry(view, System.currentTimeMillis(), new AtomicBoolean(false));
    }
  }

  private static final int DEFAULT_TTL_SECONDS = 30;
  private static final int MIN_TTL_SECONDS = 0;
  private static final int MAX_TTL_SECONDS = 300;

  private final BankRepositoryService repo;
  private final FileConfiguration settings;

  private volatile long ttlMs = DEFAULT_TTL_SECONDS * 1000L;

  private final ConcurrentHashMap<Key, Entry> byKey = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Entry> byAccountId = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<Key, CompletableFuture<View>> inFlight = new ConcurrentHashMap<>();

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
  }

  public View get(String bankIdLower, UUID ownerUuid) {
    if (bankIdLower == null || bankIdLower.isBlank() || ownerUuid == null) return null;

    Key key = new Key(normalize(bankIdLower), ownerUuid);
    Entry e = byKey.get(key);
    if (e == null) return null;

    // refresh in background if expired (serve stale)
    if (isExpired(e) && e.refreshing().compareAndSet(false, true)) {
      refreshAsync(key, ownerUuid);
    }

    return e.view();
  }

  public View get(UUID bankAccountId) {
    if (bankAccountId == null) return null;

    Entry e = byAccountId.get(bankAccountId);
    if (e == null) return null;

    // refresh in background if expired (serve stale)
    View v = e.view();
    if (v != null && v.account() != null && v.account().getBankIdLower() != null && v.account().getOwnerUuid() != null) {
      Key key = new Key(normalize(v.account().getBankIdLower()), v.account().getOwnerUuid());
      if (isExpired(e) && e.refreshing().compareAndSet(false, true)) {
        refreshAsync(key, v.account().getOwnerUuid());
      }
    }

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
  }

  public void invalidate(String bankIdLower, UUID ownerUuid) {
    if (bankIdLower == null || bankIdLower.isBlank() || ownerUuid == null) return;

    Key key = new Key(normalize(bankIdLower), ownerUuid);
    Entry e = byKey.remove(key);
    inFlight.remove(key);

    if (e != null && e.view() != null && e.view().account() != null && e.view().account().getId() != null) {
      byAccountId.remove(e.view().account().getId(), e);
    }
  }

  public CompletableFuture<View> loadOrCreate(String bankIdLower, UUID ownerUuid) {
    String bank = normalize(bankIdLower);
    if (bank.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankIdLower is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));

    Key key = new Key(bank, ownerUuid);

    Entry existing = byKey.get(key);
    if (existing != null) {
      if (isExpired(existing) && existing.refreshing().compareAndSet(false, true)) {
        refreshAsync(key, ownerUuid);
      }
      return CompletableFuture.completedFuture(existing.view());
    }

    CompletableFuture<View> inflight = inFlight.get(key);
    if (inflight != null) return inflight;

    return inFlight.computeIfAbsent(key, ignored -> {
      CompletableFuture<View> f = loadFresh(bank, ownerUuid).thenApply(view -> {
        put(key, view);
        return view;
      });

      return f.whenComplete((r, e) -> inFlight.remove(key));
    });
  }

  private CompletableFuture<View> loadFresh(String bankIdLower, UUID ownerUuid) {
    return repo.createAccountIfMissing(bankIdLower, ownerUuid).thenCompose(acc -> {
      if (acc == null || acc.getId() == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("bank account not available"));
      }

      // Ensure owner is always a member (same behavior as DefaultBankService.getOrCreateAccount)
      UUID realOwner = acc.getOwnerUuid();
      if (realOwner != null) {
        return repo.upsertMember(acc.getId(), realOwner, realOwner, "owner").thenCompose(ignored -> loadBalanceAndMembers(acc));
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
    // Single-flight through inFlight (re-uses same mechanism)
    inFlight.computeIfAbsent(key, ignored ->
        loadFresh(key.bankIdLower(), ownerUuid).thenApply(view -> {
          put(key, view);
          return view;
        }).whenComplete((r, e) -> {
          inFlight.remove(key);

          Entry entry = byKey.get(key);
          if (entry != null) {
            entry.refreshing().set(false);
          }
        })
    );
  }

  private void put(Key key, View view) {
    if (key == null || view == null || view.account() == null || view.account().getId() == null) return;

    Entry entry = Entry.of(view);
    byKey.put(key, entry);
    byAccountId.put(view.account().getId(), entry);
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

  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
  }
}