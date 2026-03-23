package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.EconomyRedisSyncService;
import io.nexstudios.nexeconomy.service.migration.MigrationRequest;
import io.nexstudios.nexeconomy.service.migration.MigrationResult;
import io.nexstudios.nexeconomy.service.migration.MigrationService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

@CommandRoot(
    name = "nexeconomy",
    description = "NexEconomy admin command"
)
@Dependencies({
    ComponentService.class,
    CurrencyRegistryService.class,
    MigrationService.class,
    PaperPluginService.class,
    EconomyPlayerCacheService.class,
    EconomyRedisSyncService.class
})
public class EconomyMigrationCommand implements Service {

  private final ComponentService componentService;
  private final CurrencyRegistryService currencyRegistry;
  private final MigrationService migrationService;
  private final Plugin plugin;
  private final EconomyPlayerCacheService playerCache;
  private final EconomyRedisSyncService redisSync;

  public EconomyMigrationCommand(ServiceAccessor accessor) {
    this.componentService = accessor.getService(ComponentService.class);
    this.currencyRegistry = accessor.getService(CurrencyRegistryService.class);
    this.migrationService = accessor.getService(MigrationService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.playerCache = accessor.getService(EconomyPlayerCacheService.class);
    this.redisSync = accessor.getService(EconomyRedisSyncService.class);
  }

  @Command(value = "migrate list", permission = "nexeconomy.admin")
  public int list(NexPaperCommandSource source) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    String ids = String.join(", ", migrationService.availableImporterIds());

    player.sendMessage(componentService.builder(player, "migration.list", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("importers", ids.isBlank() ? "none" : ids)
        ))
        .build());
    return 1;
  }

  @Command(value = "migrate vault", permission = "nexeconomy.admin")
  public int migrateVault(NexPaperCommandSource source) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    String vaultCurrencyId = currencyRegistry.vaultCurrencyId();
    if (vaultCurrencyId == null || vaultCurrencyId.isBlank()) {
      player.sendMessage(componentService.builder(player, "migration.no-vault-currency", "NotDefined", true).build());
      return 0;
    }

    player.sendMessage(componentService.builder(player, "migration.start", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("currency", vaultCurrencyId)))
        .build());

    MigrationRequest req = MigrationRequest.vaultDefaults(vaultCurrencyId, false, false, 0);

    migrationService.migrate(req).thenAccept(result ->
        Bukkit.getScheduler().runTask(plugin, () -> sendResultAndRefreshCache(player, result))
    ).exceptionally(ex -> {
      Bukkit.getScheduler().runTask(plugin, () -> sendError(player, ex));
      return null;
    });

    return 1;
  }

  @Command(value = "migrate vault dryrun", permission = "nexeconomy.admin")
  public int migrateVaultDryRun(NexPaperCommandSource source) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    String vaultCurrencyId = currencyRegistry.vaultCurrencyId();
    if (vaultCurrencyId == null || vaultCurrencyId.isBlank()) {
      player.sendMessage(componentService.builder(player, "migration.no-vault-currency", "NotDefined", true).build());
      return 0;
    }

    player.sendMessage(componentService.builder(player, "migration.start-dryrun", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("currency", vaultCurrencyId)))
        .build());

    MigrationRequest req = MigrationRequest.vaultDefaults(vaultCurrencyId, true, false, 0);

    migrationService.migrate(req).thenAccept(result ->
        Bukkit.getScheduler().runTask(plugin, () -> sendResultAndRefreshCache(player, result))
    ).exceptionally(ex -> {
      Bukkit.getScheduler().runTask(plugin, () -> sendError(player, ex));
      return null;
    });

    return 1;
  }

  @Command(value = "migrate vault overwrite", permission = "nexeconomy.admin")
  public int migrateVaultOverwrite(NexPaperCommandSource source) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    String vaultCurrencyId = currencyRegistry.vaultCurrencyId();
    if (vaultCurrencyId == null || vaultCurrencyId.isBlank()) {
      player.sendMessage(componentService.builder(player, "migration.no-vault-currency", "NotDefined", true).build());
      return 0;
    }

    player.sendMessage(componentService.builder(player, "migration.start-overwrite", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("currency", vaultCurrencyId)))
        .build());

    MigrationRequest req = MigrationRequest.vaultDefaults(vaultCurrencyId, false, true, 0);

    migrationService.migrate(req).thenAccept(result ->
        Bukkit.getScheduler().runTask(plugin, () -> sendResultAndRefreshCache(player, result))
    ).exceptionally(ex -> {
      Bukkit.getScheduler().runTask(plugin, () -> sendError(player, ex));
      return null;
    });

    return 1;
  }

  private void sendError(Player player, Throwable ex) {
    if (player == null) return;

    String msg = ex == null
        ? "Unknown"
        : (ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage());

    player.sendMessage(componentService.builder(player, "migration.failed", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("error", msg)))
        .build());
  }

  private void sendResultAndRefreshCache(Player player, MigrationResult result) {
    if (player == null || result == null) return;

    TagResolver r = TagResolver.resolver(
        Placeholder.parsed("importer", result.importerId()),
        Placeholder.parsed("currency", result.targetCurrencyIdLower()),
        Placeholder.parsed("dryrun", String.valueOf(result.dryRun())),
        Placeholder.parsed("processed", String.valueOf(result.processedPlayers())),
        Placeholder.parsed("written", String.valueOf(result.writtenPlayers())),
        Placeholder.parsed("skipped", String.valueOf(result.skippedPlayers())),
        Placeholder.parsed("failed", String.valueOf(result.failedPlayers())),
        Placeholder.parsed("total", result.totalImportedHuman() == null ? "0" : result.totalImportedHuman().toPlainString())
    );

    componentService.getComponents(player, "migration.result", "NotDefined", r, true)
        .forEach(player::sendMessage);

    if (result.dryRun()) return;

    player.sendMessage(componentService.builder(player, "migration.cache-refresh-start", "NotDefined", true).build());

    var online = Bukkit.getOnlinePlayers();

    CompletableFuture<?>[] futures = online.stream().map(p -> {
      // Local cache refresh
      playerCache.invalidate(p.getUniqueId());

      // Cross-server invalidation (other servers will drop their cache for that player)
      if (redisSync != null) {
        redisSync.publishInvalidatePlayer(p.getUniqueId());
      }

      return playerCache.loadOrCreateOnline(p);
    }).toArray(CompletableFuture[]::new);

    CompletableFuture.allOf(futures).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
      if (err != null) {
        sendError(player, err);
        return;
      }
      player.sendMessage(componentService.builder(player, "migration.cache-refresh-complete", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("amount", String.valueOf(online.size()))))
          .build());
    }));
  }
}