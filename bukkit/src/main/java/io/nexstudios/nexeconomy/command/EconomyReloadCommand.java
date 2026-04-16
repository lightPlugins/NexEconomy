package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.languageservice.service.language.LanguageService;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.interest.BankInterestService;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.placeholder.EconomyPlaceholderService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.entity.Player;

@CommandRoot(
    name = "nexeconomy",
    description = "NexEconomy admin command"
)
@Dependencies({
    ComponentService.class,
    LanguageService.class,
    CurrencyRegistryService.class,
    EconomyPlayerCacheService.class,
    EconomyPlaceholderService.class,
    BankService.class,
    BankRegistryService.class,
    BankAccountCacheService.class,
    BankInterestService.class
})
public class EconomyReloadCommand implements Service {

  private final ComponentService componentService;
  private final LanguageService languageService;
  private final CurrencyRegistryService currencyRegistry;
  private final EconomyPlayerCacheService playerCache;
  private final EconomyPlaceholderService placeholderService;
  private final BankService bankService;
  private final BankRegistryService bankRegistry;
  private final BankAccountCacheService bankCache;
  private final BankInterestService bankInterestService;

  public EconomyReloadCommand(ServiceAccessor accessor) {
    this.componentService = accessor.getService(ComponentService.class);
    this.languageService = accessor.getService(LanguageService.class);
    this.currencyRegistry = accessor.getService(CurrencyRegistryService.class);
    this.playerCache = accessor.getService(EconomyPlayerCacheService.class);
    this.placeholderService = accessor.getService(EconomyPlaceholderService.class);
    this.bankService = accessor.getService(BankService.class);
    this.bankRegistry = accessor.getService(BankRegistryService.class);
    this.bankCache = accessor.getService(BankAccountCacheService.class);
    this.bankInterestService = accessor.getService(BankInterestService.class);
  }

  @Command(value = "reload", permission = "nexeconomy.admin")
  public int reload(NexPaperCommandSource source) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    languageService.reload();
    currencyRegistry.reload();
    playerCache.ensureMissingCurrenciesForAllOnline();
    placeholderService.reload();
    bankRegistry.reload();
    bankCache.reload();
    bankService.reload();
    bankService.ensureMissingUnlockedBanksForAllOnline();
    bankInterestService.reload();

    player.sendMessage(componentService.builder(player, "general.reload", "NotDefined", true).build());
    return 1;
  }
}