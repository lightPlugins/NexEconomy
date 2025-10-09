package io.nexstudios.economy.storage.model;

import io.nexstudios.economy.currency.NexCurrency;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
public class PlayerAccount {
    private final UUID playerId;
    private final String currencyKey;
    private final NexCurrency currency;
    @Setter
    private BigDecimal balance;

    // Version for multi-server reconciliation
    @Setter
    private long version;

    // Dirty tracking for flush scheduling
    @Setter
    private boolean dirty;

    // Last local update timestamp (ms)
    @Setter
    private long updatedAtMillis;

    public PlayerAccount(UUID playerId, String currencyKey, NexCurrency currency, BigDecimal startBalance) {
        this.playerId = playerId;
        this.currencyKey = currencyKey.toLowerCase();
        this.currency = currency;
        this.balance = startBalance;
        this.version = 0L;
        this.dirty = false;
        this.updatedAtMillis = System.currentTimeMillis();
    }
}