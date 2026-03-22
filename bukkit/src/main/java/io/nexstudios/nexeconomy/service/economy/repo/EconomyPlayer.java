package io.nexstudios.nexeconomy.service.economy.repo;

import io.nexstudios.nexeconomy.service.definition.MantissaAmount;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class EconomyPlayer {

  private final UUID uuid;
  private final ConcurrentHashMap<String, BalanceEntry> balances = new ConcurrentHashMap<>();

  public EconomyPlayer(UUID uuid) {
    this.uuid = uuid;
  }

  public UUID uuid() {
    return uuid;
  }

  public BalanceEntry entry(String currencyId) {
    if (currencyId == null || currencyId.isBlank()) return null;
    return balances.get(currencyId);
  }

  public BalanceEntry getOrCreate(String currencyId, MantissaAmount initial) {
    return balances.computeIfAbsent(currencyId, k -> new BalanceEntry(initial == null ? MantissaAmount.zero() : initial));
  }

  public Set<String> currencyIds() {
    return balances.keySet();
  }

  public Map<String, BalanceEntry> allEntriesView() {
    return Map.copyOf(balances);
  }

  public static final class BalanceEntry {
    private volatile MantissaAmount amount;
    private final AtomicLong version = new AtomicLong(0);
    private volatile boolean dirty;

    public BalanceEntry(MantissaAmount initial) {
      this.amount = initial == null ? MantissaAmount.zero() : initial;
      this.dirty = false;
    }

    public MantissaAmount amount() {
      return amount;
    }

    public long version() {
      return version.get();
    }

    public boolean dirty() {
      return dirty;
    }

    public void set(MantissaAmount newValue) {
      this.amount = newValue == null ? MantissaAmount.zero() : newValue;
      this.version.incrementAndGet();
      this.dirty = true;
    }

    public void add(MantissaAmount delta) {
      MantissaAmount next = (this.amount == null ? MantissaAmount.zero() : this.amount).add(delta);
      set(next);
    }

    public void subtract(MantissaAmount delta) {
      MantissaAmount next = (this.amount == null ? MantissaAmount.zero() : this.amount).subtract(delta);
      set(next);
    }

    public void clearDirtyIfVersionMatches(long expectedVersion) {
      if (version.get() == expectedVersion) {
        dirty = false;
      }
    }
  }
}