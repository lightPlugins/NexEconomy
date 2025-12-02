package io.nexstudios.economy.placeholder;

import io.nexstudios.economy.NexEconomy;
import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.storage.InMemoryEcoService;
import io.nexstudios.economy.storage.support.EcoMath;
import io.nexstudios.nexus.bukkit.placeholder.NexPlaceholderIntrospector;
import io.nexstudios.nexus.bukkit.placeholder.NexPlaceholderProvider;
import io.nexstudios.nexus.bukkit.placeholder.PlaceholderValue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.*;

/**
 * Placeholder provider for NexEconomy (namespace "nexeconomy").
 *
 * Supported keys (after normalization: lowercase, ':' -> '_'):
 *
 *  - balance                         -> balance in the vault currency (formatted, self)
 *  - balance_raw                     -> raw numeric value of the vault currency (self)
 *  - balance_{currencyKey}           -> balance for a specific currency (formatted, self)
 *  - balance_{currencyKey}_raw       -> raw value for a specific currency (self)
 *  - balance_{currencyKey}_formatted -> explicit formatted value (same as without suffix, self)
 *
 *  - balance_player_{name}                         -> vault balance for target player
 *  - balance_player_{name}_raw                     -> raw vault balance for target player
 *  - balance_{currencyKey}_player_{name}           -> balance for target player & currency
 *  - balance_{currencyKey}_player_{name}_raw       -> raw value for target player & currency
 *  - balance_{currencyKey}_player_{name}_formatted -> same as formatted
 */
public final class NexEconomyPlaceholderProvider implements NexPlaceholderProvider, NexPlaceholderIntrospector {

    private final NexEconomy plugin;
    private final InMemoryEcoService ecoService;

    public NexEconomyPlaceholderProvider(NexEconomy plugin, InMemoryEcoService ecoService) {
        this.plugin = plugin;
        this.ecoService = ecoService;
    }

    @Override
    public @Nullable PlaceholderValue resolve(String key) {
        // No player context: economy placeholders do not make much sense here.
        // You could add global statistics in the future if needed.
        return null;
    }

    @Override
    public @Nullable PlaceholderValue resolve(Player player, String key) {
        if (player == null || key == null) return null;

        String norm = normalize(key);

        try {
            // Player-parameterized placeholders: balance[_<currency>]_player_<name>[_suffix]
            if (norm.startsWith("balance_") && norm.contains("_player_")) {
                return resolveTargetBalance(player, norm);
            }

            // Self balance: balance / balance_raw (default: vault currency)
            if ("balance".equals(norm) || "balance_raw".equals(norm)) {
                NexCurrency vault = plugin.getNexEcoFactory().getVaultCurrency();
                String cKey = plugin.getNexEcoFactory().keyOf(vault);
                return resolveBalanceFor(player.getUniqueId(), vault, cKey, norm.endsWith("_raw"));
            }

            // Self balance with explicit currency: balance_{currencyKey}[_raw|_formatted]
            if (!norm.startsWith("balance_")) {
                return null;
            }

            String[] parts = norm.split("_");
            if (parts.length < 2) {
                return null;
            }

            // balance_<currencyKey>[_suffix...]
            String currencyKey = parts[1];

            String suffix = "";
            if (parts.length > 2) {
                suffix = String.join("_", Arrays.copyOfRange(parts, 2, parts.length));
            }

            boolean raw = "raw".equals(suffix) || "value".equals(suffix);
            // "formatted" is treated the same as default formatted (no special handling needed).

            Optional<NexCurrency> oc = plugin.getNexEcoFactory().findByKey(currencyKey);
            if (oc.isEmpty()) {
                return null;
            }

            NexCurrency currency = oc.get();
            return resolveBalanceFor(player.getUniqueId(), currency, currencyKey, raw);
        } catch (Exception ex) {
            // Defensive: placeholders must not throw hard errors
            NexEconomy.nexusLogger.error("Placeholder resolve failed for key '" + key + "': " + ex.getMessage());
            return null;
        }
    }

    @Override
    public @Nullable String fallback(@Nullable Player player, String key) {
        // Simple numeric fallback if resolving fails
        return "0";
    }

    @Override
    public boolean isCacheable(String key) {
        // Balances change frequently, so global caching is usually not desired.
        // Per-value cacheability is controlled via PlaceholderValue.cacheable(...).
        return false;
    }

    @Override
    public Set<String> advertisedKeys() {
        // Pattern-like keys for documentation / introspection.
        return Set.of(
                "balance",
                "balance_raw",
                "balance_*",
                "balance_*_raw",
                "balance_*_formatted",
                "balance_player_*",
                "balance_player_*_raw",
                "balance_*_player_*",
                "balance_*_player_*_raw"
        );
    }

    /**
     * Normalizes a raw key:
     *  - lowercases it
     *  - trims whitespace
     *  - replaces ':' with '_' so that both styles are accepted.
     */
    private String normalize(String rawKey) {
        if (rawKey == null) return "";
        String k = rawKey.toLowerCase(Locale.ROOT).trim();
        k = k.replace(':', '_');
        return k;
    }

    /**
     * Resolves a "target" balance placeholder, e.g.:
     *  - balance_player_<name>
     *  - balance_player_<name>_raw
     *  - balance_<currencyKey>_player_<name>
     *  - balance_<currencyKey>_player_<name>_raw
     */
    private @Nullable PlaceholderValue resolveTargetBalance(Player viewer, String normKey) {
        // Remove leading "balance_"
        String afterPrefix = normKey.substring("balance_".length());

        int idx = afterPrefix.indexOf("_player_");
        if (idx < 0) {
            return null;
        }

        String currencyPart = afterPrefix.substring(0, idx); // may be empty -> default vault
        String tail = afterPrefix.substring(idx + "_player_".length()); // playerName[_suffix]

        if (tail.isEmpty()) {
            return null;
        }

        // Split player name and optional suffix (raw/value/formatted).
        String playerName;
        String suffix = "";

        int lastUnderscore = tail.lastIndexOf('_');
        if (lastUnderscore > 0) {
            String maybeSuffix = tail.substring(lastUnderscore + 1);
            if ("raw".equals(maybeSuffix) || "value".equals(maybeSuffix) || "formatted".equals(maybeSuffix)) {
                playerName = tail.substring(0, lastUnderscore);
                suffix = maybeSuffix;
            } else {
                playerName = tail;
            }
        } else {
            playerName = tail;
        }

        if (playerName.isBlank()) {
            return null;
        }

        boolean raw = "raw".equals(suffix) || "value".equals(suffix);

        // Resolve currency: empty -> vault, otherwise by key.
        NexCurrency currency;
        String currencyKey;
        if (currencyPart.isBlank()) {
            currency = plugin.getNexEcoFactory().getVaultCurrency();
            currencyKey = plugin.getNexEcoFactory().keyOf(currency);
        } else {
            Optional<NexCurrency> oc = plugin.getNexEcoFactory().findByKey(currencyPart);
            if (oc.isEmpty()) {
                return null;
            }
            currency = oc.get();
            currencyKey = plugin.getNexEcoFactory().keyOf(currency);
        }

        // Resolve target player by name.
        OfflinePlayer target = resolveKnownPlayerByName(playerName);
        if (target == null) {
            return null;
        } else {
            target.getUniqueId();
        }

        return resolveBalanceFor(target.getUniqueId(), currency, currencyKey, raw);
    }

    /**
     * Resolves a balance placeholder for a specific player UUID and currency.
     *
     * @param playerId    UUID of the player whose balance is requested
     * @param currency    Currency definition
     * @param currencyKey Stable currency key (factory key)
     * @param raw         If true, only the numeric value is returned; otherwise value + symbol
     */
    private PlaceholderValue resolveBalanceFor(UUID playerId,
                                               NexCurrency currency,
                                               String currencyKey,
                                               boolean raw) {

        BigDecimal balance = ecoService.getBalance(playerId, currencyKey);

        // Scale according to currency fraction digits
        BigDecimal scaled = EcoMath.scale(currency, balance);
        String plain = scaled.toPlainString();

        if (raw) {
            // Only numeric value, no symbol
            return PlaceholderValue.ofString(plain).cacheable(false);
        }

        // Build formatted value with singular/plural symbol
        Component symbol = scaled.compareTo(BigDecimal.ONE) == 0
                ? currency.getSingularSymbol()
                : currency.getPluralSymbol();

        String symbolPlain = PlainTextComponentSerializer.plainText().serialize(symbol);
        String formatted = plain + " " + symbolPlain;

        return PlaceholderValue.of(formatted, Component.text(formatted)).cacheable(false);
    }

    /**
     * Attempts to resolve a known player by name:
     *  1) online players (case-insensitive)
     *  2) known offline players
     * Returns null if no matching player could be found.
     */
    @Nullable
    private OfflinePlayer resolveKnownPlayerByName(String name) {
        String target = name.trim();
        if (target.isEmpty()) return null;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(target)) {
                return p;
            }
        }

        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(target)) {
                return op;
            }
        }

        return null;
    }
}