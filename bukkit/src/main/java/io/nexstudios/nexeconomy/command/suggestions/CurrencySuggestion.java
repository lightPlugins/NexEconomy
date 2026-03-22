package io.nexstudios.nexeconomy.command.suggestions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.commandservice.service.commands.factory.suggest.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class CurrencySuggestion implements SuggestionProvider {

  @Override
  public CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    String remaining = builder.getRemainingLowerCase();

    Plugin plugin = Bukkit.getPluginManager().getPlugin("NexEconomy");
    if (plugin == null) return builder.buildFuture();

    File dir = new File(plugin.getDataFolder(), "currencies");
    File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
    if (files == null || files.length == 0) return builder.buildFuture();

    Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

    for (File f : files) {
      String id = toId(f.getName());
      if (id.isBlank()) continue;

      if (remaining.isEmpty() || id.startsWith(remaining)) {
        builder.suggest(id);
      }
    }

    return builder.buildFuture();
  }

  private static String toId(String fileName) {
    int idx = fileName.lastIndexOf('.');
    String base = idx < 0 ? fileName : fileName.substring(0, idx);
    return base.trim().toLowerCase(Locale.ROOT);
  }
}