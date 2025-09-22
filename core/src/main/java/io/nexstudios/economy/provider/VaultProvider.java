package io.nexstudios.economy.provider;

import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.economy.NexEcoService;
import io.nexstudios.economy.economy.NexEcoResponse;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

public record VaultProvider(NexCurrency currency, NexEcoService eco) implements Economy {

    private String key() {
        return PlainTextComponentSerializer.plainText()
                .serialize(currency.getName())
                .toLowerCase(Locale.ROOT);
    }

    @Override public boolean isEnabled() { return true; }

    @Override
    public String getName() {
        return PlainTextComponentSerializer.plainText().serialize(currency.getName());
    }

    @Override public boolean hasBankSupport() { return false; }

    @Override public int fractionalDigits() { return currency.getFractionDigits(); }

    @Override
    public String format(double v) {
        BigDecimal bd = BigDecimal.valueOf(v)
                .setScale(currency.getFractionDigits(), RoundingMode.DOWN);
        String symbol = PlainTextComponentSerializer.plainText().serialize(
                bd.compareTo(BigDecimal.ONE) == 0 ? currency.getSingularSymbol() : currency.getPluralSymbol()
        );
        return bd.toPlainString() + " " + symbol;
    }

    @Override
    public String currencyNamePlural() {
        return PlainTextComponentSerializer.plainText().serialize(currency.getPluralSymbol());
    }

    @Override
    public String currencyNameSingular() {
        return PlainTextComponentSerializer.plainText().serialize(currency.getSingularSymbol());
    }

    // Account Abfragen
    @Override public boolean hasAccount(String playerName) { return false; } // Nicht unterstützt via Name
    @Override public boolean hasAccount(OfflinePlayer player) { return eco.hasAccount(player, key()); }
    @Override public boolean hasAccount(String playerName, String worldName) { return false; }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return eco.hasAccount(player, key()); }

    // Balance
    @Override public double getBalance(String playerName) { return 0; }
    @Override public double getBalance(OfflinePlayer player) { return eco.getBalance(player, key()).doubleValue(); }
    @Override public double getBalance(String playerName, String world) { return 0; }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    // Has
    @Override public boolean has(String playerName, double amount) { return false; }
    @Override public boolean has(OfflinePlayer player, double amount) {
        return eco.has(player, key(), BigDecimal.valueOf(amount));
    }
    @Override public boolean has(String playerName, String world, double amount) { return false; }
    @Override public boolean has(OfflinePlayer player, String world, double amount) {
        return has(player, amount);
    }

    // Withdraw
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse withdrawPlayer(String playerName, double amount) { return notImplemented(); }
    @Override public @NotNull EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        NexEcoResponse res = eco.withdraw(player, key(), BigDecimal.valueOf(amount));
        return toVault(res);
    }
    @Contract(value = "_, _, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse withdrawPlayer(String playerName, String world, double amount) { return notImplemented(); }
    @Override public @NotNull EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        return withdrawPlayer(player, amount);
    }

    // Deposit
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse depositPlayer(String playerName, double amount) { return notImplemented(); }
    @Override public @NotNull EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        NexEcoResponse res = eco.deposit(player, key(), BigDecimal.valueOf(amount));
        return toVault(res);
    }
    @Contract(value = "_, _, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse depositPlayer(String playerName, String world, double amount) { return notImplemented(); }
    @Override public @NotNull EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return depositPlayer(player, amount);
    }

    // Banks: nicht unterstützt
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse createBank(String name, String world) { return notImplemented(); }
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse createBank(String name, OfflinePlayer player) { return notImplemented(); }
    @Contract(value = "_ -> new", pure = true)
    @Override public @NotNull EconomyResponse deleteBank(String name) { return notImplemented(); }
    @Contract(value = "_ -> new", pure = true)
    @Override public @NotNull EconomyResponse bankBalance(String name) { return notImplemented(); }
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse bankHas(String name, double amount) { return notImplemented(); }
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse bankWithdraw(String name, double amount) { return notImplemented(); }
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse bankDeposit(String name, double amount) { return notImplemented(); }
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse isBankOwner(String name, String playerName) { return notImplemented(); }
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse isBankOwner(String name, OfflinePlayer player) { return notImplemented(); }
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse isBankMember(String name, String playerName) { return notImplemented(); }
    @Contract(value = "_, _ -> new", pure = true)
    @Override public @NotNull EconomyResponse isBankMember(String name, OfflinePlayer player) { return notImplemented(); }
    @Contract(pure = true)
    @Override public @NotNull @Unmodifiable List<String> getBanks() { return List.of(); }

    // Account-Erzeugung
    @Override public boolean createPlayerAccount(String playerName) { return false; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) {
        return eco.createAccount(player, currency).isSuccess();
    }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return false; }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // Mapping: NexEcoResponse -> EconomyResponse
    private EconomyResponse toVault(NexEcoResponse src) {
        EconomyResponse.ResponseType type = switch (src.responseType()) {
            case SUCCESS -> EconomyResponse.ResponseType.SUCCESS;
            case MAX_BALANCE, FAILURE, NOT_NEGATIVE, NOT_ENOUGH, UNKNOWN -> EconomyResponse.ResponseType.FAILURE;
            case NOT_IMPLEMENTED -> EconomyResponse.ResponseType.NOT_IMPLEMENTED;
        };
        String msg = src.errorMessage() == null ? "" : src.errorMessage();
        double amount = src.amount() == null ? 0D : src.amount().doubleValue();
        double balance = src.balance() == null ? 0D : src.balance().doubleValue();
        return new EconomyResponse(amount, balance, type, msg);
    }

    private static EconomyResponse notImplemented() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not supported");
    }
}