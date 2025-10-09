package io.nexstudios.economy.storage;

import io.nexstudios.economy.currency.NexCurrency;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface NexEcoService {

    // Account
    boolean hasAccount(UUID playerId, String currencyKey);
    Optional<AccountView> getAccount(UUID playerId, String currencyKey);

    // Erstellt einen Account mit Startguthaben lt. Currency
    NexEcoResponse createAccount(UUID playerId, NexCurrency currency);

    // Balance-Abfragen
    BigDecimal getBalance(UUID playerId, String currencyKey);

    // Checks
    boolean has(UUID playerId, String currencyKey, BigDecimal amount);

    // Mutationen
    NexEcoResponse deposit(UUID playerId, String currencyKey, BigDecimal amount);
    NexEcoResponse withdraw(UUID playerId, String currencyKey, BigDecimal amount);

    // Komfort-Overloads
    default boolean hasAccount(OfflinePlayer player, String currencyKey) {
        return hasAccount(player.getUniqueId(), currencyKey);
    }
    default Optional<AccountView> getAccount(OfflinePlayer player, String currencyKey) {
        return getAccount(player.getUniqueId(), currencyKey);
    }
    default NexEcoResponse createAccount(OfflinePlayer player, NexCurrency currency) {
        return createAccount(player.getUniqueId(), currency);
    }
    default BigDecimal getBalance(OfflinePlayer player, String currencyKey) {
        return getBalance(player.getUniqueId(), currencyKey);
    }
    default boolean has(OfflinePlayer player, String currencyKey, BigDecimal amount) {
        return has(player.getUniqueId(), currencyKey, amount);
    }
    default NexEcoResponse deposit(OfflinePlayer player, String currencyKey, BigDecimal amount) {
        return deposit(player.getUniqueId(), currencyKey, amount);
    }
    default NexEcoResponse withdraw(OfflinePlayer player, String currencyKey, BigDecimal amount) {
        return withdraw(player.getUniqueId(), currencyKey, amount);
    }

    // Readonly-View
    interface AccountView {
        UUID playerId();
        String currencyKey();
        NexCurrency currency();
        BigDecimal balance();
    }
}