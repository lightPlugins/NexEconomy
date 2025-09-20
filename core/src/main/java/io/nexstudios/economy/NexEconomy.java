package io.nexstudios.economy;

import io.nexstudios.economy.provider.NexVaultProvider;
import io.nexstudios.nexus.bukkit.files.NexusFile;
import io.nexstudios.nexus.bukkit.files.NexusFileReader;
import io.nexstudios.nexus.bukkit.handler.MessageSender;
import io.nexstudios.nexus.bukkit.language.NexusLanguage;
import io.nexstudios.nexus.bukkit.utils.NexusLogger;
import io.nexstudios.nexus.libs.commands.PaperCommandManager;
import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;

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

    public NexVaultProvider nexVaultProvider;
    public NexEcoFactory nexEcoFactory;


    @Override
    public void onLoad() {
        instance = this;
        if(!checkPluginRequirements()) return;
        nexusLogger = new NexusLogger("<reset>[<red>NexEconomy<reset>]", true, 99, "<red>");
        nexusLogger.info("Loading <red>NexEconomy<reset>...");
    }

    @Override
    public void onEnable() {
        nexusLogger.info("Starting up ...");
        commandManager = new PaperCommandManager(this);
        nexusLogger.info("Load files and drop tables ...");
        onReload();
        nexEcoFactory = new NexEcoFactory(currencyFiles);
        nexVaultProvider = new NexVaultProvider(nexEcoFactory.getVaultCurrency());
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
        nexusLogger.info("Successfully disabled NexEconomy");
    }

    public void onReload() {
        loadNexusFiles();
    }


    private void registerCommands() {

    }

    private void registerListeners() {

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
        if(Bukkit.getPluginManager().getPlugin("Nexus") == null) {
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
        if(rsp == null) {
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


}
