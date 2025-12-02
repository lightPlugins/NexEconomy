package io.nexstudios.economy;

import io.nexstudios.economy.commands.CurrencyCommand;
import io.nexstudios.economy.commands.MainCommand;
import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.placeholder.NexEconomyPlaceholderProvider;
import io.nexstudios.economy.storage.InMemoryEcoService;
import io.nexstudios.economy.storage.persistence.EcoPersistencePort;
import io.nexstudios.economy.storage.persistence.sql.EcoSqlDialect;
import io.nexstudios.economy.storage.persistence.sql.EcoSqlPersistence;
import io.nexstudios.economy.storage.persistence.sql.SqlDialectResolver;
import io.nexstudios.economy.storage.support.EcoFlushScheduler;
import io.nexstudios.economy.storage.support.TransactionLogger;
import io.nexstudios.economy.provider.VaultProvider;
import io.nexstudios.nexus.bukkit.database.api.NexusDatabaseService;
import io.nexstudios.nexus.bukkit.files.NexusFile;
import io.nexstudios.nexus.bukkit.files.NexusFileReader;
import io.nexstudios.nexus.bukkit.handler.MessageSender;
import io.nexstudios.nexus.bukkit.language.NexusLanguage;
import io.nexstudios.nexus.bukkit.placeholder.NexusPlaceholderRegistry;
import io.nexstudios.nexus.bukkit.utils.NexusLogger;
import io.nexstudios.nexus.libs.commands.PaperCommandManager;
import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Getter
public class NexEconomy extends JavaPlugin {

    @Getter
    private static NexEconomy instance;
    public PaperCommandManager commandManager;
    public static NexusLogger nexusLogger;
    public NexusFile settingsFile;
    public NexusFileReader languageFiles;
    public NexusFileReader currencyFiles;
    public NexusLanguage nexusLanguage;
    public MessageSender messageSender;

    // New
    private NexusDatabaseService db;
    private EcoPersistencePort ecoPersistence;
    private TransactionLogger transactionLogger;
    private InMemoryEcoService ecoService;
    private EcoFlushScheduler flushScheduler;
    private VaultProvider nexVaultProvider;
    private NexEcoFactory nexEcoFactory;
    private EcoPlayerListener playerListener;

    private Map<String, CurrencyCommand> currencyCommandMap = new HashMap<>();
    private List<CurrencyCommand> registeredCurrencyCommands = new ArrayList<>();

    @Override
    public void onLoad() {
        instance = this;
        if (!checkPluginRequirements()) return;
        nexusLogger = new NexusLogger("<reset>[<red>NexEconomy<reset>]", true, 99, "<red>");
        nexusLogger.info("Loading <red>NexEconomy<reset>...");
    }

    @Override
    public void onEnable() {
        nexusLogger.info("Starting up ...");
        commandManager = new PaperCommandManager(this);

        nexusLogger.info("Load files and drop tables ...");
        onReload();

        // Register database
        registerDatabase();

        // Build factory (currencies)
        nexEcoFactory = new NexEcoFactory(currencyFiles);

        // Build transaction logger
        transactionLogger = new TransactionLogger(this);

        // Build persistence from Nexus DB
        ecoPersistence = buildEcoPersistence();

        // Build economy service (cache-first)
        ecoService = new InMemoryEcoService(
                key -> nexEcoFactory.findByKey(key),
                nexEcoFactory::keyOf,
                ecoPersistence,
                transactionLogger
        );

        registerPlaceholders();

        try {
            var fut = ecoPersistence.loadAllAccountsAllPlayers();
            var all = fut.get(15, TimeUnit.SECONDS); // timeout configurable later
            int imported = ecoService.importAllSnapshots(all);
            nexusLogger.info("Startup warm-load imported " + imported + " account(s) into cache.");
        } catch (TimeoutException te) {
            nexusLogger.warning("Startup warm-load timed out, continuing without full preload.");
        } catch (Exception ex) {
            nexusLogger.error("Startup warm-load failed: " + ex.getMessage());
        }


        // Start periodic flush (configurable later)
        flushScheduler = new EcoFlushScheduler(this, ecoService);
        flushScheduler.start(Duration.ofSeconds(60)); // example: every 60s

        // Register listeners for join/quit
        playerListener = new EcoPlayerListener(ecoService);
        getServer().getPluginManager().registerEvents(playerListener, this);

        // Build Vault provider for "vault" currency and register
        NexCurrency vaultCurrency = nexEcoFactory.getVaultCurrency();
        nexVaultProvider = new VaultProvider(vaultCurrency, ecoService);

        nexusLogger.info("Register commands ...");
        registerCommands();

        nexusLogger.info("Register events ...");
        registerListeners();

        nexusLogger.info("Register services ...");
        registerEconomyProvider();

        nexusLogger.info("Successfully started up.");
    }

    @Override
    public void onDisable() {
        // Stop scheduler and do final flush
        if (flushScheduler != null) {
            flushScheduler.stop();
        }
        if (ecoService != null) {
            try {
                int n = ecoService.flushAllNow();
                nexusLogger.info("Eco: final flush wrote " + n + " account(s).");
            } catch (Throwable t) {
                nexusLogger.error("Eco: final flush failed: " + t.getMessage());
            }
        }
        // Unregister events
        HandlerList.unregisterAll(this);
        nexusLogger.info("Successfully disabled NexEconomy");
    }

    public void onReload() {
        loadNexusFiles();
        this.messageSender = new MessageSender(nexusLanguage);

        // Rebuild factory with new currency configs
        if (nexEcoFactory != null) {
            nexusLogger.info("Reloading currency configurations...");

            // Check for removed currencies
            Set<String> oldKeys = new HashSet<>(currencyCommandMap.keySet());

            nexEcoFactory = new NexEcoFactory(currencyFiles);

            Set<String> newKeys = nexEcoFactory.getCurrencies().stream()
                    .map(nexEcoFactory::keyOf)
                    .collect(java.util.stream.Collectors.toSet());

            oldKeys.removeAll(newKeys);
            if (!oldKeys.isEmpty()) {
                nexusLogger.warning("The following currencies were removed but their commands remain active until server restart:");
                oldKeys.forEach(key -> nexusLogger.warning(" - " + key));
            }

            // Refresh all cached accounts with new currency instances
            int refreshed = ecoService.refreshCurrencies();
            nexusLogger.info("Refreshed " + refreshed + " cached account(s) with new currency configs.");

            // Update VaultProvider with new currency instance (without re-registering)
            NexCurrency vaultCurrency = nexEcoFactory.getVaultCurrency();
            nexVaultProvider.updateCurrency(vaultCurrency);

            boolean redisEnabled = settingsFile != null && settingsFile.getBoolean("economy.redis.enabled", false);

            // Detect new currencies
            Set<String> newCurrencies = new HashSet<>(newKeys);
            newCurrencies.removeAll(currencyCommandMap.keySet());

            // Update existing commands and register new ones
            int updated = 0;
            int registered = 0;

            for (var currency : nexEcoFactory.getCurrencies()) {
                String key = nexEcoFactory.keyOf(currency);
                CurrencyCommand existingCmd = currencyCommandMap.get(key);

                if (existingCmd != null) {
                    // Update existing command
                    existingCmd.updateCurrency(currency);
                    updated++;
                    nexusLogger.info("Updated currency command for: " + key);
                } else {
                    // Register new command
                    String main = currency.getMainCommand();
                    List<String> aliases = currency.getAliases() != null ? currency.getAliases() : List.of();
                    String joined = buildAliasString(main, aliases);

                    commandManager.getCommandReplacements().addReplacement("currency", joined);

                    var newCmd = new CurrencyCommand(
                            currency, key, ecoService, commandManager, nexusLanguage, redisEnabled, transactionLogger
                    );
                    commandManager.registerCommand(newCmd);
                    currencyCommandMap.put(key, newCmd);
                    registered++;

                    nexusLogger.info("Registered new currency command: /" + sanitizeAlias(main)
                            + (aliases.isEmpty() ? "" : " (" + String.join(", ", aliases) + ")"));
                }
            }

            nexusLogger.info("Currency configurations reloaded: " + updated + " updated, " + registered + " newly registered.");

            // Auto-initialize accounts for new currencies
            if (!newCurrencies.isEmpty()) {
                Bukkit.getScheduler().runTaskAsynchronously(this, () ->
                        autoInitNewCurrencies(newCurrencies)
                );
            }
        }
    }

    /**
     * Automatically creates accounts for new currencies for all players who have a vault account.
     */
    private void autoInitNewCurrencies(Set<String> newCurrencyKeys) {
        try {
            nexusLogger.info("Auto-initializing accounts for new currencies: " + String.join(", ", newCurrencyKeys));

            // 1. First: Create accounts for all online players (immediate)
            Set<UUID> onlinePlayerIds = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getUniqueId)
                    .collect(Collectors.toSet());

            int onlineCreated = 0;
            for (UUID playerId : onlinePlayerIds) {
                var newCurrencies = newCurrencyKeys.stream()
                        .map(nexEcoFactory::findByKey)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .toList();

                int created = ecoService.ensureAccountsForPlayer(playerId, newCurrencies);
                if (created > 0) {
                    ecoService.flushPlayerNow(playerId);
                    onlineCreated += created;
                }
            }

            nexusLogger.info("Created " + onlineCreated + " account(s) for " + onlinePlayerIds.size() + " online player(s).");

            // 2. Then: Get all players with vault accounts from DB
            String vaultKey = nexEcoFactory.keyOf(nexEcoFactory.getVaultCurrency());
            Set<UUID> vaultPlayerIds = ecoPersistence.getAllPlayerIdsWithCurrency(vaultKey)
                    .get(30, TimeUnit.SECONDS);

            // Remove online players (already handled)
            vaultPlayerIds.removeAll(onlinePlayerIds);

            if (vaultPlayerIds.isEmpty()) {
                nexusLogger.info("No offline players with vault accounts found.");
                return;
            }

            nexusLogger.info("Found " + vaultPlayerIds.size() + " offline player(s) with vault accounts. Creating accounts...");

            // 3. Create accounts for offline players in batches
            int batchSize = 100;
            List<UUID> playerList = new ArrayList<>(vaultPlayerIds);
            int offlineCreated = 0;

            for (int i = 0; i < playerList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, playerList.size());
                List<UUID> batch = playerList.subList(i, end);

                for (UUID playerId : batch) {
                    var newCurrencies = newCurrencyKeys.stream()
                            .map(nexEcoFactory::findByKey)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .toList();

                    int created = ecoService.ensureAccountsForPlayer(playerId, newCurrencies);
                    if (created > 0) {
                        ecoService.flushPlayerNow(playerId);
                        offlineCreated += created;
                    }
                }

                nexusLogger.info("Progress: " + end + "/" + playerList.size() + " players processed.");
            }

            nexusLogger.info("Successfully created " + offlineCreated + " account(s) for " + vaultPlayerIds.size() + " offline player(s).");

        } catch (Exception e) {
            nexusLogger.error("Failed to auto-initialize new currency accounts: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void registerCommands() {
        boolean redisEnabled = settingsFile != null && settingsFile.getBoolean("economy.redis.enabled", false);

        // Global completions (dynamic placeholders)
        commandManager.getCommandCompletions().registerCompletion("ecoPlayers",
                c -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        commandManager.getCommandCompletions().registerCompletion("ecoAmounts", c -> List.of("1", "10", "100"));
        commandManager.getCommandCompletions().registerCompletion("ecoFlags", c -> List.of("-s"));
        commandManager.getCommandCompletions().registerCompletion("ecoAllPlayers", c -> {
            Set<String> names = new HashSet<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null) names.add(op.getName());
            }
            return new ArrayList<>(names);
        });

        for (var currency : nexEcoFactory.getCurrencies()) {
            String key = nexEcoFactory.keyOf(currency);
            String main = currency.getMainCommand();
            List<String> aliases = currency.getAliases() != null ? currency.getAliases() : List.of();

            // Build replacement string: "main|alias1|alias2"
            String joined = buildAliasString(main, aliases);
            nexusLogger.debug("Built alias string for '" + key + "': " + joined, 1);

            // IMPORTANT: set the replacement BEFORE registering the command
            commandManager.getCommandReplacements().addReplacement("currency", joined);

            var cmd = new CurrencyCommand(
                    currency, key, ecoService, commandManager, nexusLanguage, redisEnabled, transactionLogger
            );
            commandManager.registerCommand(cmd);

            // Store command for reload updates
            currencyCommandMap.put(key, cmd);

            nexusLogger.info("Registered currency command: /" + sanitizeAlias(main)
                    + (aliases.isEmpty() ? "" : " (" + String.join(", ", aliases) + ")"));
        }

        // Register global commands (only once)
        commandManager.registerCommand(new MainCommand());
    }

    private static String sanitizeAlias(String s) {
        if (s == null) return "";
        String t = s.trim();
        // Strip leading slash
        if (t.startsWith("/")) t = t.substring(1);

        // Strip surrounding placeholder markers like %name% -> name
        if (t.length() >= 2 && t.startsWith("%") && t.endsWith("%")) {
            t = t.substring(1, t.length() - 1).trim();
        }

        // Strip accidental leading/trailing '%' if any remain
        while (t.startsWith("%")) t = t.substring(1).trim();
        while (t.endsWith("%")) t = t.substring(0, t.length() - 1).trim();

        return t;
    }

    private static String buildAliasString(String main, List<String> aliases) {
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        String m = sanitizeAlias(main);
        if (!m.isEmpty()) parts.add(m);
        if (aliases != null) {
            for (String a : aliases) {
                String s = sanitizeAlias(a);
                if (!s.isEmpty()) parts.add(s);
            }
        }
        // Join with '|', required by ACF replacement aliases
        return String.join("|", parts);
    }


    private void registerListeners() { }

    private void loadNexusFiles() {
        settingsFile = new NexusFile(this, "settings.yml", nexusLogger, true);
        new NexusFile(this, "languages/english.yml", nexusLogger, true);
        nexusLogger.setDebugEnabled(settingsFile.getBoolean("logging.debug.enable", true));
        nexusLogger.setDebugLevel(settingsFile.getInt("logging.debug.level", 3));
        languageFiles = new NexusFileReader("languages", this);
        nexusLanguage = new NexusLanguage(languageFiles, nexusLogger);
        new NexusFile(this, "currencies/vault.yml", nexusLogger, true);
        currencyFiles = new NexusFileReader("currencies", this);
        nexusLogger.info("All Nexus files have been (re)loaded successfully.");
    }

    private boolean checkPluginRequirements() {
        if (Bukkit.getPluginManager().getPlugin("Nexus") == null) {
            getLogger().severe("NexEconomy requires the Nexus plugin to be installed.");
            getLogger().severe("Please download the plugin from https://www.spigotmc.org/resources/nexus.10000/");
            getLogger().severe("Disabling NexEconomy ...");
            Bukkit.getPluginManager().disablePlugin(this);
            return false;
        }
        return true;
    }

    private void registerEconomyProvider() {
        Bukkit.getServicesManager().register(Economy.class, nexVaultProvider, this, ServicePriority.Highest);
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            nexusLogger.error("Could not register NexVaultProvider for Vault. Please contact the plugin author.");
            nexusLogger.error(List.of(
                    "Could not register NexVaultProvider for Vault!",
                    "Possible reasons:",
                    " - The Vault plugin is not installed or enabled. (show in console)",
                    " - Nexus is not installed or enabled. (show in console)",
                    " ",
                    "If the reasons above are not the case, please contact the plugin author",
                    "including the following information:",
                    " - Plugin version: " + getPluginMeta().getVersion(),
                    " - Server version: " + Bukkit.getVersion(),
                    " - Java version: " + System.getProperty("java.version"),
                    " - Nexus version: " + Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Nexus")).getPluginMeta().getVersion(),
                    " - Complete server log (copy paste to https://mclo.gs)",
                    " ",
                    "Disabling NexEconomy ..."
            ));
            Bukkit.getPluginManager().disablePlugin(this);
        } else {
            nexusLogger.info("NexVaultProvider registered successfully.");
        }
    }

    private void registerDatabase() {
        RegisteredServiceProvider<NexusDatabaseService> reg =
                getServer().getServicesManager().getRegistration(NexusDatabaseService.class);

        if (reg == null) {
            nexusLogger.error("Could not find NexusDatabaseService. Disabling plugin");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.db = reg.getProvider();

        if (!db.isHealthy()) {
            nexusLogger.error("It looks like the database is not healthy! (isHealthy=false)");
            return;
        }

        try {
            db.isHealthy();
        } catch (Exception e) {
            nexusLogger.error("Database health check failed: " + e.getMessage());
        }
    }

    private EcoPersistencePort buildEcoPersistence() {
        // Resolve dialect using JDBC metadata from NexusDatabaseService
        EcoSqlDialect dialect = EcoSqlDialect.MYSQL;
        try {
            dialect = db.withConnection(SqlDialectResolver::resolve);
        } catch (Exception ignored) {
            // Fallback remains MYSQL
        }
        DataSource ds = db.getDataSource();
        return new EcoSqlPersistence(ds, dialect);
    }

    private void registerPlaceholders() {
        try {
            var provider = new NexEconomyPlaceholderProvider(this, ecoService);

            long defaultTtlMillis = Duration.ofSeconds(1).toMillis();

            NexusPlaceholderRegistry.CachePolicy policy = new NexusPlaceholderRegistry.CachePolicy(
                    defaultTtlMillis,
                    Set.of(),
                    Map.of()
            );

            boolean registered = NexusPlaceholderRegistry.register(
                    this,
                    "nexeconomy",
                    provider,
                    policy
            );

            if (registered) {
                nexusLogger.info("Registered NexEconomy placeholders under namespace 'nexeconomy'.");
            } else {
                nexusLogger.warning("Failed to register NexEconomy placeholders (namespace 'nexeconomy').");
            }
        } catch (Throwable t) {
            nexusLogger.error("Error while registering NexEconomy placeholders: " + t.getMessage());
        }
    }

}