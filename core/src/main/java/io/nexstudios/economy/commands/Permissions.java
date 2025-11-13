package io.nexstudios.economy.commands;

/**
 * Centralized permissions for NexEconomy commands.
 * Use withKey(key) to get currency-specific variants (nexeco.<key>....).
 */
public enum Permissions {
    // Global fallbacks (apply to any currency if specific one is not present)
    GLOBAL_BALANCE_TOP("nexeco.balance.top"),
    GLOBAL_BALANCE_SELF("nexeco.balance.self"),
    GLOBAL_BALANCE_OTHER("nexeco.balance.other"),
    GLOBAL_DEPOSIT("nexeco.deposit"),
    GLOBAL_WITHDRAW("nexeco.withdraw"),
    GLOBAL_SET("nexeco.set"),

    // Currency-specific templates; call withKey("vault") -> "nexeco.vault.balance.self"
    CURRENCY_BALANCE_TOP("nexeco.%s.balance.top"),
    CURRENCY_BALANCE_SELF("nexeco.%s.balance.self"),
    CURRENCY_BALANCE_OTHER("nexeco.%s.balance.other"),
    CURRENCY_DEPOSIT("nexeco.%s.deposit"),
    CURRENCY_WITHDRAW("nexeco.%s.withdraw"),
    CURRENCY_SET("nexeco.%s.set");

    private final String pattern;

    Permissions(String pattern) {
        this.pattern = pattern;
    }

    public String raw() {
        return pattern;
    }

    public String withKey(String key) {
        if (!pattern.contains("%s")) return pattern;
        return String.format(pattern, key.toLowerCase());
    }
}