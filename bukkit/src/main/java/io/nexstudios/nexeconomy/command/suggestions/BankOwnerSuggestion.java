package io.nexstudios.nexeconomy.command.suggestions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.commandservice.service.commands.factory.suggest.SuggestionProvider;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class BankOwnerSuggestion implements SuggestionProvider, Service {

  private final ServiceAccessor accessor;

  public BankOwnerSuggestion(ServiceAccessor accessor) {
    this.accessor = accessor;
  }

  @Override
  public CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    String remaining = builder.getRemainingLowerCase();

    Player sender;
    try {
      sender = (Player) ctx.getSource().getSender();
    } catch (Exception ignored) {
      return builder.buildFuture();
    }

    UUID memberUuid = sender.getUniqueId();
    BankRepositoryService repo = accessor.getService(BankRepositoryService.class);
    if (repo == null) return builder.buildFuture();

    // If a "bank" argument exists in the command, filter to that bank.
    String bankIdLower = null;
    try {
      String bankRaw = ctx.getArgument("bank", String.class);
      if (bankRaw != null && !bankRaw.isBlank()) {
        bankIdLower = bankRaw.trim().toLowerCase(Locale.ROOT);
      }
    } catch (Exception ignored) {
      // command has no "bank" arg => suggest across all banks where sender is member
    }

    CompletableFuture<List<UUID>> ownersFuture = (bankIdLower == null)
        ? repo.findOwnerUuidsForMember(memberUuid)
        : repo.findOwnerUuidsForMember(bankIdLower, memberUuid);

    return ownersFuture.thenApply(owners -> {
      if (owners == null || owners.isEmpty()) return builder.build();

      for (UUID ownerUuid : owners) {
        if (ownerUuid == null) continue;

        OfflinePlayer off = Bukkit.getOfflinePlayer(ownerUuid);
        String name = off.getName();
        if (name == null || name.isBlank()) continue;

        String lower = name.toLowerCase(Locale.ROOT);
        if (remaining.isEmpty() || lower.startsWith(remaining)) {
          builder.suggest(name);
        }
      }

      return builder.build();
    }).exceptionally(ex -> builder.build());
  }
}