package io.nexstudios.nexeconomy.service.economy;

import io.nexstudios.nexeconomy.service.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.definition.CurrencyType;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@Dependencies({
    LoggerService.class,
    CurrencyRegistryService.class,
    EconomyPlayerCacheService.class,
    EconomyFlushService.class
})
public final class EconomyService implements Service {

  private static final BigDecimal VAULT_DOUBLE_SAFE_INTEGER_LIMIT = new BigDecimal("9000000000000000");

  private final LoggerService logger;
  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyFlushService flush;

  public EconomyService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.flush = accessor.getService(EconomyFlushService.class);
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
    MantissaAmount value = normalizeForCurrency(def, amount);

    return cache.loadOrCreateOnline(target).thenApply(econ -> {
      econ.getOrCreate(cur, MantissaAmount.zero()).set(value);
      flush.requestFlush(econ);
      return true;
    });
  }

  public CompletableFuture<Boolean> add(@NotNull Player target, @NotNull String currencyId, @NotNull MantissaAmount delta) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    CurrencyDefinition def = currencies.currency(cur);
    MantissaAmount d = normalizeForCurrency(def, delta);

    return cache.loadOrCreateOnline(target).thenApply(econ -> {
      EconomyPlayer.BalanceEntry entry = econ.getOrCreate(cur, MantissaAmount.zero());

      // Vault
      if (def != null && def.type() == CurrencyType.VAULT) {
        BigDecimal nextHuman = entry.amount() == null ? BigDecimal.ZERO : entry.amount().toHuman();
        nextHuman = nextHuman.add(d.toHuman());
        entry.set(MantissaAmount.of(clampVaultHuman(def, nextHuman), 0));
      } else {
        entry.add(d);
      }

      flush.requestFlush(econ);
      return true;
    });
  }

  public CompletableFuture<Boolean> remove(@NotNull Player target, @NotNull String currencyId, @NotNull MantissaAmount delta) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    CurrencyDefinition def = currencies.currency(cur);
    MantissaAmount d = normalizeForCurrency(def, delta);

    return cache.loadOrCreateOnline(target).thenApply(econ -> {
      EconomyPlayer.BalanceEntry entry = econ.getOrCreate(cur, MantissaAmount.zero());
      MantissaAmount current = entry.amount() == null ? MantissaAmount.zero() : entry.amount();

      if (current.compareTo(d) < 0) return false;

      if (def != null && def.type() == CurrencyType.VAULT) {
        BigDecimal nextHuman = current.toHuman().subtract(d.toHuman());
        entry.set(MantissaAmount.of(scaleVaultHuman(def, nextHuman), 0));
      } else {
        entry.subtract(d);
      }

      flush.requestFlush(econ);
      return true;
    });
  }

  private static MantissaAmount normalizeForCurrency(CurrencyDefinition def, MantissaAmount a) {
    if (a == null) return MantissaAmount.zero();
    MantissaAmount n = MantissaAmount.normalize(a);

    if (def != null && def.type() == CurrencyType.VAULT) {
      BigDecimal human = n.toHuman();
      human = scaleVaultHuman(def, human);
      human = clampVaultHuman(def, human);
      return MantissaAmount.of(human, 0); // Vault immer exp3=0 (kein aa/ab/zz)
    }

    return n;
  }

  private static BigDecimal scaleVaultHuman(CurrencyDefinition def, BigDecimal human) {
    if (human == null) return BigDecimal.ZERO;
    int fd = def == null ? 0 : def.fractionDigits();
    if (fd < 0) fd = 0;
    if (fd > 8) fd = 8;
    return human.setScale(fd, RoundingMode.DOWN);
  }

  private static BigDecimal clampVaultHuman(CurrencyDefinition def, BigDecimal human) {
    if (human == null) return BigDecimal.ZERO;

    int fd = def == null ? 0 : def.fractionDigits();
    if (fd < 0) fd = 0;
    if (fd > 8) fd = 8;

    BigDecimal cap = VAULT_DOUBLE_SAFE_INTEGER_LIMIT.movePointLeft(fd); // 9e15 / 10^fd

    if (human.compareTo(cap) > 0) return cap;
    if (human.compareTo(cap.negate()) < 0) return cap.negate();
    return human;
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
}