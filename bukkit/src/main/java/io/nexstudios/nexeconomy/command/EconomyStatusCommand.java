package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.databaseservice.bukkit.service.api.pubsub.PubSubHealth;
import io.nexstudios.databaseservice.bukkit.service.api.pubsub.RedisPubSubService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.EconomyBalanceEntity;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@CommandRoot(
    name = "nexeconomy",
    description = "NexEconomy admin command"
)
@Dependencies({
    ComponentService.class,
    CurrencyRegistryService.class,
    EconomyRepository.class,
    PaperPluginService.class
})
public final class EconomyStatusCommand implements Service {

  private final ComponentService componentService;
  private final CurrencyRegistryService currencies;
  private final EconomyRepository repo;
  private final Plugin plugin;

  public EconomyStatusCommand(ServiceAccessor accessor) {
    this.componentService = accessor.getService(ComponentService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.repo = accessor.getService(EconomyRepository.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
  }

  @Command(value = "status", permission = "nexeconomy.admin")
  public int status(NexPaperCommandSource source) {
    source.sender().sendMessage(componentService.builder(source.sender(), "status.start", "NotDefined", true).build());

    long dbStartNs = System.nanoTime();
    CompletableFuture<DbStatus> dbFuture = repo.ping()
        .thenApply(v -> DbStatus.ok(msSince(dbStartNs)))
        .exceptionally(ex -> DbStatus.failed(msSince(dbStartNs), ex));

    RedisStatus redisStatus = readRedisStatus();

    List<String> currencyIds = new ArrayList<>(currencies.currencyIds());

    CompletableFuture<List<CurrencyStat>> playerCurrencyFuture = countCurrencies(currencyIds, EconomyBalanceEntity.EconomyAccountType.PLAYER);
    CompletableFuture<List<CurrencyStat>> townyCurrencyFuture = countCurrencies(currencyIds, EconomyBalanceEntity.EconomyAccountType.TOWNY);

    CompletableFuture.allOf(dbFuture, playerCurrencyFuture, townyCurrencyFuture).whenComplete((v, err) -> {
      DbStatus db = dbFuture.getNow(DbStatus.failed(0, err));
      List<CurrencyStat> playerStats = playerCurrencyFuture.getNow(List.of());
      List<CurrencyStat> townyStats = townyCurrencyFuture.getNow(List.of());

      Bukkit.getScheduler().runTask(plugin, () -> sendStatus(source, db, redisStatus, playerStats, townyStats));
    });

    return 1;
  }

  private CompletableFuture<List<CurrencyStat>> countCurrencies(List<String> currencyIdsLower, EconomyBalanceEntity.EconomyAccountType accountTypeUpper) {
    CompletableFuture<List<CurrencyStat>> chain = CompletableFuture.completedFuture(new ArrayList<>());

    for (String cur : currencyIdsLower) {
      chain = chain.thenCompose(list -> {
        long start = System.nanoTime();
        return repo.countAccountsForCurrency(cur, accountTypeUpper).thenApply(count -> {
          list.add(new CurrencyStat(cur, count, msSince(start)));
          return list;
        }).exceptionally(ex -> {
          list.add(new CurrencyStat(cur, -1, msSince(start)));
          return list;
        });
      });
    }

    return chain;
  }

  private void sendStatus(
      NexPaperCommandSource source,
      DbStatus db,
      RedisStatus redis,
      List<CurrencyStat> playerCurrencyStats,
      List<CurrencyStat> townyCurrencyStats
  ) {
    componentService.getComponents(source.sender(), "status.header", "NotDefined", TagResolver.empty(), false)
        .forEach(source.sender()::sendMessage);

    // DB
    source.sender().sendMessage(componentService.builder(source.sender(), "status.db", "NotDefined", false)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("state", db.ok ? "OK" : "FAILED"),
            Placeholder.parsed("took", String.valueOf(db.tookMillis)),
            Placeholder.parsed("error", db.errorMessage == null ? "-" : db.errorMessage)
        ))
        .build());

    // Redis
    source.sender().sendMessage(componentService.builder(source.sender(), "status.redis", "NotDefined", false)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("enabled", String.valueOf(redis.enabled)),
            Placeholder.parsed("connected", String.valueOf(redis.connected)),
            Placeholder.parsed("message", redis.message == null ? "-" : redis.message),
            Placeholder.parsed("took", String.valueOf(redis.tookMillis))
        ))
        .build());

    // Currencies (PLAYER)
    componentService.getComponents(source.sender(), "status.currencies.header", "NotDefined", TagResolver.empty(), false)
        .forEach(source.sender()::sendMessage);

    for (CurrencyStat s : playerCurrencyStats) {
      String accounts = s.accounts >= 0 ? String.valueOf(s.accounts) : "error";

      source.sender().sendMessage(componentService.builder(source.sender(), "status.currencies.row", "NotDefined", false)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("currency", s.currencyIdLower),
              Placeholder.parsed("accounts", accounts),
              Placeholder.parsed("took", String.valueOf(s.tookMillis))
          ))
          .build());
    }

    // Towny accounts (per currency) - show as extra rows (won't break language files)
    for (CurrencyStat s : townyCurrencyStats) {
      String accounts = s.accounts >= 0 ? String.valueOf(s.accounts) : "error";

      source.sender().sendMessage(componentService.builder(source.sender(), "status.currencies.row", "NotDefined", false)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("currency", s.currencyIdLower + " (towny)"),
              Placeholder.parsed("accounts", accounts),
              Placeholder.parsed("took", String.valueOf(s.tookMillis))
          ))
          .build());
    }

    componentService.getComponents(source.sender(), "status.footer", "NotDefined", TagResolver.empty(), false)
        .forEach(source.sender()::sendMessage);
  }

  private RedisStatus readRedisStatus() {
    Optional<RedisPubSubService> pubSubOpt = NexEconomyPlugin.getNexLogicService().findService(RedisPubSubService.class);
    RedisPubSubService pubSub = pubSubOpt.orElse(null);

    if (pubSub == null) {
      return new RedisStatus(false, false, "Redis Pub/Sub service not available", 0);
    }

    boolean enabled = pubSub.isEnabled();
    boolean connected = pubSub.isConnected();

    if (!enabled) {
      return new RedisStatus(false, connected, "Redis Pub/Sub disabled (redis.enabled=false)", 0);
    }

    if (!connected) {
      PubSubHealth health = pubSub.healthCheck(Duration.ofSeconds(2));
      return new RedisStatus(true, false, health.message(), health.tookMillis());
    }

    PubSubHealth health = pubSub.healthCheck(Duration.ofSeconds(2));
    return new RedisStatus(true, true, health.message(), health.tookMillis());
  }

  private static long msSince(long startNs) {
    return (System.nanoTime() - startNs) / 1_000_000L;
  }

  private record DbStatus(boolean ok, long tookMillis, String errorMessage) {
    static DbStatus ok(long tookMillis) { return new DbStatus(true, tookMillis, null); }
    static DbStatus failed(long tookMillis, Throwable ex) {
      String msg = ex == null ? "Unknown" : (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
      return new DbStatus(false, tookMillis, msg);
    }
  }

  private record RedisStatus(boolean enabled, boolean connected, String message, long tookMillis) {}

  private record CurrencyStat(String currencyIdLower, long accounts, long tookMillis) {}
}