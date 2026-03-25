package io.nexstudios.nexeconomy.command.suggestions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.commandservice.service.commands.factory.suggest.SuggestionProvider;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public final class BankSuggestion implements SuggestionProvider {

  @Override
  public CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    String remaining = builder.getRemainingLowerCase();

    BankRegistryService registry = NexEconomyPlugin.getNexLogicService() == null
        ? null
        : NexEconomyPlugin.getNexLogicService().findService(BankRegistryService.class).orElse(null);

    if (registry == null) return builder.buildFuture();

    Collection<String> ids = registry.bankIds();
    if (ids == null) return builder.buildFuture();

    for (String id : ids) {
      if (id == null || id.isBlank()) continue;
      if (remaining.isEmpty() || id.startsWith(remaining)) {
        builder.suggest(id);
      }
    }

    return builder.buildFuture();
  }
}