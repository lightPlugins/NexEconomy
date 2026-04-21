package io.nexstudios.nexeconomy.service.economy;

import io.nexstudios.databaseservice.bukkit.service.api.pubsub.PubSubHealth;
import io.nexstudios.databaseservice.bukkit.service.api.pubsub.PubSubMessage;
import io.nexstudios.databaseservice.bukkit.service.api.pubsub.PubSubSubscription;
import io.nexstudios.databaseservice.bukkit.service.api.pubsub.RedisPubSubService;
import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.EconomyBalanceEntity;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Dependencies({
    LoggerService.class,
    PaperPluginService.class,
    EconomyPlayerCacheService.class
})
public final class EconomyRedisSyncService implements Service, AutoCloseable {

  private static final String TOPIC = "economy";

  private static final String IDENTIFIER_INVALIDATE_PLAYER = "invalidate-player";
  private static final String IDENTIFIER_INVALIDATE_ACCOUNT = "invalidate-account";

  private final LoggerService logger;
  private final Plugin plugin;
  private final EconomyPlayerCacheService cache;

  private final Optional<RedisPubSubService> pubSubOpt;

  private PubSubSubscription subscription;

  public EconomyRedisSyncService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.cache = accessor.getService(EconomyPlayerCacheService.class);

    this.pubSubOpt = NexEconomyPlugin.getNexLogicService()
        .findService(RedisPubSubService.class);
  }

  public void start() {
    RedisPubSubService pubSub = pubSubOpt.orElse(null);
    if (pubSub == null) {
      logger.logger().warning("Redis Pub/Sub service not available. Cross-server sync is disabled.");
      return;
    }

    if (!pubSub.isEnabled()) {
      logger.logger().info("Redis Pub/Sub is disabled (NexLogic: redis.enabled=false). Multi-server sync is disabled.");
      return;
    }

    if (!pubSub.isConnected()) {
      PubSubHealth health = pubSub.healthCheck(Duration.ofSeconds(2));
      logger.logger().severe("Redis Pub/Sub is enabled, but not connected: " + health.message() + " (took=" + health.tookMillis() + "ms)");
      return;
    }

    String topic = pubSub.namespacedTopic(plugin, TOPIC);
    this.subscription = pubSub.subscribeOnMainThread(plugin, topic, this::handle);

    logger.logger().info("Redis Pub/Sub sync enabled. topic=" + topic + ", identifiers=[" + IDENTIFIER_INVALIDATE_PLAYER + "," + IDENTIFIER_INVALIDATE_ACCOUNT + "]");
  }

  public void publishInvalidatePlayer(UUID playerId) {
    publishInvalidateAccount(playerId, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  public void publishInvalidateAccount(UUID accountId, EconomyBalanceEntity.EconomyAccountType accountType) {
    Objects.requireNonNull(accountId, "accountId");

    RedisPubSubService pubSub = pubSubOpt.orElse(null);
    if (pubSub == null) return;
    if (!pubSub.isEnabled()) return;
    if (!pubSub.isConnected()) return;

    EconomyBalanceEntity.EconomyAccountType type = accountType == null
        ? EconomyBalanceEntity.EconomyAccountType.PLAYER
        : accountType;

    String topic = pubSub.namespacedTopic(plugin, TOPIC);

    pubSub.publishValues(
        topic,
        IDENTIFIER_INVALIDATE_ACCOUNT,
        Map.of(
            "accountId", accountId.toString(),
            "accountType", type.name()
        )
    );
  }

  private void handle(PubSubMessage msg) {
    RedisPubSubService pubSub = pubSubOpt.orElse(null);
    if (pubSub == null) return;

    // cross-server only
    if (Objects.equals(pubSub.originId(), msg.originId())) return;

    if (IDENTIFIER_INVALIDATE_PLAYER.equals(msg.identifier())) {
      Optional<String> playerIdStr = msg.stringValue("playerId");
      if (playerIdStr.isEmpty()) return;

      UUID playerId;
      try {
        playerId = UUID.fromString(playerIdStr.get());
      } catch (IllegalArgumentException ex) {
        return;
      }

      cache.invalidate(playerId, EconomyBalanceEntity.EconomyAccountType.PLAYER);
      return;
    }

    if (IDENTIFIER_INVALIDATE_ACCOUNT.equals(msg.identifier())) {
      Optional<String> accountIdStr = msg.stringValue("accountId");
      if (accountIdStr.isEmpty()) return;

      UUID accountId;
      try {
        accountId = UUID.fromString(accountIdStr.get());
      } catch (IllegalArgumentException ex) {
        return;
      }

      String typeStr = msg.stringValue("accountType").orElse(EconomyBalanceEntity.EconomyAccountType.PLAYER.name());

      EconomyBalanceEntity.EconomyAccountType type;
      try {
        type = EconomyBalanceEntity.EconomyAccountType.valueOf(typeStr.trim().toUpperCase());
      } catch (Exception ignored) {
        type = EconomyBalanceEntity.EconomyAccountType.PLAYER;
      }

      cache.invalidate(accountId, type);
    }
  }

  @Override
  public void close() {
    if (subscription != null) {
      subscription.close();
      subscription = null;
    }
  }
}