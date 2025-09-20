package io.nexstudios.economy;

import io.nexstudios.economy.model.CurrencyType;
import io.nexstudios.economy.model.NexCurrency;
import io.nexstudios.nexus.bukkit.files.NexusFileReader;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;

/**
 * The NexEcoFactory class is responsible for managing and loading currency configurations
 * within the NexEconomy plugin. It interacts with files to define and register currencies
 * and handles their retrieval, searching, and categorization.
 * This class ensures proper initialization and runtime management of currency data.
 */
public class NexEcoFactory {

    private final NexusFileReader currencyFiles;
    private final Map<String, NexCurrency> currenciesByKey = new HashMap<>();

    public NexEcoFactory(NexusFileReader currencyFiles) {
        this.currencyFiles = currencyFiles;
        NexEconomy.nexusLogger.info("Loading currencies ...");
        loadCurrencies();
    }

    private void loadCurrencies() {
        currenciesByKey.clear();
        for (File currencyFile : currencyFiles.getFiles()) {
            NexCurrency currency = createCurrency(currencyFile);
            String key = resolveKey(currencyFile, currency);
            currenciesByKey.put(key, currency);
        }
        NexEconomy.nexusLogger.info("Loaded currencies: " + String.join(", ", currenciesByKey.keySet()));
    }

    /**
     * Retrieves the currency mapped to the "vault" key.
     *
     * @return The {@link NexCurrency} instance associated with the "vault" currency key.
     *         Never returns {@code null}.
     */
    @NotNull
    public NexCurrency getVaultCurrency() {
        return currenciesByKey.get("vault");
    }

    /**
     * Registers an external currency with the system.
     *
     * @param namespace The unique string identifier or namespace under which the currency will be registered.
     * @param currency  The {@link NexCurrency} instance representing the external currency to be registered.
     * @param plugin    The {@link JavaPlugin} instance representing the plugin that provides the currency.
     */
    public void registerExternalCurrencies(String namespace, NexCurrency currency, JavaPlugin plugin) {
        NexEconomy.nexusLogger.info("Registering external currency: " + currency.getName() + " by " + plugin.getName());
        currenciesByKey.put(namespace, currency);
        loadCurrencies();
    }

    /**
     * Resolves a unique key for a given currency configuration file. The method attempts to determine
     * the key in the following priority order:
     * 1. Explicitly defined key in the configuration file under the 'key' property.
     * 2. The base name of the file (filename without extension).
     * 3. The name of the provided {@link NexCurrency}.
     *
     * @param file     The configuration file used to define the currency. Must not be null.
     * @param currency The {@link NexCurrency} instance associated with the file.
     *                 Provides a fallback for the key resolution if required.
     * @return A string representing the resolved unique key, converted to lowercase. Never null.
     */
    private String resolveKey(File file, NexCurrency currency) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String explicitKey = Optional.ofNullable(cfg.getString("key")).map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        if (explicitKey != null) return explicitKey.toLowerCase(Locale.ROOT);

        String fileName = file.getName();
        int dot = fileName.lastIndexOf('.');
        String base = (dot > 0 ? fileName.substring(0, dot) : fileName);
        if (!base.isEmpty()) return base.toLowerCase(Locale.ROOT);

        return currency.getName().toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Retrieves an unmodifiable collection of all registered currencies.
     *
     * @return A collection containing all instances of {@link NexCurrency} currently registered.
     *         The returned collection is unmodifiable, ensuring that it cannot be altered.
     */
    public Collection<NexCurrency> getCurrencies() {
        return Collections.unmodifiableCollection(currenciesByKey.values());
    }

    /**
     * Retrieves the currency associated with the given key.
     * The key is to treat the case insensitively.
     *
     * @param key the key to search for, representing a currency identifier; can be {@code null}.
     * @return an {@link Optional} containing the {@link NexCurrency} associated with the key,
     *         or an empty {@link Optional} if the key is {@code null} or no currency is found for the key.
     */
    public Optional<NexCurrency> findByKey(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(currenciesByKey.get(key.toLowerCase(Locale.ROOT)));
    }

    /**
     * Finds and retrieves a list of {@link NexCurrency} instances that match the specified {@link CurrencyType}.
     *
     * @param type the {@link CurrencyType} to filter the currencies. Must not be null.
     * @return a list of {@link NexCurrency} instances that match the provided type. If no matches are found, an empty list is returned.
     */
    public List<NexCurrency> findByType(CurrencyType type) {
        return currenciesByKey.values().stream()
                .filter(c -> c.getCurrencyType() == type)
                .toList();
    }

    /**
     * Creates a new {@link NexCurrency} instance based on the configuration provided in the given file.
     *
     * @param file The configuration file used to set up the currency. Must not be {@code null}.
     * @return A {@link NexCurrency} instance initialized based on the file's content.
     */
    private NexCurrency createCurrency(File file) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        final Component name = Component.text(config.getString("name", "vault"));
        final Component symbolPlural = Component.text(config.getString("symbol.plural", "plural"));
        final Component symbolSingular = Component.text(config.getString("symbol.singular", "singular"));
        final String placeholder = config.getString("placeholder", "#name# - #amount# - #symbol#");
        final String mainCommand = config.getString("command.main", "vault");
        final List<String> aliases = config.getStringList("command.aliases");

        final int fractionDigits = config.getInt("fraction-digits", 2);

        String rawType = config.getString("type", "vault");
        String normalizedType = rawType.trim().toUpperCase(Locale.ROOT);

        CurrencyType parsedType;
        try {
            parsedType = CurrencyType.valueOf(normalizedType);
        } catch (IllegalArgumentException ex) {
            NexEconomy.nexusLogger.warning("Unknown currency type '" + rawType + "' in " + file.getName() + ", falling back to VAULT");
            parsedType = CurrencyType.VAULT;
        }
        final CurrencyType currencyType = parsedType;

        final BigDecimal startBalance = new BigDecimal(String.valueOf(config.get("start-balance", "0")));
        final BigDecimal maxBalance = new BigDecimal(String.valueOf(config.get("max-balance", "-1")));

        return new NexCurrency() {
            @Override public Component getName() { return name; }
            @Override public Component getPluralSymbol() { return symbolPlural; }
            @Override public Component getSingularSymbol() { return symbolSingular; }
            @Override public String getPlaceholder() { return placeholder; }
            @Override public String getMainCommand() { return mainCommand; }
            @Override public List<String> getAliases() { return aliases; }
            @Override public int getFractionDigits() { return fractionDigits; }
            @Override public CurrencyType getCurrencyType() { return currencyType; }
            @Override public BigDecimal getStartBalance() { return startBalance; }
            @Override public BigDecimal getMaxBalance() { return maxBalance; }
        };
    }
}
