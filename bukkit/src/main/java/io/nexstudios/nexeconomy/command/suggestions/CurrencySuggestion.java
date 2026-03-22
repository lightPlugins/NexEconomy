package io.nexstudios.nexeconomy.command.suggestions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.commandservice.service.commands.factory.suggest.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.concurrent.CompletableFuture;

public class CurrencySuggestion implements SuggestionProvider {


  @Override
  public CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> commandContext, SuggestionsBuilder suggestionsBuilder) {
    return null;
  }
}
