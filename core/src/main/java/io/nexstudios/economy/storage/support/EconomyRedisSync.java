package io.nexstudios.economy.storage.support;

import io.nexstudios.economy.NexEconomy;
import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.currency.NexCurrencyType;
import io.nexstudios.economy.storage.InMemoryEcoService;
import io.nexstudios.economy.storage.model.PlayerAccount;
import io.nexstudios.nexus.bukkit.redis.NexusRedisApi;
import io.nexstudios.nexus.bukkit.redis.NexusRedisListener;
import io.nexstudios.nexus.bukkit.redis.NexusRedisMessage;
import io.nexstudios.nexus.bukkit.redis.NexusRedisPayload;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles cross-server economy synchronization using Nexus Redis API.
 * This class is responsible for:
 *  - Publishing local balance changes to Redis
 *  - Receiving remote updates from Redis and applying them to the local cache
 *  - Broadcasting player preload events so other servers can warm their caches
 *  - Broadcasting reset events so that cache resets are applied consistently across servers
 *  - Broadcasting notification events so players receive messages even on remote servers
 *  - Broadcasting currency definitions so virtual currency config files can be created on other servers
 */
public class EconomyRedisSync implements InMemoryEcoService.RemoteUpdateBroadcaster {

    private static final String BALANCE_CHANNEL = "nexus:economy:balances";
    private static final String PLAYER_SYNC_CHANNEL = "nexus:economy:player-sync";
    private static final String RESET_CHANNEL = "nexus:economy:reset";
    private static final String NOTIFY_CHANNEL = "nexus:economy:notify";
    private static final String CURRENCY_SYNC_CHANNEL = "nexus:economy:currency-sync";

    private final NexEconomy plugin;
    private final InMemoryEcoService ecoService;
    private final String serverId;

    private UUID balanceSubscriptionId;
    private UUID playerSyncSubscriptionId;
    private UUID resetSubscriptionId;
    private UUID notifySubscriptionId;
    private UUID currencySyncSubscriptionId;

    public EconomyRedisSync(NexEconomy plugin, InMemoryEcoService ecoService, String serverId) {
        this.plugin = plugin;
        this.ecoService = ecoService;
        this.serverId = serverId;
    }

    /**
     * Registers Redis subscriptions for all economy-related channels.
     */
    public void init() {
        if (!NexusRedisApi.isServicePresent()) {
            plugin.getLogger().warning("EconomyRedisSync.init called but Nexus Redis service is not present.");
            return;
        }

        // Subscribe to balance updates
        NexusRedisListener balanceListener = this::handleBalanceMessage;
        Optional<UUID> balanceOpt = NexusRedisApi.subscribe(BALANCE_CHANNEL, balanceListener);
        balanceOpt.ifPresentOrElse(
                id -> {
                    balanceSubscriptionId = id;
                    NexEconomy.nexusLogger.info("Subscribed to Redis balance channel '" + BALANCE_CHANNEL);
                },
                () -> NexEconomy.nexusLogger.warning("Failed to subscribe to Redis balance channel '" + BALANCE_CHANNEL + "'.")
        );

        // Subscribe to player preload notifications
        NexusRedisListener playerListener = this::handlePlayerSyncMessage;
        Optional<UUID> playerOpt = NexusRedisApi.subscribe(PLAYER_SYNC_CHANNEL, playerListener);
        playerOpt.ifPresentOrElse(
                id -> {
                    playerSyncSubscriptionId = id;
                    NexEconomy.nexusLogger.info("Subscribed to Redis player-sync channel '" + PLAYER_SYNC_CHANNEL);
                },
                () -> NexEconomy.nexusLogger.warning("Failed to subscribe to Redis player-sync channel '" + PLAYER_SYNC_CHANNEL + "'.")
        );

        // Subscribe to reset events (single-player and global)
        NexusRedisListener resetListener = this::handleResetMessage;
        Optional<UUID> resetOpt = NexusRedisApi.subscribe(RESET_CHANNEL, resetListener);
        resetOpt.ifPresentOrElse(
                id -> {
                    resetSubscriptionId = id;
                    NexEconomy.nexusLogger.info("Subscribed to Redis reset channel '" + RESET_CHANNEL);
                },
                () -> NexEconomy.nexusLogger.warning("Failed to subscribe to Redis reset channel '" + RESET_CHANNEL + "'.")
        );

        // Subscribe to notification events (cross-server messages to players)
        NexusRedisListener notifyListener = this::handleNotifyMessage;
        Optional<UUID> notifyOpt = NexusRedisApi.subscribe(NOTIFY_CHANNEL, notifyListener);
        notifyOpt.ifPresentOrElse(
                id -> {
                    notifySubscriptionId = id;
                    NexEconomy.nexusLogger.info("Subscribed to Redis notify channel '" + NOTIFY_CHANNEL);
                },
                () -> NexEconomy.nexusLogger.warning("Failed to subscribe to Redis notify channel '" + NOTIFY_CHANNEL + "'.")
        );

        // Subscribe to currency sync events (virtual currency definitions)
        NexusRedisListener currencyListener = this::handleCurrencySyncMessage;
        Optional<UUID> currencyOpt = NexusRedisApi.subscribe(CURRENCY_SYNC_CHANNEL, currencyListener);
        currencyOpt.ifPresentOrElse(
                id -> {
                    currencySyncSubscriptionId = id;
                    NexEconomy.nexusLogger.info("Subscribed to Redis currency-sync channel '" + CURRENCY_SYNC_CHANNEL);
                },
                () -> NexEconomy.nexusLogger.warning("Failed to subscribe to Redis currency-sync channel '" + CURRENCY_SYNC_CHANNEL + "'.")
        );
    }

    /**
     * Unsubscribes from all Redis channels. Should be called during plugin shutdown.
     */
    public void shutdown() {
        if (!NexusRedisApi.isServicePresent()) {
            return;
        }
        if (balanceSubscriptionId != null) {
            NexusRedisApi.unsubscribe(balanceSubscriptionId);
            balanceSubscriptionId = null;
        }
        if (playerSyncSubscriptionId != null) {
            NexusRedisApi.unsubscribe(playerSyncSubscriptionId);
            playerSyncSubscriptionId = null;
        }
        if (resetSubscriptionId != null) {
            NexusRedisApi.unsubscribe(resetSubscriptionId);
            resetSubscriptionId = null;
        }
        if (notifySubscriptionId != null) {
            NexusRedisApi.unsubscribe(notifySubscriptionId);
            notifySubscriptionId = null;
        }
        if (currencySyncSubscriptionId != null) {
            NexusRedisApi.unsubscribe(currencySyncSubscriptionId);
            currencySyncSubscriptionId = null;
        }
    }

    @Override
    public void broadcastBalanceUpdate(PlayerAccount account, BigDecimal delta, String operationType) {
        // Only broadcast if Redis is available and connected
        if (!NexusRedisApi.isServicePresent() || !NexusRedisApi.isConnected()) {
            return;
        }
        if (account == null || delta == null || operationType == null) {
            return;
        }

        UUID playerId = account.getPlayerId();
        String currencyKey = account.getCurrencyKey();
        BigDecimal newBalance = account.getBalance();
        long version = account.getVersion();
        long now = System.currentTimeMillis();

        NexusRedisPayload payload = NexusRedisPayload.create()
                .put("playerId", playerId.toString())
                .put("currencyKey", currencyKey)
                .put("type", operationType)
                .put("delta", delta.toPlainString())
                .put("newBalance", newBalance.toPlainString())
                .put("version", version)
                .put("updatedAt", now);

        NexusRedisMessage msg = payload.toMessage(
                "BALANCE_UPDATE",
                serverId
        );

        NexusRedisApi.publish(BALANCE_CHANNEL, msg).whenComplete((receivers, error) -> {
            if (error != null) {
                NexEconomy.nexusLogger.warning("Failed to publish balance update to Redis: " + error.getMessage());
            }
        });
    }

    /**
     * Publishes a player preload notification to other servers.
     */
    public void publishPlayerPreload(UUID playerId) {
        if (playerId == null) return;
        if (!NexusRedisApi.isServicePresent() || !NexusRedisApi.isConnected()) {
            return;
        }

        NexusRedisPayload payload = NexusRedisPayload.create()
                .put("playerId", playerId.toString());

        NexusRedisMessage msg = payload.toMessage(
                "PLAYER_PRELOAD",
                serverId
        );

        NexusRedisApi.publish(PLAYER_SYNC_CHANNEL, msg).whenComplete((receivers, error) -> {
            if (error != null) {
                NexEconomy.nexusLogger.warning("Failed to publish player preload to Redis: " + error.getMessage());
            }
        });
    }

    /**
     * Publishes a single-player reset event so that other servers clear any cached
     * accounts for this player.
     */
    public void publishPlayerReset(UUID playerId) {
        if (playerId == null) return;
        if (!NexusRedisApi.isServicePresent() || !NexusRedisApi.isConnected()) {
            return;
        }

        NexusRedisPayload payload = NexusRedisPayload.create()
                .put("mode", "PLAYER_RESET")
                .put("playerId", playerId.toString());

        NexusRedisMessage msg = payload.toMessage(
                "PLAYER_RESET",
                serverId
        );

        NexusRedisApi.publish(RESET_CHANNEL, msg).whenComplete((receivers, error) -> {
            if (error != null) {
                NexEconomy.nexusLogger.warning("Failed to publish player reset to Redis: " + error.getMessage());
            }
        });
    }

    /**
     * Publishes a global reset event so that other servers clear their entire
     * economy cache after the database has been truncated.
     */
    public void publishGlobalReset() {
        if (!NexusRedisApi.isServicePresent() || !NexusRedisApi.isConnected()) {
            return;
        }

        NexusRedisPayload payload = NexusRedisPayload.create()
                .put("mode", "RESET_ALL");

        NexusRedisMessage msg = payload.toMessage(
                "RESET_ALL",
                serverId
        );

        NexusRedisApi.publish(RESET_CHANNEL, msg).whenComplete((receivers, error) -> {
            if (error != null) {
                NexEconomy.nexusLogger.warning("Failed to publish global reset to Redis: " + error.getMessage());
            }
        });
    }

    /**
     * Publishes a currency notification event (SET/DEPOSIT/WITHDRAW) so that the
     * target player receives a message even when online on another server.
     */
    public void publishCurrencyNotification(UUID playerId,
                                            String currencyKey,
                                            BigDecimal amount,
                                            String operationType,
                                            String actorName) {
        if (playerId == null || currencyKey == null || amount == null || operationType == null) {
            return;
        }
        if (!NexusRedisApi.isServicePresent() || !NexusRedisApi.isConnected()) {
            return;
        }

        NexusRedisPayload payload = NexusRedisPayload.create()
                .put("mode", "CURRENCY_NOTIFY")
                .put("op", operationType)
                .put("playerId", playerId.toString())
                .put("currencyKey", currencyKey.toLowerCase())
                .put("amount", amount.toPlainString())
                .put("actor", actorName == null ? "" : actorName);

        NexusRedisMessage msg = payload.toMessage(
                "CURRENCY_NOTIFY",
                serverId
        );

        NexusRedisApi.publish(NOTIFY_CHANNEL, msg).whenComplete((receivers, error) -> {
            if (error != null) {
                NexEconomy.nexusLogger.warning("Failed to publish currency notify to Redis: " + error.getMessage());
            }
        });
    }

    /**
     * Publishes a virtual currency definition so that other servers can create
     * corresponding currency config files (e.g. in currencies/extern/).
     *
     * @param currency   the currency definition
     * @param currencyKey stable factory key for the currency
     */
    public void publishCurrencyDefinition(NexCurrency currency, String currencyKey) {
        if (currency == null || currencyKey == null) return;
        if (!NexusRedisApi.isServicePresent() || !NexusRedisApi.isConnected()) {
            return;
        }
        if (currency.getCurrencyType() != NexCurrencyType.VIRTUAL) {
            return;
        }

        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

        NexusRedisPayload payload = NexusRedisPayload.create()
                .put("mode", "CURRENCY_SYNC")
                .put("currencyKey", currencyKey.toLowerCase())
                .put("name", plain.serialize(currency.getName()))
                .put("symbolPlural", plain.serialize(currency.getPluralSymbol()))
                .put("symbolSingular", plain.serialize(currency.getSingularSymbol()))
                .put("placeholder", currency.getPlaceholder())
                .put("mainCommand", currency.getMainCommand())
                .put("aliases", currency.getAliases())
                .put("fractionDigits", currency.getFractionDigits())
                .put("currencyType", currency.getCurrencyType().name().toLowerCase(Locale.ROOT))
                .put("startBalance", currency.getStartBalance().toPlainString())
                .put("maxBalance", currency.getMaxBalance().toPlainString());

        NexusRedisMessage msg = payload.toMessage(
                "CURRENCY_SYNC",
                serverId
        );

        NexusRedisApi.publish(CURRENCY_SYNC_CHANNEL, msg).whenComplete((receivers, error) -> {
            if (error != null) {
                NexEconomy.nexusLogger.warning("Failed to publish currency definition to Redis: " + error.getMessage());
            }
        });
    }

    // --------------------- Handlers ---------------------

    private void handleBalanceMessage(String channel, NexusRedisMessage message) {
        if (message == null) return;

        if (message.getOrigin() != null && message.getOrigin().equals(serverId)) {
            return;
        }

        try {
            NexusRedisPayload payload = NexusRedisPayload.fromMessage(message);

            String playerIdStr = payload.getString("playerId", null);
            String currencyKey = payload.getString("currencyKey", null);
            String newBalanceStr = payload.getString("newBalance", null);
            long version = payload.getLong("version", 0L);
            long updatedAt = payload.getLong("updatedAt", System.currentTimeMillis());

            if (playerIdStr == null || currencyKey == null || newBalanceStr == null) {
                return;
            }

            UUID playerId = UUID.fromString(playerIdStr);
            BigDecimal newBalance = new BigDecimal(newBalanceStr);

            ecoService.applyRemoteBalance(playerId, currencyKey, newBalance, version, updatedAt);
        } catch (Exception ex) {
            NexEconomy.nexusLogger.error("Error while handling Redis balance message: " + ex.getMessage());
        }
    }

    private void handlePlayerSyncMessage(String channel, NexusRedisMessage message) {
        if (message == null) {
            return;
        }

        if (message.getOrigin() != null && message.getOrigin().equals(serverId)) {
            return;
        }

        try {
            NexusRedisPayload payload = NexusRedisPayload.fromMessage(message);
            String playerIdStr = payload.getString("playerId", null);
            if (playerIdStr == null) {
                NexEconomy.nexusLogger.info("Ignoring Redis player sync message with null playerId");
                return;
            }

            UUID playerId = UUID.fromString(playerIdStr);

            int loaded = ecoService.loadAllForPlayerIfAbsent(playerId);
            if (loaded > 0) {
                NexEconomy.nexusLogger.info("Redis player preload loaded " + loaded + " accounts for " + playerId);
            }
        } catch (Exception ex) {
            NexEconomy.nexusLogger.error("Error while handling Redis player-sync message: " + ex.getMessage());
        }
    }

    private void handleResetMessage(String channel, NexusRedisMessage message) {
        if (message == null) return;

        if (message.getOrigin() != null && message.getOrigin().equals(serverId)) {
            return;
        }

        try {
            NexusRedisPayload payload = NexusRedisPayload.fromMessage(message);
            String mode = payload.getString("mode", "");

            if ("PLAYER_RESET".equalsIgnoreCase(mode)) {
                String playerIdStr = payload.getString("playerId", null);
                if (playerIdStr == null) return;
                UUID playerId = UUID.fromString(playerIdStr);

                ecoService.applyRemoteResetPlayer(playerId);
                NexEconomy.nexusLogger.info("Applied remote player reset for " + playerId);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p != null && p.isOnline()) {
                        plugin.getMessageSender().send(p, "general.reset-target");
                    }
                });
            } else if ("RESET_ALL".equalsIgnoreCase(mode)) {
                ecoService.applyRemoteResetAll();
                NexEconomy.nexusLogger.debug("Applied remote global reset on this server.", 1);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        plugin.getMessageSender().send(p, "general.reset-target");
                    }
                });
            }
        } catch (Exception ex) {
            NexEconomy.nexusLogger.error("Error while handling Redis reset message: " + ex.getMessage());
        }
    }

    private void handleNotifyMessage(String channel, NexusRedisMessage message) {
        if (message == null) return;

        if (message.getOrigin() != null && message.getOrigin().equals(serverId)) {
            return;
        }

        try {
            NexusRedisPayload payload = NexusRedisPayload.fromMessage(message);
            String mode = payload.getString("mode", "");
            if (!"CURRENCY_NOTIFY".equalsIgnoreCase(mode)) {
                return;
            }

            String op = payload.getString("op", "");
            String playerIdStr = payload.getString("playerId", null);
            String currencyKey = payload.getString("currencyKey", null);
            String amountStr = payload.getString("amount", null);
            String actor = payload.getString("actor", "");

            if (playerIdStr == null || currencyKey == null || amountStr == null || op.isEmpty()) {
                return;
            }

            UUID playerId = UUID.fromString(playerIdStr);
            BigDecimal amount = new BigDecimal(amountStr);

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player target = Bukkit.getPlayer(playerId);
                if (target == null || !target.isOnline()) {
                    return;
                }

                Optional<NexCurrency> oc = plugin.getNexEcoFactory().findByKey(currencyKey);
                if (oc.isEmpty()) {
                    return;
                }
                NexCurrency currency = oc.get();

                BigDecimal scaled = amount.setScale(Math.max(0, currency.getFractionDigits()), BigDecimal.ROUND_DOWN);
                String amountFormatted = scaled.toPlainString();
                String symbol = PlainTextComponentSerializer.plainText().serialize(
                        scaled.compareTo(BigDecimal.ONE) == 0 ? currency.getSingularSymbol() : currency.getPluralSymbol()
                );

                TagResolver resolver = TagResolver.resolver(
                        Placeholder.parsed("amount", amountFormatted),
                        Placeholder.parsed("currency", symbol),
                        Placeholder.parsed("player", actor == null ? "" : actor)
                );

                switch (op.toUpperCase()) {
                    case "SET" -> plugin.getMessageSender().send(target, "currency.set-other", resolver);
                    case "DEPOSIT" -> plugin.getMessageSender().send(target, "currency.deposit-other", resolver);
                    case "WITHDRAW" -> plugin.getMessageSender().send(target, "currency.withdraw-other", resolver);
                    default -> {
                    }
                }
            });
        } catch (Exception ex) {
            NexEconomy.nexusLogger.error("Error while handling Redis notify message: " + ex.getMessage());
        }
    }

    /**
     * Handles incoming currency definition sync events. Creates virtual currency
     * config files locally if they do not yet exist. Does not publish anything
     * back to Redis to avoid loops.
     */
    private void handleCurrencySyncMessage(String channel, NexusRedisMessage message) {
        if (message == null) return;

        if (message.getOrigin() != null && message.getOrigin().equals(serverId)) {
            return;
        }

        try {
            NexusRedisPayload payload = NexusRedisPayload.fromMessage(message);
            String mode = payload.getString("mode", "");
            if (!"CURRENCY_SYNC".equalsIgnoreCase(mode)) {
                return;
            }

            String currencyKey = payload.getString("currencyKey", null);
            String name = payload.getString("name", null);
            String symbolPlural = payload.getString("symbolPlural", null);
            String symbolSingular = payload.getString("symbolSingular", null);
            String placeholder = payload.getString("placeholder", null);
            String mainCommand = payload.getString("mainCommand", null);
            var aliases = payload.getStringList("aliases");
            int fractionDigits = (int) payload.getLong("fractionDigits", 2L);
            String currencyType = payload.getString("currencyType", "VIRTUAL");
            String startBalance = payload.getString("startBalance", "0");
            String maxBalance = payload.getString("maxBalance", "-1");

            if (currencyKey == null) {
                return;
            }

            // Only create files for virtual currencies
            if (!"VIRTUAL".equalsIgnoreCase(currencyType)) {
                return;
            }

            // Build target path: <pluginData>/currencies/extern/<currencyKey>.yml
            File currenciesDir = new File(plugin.getDataFolder(), "currencies");
            File externDir = new File(currenciesDir, "extern");
            if (!externDir.exists() && !externDir.mkdirs()) {
                NexEconomy.nexusLogger.warning("Could not create extern currency directory at " + externDir.getAbsolutePath());
                return;
            }

            File targetFile = new File(externDir, currencyKey.toLowerCase() + ".yml");
            if (targetFile.exists()) {
                // File already exists, do not override to avoid conflicts
                NexEconomy.nexusLogger.debug("Currency file already exists for key '" + currencyKey + "', skipping creation.", 1);
                return;
            }

            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("name", name != null ? name : currencyKey);
            cfg.set("symbol.plural", symbolPlural != null ? symbolPlural : currencyKey);
            cfg.set("symbol.singular", symbolSingular != null ? symbolSingular : currencyKey);
            if (placeholder != null) cfg.set("placeholder", placeholder);
            if (mainCommand != null) cfg.set("command.main", mainCommand);
            if (aliases instanceof java.util.List<?> aList) {
                cfg.set("command.aliases", aList);
            }
            cfg.set("fraction-digits", fractionDigits);
            cfg.set("currency-type", "VIRTUAL");
            cfg.set("start-balance", startBalance);
            cfg.set("max-balance", maxBalance);

            try {
                cfg.save(targetFile);
                NexEconomy.nexusLogger.info("Created virtual currency file from sync: " + targetFile.getName());
            } catch (IOException e) {
                NexEconomy.nexusLogger.warning("Failed to save synced currency file " + targetFile.getAbsolutePath() + ": " + e.getMessage());
            }
        } catch (Exception ex) {
            NexEconomy.nexusLogger.error("Error while handling Redis currency-sync message: " + ex.getMessage());
        }
    }
}