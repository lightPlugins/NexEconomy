package io.nexstudios.nexeconomy.service.domain.container;

import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.EconomyLocks;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Synchronous read/write access to a player's Virtual currency balances (e.g. gems, tokens).
 * Backed by the in-memory {@link EconomyPlayer} state – no async required for online players.
 */
public final class VirtualContainer {

  private final EconomyPlayer state;
  private final CurrencyRegistryService currencies;
  private final EconomyFlushService flush;

  public VirtualContainer(EconomyPlayer state, CurrencyRegistryService currencies, EconomyFlushService flush) {
    this.state = state;
    this.currencies = currencies;
    this.flush = flush;
  }

  // ─── Reads ────────────────────────────────────────────────────────────────

  /**
   * Returns the current balance for the given Virtual currency. Always non-null.
   */
  public @NotNull MantissaAmount balance(String currencyId) {
    CurrencyDefinition def = requireVirtual(currencyId);
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
   * Sets the balance for a Virtual currency.
   * Thread-safe. Schedules a flush.
   */
  public void set(String currencyId, MantissaAmount amount) {
    CurrencyDefinition def = requireVirtual(currencyId);
    if (def == null) return;

    MantissaAmount value = amount == null ? MantissaAmount.zero() : MantissaAmount.normalize(amount);

    var lock = EconomyLocks.lockFor(state.uuid());
    lock.lock();
    try {
      state.getOrCreate(def.id(), MantissaAmount.zero()).set(value);
      flush.requestFlush(state);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Adds {@code delta} to the Virtual currency balance.
   * Returns the actually added amount (normalized).
   * Thread-safe. Schedules a flush.
   */
  public @NotNull MantissaAmount add(String currencyId, MantissaAmount delta) {
    CurrencyDefinition def = requireVirtual(currencyId);
    if (def == null) return MantissaAmount.zero();

    MantissaAmount d = delta == null ? MantissaAmount.zero() : MantissaAmount.normalize(delta);
    if (d.isNegative() || d.compareTo(MantissaAmount.zero()) == 0) return MantissaAmount.zero();

    var lock = EconomyLocks.lockFor(state.uuid());
    lock.lock();
    try {
      EconomyPlayer.BalanceEntry entry = state.getOrCreate(def.id(), MantissaAmount.zero());
      entry.add(d);
      flush.requestFlush(state);
      return d;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Removes {@code delta} from the Virtual currency balance if sufficient funds exist.
   * Returns {@code true} on success, {@code false} if insufficient funds.
   * Thread-safe. Schedules a flush on success.
   */
  public boolean remove(String currencyId, MantissaAmount delta) {
    CurrencyDefinition def = requireVirtual(currencyId);
    if (def == null) return false;

    MantissaAmount d = delta == null ? MantissaAmount.zero() : MantissaAmount.normalize(delta);
    if (d.isNegative() || d.compareTo(MantissaAmount.zero()) == 0) return false;

    var lock = EconomyLocks.lockFor(state.uuid());
    lock.lock();
    try {
      EconomyPlayer.BalanceEntry entry = state.getOrCreate(def.id(), MantissaAmount.zero());
      MantissaAmount current = entry.amount() == null ? MantissaAmount.zero() : entry.amount();
      if (current.compareTo(d) < 0) return false;
      entry.subtract(d);
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

  private CurrencyDefinition requireVirtual(String currencyId) {
    if (currencyId == null) return null;
    String id = currencyId.trim().toLowerCase(Locale.ROOT);
    CurrencyDefinition def = currencies.currency(id);
    return (def != null && def.type() == CurrencyType.VIRTUAL) ? def : null;
  }
}
