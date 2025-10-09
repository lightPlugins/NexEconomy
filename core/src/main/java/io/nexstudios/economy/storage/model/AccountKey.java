package io.nexstudios.economy.storage.model;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record AccountKey(UUID playerId, String currencyKey) {
    public AccountKey {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currencyKey, "currencyKey");
        currencyKey = currencyKey.toLowerCase(Locale.ROOT);
    }
}