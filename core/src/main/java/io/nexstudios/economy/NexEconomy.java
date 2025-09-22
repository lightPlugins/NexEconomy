package io.nexstudios.economy;

import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.economy.InMemoryEcoService;
import io.nexstudios.economy.economy.persistence.EcoPersistencePort;
import io.nexstudios.economy.economy.persistence.sql.EcoSqlDialect;
import io.nexstudios.economy.economy.persistence.sql.EcoSqlPersistence;
import io.nexstudios.economy.economy.persistence.sql.SqlDialectResolver;
import io.nexstudios.economy.economy.support.EcoFlushScheduler;
import io.nexstudios.economy.economy.support.TransactionLogger;
import io.nexstudios.economy.provider.VaultProvider;
import io.nexstudios.nexus.bukkit.database.api.NexusDatabaseService;
import io.nexstudios.nexus.bukkit.files.NexusFile;
import io.nexstudios.nexus.bukkit.files.NexusFileReader;
import io.nexstudios.nexus.bukkit.handler.MessageSender;
import io.nexstudios.nexus.bukkit.language.NexusLanguage;
import io.nexstudios.nexus.bukkit.utils.NexusLogger;
import io.nexstudios.nexus.libs.commands.PaperCommandManager;
import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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


        try {
            var fut = ecoPersistence.loadAllAccountsAllPlayers();
            var all = fut.get(15, TimeUnit.SECONDS); // timeout configurable later
            int imported = ecoService.importAllSnapshots(all);
            nexusLogger.info("Startup warm-load imported " + imported + " account(s) into cache.");
        } catch (TimeoutException te) {
            nexusLogger.error("Startup warm-load timed out, continuing without full preload.");
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
    }

    private void registerCommands() {
        // register ACF commands if any
    }

    private void registerListeners() {
        // other listeners if any
    }

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
        javax.sql.DataSource ds = db.getDataSource();
        return new EcoSqlPersistence(ds, dialect);
    }

}