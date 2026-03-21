package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.languageservice.service.language.LanguageService;
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
    LanguageService.class
})
public class EconomyReloadCommand implements Service {

  private final ComponentService componentService;
  private final LanguageService languageService;

  public EconomyReloadCommand(ServiceAccessor accessor) {
    this.componentService = accessor.getService(ComponentService.class);
    this.languageService = accessor.getService(LanguageService.class);
  }

  @Command(value = "reload", permission = "nexeconomy.admin")
  public int reload(NexPaperCommandSource source) {
    Player player = (Player) source.sender();
    if(player == null) return 0;


    player.sendMessage(componentService.builder(player, "general.reload", "NotDefined", true).build());
    return 1; //0 if failed
  }

}
