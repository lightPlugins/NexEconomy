package io.nexstudios.nexeconomy.service.economy;

import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.EconomyBalanceEntity;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Dependencies({
    CurrencyRegistryService.class,
    EconomyPlayerCacheService.class,
    EconomyFlushService.class,
    EconomyRedisSyncService.class,
    EconomyRepository.class
})
public final class EconomyService implements Service {

  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyFlushService flush;
  private final EconomyRedisSyncService redisSync;
  private final EconomyRepository repo;

  public EconomyService(ServiceAccessor accessor) {
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.flush = accessor.getService(EconomyFlushService.class);
    this.redisSync = accessor.getService(EconomyRedisSyncService.class);
    this.repo = accessor.getService(EconomyRepository.class);
  }

  /**
   * Offline-capable balance read: works directly on DB via UUID.
   */
  public CompletableFuture<MantissaAmount> balance(@NotNull UUID playerId, @NotNull String currencyId) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));
    return repo.loadSingleBalance(playerId, cur, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  /**
   * Offline-capable add: updates RAM cache if player is online, otherwise writes directly to DB.
   * Always publishes a Redis invalidation so other servers reload the player's data.
   */
  public CompletableFuture<MantissaAmount> add(@NotNull UUID playerId, @NotNull String currencyId, @NotNull MantissaAmount delta) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    CurrencyDefinition def = currencies.currency(cur);
    MantissaAmount d = VaultMath.normalizeForCurrency(def, delta);

    // Online path: update RAM cache (flush + Redis handled by EconomyFlushService)
    EconomyPlayer cached = cache.getOnline(playerId);
    if (cached != null) {
      var lock = EconomyLocks.lockFor(playerId);
      lock.lock();
      try {
        EconomyPlayer.BalanceEntry entry = cached.getOrCreate(cur, MantissaAmount.zero());
        if (def != null && def.type() == CurrencyType.VAULT) {
          BigDecimal nextHuman = (entry.amount() == null ? BigDecimal.ZERO : entry.amount().toHuman()).add(d.toHuman());
          entry.set(MantissaAmount.of(VaultMath.clampVaultHuman(def, VaultMath.scaleVaultHuman(def, nextHuman)), 0));
        } else {
          entry.add(d);
        }
        flush.requestFlush(cached);
      } finally {
        lock.unlock();
      }
      return CompletableFuture.completedFuture(d);
    }

    // Offline path: write to DB, then publish Redis so other servers invalidate their cache
    return repo.applyDelta(playerId, cur, d, EconomyBalanceEntity.EconomyAccountType.PLAYER)
        .thenApply(result -> {
          redisSync.publishInvalidatePlayer(playerId);
          return result;
        });
  }

  /**
   * Offline-capable remove: updates RAM cache if player is online, otherwise checks DB first.
   * Always publishes a Redis invalidation on success.
   */
  public CompletableFuture<Boolean> remove(@NotNull UUID playerId, @NotNull String currencyId, @NotNull MantissaAmount delta) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    CurrencyDefinition def = currencies.currency(cur);
    MantissaAmount d = VaultMath.normalizeForCurrency(def, delta);
    if (d.isNegative() || d.compareTo(MantissaAmount.zero()) == 0) {
      return CompletableFuture.completedFuture(false);
    }

    // Online path: update RAM cache
    EconomyPlayer cached = cache.getOnline(playerId);
    if (cached != null) {
      var lock = EconomyLocks.lockFor(playerId);
      lock.lock();
      try {
        EconomyPlayer.BalanceEntry entry = cached.getOrCreate(cur, MantissaAmount.zero());
        MantissaAmount current = entry.amount() == null ? MantissaAmount.zero() : entry.amount();
        if (current.compareTo(d) < 0) return CompletableFuture.completedFuture(false);

        if (def != null && def.type() == CurrencyType.VAULT) {
          BigDecimal nextHuman = VaultMath.scaleVaultHuman(def, current.toHuman().subtract(d.toHuman()));
          entry.set(MantissaAmount.of(nextHuman, 0));
        } else {
          entry.subtract(d);
        }
        flush.requestFlush(cached);
      } finally {
        lock.unlock();
      }
      return CompletableFuture.completedFuture(true);
    }

    // Offline path: check DB balance first, then apply negative delta
    return repo.loadSingleBalance(
        playerId,
        cur,
        EconomyBalanceEntity.EconomyAccountType.PLAYER
    ).thenCompose(current -> {
      MantissaAmount c = current == null ? MantissaAmount.zero() : current;
      if (c.compareTo(d) < 0) return CompletableFuture.completedFuture(false);

      // Build negative delta for applyDelta
      MantissaAmount negative;
      if (def != null && def.type() == CurrencyType.VAULT) {
        negative = MantissaAmount.of(d.toHuman().negate(), 0);
      } else {
        negative = MantissaAmount.normalize(d).subtract(d.add(d)); // -d, mantissa-precise
      }

      return repo.applyDelta(
          playerId,
          cur,
          negative,
          EconomyBalanceEntity.EconomyAccountType.PLAYER
      ).thenApply(ignored -> {
        redisSync.publishInvalidatePlayer(playerId);
        return true;
      });
    });
  }

  public CompletableFuture<MantissaAmount> balance(@NotNull Player player, @NotNull String currencyId) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    return cache.loadOrCreateOnline(player).thenApply(econ -> {
      EconomyPlayer.BalanceEntry entry = econ.entry(cur);
      return entry == null || entry.amount() == null ? MantissaAmount.zero() : entry.amount();
    });
  }

  public CompletableFuture<Boolean> set(@NotNull Player target, @NotNull String currencyId, @NotNull MantissaAmount amount) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    CurrencyDefinition def = currencies.currency(cur);
    MantissaAmount value = VaultMath.normalizeForCurrency(def, amount);

    return cache.loadOrCreateOnline(target).thenApply(econ -> {
      var lock = EconomyLocks.lockFor(target.getUniqueId());
      lock.lock();
      try {
        econ.getOrCreate(cur, MantissaAmount.zero()).set(value);
        flush.requestFlush(econ);
        return true;
      } finally {
        lock.unlock();
      }
    });
  }

  public CompletableFuture<Boolean> add(@NotNull Player target, @NotNull String currencyId, @NotNull MantissaAmount delta) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    CurrencyDefinition def = currencies.currency(cur);
    MantissaAmount d = VaultMath.normalizeForCurrency(def, delta);

    return cache.loadOrCreateOnline(target).thenApply(econ -> {
      var lock = EconomyLocks.lockFor(target.getUniqueId());
      lock.lock();
      try {
        EconomyPlayer.BalanceEntry entry = econ.getOrCreate(cur, MantissaAmount.zero());

        if (def != null && def.type() == CurrencyType.VAULT) {
          BigDecimal nextHuman = entry.amount() == null ? BigDecimal.ZERO : entry.amount().toHuman();
          nextHuman = nextHuman.add(d.toHuman());
          entry.set(MantissaAmount.of(VaultMath.clampVaultHuman(def, nextHuman), 0));
        } else {
          entry.add(d);
        }

        flush.requestFlush(econ);
        return true;
      } finally {
        lock.unlock();
      }
    });
  }

  public CompletableFuture<Boolean> remove(@NotNull Player target, @NotNull String currencyId, @NotNull MantissaAmount delta) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    CurrencyDefinition def = currencies.currency(cur);
    MantissaAmount d = VaultMath.normalizeForCurrency(def, delta);

    return cache.loadOrCreateOnline(target).thenApply(econ -> {
      var lock = EconomyLocks.lockFor(target.getUniqueId());
      lock.lock();
      try {
        EconomyPlayer.BalanceEntry entry = econ.getOrCreate(cur, MantissaAmount.zero());
        MantissaAmount current = entry.amount() == null ? MantissaAmount.zero() : entry.amount();

        if (current.compareTo(d) < 0) return false;

        if (def != null && def.type() == CurrencyType.VAULT) {
          BigDecimal nextHuman = current.toHuman().subtract(d.toHuman());
          entry.set(MantissaAmount.of(VaultMath.scaleVaultHuman(def, nextHuman), 0));
        } else {
          entry.subtract(d);
        }

        flush.requestFlush(econ);
        return true;
      } finally {
        lock.unlock();
      }
    });
  }

  public CurrencyDefinition requireCurrency(String currencyId) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return null;
    return currencies.currency(cur);
  }

  public String defaultCurrencyIdOrNull() {
    String vaultId = currencies.vaultCurrencyId();
    if (vaultId != null) return vaultId;
    return currencies.currencies().stream().findFirst().map(CurrencyDefinition::id).orElse(null);
  }

  private static String normalize(String currency) {
    return currency == null ? "" : currency.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Provider-facing deposit that returns the actually applied delta (may be capped by max-balance).
   * Works for online (RAM+flush) and offline (DB).
   */
  public CompletableFuture<MantissaAmount> providerDeposit(
      UUID accountId,
      String currencyId,
      MantissaAmount requestedDelta,
      EconomyBalanceEntity.EconomyAccountType accountType
  ) {
    String cur = normalize(currencyId);
    if (accountId == null) return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.INVALID_PLAYER));
    if (cur.isBlank()) return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.CURRENCY_NOT_CONFIGURED));

    CurrencyDefinition def = currencies.currency(cur);
    if (def == null) return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.CURRENCY_NOT_FOUND));

    EconomyBalanceEntity.EconomyAccountType type = accountType == null
        ? EconomyBalanceEntity.EconomyAccountType.PLAYER
        : accountType;

    MantissaAmount delta = VaultMath.normalizeForCurrency(def, requestedDelta);
    if (delta.isNegative() || delta.compareTo(MantissaAmount.zero()) == 0) {
      return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.INVALID_AMOUNT));
    }

    EconomyPlayer cached = (type == EconomyBalanceEntity.EconomyAccountType.TOWNY)
        ? cache.getTowny(accountId)
        : cache.getOnline(accountId);

    if (cached != null) {
      EconomyPlayer.BalanceEntry entry = cached.getOrCreate(cur, MantissaAmount.zero());
      MantissaAmount current = entry.amount() == null ? MantissaAmount.zero() : entry.amount();

      MantissaAmount allowed = VaultMath.capDeltaToMax(def, current, delta);
      if (allowed.compareTo(MantissaAmount.zero()) == 0) {
        return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.MAX_BALANCE_REACHED));
      }

      entry.add(allowed);
      if (type == EconomyBalanceEntity.EconomyAccountType.TOWNY) {
        flush.requestFlushTowny(cached);
      } else {
        flush.requestFlush(cached);
      }
      return CompletableFuture.completedFuture(allowed);
    }

    return repo.loadSingleBalance(accountId, cur, type).thenCompose(current -> {
      MantissaAmount curBal = current == null ? MantissaAmount.zero() : current;
      MantissaAmount allowed = VaultMath.capDeltaToMax(def, curBal, delta);

      if (allowed.compareTo(MantissaAmount.zero()) == 0) {
        return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.MAX_BALANCE_REACHED));
      }

      return repo.applyDelta(accountId, cur, allowed, type).thenApply(ignored -> {
        redisSync.publishInvalidateAccount(accountId, type);
        return allowed;
      });
    }).exceptionally(ex -> {
      if (ex instanceof EconomyException ee) throw ee;
      throw new EconomyException(EconomyErrorCode.DB_ERROR, ex);
    });
  }

  public CompletableFuture<MantissaAmount> providerDeposit(UUID playerId, String currencyId, MantissaAmount requestedDelta) {
    return providerDeposit(playerId, currencyId, requestedDelta, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  /**
   * Provider-facing withdraw that is atomic and returns the actually withdrawn delta.
   */
  public CompletableFuture<MantissaAmount> providerWithdraw(
      UUID accountId,
      String currencyId,
      MantissaAmount requestedDelta,
      EconomyBalanceEntity.EconomyAccountType accountType
  ) {
    String cur = normalize(currencyId);
    if (accountId == null) return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.INVALID_PLAYER));
    if (cur.isBlank()) return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.CURRENCY_NOT_CONFIGURED));

    CurrencyDefinition def = currencies.currency(cur);
    if (def == null) return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.CURRENCY_NOT_FOUND));

    EconomyBalanceEntity.EconomyAccountType type = accountType == null
        ? EconomyBalanceEntity.EconomyAccountType.PLAYER
        : accountType;

    MantissaAmount delta = VaultMath.normalizeForCurrency(def, requestedDelta);
    if (delta.isNegative() || delta.compareTo(MantissaAmount.zero()) == 0) {
      return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.INVALID_AMOUNT));
    }

    EconomyPlayer cached = (type == EconomyBalanceEntity.EconomyAccountType.TOWNY)
        ? cache.getTowny(accountId)
        : cache.getOnline(accountId);

    if (cached != null) {
      EconomyPlayer.BalanceEntry entry = cached.getOrCreate(cur, MantissaAmount.zero());
      MantissaAmount current = entry.amount() == null ? MantissaAmount.zero() : entry.amount();

      if (current.compareTo(delta) < 0) {
        return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.INSUFFICIENT_FUNDS));
      }

      entry.subtract(delta);
      if (type == EconomyBalanceEntity.EconomyAccountType.TOWNY) {
        flush.requestFlushTowny(cached);
      } else {
        flush.requestFlush(cached);
      }
      return CompletableFuture.completedFuture(delta);
    }

    return repo.tryWithdraw(accountId, cur, delta, type).thenCompose(res -> {
      if (res == null || !res.success()) {
        return CompletableFuture.failedFuture(new EconomyException(EconomyErrorCode.INSUFFICIENT_FUNDS));
      }
      redisSync.publishInvalidateAccount(accountId, type);
      return CompletableFuture.completedFuture(delta);
    }).exceptionally(ex -> {
      if (ex instanceof EconomyException ee) throw ee;
      throw new EconomyException(EconomyErrorCode.DB_ERROR, ex);
    });
  }

  public CompletableFuture<MantissaAmount> providerWithdraw(UUID playerId, String currencyId, MantissaAmount requestedDelta) {
    return providerWithdraw(playerId, currencyId, requestedDelta, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  // ─── Atomic Transfer ──────────────────────────────────────────────────────

  public record TransferResult(boolean success, MantissaAmount paid, boolean capped) {
    public static TransferResult failed() { return new TransferResult(false, MantissaAmount.zero(), false); }
    public static TransferResult ok(MantissaAmount paid, boolean capped) { return new TransferResult(true, paid, capped); }
  }

  /**
   * Atomically transfers {@code amount} of {@code currencyId} from {@code sender} to {@code target}.
   * Both players must be online. The transfer is lock-ordered to prevent deadlocks.
   * Max-balance of the target is respected; the result contains the actually transferred amount.
   * Flushes both sides and triggers Redis invalidation via the flush pipeline.
   */
  public CompletableFuture<TransferResult> transfer(
      @NotNull Player sender,
      @NotNull Player target,
      @NotNull String currencyId,
      @NotNull MantissaAmount amount
  ) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    CurrencyDefinition def = currencies.currency(cur);
    MantissaAmount parsed = VaultMath.normalizeForCurrency(def, amount);
    BigDecimal maxBalance = def == null ? null : def.maxBalance();

    return cache.loadOrCreateOnline(sender).thenCompose(senderEcon ->
        cache.loadOrCreateOnline(target).thenApply(targetEcon -> {
          TransferResult res = applyAtomicTransfer(senderEcon, targetEcon, cur, parsed, maxBalance);
          if (res.success() && res.paid().compareTo(MantissaAmount.zero()) > 0) {
            flush.requestFlush(senderEcon);
            flush.requestFlush(targetEcon);
          }
          return res;
        })
    );
  }

  private static TransferResult applyAtomicTransfer(
      EconomyPlayer sender,
      EconomyPlayer target,
      String currencyId,
      MantissaAmount requestedDelta,
      BigDecimal maxBalanceHuman
  ) {
    if (sender == null || target == null) return TransferResult.failed();
    if (currencyId == null || currencyId.isBlank()) return TransferResult.failed();
    if (requestedDelta == null || requestedDelta.isNegative()) return TransferResult.failed();

    UUID a = sender.uuid();
    UUID b = target.uuid();
    if (a == null || b == null) return TransferResult.failed();

    // Lock in consistent UUID order to prevent deadlocks
    var first  = a.compareTo(b) <= 0 ? a : b;
    var second = a.compareTo(b) <= 0 ? b : a;
    var l1 = EconomyLocks.lockFor(first);
    var l2 = EconomyLocks.lockFor(second);

    l1.lock();
    try {
      l2.lock();
      try {
        EconomyPlayer.BalanceEntry from = sender.getOrCreate(currencyId, MantissaAmount.zero());
        EconomyPlayer.BalanceEntry to   = target.getOrCreate(currencyId, MantissaAmount.zero());

        MantissaAmount senderCurrent = from.amount() == null ? MantissaAmount.zero() : from.amount();
        if (senderCurrent.compareTo(requestedDelta) < 0) return TransferResult.failed();

        MantissaAmount delta = requestedDelta;
        boolean capped = false;

        // Apply target max-balance if configured (>= 0; -1 = unlimited)
        if (maxBalanceHuman != null && maxBalanceHuman.compareTo(BigDecimal.ZERO) >= 0) {
          MantissaAmount targetCurrent = to.amount() == null ? MantissaAmount.zero() : to.amount();
          BigDecimal remaining = maxBalanceHuman.subtract(targetCurrent.toHuman());
          if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return TransferResult.ok(MantissaAmount.zero(), true);
          }
          BigDecimal allowed = requestedDelta.toHuman().min(remaining);
          if (allowed.compareTo(BigDecimal.ZERO) <= 0) {
            return TransferResult.ok(MantissaAmount.zero(), true);
          }
          MantissaAmount allowedAmount = MantissaAmount.of(allowed, 0);
          if (allowedAmount.compareTo(requestedDelta) < 0) capped = true;
          delta = allowedAmount;
        }

        if (delta.compareTo(MantissaAmount.zero()) == 0) {
          return TransferResult.ok(MantissaAmount.zero(), true);
        }

        from.subtract(delta);
        to.add(delta);
        return TransferResult.ok(delta, capped);
      } finally {
        l2.unlock();
      }
    } finally {
      l1.unlock();
    }
  }
}

