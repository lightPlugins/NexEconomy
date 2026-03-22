package io.nexstudios.nexeconomy.command.suggestions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.commandservice.service.commands.factory.suggest.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public class PlayerSuggestion implements SuggestionProvider {

  @Override
  public CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> commandContext, SuggestionsBuilder suggestionsBuilder) {
    String remaining = suggestionsBuilder.getRemainingLowerCase();

    for (Player player : Bukkit.getOnlinePlayers()) {
      String id = player.getName();
      if (remaining.isEmpty() || id.startsWith(remaining)) {
        suggestionsBuilder.suggest(id);
      }
    }

    return suggestionsBuilder.buildFuture();
  }
}
