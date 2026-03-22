package io.nexstudios.nexeconomy;

import io.nexstudios.commandservice.CommandServiceModule;
import io.nexstudios.commandservice.service.commands.CommandService;
import io.nexstudios.configservice.ConfigServiceModule;
import io.nexstudios.framework.paper.NexPaperPlugin;
import io.nexstudios.itemservice.bukkit.ItemServiceModule;
import io.nexstudios.languageservice.LanguageServiceModule;
import io.nexstudios.languageservice.service.language.LanguageService;
import io.nexstudios.nexeconomy.command.EconomyPayCommand;
import io.nexstudios.nexeconomy.command.EconomyReloadCommand;
import io.nexstudios.nexeconomy.command.MoneyCommand;
import io.nexstudios.nexeconomy.modules.EconomyCoreModule;
import io.nexstudios.nexlogic.bukkit.NexLogicPlugin;
import io.nexstudios.nexlogic.bukkit.services.effects.logging.BukkitLoggerService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import io.nexstudios.serviceregistry.di.ServiceModule;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class NexEconomyPlugin extends NexPaperPlugin {

  @Getter
  private static ServiceAccessor nexLogicService;

  @Override
  protected void configureServices(@NotNull ServiceAccessor services) {

    initNexLogic();

    // install ConfigService
    services.install(new ConfigServiceModule(getDataPath(), getClassLoader()));
    // install LanguageService (require ConfigService loaded)
    services.install(new LanguageServiceModule(this));
    // install ItemService
    services.install(new ItemServiceModule(this));
    // install Command Service
    services.install(new CommandServiceModule(this));

    services.register(LoggerService.class, BukkitLoggerService.class);

    // install internal ServiceModules
    List<ServiceModule> modules = List.of(
        new EconomyCoreModule()
    );
    services.installAll(modules);

  }

  @Override
  protected void load() {
    getLogger().info("NexEconomy is loading...");
  }

  @Override
  protected void start() {
    getLogger().info("NexEconomy is starting...");

    // init language files
    services().getService(LanguageService.class).reload();

    // register commands
    services().getService(CommandService.class).registerAll(
        List.of(
            EconomyPayCommand.class,
            EconomyReloadCommand.class,
            MoneyCommand.class
        )
    );

    getLogger().info("NexEconomy successfully started.");
  }

  @Override
  protected void stop() {
    getLogger().info("NexEconomy stopped.");
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