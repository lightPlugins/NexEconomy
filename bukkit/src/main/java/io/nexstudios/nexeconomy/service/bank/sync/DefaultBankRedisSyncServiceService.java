package io.nexstudios.nexeconomy.service.bank.sync;

import io.nexstudios.databaseservice.bukkit.service.api.pubsub.PubSubHealth;
import io.nexstudios.databaseservice.bukkit.service.api.pubsub.PubSubMessage;
import io.nexstudios.databaseservice.bukkit.service.api.pubsub.PubSubSubscription;
import io.nexstudios.databaseservice.bukkit.service.api.pubsub.RedisPubSubService;
import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.level.BankLevelService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Redis Pub/Sub sync for bank account caches. */
@Dependencies({
    LoggerService.class,
    PaperPluginService.class,
    BankAccountCacheService.class,
    BankLevelService.class
})
public final class DefaultBankRedisSyncServiceService implements BankRedisSyncService, AutoCloseable {

  private static final String TOPIC = "bank";
  private static final String IDENTIFIER_INVALIDATE_ACCOUNT = "invalidate-bank-account";

  private final LoggerService logger;
  private final Plugin plugin;

  private final BankAccountCacheService cache;
  private final BankLevelService levelService;

  private final RedisPubSubService pubSub;

  private PubSubSubscription subscription;

  public DefaultBankRedisSyncServiceService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();

    this.cache = accessor.getService(BankAccountCacheService.class);
    this.levelService = accessor.getService(BankLevelService.class);

    this.pubSub = NexEconomyPlugin.getNexLogicService().findService(RedisPubSubService.class).orElse(null);
  }

  @Override
  public void start() {
    if (pubSub == null) {
      logger.logger().warning("Redis Pub/Sub service not available. Bank cross-server sync is disabled.");
      return;
    }

    if (!pubSub.isEnabled()) {
      logger.logger().info("Redis Pub/Sub is disabled (NexLogic: redis.enabled=false). Bank multi-server sync is disabled.");
      return;
    }

    if (!pubSub.isConnected()) {
      PubSubHealth health = pubSub.healthCheck(Duration.ofSeconds(2));
      logger.logger().severe("Redis Pub/Sub is enabled, but not connected (bank): " + health.message() + " (took=" + health.tookMillis() + "ms)");
      return;
    }

    String topic = pubSub.namespacedTopic(plugin, TOPIC);
    this.subscription = pubSub.subscribeOnMainThread(plugin, topic, this::handle);

    logger.logger().info("Bank Redis Pub/Sub sync enabled. topic=" + topic + ", identifiers=[" + IDENTIFIER_INVALIDATE_ACCOUNT + "]");
  }

  @Override
  public void publishInvalidateAccount(UUID bankAccountId) {
    if (bankAccountId == null) return;

    if (pubSub == null) return;
    if (!pubSub.isEnabled()) return;
    if (!pubSub.isConnected()) return;

    String topic = pubSub.namespacedTopic(plugin, TOPIC);

    pubSub.publishValues(
        topic,
        IDENTIFIER_INVALIDATE_ACCOUNT,
        Map.of("bankAccountId", bankAccountId.toString())
    );
  }

  private void handle(PubSubMessage msg) {
    if (pubSub == null) return;

    // cross-server only
    if (Objects.equals(pubSub.originId(), msg.originId())) return;

    if (!IDENTIFIER_INVALIDATE_ACCOUNT.equals(msg.identifier())) return;

    msg.stringValue("bankAccountId").ifPresent(id -> {
      try {
        java.util.UUID accountId = java.util.UUID.fromString(id);
        if (cache != null) {
          cache.invalidate(accountId);
        }
        if (levelService != null) {
          levelService.invalidate(accountId);
        }
      } catch (IllegalArgumentException ignored) {
        // ignore invalid payload
      }
    });
  }

  @Override
  public void close() {
    if (subscription != null) {
      subscription.close();
      subscription = null;
    }
  }
}