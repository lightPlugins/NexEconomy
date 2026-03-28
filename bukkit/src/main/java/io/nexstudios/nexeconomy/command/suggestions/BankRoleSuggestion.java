package io.nexstudios.nexeconomy.command.suggestions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.commandservice.service.commands.factory.suggest.SuggestionProvider;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class BankRoleSuggestion implements SuggestionProvider, Service {


  private final ServiceAccessor accessor;

  public BankRoleSuggestion(ServiceAccessor accessor) {
    this.accessor = accessor;
  }

  @Override
  public CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    String remaining = builder.getRemainingLowerCase();

    String bankRaw;
    try {
      bankRaw = ctx.getArgument("bank", String.class);
    } catch (Exception ignored) {
      return builder.buildFuture();
    }

    if (bankRaw == null || bankRaw.isBlank()) return builder.buildFuture();
    String bankIdLower = bankRaw.trim().toLowerCase(java.util.Locale.ROOT);

    BankRegistryService registry = accessor.getService(BankRegistryService.class);
    if (registry == null) return builder.buildFuture();

    BankDefinition def = registry.bank(bankIdLower).orElse(null);
    if (def == null) return builder.buildFuture();

    BankDefinition.MemberSystem ms = def.memberSystem();
    if (ms == null || !ms.enabled()) return builder.buildFuture();

    Map<String, BankDefinition.RoleDefinition> roles = ms.rolesByIdLower();
    if (roles == null || roles.isEmpty()) return builder.buildFuture();


    for (String id : roles.keySet()) {
      if (id == null || id.isBlank()) continue;
      if (remaining.isEmpty() || id.startsWith(remaining)) {
        builder.suggest(id);
      }
    }

    return builder.buildFuture();
  }
}