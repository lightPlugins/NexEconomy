package io.nexstudios.nexeconomy.service.domain.container;

import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.EconomyLocks;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Synchronous read/write access to a player's Vault (real-money) currency balances.
 * Backed by the in-memory {@link EconomyPlayer} state – no async required for online players.
 */
public final class VaultContainer {

  private static final BigDecimal VAULT_DOUBLE_SAFE_INTEGER_LIMIT = new BigDecimal("9000000000000000");

  private final EconomyPlayer state;
  private final CurrencyRegistryService currencies;
  private final EconomyFlushService flush;

  public VaultContainer(EconomyPlayer state, CurrencyRegistryService currencies, EconomyFlushService flush) {
    this.state = state;
    this.currencies = currencies;
    this.flush = flush;
  }

  // ─── Reads ────────────────────────────────────────────────────────────────

  /**
   * Returns the current balance for the given Vault currency. Always non-null.
   */
  public @NotNull MantissaAmount balance(String currencyId) {
    CurrencyDefinition def = requireVault(currencyId);
    if (def == null) return MantissaAmount.zero();
    EconomyPlayer.BalanceEntry entry = state.entry(def.id());
    return entry == null || entry.amount() == null ? MantissaAmount.zero() : entry.amount();
  }

  /** Returns true if the player has at least {@code amount}. */
  public boolean has(String currencyId, MantissaAmount amount) {
    if (amount == null) return true;
    return balance(currencyId).compareTo(amount) >= 0;
  }

  // ─── Writes ───────────────────────────────────────────────────────────────

  /**
   * Sets the balance for a Vault currency. Clamps to max-balance if configured.
   * Thread-safe. Schedules a flush.
   */
  public void set(String currencyId, MantissaAmount amount) {
    CurrencyDefinition def = requireVault(currencyId);
    if (def == null) return;

    MantissaAmount value = amount == null ? MantissaAmount.zero() : amount;
    BigDecimal human = scaleVaultHuman(def, value.toHuman());
    human = clampVaultHuman(def, human);

    var lock = EconomyLocks.lockFor(state.uuid());
    lock.lock();
    try {
      state.getOrCreate(def.id(), MantissaAmount.zero()).set(MantissaAmount.of(human, 0));
      flush.requestFlush(state);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Adds {@code delta} to the Vault currency balance, respecting max-balance cap.
   * Returns the actually added amount (may be less than {@code delta} if capped, or zero if already at max).
   * Thread-safe. Schedules a flush.
   */
  public @NotNull MantissaAmount add(String currencyId, MantissaAmount delta) {
    CurrencyDefinition def = requireVault(currencyId);
    if (def == null) return MantissaAmount.zero();

    MantissaAmount d = normalizeForVault(def, delta);
    if (d.isNegative() || d.compareTo(MantissaAmount.zero()) == 0) return MantissaAmount.zero();

    var lock = EconomyLocks.lockFor(state.uuid());
    lock.lock();
    try {
      EconomyPlayer.BalanceEntry entry = state.getOrCreate(def.id(), MantissaAmount.zero());
      MantissaAmount current = entry.amount() == null ? MantissaAmount.zero() : entry.amount();

      MantissaAmount allowed = capDeltaToMax(def, current, d);
      if (allowed.compareTo(MantissaAmount.zero()) == 0) return MantissaAmount.zero();

      BigDecimal nextHuman = scaleVaultHuman(def, current.toHuman().add(allowed.toHuman()));
      nextHuman = clampVaultHuman(def, nextHuman);
      entry.set(MantissaAmount.of(nextHuman, 0));
      flush.requestFlush(state);
      return allowed;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Removes {@code delta} from the Vault currency balance if sufficient funds exist.
   * Returns {@code true} on success, {@code false} if insufficient funds.
   * Thread-safe. Schedules a flush on success.
   */
  public boolean remove(String currencyId, MantissaAmount delta) {
    CurrencyDefinition def = requireVault(currencyId);
    if (def == null) return false;

    MantissaAmount d = normalizeForVault(def, delta);
    if (d.isNegative() || d.compareTo(MantissaAmount.zero()) == 0) return false;

    var lock = EconomyLocks.lockFor(state.uuid());
    lock.lock();
    try {
      EconomyPlayer.BalanceEntry entry = state.getOrCreate(def.id(), MantissaAmount.zero());
      MantissaAmount current = entry.amount() == null ? MantissaAmount.zero() : entry.amount();

      if (current.compareTo(d) < 0) return false;

      BigDecimal nextHuman = scaleVaultHuman(def, current.toHuman().subtract(d.toHuman()));
      entry.set(MantissaAmount.of(nextHuman, 0));
      flush.requestFlush(state);
      return true;
    } finally {
      lock.unlock();
    }
  }

  // ─── Internal ─────────────────────────────────────────────────────────────

  /** Returns the underlying mutable state. For use by EconomyService / EconomyFlushService only. */
  public EconomyPlayer state() {
    return state;
  }

  private CurrencyDefinition requireVault(String currencyId) {
    if (currencyId == null) return null;
    String id = currencyId.trim().toLowerCase(Locale.ROOT);
    CurrencyDefinition def = currencies.currency(id);
    return (def != null && def.type() == CurrencyType.VAULT) ? def : null;
  }

  private static MantissaAmount normalizeForVault(CurrencyDefinition def, MantissaAmount a) {
    if (a == null) return MantissaAmount.zero();
    BigDecimal human = MantissaAmount.normalize(a).toHuman();
    human = scaleVaultHuman(def, human);
    human = clampVaultHuman(def, human);
    return MantissaAmount.of(human, 0);
  }

  private static BigDecimal scaleVaultHuman(CurrencyDefinition def, BigDecimal human) {
    if (human == null) return BigDecimal.ZERO;
    int fd = def == null ? 0 : Math.max(0, Math.min(8, def.fractionDigits()));
    return human.setScale(fd, RoundingMode.DOWN);
  }

  private static BigDecimal clampVaultHuman(CurrencyDefinition def, BigDecimal human) {
    if (human == null) return BigDecimal.ZERO;
    int fd = def == null ? 0 : Math.max(0, Math.min(8, def.fractionDigits()));
    BigDecimal cap = VAULT_DOUBLE_SAFE_INTEGER_LIMIT.movePointLeft(fd);
    if (human.compareTo(cap) > 0) return cap;
    if (human.compareTo(cap.negate()) < 0) return cap.negate();
    return human;
  }

  private static MantissaAmount capDeltaToMax(CurrencyDefinition def, MantissaAmount current, MantissaAmount requested) {
    if (def == null) return requested;
    BigDecimal max = def.maxBalance();
    if (max == null || max.compareTo(BigDecimal.ZERO) < 0) return requested; // unlimited

    BigDecimal remaining = max.subtract((current == null ? MantissaAmount.zero() : MantissaAmount.normalize(current)).toHuman());
    if (remaining.compareTo(BigDecimal.ZERO) <= 0) return MantissaAmount.zero();

    BigDecimal allowedHuman = (requested == null ? MantissaAmount.zero() : MantissaAmount.normalize(requested)).toHuman().min(remaining);
    return allowedHuman.compareTo(BigDecimal.ZERO) <= 0 ? MantissaAmount.zero() : MantissaAmount.of(allowedHuman, 0);
  }
}

