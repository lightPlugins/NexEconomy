package io.nexstudios.economy;

import io.nexstudios.economy.currency.NexCurrencyType;
import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.nexus.bukkit.files.NexusFileReader;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
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
    // Keep stable reverse mapping: currency instance -> key
    private final Map<NexCurrency, String> keysByCurrency = new IdentityHashMap<>();

    public NexEcoFactory(NexusFileReader currencyFiles) {
        this.currencyFiles = currencyFiles;
        NexEconomy.nexusLogger.info("Loading currencies ...");
        loadCurrencies();
    }

    private void loadCurrencies() {
        currenciesByKey.clear();
        keysByCurrency.clear();
        for (File currencyFile : currencyFiles.getFiles()) {
            NexCurrency currency = createCurrency(currencyFile);
            String key = resolveKey(currencyFile, currency);
            currenciesByKey.put(key, currency);
            keysByCurrency.put(currency, key);
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
    public boolean registerExternalCurrencies(String namespace, NexCurrency currency, JavaPlugin plugin) {
        NexEconomy.nexusLogger.info("Registering external currency: " + currency.getName() + " by " + plugin.getName());
        currenciesByKey.put(namespace, currency);
        keysByCurrency.put(currency, namespace.toLowerCase(Locale.ROOT));
        return createFileFromCurrency(currency);
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

        // Fallback on visual name (not recommended, but kept as last resort)
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
     * Returns the stable key (namespace) for a given currency instance.
     * This is the filename without extension or explicit 'key' from config.
     */
    public String keyOf(NexCurrency currency) {
        String key = keysByCurrency.get(currency);
        if (key != null) return key;
        // Fallback: attempt reverse lookup (should not be needed in normal flow)
        for (Map.Entry<String, NexCurrency> e : currenciesByKey.entrySet()) {
            if (e.getValue() == currency) return e.getKey();
        }
        // As last resort, return a lower-cased plain string of name (not ideal)
        return currency.getName().toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Finds and retrieves a list of {@link NexCurrency} instances that match the specified {@link NexCurrencyType}.
     *
     * @param type the {@link NexCurrencyType} to filter the currencies. Must not be null.
     * @return a list of {@link NexCurrency} instances that match the provided type. If no matches are found, an empty list is returned.
     */
    public List<NexCurrency> findByType(NexCurrencyType type) {
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

        String rawType = config.getString("currency-type", "vault");
        String normalizedType = rawType.trim().toUpperCase(Locale.ROOT);

        NexCurrencyType parsedType;
        try {
            parsedType = NexCurrencyType.valueOf(normalizedType);
        } catch (IllegalArgumentException ex) {
            NexEconomy.nexusLogger.warning("Unknown currency type '" + rawType + "' in " + file.getName() + ", falling back to VAULT");
            parsedType = NexCurrencyType.VAULT;
        }
        final NexCurrencyType currencyType = parsedType;

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
            @Override public NexCurrencyType getCurrencyType() { return currencyType; }
            @Override public BigDecimal getStartBalance() { return startBalance; }
            @Override public BigDecimal getMaxBalance() { return maxBalance; }
        };
    }

    private boolean createFileFromCurrency(NexCurrency currency) {

        if (currency.getCurrencyType() == NexCurrencyType.VAULT) {
            NexEconomy.nexusLogger.warning("Cannot create file for VAULT currency: " + currency.getName());
            NexEconomy.nexusLogger.warning("Vault currencies are automatically registered and loaded by the plugin!");
            return false;
        }

        File file = new File("/currencies/extern/" + currency.getName() + ".yml");
        if (file.exists()) {
            NexEconomy.nexusLogger.warning("Currency file already exists for " + currency.getName());
            return false;
        }

        try {
            if(file.createNewFile()) {
                NexEconomy.nexusLogger.info("Created new currency file for " + currency.getName());
            } else {
                NexEconomy.nexusLogger.warning("Failed to create currency file for " + currency.getName());
                return false;
            }
        } catch (IOException e) {
            NexEconomy.nexusLogger.warning("Failed to create currency file for " + currency.getName());
            NexEconomy.nexusLogger.warning("Please check the file permissions and try again.");
            throw new RuntimeException(e);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("name", currency.getName());
        config.set("symbol.plural", currency.getPluralSymbol());
        config.set("symbol.singular", currency.getSingularSymbol());
        config.set("placeholder", currency.getPlaceholder());
        config.set("command.main", currency.getMainCommand());
        config.set("command.aliases", currency.getAliases());
        config.set("fraction-digits", currency.getFractionDigits());
        config.set("currency-type", currency.getCurrencyType().name());
        config.set("start-balance", currency.getStartBalance());
        config.set("max-balance", currency.getMaxBalance());

        try {
            config.save(file);
            return true;
        } catch (IOException e) {
            NexEconomy.nexusLogger.warning("Failed to save currency file for " + currency.getName());
            NexEconomy.nexusLogger.warning("Please check the file permissions and try again.");
            throw new RuntimeException(e);
        }
    }
}