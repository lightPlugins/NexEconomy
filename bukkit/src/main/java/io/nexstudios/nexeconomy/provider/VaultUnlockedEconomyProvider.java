package io.nexstudios.nexeconomy.provider;

import io.nexstudios.nexeconomy.service.economy.*;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.EconomyBalanceEntity;
import io.nexstudios.serviceregistry.di.Service;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import org.bukkit.Bukkit;

public final class VaultUnlockedEconomyProvider implements Economy, Service {

  private static final long JOIN_TIMEOUT_MS = 500L;
  private static final long MAIN_THREAD_WARN_COOLDOWN_MS = 5_000L;
  private static final AtomicLong LAST_MAIN_THREAD_WARN_MS = new AtomicLong(0L);

  private final LoggerService logger;

  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyRepository repo;
  private final EconomyService economy;

  /**
   * Vault2 works with BigDecimal, so we can allow larger values than the double-safe limit used in Vault v1.
   * This is a safety cap to prevent accidental insane values, not the configured currency max-balance.
   */
  private static final BigDecimal VAULT2_HARD_CAP_ABS = new BigDecimal("1000000000000000000000000000"); // 1e27

  private final ConcurrentHashMap<UUID, String> accountNames = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Boolean> accountIsPlayer = new ConcurrentHashMap<>();

  public VaultUnlockedEconomyProvider(
      LoggerService logger,
      CurrencyRegistryService currencies,
      EconomyPlayerCacheService cache,
      EconomyRepository repo,
      EconomyService economy
  ) {
    this.logger = logger;
    this.currencies = currencies;
    this.cache = cache;
    this.repo = repo;
    this.economy = economy;
  }

  @Override
  public boolean isEnabled() {
    return vaultDef() != null;
  }

  @Override
  public @NotNull String getName() {
    return "NexEconomy";
  }

  @Override
  public boolean hasSharedAccountSupport() {
    return false;
  }

  @Override
  public boolean hasMultiCurrencySupport() {
    return false;
  }

  @Override
  public int fractionalDigits(@NotNull String pluginName) {
    CurrencyDefinition def = vaultDef();
    return def == null ? 0 : clampFractionDigits(def.fractionDigits());
  }

  @Override
  public int fractionalDigits(@NotNull String pluginName, @NotNull String currency) {
    if (!hasCurrency(currency)) return 0;
    return fractionalDigits(pluginName);
  }

  @Override
  public @NotNull String format(@NotNull BigDecimal amount) {
    return format(getName(), amount);
  }

  @Override
  public @NotNull String format(@NotNull String pluginName, @NotNull BigDecimal amount) {
    int fd = fractionalDigits(pluginName);
    return amount.setScale(fd, RoundingMode.HALF_UP).toPlainString();
  }

  @Override
  public @NotNull String format(@NotNull BigDecimal amount, @NotNull String currency) {
    return format(getName(), amount, currency);
  }

  @Override
  public @NotNull String format(@NotNull String pluginName, @NotNull BigDecimal amount, @NotNull String currency) {
    if (!hasCurrency(currency)) return format(pluginName, amount);
    int fd = fractionalDigits(pluginName, currency);
    return amount.setScale(fd, RoundingMode.HALF_UP).toPlainString();
  }

  @Override
  public boolean hasCurrency(@NotNull String currency) {
    String id = vaultId();
    return id != null && !id.isBlank() && id.equalsIgnoreCase(currency);
  }

  @Override
  public @NotNull String getDefaultCurrency(@NotNull String pluginName) {
    String id = vaultId();
    return id == null ? "" : id;
  }

  @Override
  public @NotNull String defaultCurrencyNamePlural(@NotNull String pluginName) {
    CurrencyDefinition def = vaultDef();
    return def == null ? "Money" : def.symbolPlural();
  }

  @Override
  public @NotNull String defaultCurrencyNameSingular(@NotNull String pluginName) {
    CurrencyDefinition def = vaultDef();
    return def == null ? "Money" : def.symbolSingular();
  }

  @Override
  public @NotNull Collection<String> currencies() {
    String id = vaultId();
    return id == null || id.isBlank() ? List.of() : List.of(id);
  }

  @Override
  public boolean createAccount(@NotNull UUID accountID, @NotNull String name) {
    return createAccount(accountID, name, true);
  }

  @Override
  public boolean createAccount(@NotNull UUID accountID, @NotNull String name, boolean player) {
    if (name.isBlank()) return false;

    accountNames.put(accountID, name);
    accountIsPlayer.put(accountID, player);
    return true;
  }

  @Override
  public boolean createAccount(@NotNull UUID accountID, @NotNull String name, @NotNull String worldName) {
    return createAccount(accountID, name, worldName, true);
  }

  @Override
  public boolean createAccount(@NotNull UUID accountID, @NotNull String name, @NotNull String worldName, boolean player) {
    // worldName is ignored in this implementation (single economy context).
    return createAccount(accountID, name, player);
  }

  @Override
  public @NotNull Map<UUID, String> getUUIDNameMap() {
    return Map.copyOf(accountNames);
  }

  @Override
  public Optional<String> getAccountName(@NotNull UUID accountID) {
    return Optional.ofNullable(accountNames.get(accountID));
  }

  @Override
  public boolean hasAccount(@NotNull UUID accountID) {

    String cur = vaultId();
    if (cur == null || cur.isBlank()) return false;

    EconomyBalanceEntity.EconomyAccountType type = resolveAccountType(accountID);

    // Player accounts: if online cached, treat as existing
    if (type == EconomyBalanceEntity.EconomyAccountType.PLAYER) {
      EconomyPlayer econ = cache.getOnline(accountID);
      if (econ != null) return true;
    }

    // Otherwise verify existence in DB (accountType-aware)
    try {
      return repo.hasBalanceRow(accountID, cur, type).join();
    } catch (Exception ex) {
      return false;
    }
  }

  @Override
  public boolean hasAccount(@NotNull UUID accountID, @NotNull String worldName) {
    return hasAccount(accountID);
  }

  @Override
  public boolean renameAccount(@NotNull UUID accountID, @NotNull String name) {
    if (name.isBlank()) return false;
    accountNames.put(accountID, name);
    return true;
  }

  @Override
  public boolean renameAccount(@NotNull String plugin, @NotNull UUID accountID, @NotNull String name) {
    return renameAccount(accountID, name);
  }

  @Override
  public boolean deleteAccount(@NotNull String plugin, @NotNull UUID accountID) {
    return false;
  }

  @Override
  public boolean accountSupportsCurrency(@NotNull String plugin, @NotNull UUID accountID, @NotNull String currency) {
    return hasCurrency(currency);
  }

  @Override
  public boolean accountSupportsCurrency(@NotNull String plugin, @NotNull UUID accountID, @NotNull String currency, @NotNull String world) {
    return hasCurrency(currency);
  }

  @Override
  public @NotNull BigDecimal getBalance(@NotNull String pluginName, @NotNull UUID accountID) {
    return getBalance(pluginName, accountID, "", getDefaultCurrency(pluginName));
  }

  @Override
  public @NotNull BigDecimal getBalance(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String world) {
    return getBalance(pluginName, accountID, world, getDefaultCurrency(pluginName));
  }

  @Override
  public @NotNull BigDecimal getBalance(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull String currency) {
    String cur = normalizeCurrency(currency);
    if (!hasCurrency(cur)) return BigDecimal.ZERO;

    EconomyBalanceEntity.EconomyAccountType type = resolveAccountType(accountID);

    // Player accounts: if online cached, return RAM value
    if (type == EconomyBalanceEntity.EconomyAccountType.PLAYER) {
      EconomyPlayer econ = cache.getOnline(accountID);
      if (econ != null) {
        EconomyPlayer.BalanceEntry entry = econ.entry(cur);
        MantissaAmount a = entry == null ? MantissaAmount.zero() : entry.amount();
        return a == null ? BigDecimal.ZERO : a.toHuman();
      }
    }

    try {
      MantissaAmount a = repo.loadSingleBalance(accountID, cur, type).join();
      a = a == null ? MantissaAmount.zero() : a;
      return a.toHuman();
    } catch (Exception ex) {
      return BigDecimal.ZERO;
    }
  }

  @Override
  public boolean has(@NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
    return has(pluginName, accountID, "", getDefaultCurrency(pluginName), amount);
  }

  @Override
  public boolean has(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull BigDecimal amount) {
    return has(pluginName, accountID, worldName, getDefaultCurrency(pluginName), amount);
  }

  @Override
  public boolean has(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull String currency, @NotNull BigDecimal amount) {
    if (amount.compareTo(BigDecimal.ZERO) < 0) return false;
    return getBalance(pluginName, accountID, worldName, currency).compareTo(amount) >= 0;
  }

  @Override
  public @NotNull EconomyResponse withdraw(@NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
    return withdraw(pluginName, accountID, "", getDefaultCurrency(pluginName), amount);
  }

  @Override
  public @NotNull EconomyResponse withdraw(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull BigDecimal amount) {
    return withdraw(pluginName, accountID, worldName, getDefaultCurrency(pluginName), amount);
  }

  @Override
  public @NotNull EconomyResponse withdraw(
      @NotNull String pluginName,
      @NotNull UUID accountID,
      @NotNull String worldName,
      @NotNull String currency,
      @NotNull BigDecimal amount
  ) {
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      return new EconomyResponse(amount, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.INVALID_AMOUNT.name());
    }

    String cur = normalizeCurrency(currency);
    if (!hasCurrency(cur)) {
      return new EconomyResponse(amount, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.CURRENCY_NOT_CONFIGURED.name());
    }

    BigDecimal safeAmount = clampAbs(amount);
    if (safeAmount.compareTo(BigDecimal.ZERO) <= 0) {
      return new EconomyResponse(amount, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.INVALID_AMOUNT.name());
    }

    EconomyBalanceEntity.EconomyAccountType type = resolveAccountType(accountID);
    MantissaAmount delta = MantissaAmount.of(safeAmount, 0);

    // Warn if cache miss and we're on main thread
    boolean cached = (type == EconomyBalanceEntity.EconomyAccountType.TOWNY)
        ? (cache.getTowny(accountID) != null)
        : (cache.getOnline(accountID) != null);
    if (!cached) {
      warnIfMainThreadBlocking("vault2.withdraw", accountID);
    }

    try {
      MantissaAmount withdrawn = joinWithTimeout(economy.providerWithdraw(accountID, cur, delta, type), "vault2.withdraw", accountID);
      return new EconomyResponse(withdrawn.toHuman(), BigDecimal.ZERO, EconomyResponse.ResponseType.SUCCESS, "Successfully withdrawn");
    } catch (Exception ex) {
      EconomyErrorCode code = unwrapCode(ex, EconomyErrorCode.DB_ERROR);
      return new EconomyResponse(amount, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, code.name());
    }
  }

  @Override
  public @NotNull EconomyResponse deposit(@NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
    return deposit(pluginName, accountID, "", getDefaultCurrency(pluginName), amount);
  }

  @Override
  public @NotNull EconomyResponse deposit(
      @NotNull String pluginName,
      @NotNull UUID accountID,
      @NotNull String worldName,
      @NotNull BigDecimal amount
  ) {
    return deposit(pluginName, accountID, worldName, getDefaultCurrency(pluginName), amount);
  }

  @Override
  public @NotNull EconomyResponse deposit(
      @NotNull String pluginName,
      @NotNull UUID accountID,
      @NotNull String worldName,
      @NotNull String currency,
      @NotNull BigDecimal amount
  ) {
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      return new EconomyResponse(amount, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.INVALID_AMOUNT.name());
    }

    String cur = normalizeCurrency(currency);
    if (!hasCurrency(cur)) {
      return new EconomyResponse(amount, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.CURRENCY_NOT_CONFIGURED.name());
    }

    BigDecimal safeAmount = clampAbs(amount);
    if (safeAmount.compareTo(BigDecimal.ZERO) <= 0) {
      return new EconomyResponse(amount, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, EconomyErrorCode.INVALID_AMOUNT.name());
    }

    EconomyBalanceEntity.EconomyAccountType type = resolveAccountType(accountID);
    MantissaAmount delta = MantissaAmount.of(safeAmount, 0);

    boolean cached = (type == EconomyBalanceEntity.EconomyAccountType.TOWNY)
        ? (cache.getTowny(accountID) != null)
        : (cache.getOnline(accountID) != null);
    if (!cached) {
      warnIfMainThreadBlocking("vault2.deposit", accountID);
    }

    try {
      MantissaAmount applied = joinWithTimeout(economy.providerDeposit(accountID, cur, delta, type), "vault2.deposit", accountID);
      return new EconomyResponse(applied.toHuman(), BigDecimal.ZERO, EconomyResponse.ResponseType.SUCCESS, "Successfully deposited");
    } catch (Exception ex) {
      EconomyErrorCode code = unwrapCode(ex, EconomyErrorCode.DB_ERROR);
      return new EconomyResponse(amount, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, code.name());
    }
  }

  @Override
  public boolean createSharedAccount(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String name, @NotNull UUID owner) {
    return false;
  }

  @Override
  public boolean isAccountOwner(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
    return false;
  }

  @Override
  public boolean setOwner(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
    return false;
  }

  @Override
  public boolean isAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
    return false;
  }

  @Override
  public boolean addAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
    return false;
  }

  @Override
  public boolean addAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid, @NotNull AccountPermission... initialPermissions) {
    return false;
  }

  @Override
  public boolean removeAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
    return false;
  }

  @Override
  public boolean hasAccountPermission(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid, @NotNull AccountPermission permission) {
    return false;
  }

  @Override
  public boolean updateAccountPermission(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid, @NotNull AccountPermission permission, boolean value) {
    return false;
  }

  private <T> T joinWithTimeout(java.util.concurrent.CompletableFuture<T> future, String op, UUID uuid) {
    try {
      return future.orTimeout(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS).join();
    } catch (CompletionException ex) {
      Throwable root = ex.getCause() == null ? ex : ex.getCause();
      if (root instanceof java.util.concurrent.TimeoutException) {
        if (logger != null) {
          logger.logger().warning("VaultUnlockedEconomyProvider: timeout after " + JOIN_TIMEOUT_MS + "ms op=" + op + " uuid=" + uuid);
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
          "VaultUnlockedEconomyProvider: main-thread blocking Vault call (cache miss). op=" + op + " uuid=" + uuid +
              " timeout=" + JOIN_TIMEOUT_MS + "ms"
      );
    }
  }

  private static BigDecimal clampAbs(BigDecimal value) {
    if (value == null) return BigDecimal.ZERO;
    BigDecimal abs = value.abs();
    if (abs.compareTo(VaultUnlockedEconomyProvider.VAULT2_HARD_CAP_ABS) > 0) {
      return value.signum() >= 0 ? VaultUnlockedEconomyProvider.VAULT2_HARD_CAP_ABS : VaultUnlockedEconomyProvider.VAULT2_HARD_CAP_ABS.negate();
    }
    return value;
  }

  private EconomyBalanceEntity.EconomyAccountType resolveAccountType(UUID accountId) {
    boolean player = accountIsPlayer.getOrDefault(accountId, true);
    return player ? EconomyBalanceEntity.EconomyAccountType.PLAYER : EconomyBalanceEntity.EconomyAccountType.TOWNY;
  }

  private String vaultId() {
    return currencies == null ? null : currencies.vaultCurrencyId();
  }

  private CurrencyDefinition vaultDef() {
    String id = vaultId();
    return id == null ? null : currencies.currency(id);
  }

  private static int clampFractionDigits(int fd) {
    if (fd < 0) return 0;
    return Math.min(fd, 8);
  }

  private static EconomyErrorCode unwrapCode(Throwable ex, EconomyErrorCode fallback) {
    Throwable t = ex;
    for (int i = 0; i < 8 && t != null; i++) {
      if (t instanceof EconomyException ee && ee.code() != null) return ee.code();
      t = t.getCause();
    }
    return fallback == null ? EconomyErrorCode.DB_ERROR : fallback;
  }

  private static String normalizeCurrency(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}