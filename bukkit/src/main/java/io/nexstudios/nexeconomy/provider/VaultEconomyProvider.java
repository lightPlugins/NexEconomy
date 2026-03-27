package io.nexstudios.nexeconomy.provider;

import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.EconomyErrorCode;
import io.nexstudios.nexeconomy.service.economy.EconomyException;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.EconomyService;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import org.bukkit.Bukkit;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.nexstudios.serviceregistry.di.Service;
import lombok.extern.slf4j.Slf4j;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@SuppressWarnings("deprecation")
public final class VaultEconomyProvider implements Economy, Service {

  private static final long JOIN_TIMEOUT_MS = 500L;
  private static final long MAIN_THREAD_WARN_COOLDOWN_MS = 5_000L;
  private static final AtomicLong LAST_MAIN_THREAD_WARN_MS = new AtomicLong(0L);

  private final LoggerService logger;

  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyFlushService flush;
  private final EconomyRepository repo;
  private final EconomyService economy;

  public VaultEconomyProvider(
      LoggerService logger,
      CurrencyRegistryService currencies,
      EconomyPlayerCacheService cache,
      EconomyFlushService flush,
      EconomyRepository repo,
      EconomyService economy
  ) {
    this.logger = logger;
    this.currencies = currencies;
    this.cache = cache;
    this.flush = flush;
    this.repo = repo;
    this.economy = economy;
  }

  @Override
  public boolean isEnabled() {
    return vaultDef() != null;
  }

  @Override
  public String getName() {
    return "NexEconomy";
  }

  @Override
  public int fractionalDigits() {
    CurrencyDefinition def = vaultDef();
    return def == null ? 0 : def.fractionDigits();
  }

  @Override
  public String currencyNamePlural() {
    CurrencyDefinition def = vaultDef();
    return def == null ? "Money" : def.symbolPlural();
  }

  @Override
  public String currencyNameSingular() {
    CurrencyDefinition def = vaultDef();
    return def == null ? "Money" : def.symbolSingular();
  }

  @Override
  public boolean hasAccount(OfflinePlayer player) {
    return player != null;
  }

  @Override
  public boolean hasAccount(OfflinePlayer player, String worldName) {
    return hasAccount(player);
  }

  @Override
  public String format(double amount) {
    int fd = fractionalDigits();
    if (fd < 0) fd = 0;
    if (fd > 8) fd = 8;
    return BigDecimal.valueOf(amount)
        .setScale(fd, RoundingMode.HALF_UP)
        .toPlainString();
  }

  @Override
  public double getBalance(OfflinePlayer player) {
    if (player == null) return 0D;

    String vaultId = vaultId();
    if (vaultId == null) return 0D;

    UUID uuid = player.getUniqueId();

    EconomyPlayer cached = cache.getOnline(uuid);
    if (cached != null) {
      EconomyPlayer.BalanceEntry entry = cached.entry(vaultId);
      MantissaAmount a = entry == null ? MantissaAmount.zero() : entry.amount();
      return a == null ? 0D : a.toDoubleApprox();
    }

    warnIfMainThreadBlocking("getBalance", uuid);

    try {
      var map = joinWithTimeout(repo.loadBalances(uuid, Set.of(vaultId)), "getBalance", uuid);
      MantissaAmount a = map.get(vaultId);
      return a == null ? 0D : a.toDoubleApprox();
    } catch (Exception ex) {
      return 0D;
    }
  }

  @Override
  public double getBalance(OfflinePlayer player, String world) {
    return getBalance(player);
  }

  @Override
  public boolean has(OfflinePlayer player, double amount) {
    if (player == null) return false;
    if (amount < 0) return false;

    String vaultId = vaultId();
    if (vaultId == null) return false;

    UUID uuid = player.getUniqueId();

    int fd = fractionalDigits();
    BigDecimal neededHuman = BigDecimal.valueOf(amount).setScale(fd, RoundingMode.DOWN);
    MantissaAmount needed = MantissaAmount.of(neededHuman, 0);

    EconomyPlayer cached = cache.getOnline(uuid);
    if (cached != null) {
      EconomyPlayer.BalanceEntry entry = cached.entry(vaultId);
      MantissaAmount cur = entry == null || entry.amount() == null ? MantissaAmount.zero() : entry.amount();
      return cur.compareTo(needed) >= 0;
    }

    warnIfMainThreadBlocking("has", uuid);

    try {
      var map = joinWithTimeout(repo.loadBalances(uuid, Set.of(vaultId)), "has", uuid);
      MantissaAmount cur = map.get(vaultId);
      if (cur == null) cur = MantissaAmount.zero();
      return cur.compareTo(needed) >= 0;
    } catch (Exception ex) {
      return false;
    }
  }

  @Override
  public boolean has(OfflinePlayer player, String worldName, double amount) {
    return has(player, amount);
  }

  @Override
  public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
    if (player == null) {
      return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.INVALID_PLAYER.name());
    }

    String vaultId = vaultId();
    if (vaultId == null) {
      return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.CURRENCY_NOT_CONFIGURED.name());
    }

    int fd = fractionalDigits();
    BigDecimal requestedHuman = BigDecimal.valueOf(amount).setScale(fd, RoundingMode.DOWN);
    MantissaAmount delta = MantissaAmount.of(requestedHuman, 0);

    if (delta.compareTo(MantissaAmount.zero()) <= 0) {
      return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.INVALID_AMOUNT.name());
    }

    UUID uuid = player.getUniqueId();

    // Fast-path: online cached => RAM + flush (no join)
    if (cache.getOnline(uuid) != null) {
      try {
        MantissaAmount withdrawn = joinWithTimeout(economy.providerWithdraw(uuid, vaultId, delta), "withdrawPlayer", uuid);
        double newBal = getBalance(player);
        return new EconomyResponse(withdrawn.toHuman().doubleValue(), newBal, EconomyResponse.ResponseType.SUCCESS, null);
      } catch (Exception ex) {
        EconomyErrorCode code = unwrapCode(ex, EconomyErrorCode.DB_ERROR);
        return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, code.name());
      }
    }

    // Cache miss => likely DB path => warn if on main thread
    warnIfMainThreadBlocking("withdrawPlayer", uuid);

    try {
      MantissaAmount withdrawn = joinWithTimeout(economy.providerWithdraw(uuid, vaultId, delta), "withdrawPlayer", uuid);
      double newBal = getBalance(player);
      return new EconomyResponse(withdrawn.toHuman().doubleValue(), newBal, EconomyResponse.ResponseType.SUCCESS, null);
    } catch (Exception ex) {
      EconomyErrorCode code = unwrapCode(ex, EconomyErrorCode.DB_ERROR);
      return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, code.name());
    }
  }

  @Override
  public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
    return withdrawPlayer(player, amount);
  }

  @Override
  public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
    if (player == null) {
      return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.INVALID_PLAYER.name());
    }

    String vaultId = vaultId();
    if (vaultId == null) {
      return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.CURRENCY_NOT_CONFIGURED.name());
    }

    int fd = fractionalDigits();
    BigDecimal requestedHuman = BigDecimal.valueOf(amount).setScale(fd, RoundingMode.DOWN);
    MantissaAmount delta = MantissaAmount.of(requestedHuman, 0);

    if (delta.compareTo(MantissaAmount.zero()) <= 0) {
      return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.INVALID_AMOUNT.name());
    }

    UUID uuid = player.getUniqueId();

    // Fast-path: online cached => RAM + flush (no join)
    if (cache.getOnline(uuid) != null) {
      try {
        MantissaAmount applied = joinWithTimeout(economy.providerDeposit(uuid, vaultId, delta), "depositPlayer", uuid);
        double newBal = getBalance(player);
        return new EconomyResponse(applied.toHuman().doubleValue(), newBal, EconomyResponse.ResponseType.SUCCESS, null);
      } catch (Exception ex) {
        EconomyErrorCode code = unwrapCode(ex, EconomyErrorCode.DB_ERROR);
        return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, code.name());
      }
    }

    // Cache miss => likely DB path => warn if on main thread
    warnIfMainThreadBlocking("depositPlayer", uuid);

    try {
      MantissaAmount applied = joinWithTimeout(economy.providerDeposit(uuid, vaultId, delta), "depositPlayer", uuid);
      double newBal = getBalance(player);
      return new EconomyResponse(applied.toHuman().doubleValue(), newBal, EconomyResponse.ResponseType.SUCCESS, null);
    } catch (Exception ex) {
      EconomyErrorCode code = unwrapCode(ex, EconomyErrorCode.DB_ERROR);
      return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, code.name());
    }
  }

  @Override
  public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
    return depositPlayer(player, amount);
  }

  // --- Bank not supported ---
  @Override public boolean hasBankSupport() { return false; }
  @Override public EconomyResponse createBank(String name, String player) { return notSupported(); }
  @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return notSupported(); }
  @Override public EconomyResponse deleteBank(String name) { return notSupported(); }
  @Override public EconomyResponse bankBalance(String name) { return notSupported(); }
  @Override public EconomyResponse bankHas(String name, double amount) { return notSupported(); }
  @Override public EconomyResponse bankWithdraw(String name, double amount) { return notSupported(); }
  @Override public EconomyResponse bankDeposit(String name, double amount) { return notSupported(); }
  @Override public EconomyResponse isBankOwner(String name, String playerName) { return notSupported(); }
  @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return notSupported(); }
  @Override public EconomyResponse isBankMember(String name, String playerName) { return notSupported(); }
  @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return notSupported(); }
  @Override public List<String> getBanks() { return List.of(); }

  @Override
  public boolean createPlayerAccount(OfflinePlayer player) {
    if (player == null) return false;

    Player online = player.getPlayer();
    if (online == null || !online.isOnline()) return false;

    try {
      EconomyPlayer econ = joinWithTimeout(cache.loadOrCreateOnline(online), "createPlayerAccount", online.getUniqueId());
      if (flush != null && econ != null) flush.requestFlush(econ);
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  @Override
  public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
    return createPlayerAccount(player);
  }

  private static EconomyResponse notSupported() {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "not supported");
  }

  private String vaultId() {
    return currencies.vaultCurrencyId();
  }

  private CurrencyDefinition vaultDef() {
    String id = vaultId();
    return id == null ? null : currencies.currency(id);
  }

  private static EconomyErrorCode unwrapCode(Throwable ex, EconomyErrorCode fallback) {
    Throwable t = ex;
    for (int i = 0; i < 8 && t != null; i++) {
      if (t instanceof EconomyException ee && ee.code() != null) return ee.code();
      t = t.getCause();
    }
    return fallback == null ? EconomyErrorCode.DB_ERROR : fallback;
  }

  private <T> T joinWithTimeout(java.util.concurrent.CompletableFuture<T> future, String op, UUID uuid) {
    try {
      return future.orTimeout(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS).join();
    } catch (CompletionException ex) {
      Throwable root = ex.getCause() == null ? ex : ex.getCause();
      if (root instanceof java.util.concurrent.TimeoutException) {
        if (logger != null) {
          logger.logger().warning("VaultEconomyProvider: timeout after " + JOIN_TIMEOUT_MS + "ms op=" + op + " uuid=" + uuid);
        }
        throw new EconomyException(EconomyErrorCode.DB_ERROR, root);
      }
      throw ex;
    }
  }

  private void warnIfMainThreadBlocking(String op, UUID uuid) {
    if (!Bukkit.isPrimaryThread()) return;

    long now = System.currentTimeMillis();
    long last = LAST_MAIN_THREAD_WARN_MS.get();
    if ((now - last) < MAIN_THREAD_WARN_COOLDOWN_MS) return;
    if (!LAST_MAIN_THREAD_WARN_MS.compareAndSet(last, now)) return;

    if (logger != null) {
      logger.logger().warning(
          "VaultEconomyProvider: main-thread blocking Vault call (cache miss). op=" + op + " uuid=" + uuid +
              " timeout=" + JOIN_TIMEOUT_MS + "ms"
      );
    }
  }

  // Legacy string-based overloads: unsupported
  @Override public boolean hasAccount(String playerName) { return false; }
  @Override public boolean hasAccount(String playerName, String worldName) { return false; }
  @Override public double getBalance(String playerName) { return 0D; }
  @Override public double getBalance(String playerName, String world) { return 0D; }
  @Override public boolean has(String playerName, double amount) { return false; }
  @Override public boolean has(String playerName, String worldName, double amount) { return false; }
  @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return notSupported(); }
  @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return notSupported(); }
  @Override public EconomyResponse depositPlayer(String playerName, double amount) { return notSupported(); }
  @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return notSupported(); }
  @Override public boolean createPlayerAccount(String playerName) { return false; }
  @Override public boolean createPlayerAccount(String playerName, String worldName) { return false; }
}