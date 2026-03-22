package io.nexstudios.nexeconomy.command.suggestions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.commandservice.service.commands.factory.suggest.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AmountSuggestion implements SuggestionProvider {

  private static final List<String> SUGGESTIONS = List.of(
      "50",
      "100",
      "1500",
      "5000"
  );


  @Override
  public CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    String remaining = builder.getRemainingLowerCase();

    for (String s : SUGGESTIONS) {
      if (remaining.isEmpty() || s.startsWith(remaining)) {
        builder.suggest(s);
      }
    }
    return builder.buildFuture();
  }
}
