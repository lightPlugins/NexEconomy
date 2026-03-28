package io.nexstudios.nexeconomy;

import io.nexstudios.commandservice.CommandServiceModule;
import io.nexstudios.commandservice.service.commands.CommandService;
import io.nexstudios.configservice.ConfigServiceModule;
import io.nexstudios.framework.paper.NexPaperPlugin;
import io.nexstudios.itemservice.bukkit.ItemServiceModule;
import io.nexstudios.languageservice.LanguageServiceModule;
import io.nexstudios.languageservice.service.language.LanguageService;
import io.nexstudios.nexeconomy.command.*;
import io.nexstudios.nexeconomy.modules.BankCoreModule;
import io.nexstudios.nexeconomy.modules.EconomyCoreModule;
import io.nexstudios.nexeconomy.provider.VaultEconomyBridgeService;
import io.nexstudios.nexeconomy.service.bank.listener.BankPlayerListener;
import io.nexstudios.nexeconomy.service.bank.sync.BankRedisSyncService;
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.EconomyRedisSyncService;
import io.nexstudios.nexeconomy.service.economy.listener.EconomyPlayerListener;
import io.nexstudios.nexeconomy.service.placeholder.EconomyPlaceholderService;
import io.nexstudios.nexlogic.bukkit.NexLogicPlugin;
import io.nexstudios.nexlogic.bukkit.services.effects.logging.BukkitLoggerService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import io.nexstudios.serviceregistry.di.ServiceModule;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class NexEconomyPlugin extends NexPaperPlugin {

  @Getter
  private static ServiceAccessor nexLogicService;

  @Override
  protected void configureServices(@NotNull ServiceAccessor services) {

    // install ConfigService
    services.install(new ConfigServiceModule(getDataPath(), getClassLoader()));
    // install LanguageService (require ConfigService loaded)
    services.install(new LanguageServiceModule(this));
    // install ItemService
    services.install(new ItemServiceModule(this));
    // install Command Service
    services.install(new CommandServiceModule(this));

    services.register(LoggerService.class, BukkitLoggerService.class);

    initNexLogic();
    // install internal ServiceModule
    List<ServiceModule> modules = List.of(
        new EconomyCoreModule(),
        new BankCoreModule()
    );
    services().installAll(modules);

  }

  @Override
  protected void load() {
    getLogger().info("NexEconomy is loading...");

    services().getService(VaultEconomyBridgeService.class);

  }

  @Override
  protected void start() {
    getLogger().info("NexEconomy is starting...");

    // start background services that require an enabled plugin
    services().getService(EconomyFlushService.class).start();

    // init language files
    services().getService(LanguageService.class).reload();

    // register commands
    services().getService(CommandService.class).registerAll(
        List.of(
            EconomyPayCommand.class,
            EconomyReloadCommand.class,
            EconomyMainCommand.class,
            EconomyMigrationCommand.class,
            EconomyStatusCommand.class,
            EconomyBankCommand.class,
            EconomyBankAdminCommand.class
        )
    );

    services().getService(EconomyRedisSyncService.class).start();
    services().getService(BankRedisSyncService.class).start();

    registerListeners(
        new EconomyPlayerListener(services()),
        new BankPlayerListener(services())
    );

    getLogger().info("NexEconomy successfully started.");
  }

  @Override
  protected void stop() {
    getLogger().info("NexEconomy Shutting down...");
    EconomyFlushService flush = services().getService(EconomyFlushService.class);

    flush.stop();
    // unregister placeholders
    services().findService(EconomyPlaceholderService.class).ifPresent(EconomyPlaceholderService::close);

    try {
      getLogger().info("Waiting for final economy flush to finish...");
      flush.flushAllDirtyAndWait().orTimeout(Duration.ofSeconds(15).toSeconds(), TimeUnit.SECONDS).join();
    } catch (Exception e) {
      getLogger().warning("Final economy flush did not finish before shutdown: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }

    getLogger().info("Successfully stopped NexEconomy. See you next time! :)");
  }

  private void initNexLogic() {
    getLogger().info("Hooking into NexLogic...");
    Plugin plugin = Bukkit.getPluginManager().getPlugin("NexLogic");
    if (plugin == null) {
      throw new IllegalStateException("Could not find NexLogic plugin! Please install it!");
    }

    if (!(plugin instanceof NexLogicPlugin nexLogic)) {
      throw new IllegalStateException("Plugin " + plugin.getName() + " is not a NexLogicPlugin: " + plugin.getClass().getName());
    }

    nexLogicService = nexLogic.services();
    getLogger().info("Successfully hooked into NexLogic!");
  }
}