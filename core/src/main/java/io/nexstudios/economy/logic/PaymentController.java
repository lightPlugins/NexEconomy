package io.nexstudios.economy.logic;

import io.nexstudios.economy.NexEconomy;
import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.storage.NexEcoResponse;
import io.nexstudios.economy.storage.NexEcoService;
import io.nexstudios.economy.storage.persistence.sql.EcoDailyLimitsTable;
import io.nexstudios.economy.storage.support.EcoMath;
import io.nexstudios.economy.storage.support.TransactionLogger;
import io.nexstudios.nexus.bukkit.NexusPlugin;
import io.nexstudios.nexus.bukkit.language.NexusLanguage;
import io.nexstudios.nexus.bukkit.redis.NexusRedisApi;
import io.nexstudios.nexus.bukkit.redis.NexusRedisListener;
import io.nexstudios.nexus.bukkit.redis.NexusRedisMessage;
import io.nexstudios.nexus.bukkit.redis.NexusRedisPayload;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central controller for player-to-player payments across currencies.
 *
 * Responsibilities:
 * - Load payment profiles from settings.yml (per-currency configuration).
 * - Enforce conditions, per-transaction min/max amounts and cooldowns.
 * - Enforce daily send/receive limits with persisted counters in the database.
 * - Perform the actual withdraw/deposit operations via NexEcoService.
 * - Coordinate cross-server notifications via Redis when available.
 *
 * The controller is stateless with respect to long-term data:
 * - Daily limits are stored in a dedicated DB table (eco_daily_limits).
 * - Cooldowns are kept in memory only (per server).
 *
 * The command layer (CurrencyCommand) delegates to this controller and is responsible
 * for sending user-facing messages based on the returned PaymentResult.
 */
public final class PaymentController {

    public enum ErrorCode {
        NONE,
        DISABLED_FOR_CURRENCY,
        INVALID_AMOUNT,
        NOT_ENOUGH_BALANCE,
        BELOW_MIN,
        ABOVE_MAX,
        DAILY_LIMIT_SENDER,
        DAILY_LIMIT_TARGET,
        COOLDOWN,
        CONDITIONS_FAILED,
        INTERNAL_ERROR
    }

    public record PaymentResult(
            boolean success,
            ErrorCode error,
            String errorDetail,
            BigDecimal amount,
            BigDecimal minOrMaxLimit,     // Used for min/max violations or daily-limit feedback
            UUID senderId,
            UUID targetId
    ) {
        public static PaymentResult ok(UUID senderId, UUID targetId, BigDecimal amount) {
            return new PaymentResult(true, ErrorCode.NONE, null, amount, null, senderId, targetId);
        }

        public static PaymentResult error(ErrorCode code,
                                          String detail,
                                          BigDecimal amount,
                                          BigDecimal limit,
                                          UUID senderId,
                                          UUID targetId) {
            return new PaymentResult(false, code, detail, amount, limit, senderId, targetId);
        }
    }

    // Cooldowns: per currencyKey + sender UUID
    private final Map<String, Long> lastPayTimestamps = new ConcurrentHashMap<>();

    private final NexEconomy plugin;
    private final NexEcoService eco;
    private final DataSource dataSource;
    private final TransactionLogger txLogger;

    // Payment profiles loaded from settings.yml, keyed by currencyKey (id in config)
    private final Map<String, PaymentProfile> profiles = new ConcurrentHashMap<>();

    // Optional Redis integration for custom payment events (may be null)
    private final String redisChannelPayments = "nexus:economy:payments";

    public PaymentController(NexEconomy plugin,
                             NexEcoService eco,
                             DataSource dataSource,
                             TransactionLogger txLogger,
                             NexusLanguage lang) {
        this.plugin = plugin;
        this.eco = eco;
        this.dataSource = dataSource;
        this.txLogger = txLogger;

        ensureDailyTable();
        reloadConfig();
        initRedisListener();
    }

    /**
     * Deletes all stored daily limits for the given player (all currencies and days).
     * This is used by the admin command to reset a player's daily limits.
     */
    public void resetDailyLimitsForPlayer(UUID playerId) {
        if (playerId == null) return;
        String sql = "DELETE FROM " + EcoDailyLimitsTable.TABLE
                + " WHERE " + EcoDailyLimitsTable.COL_PLAYER + " = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            int affected = ps.executeUpdate();
            NexEconomy.nexusLogger.info("Reset daily limits for player " + playerId + " (" + affected + " row(s) removed).");
        } catch (SQLException e) {
            NexEconomy.nexusLogger.error("Failed to reset daily limits for player " + playerId + ": " + e.getMessage());
        }
    }

    /**
     * Reloads all payment profiles from settings.yml.
     * Should be called on plugin startup and on /nexeconomy reload.
     */
    @SuppressWarnings("unchecked")
    public void reloadConfig() {
        profiles.clear();
        FileConfiguration cfg = plugin.getSettingsFile().getConfig();
        List<Map<?, ?>> rawList = cfg.getMapList("payments");
        if (rawList.isEmpty()) {
            return;
        }

        for (Map<?, ?> raw : rawList) {
            Object idObj = raw.get("id");
            if (idObj == null) continue;
            String currencyKey = String.valueOf(idObj).toLowerCase(Locale.ROOT);

            Map<String, Object> map = (Map<String, Object>) (Map<?, ?>) raw;

            List<Map<String, Object>> conditions = List.of();
            Object condObj = map.get("conditions");
            if (condObj instanceof List<?> rawConds && !rawConds.isEmpty()) {
                conditions = (List<Map<String, Object>>) (List<?>) rawConds;
            }

            long cooldownSeconds = getCooldown(map);
            BigDecimal min = getBigDecimal(map, "min-amount-per-transaction", BigDecimal.ZERO);
            BigDecimal max = getBigDecimal(map, "max-amount-per-transaction", BigDecimal.valueOf(Long.MAX_VALUE));

            DailyLimits dailyLimits = DailyLimits.fromConfig(map);

            profiles.put(currencyKey, new PaymentProfile(currencyKey, conditions, cooldownSeconds, min, max, dailyLimits));
        }

        NexEconomy.nexusLogger.info("Loaded payment profiles for currencies: " + profiles.keySet());
    }

    /**
     * Old synchronous API – kept for compatibility.
     * Internally delegates to payAsync(...) and joins its result.
     */
    public PaymentResult pay(Player sender,
                             OfflinePlayer target,
                             NexCurrency currency,
                             String currencyKey,
                             BigDecimal rawAmount) {
        try {
            return payAsync(sender, target, currency, currencyKey, rawAmount).join();
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            NexEconomy.nexusLogger.error("PaymentController.pay (sync wrapper) failed: " + cause.getMessage());
            txLogger.logRaw("ERROR", "PAY (sync wrapper) failed: " + cause.getMessage());
            return PaymentResult.error(
                    ErrorCode.INTERNAL_ERROR,
                    cause.getMessage(),
                    rawAmount,
                    null,
                    sender != null ? sender.getUniqueId() : null,
                    target != null ? target.getUniqueId() : null
            );
        }
    }

    /**
     * Fully asynchronous payment execution, including asynchronous condition evaluation.
     * This method must never be called on the main thread directly; always call it
     * from an async task (as done in CurrencyCommand).
     */
    public CompletableFuture<PaymentResult> payAsync(Player sender,
                                                     OfflinePlayer target,
                                                     NexCurrency currency,
                                                     String currencyKey,
                                                     BigDecimal rawAmount) {

        UUID senderId = sender.getUniqueId();
        UUID targetId = target != null ? target.getUniqueId() : null;

        try {
            // Fast pre-validation without hitting async Conditions yet
            PaymentProfile profile = profiles.get(currencyKey.toLowerCase(Locale.ROOT));
            if (profile == null) {
                return CompletableFuture.completedFuture(
                        PaymentResult.error(
                                ErrorCode.DISABLED_FOR_CURRENCY,
                                "Payments are disabled for currency '" + currencyKey + "'.",
                                rawAmount, null, senderId, targetId
                        )
                );
            }

            if (target == null || senderId.equals(targetId)) {
                return CompletableFuture.completedFuture(
                        PaymentResult.error(
                                ErrorCode.INVALID_AMOUNT,
                                "Sender tried to pay themselves or target is null.",
                                rawAmount, null, senderId, targetId
                        )
                );
            }

            boolean redisActive = isRedisActive();
            if (!redisActive && (target.getPlayer() == null || !target.getPlayer().isOnline())) {
                return CompletableFuture.completedFuture(
                        PaymentResult.error(
                                ErrorCode.INTERNAL_ERROR,
                                "Cross-server disabled or Redis not active; target is not online on this server.",
                                rawAmount, null, senderId, targetId
                        )
                );
            }

            BigDecimal amount = EcoMath.scale(currency, rawAmount);
            if (amount == null || amount.signum() <= 0) {
                return CompletableFuture.completedFuture(
                        PaymentResult.error(
                                ErrorCode.INVALID_AMOUNT,
                                "Amount is null or not positive.",
                                rawAmount, null, senderId, targetId
                        )
                );
            }

            // All expensive / async parts are combined here:
            // - Top-level profile conditions for sender
            // - Daily-limit group conditions for sender/target
            CompletableFuture<Boolean> conditionsFuture = profile.checkConditionsAsync(sender);
            CompletableFuture<DailyLimitEffective> senderLimitsFuture = profile.dailyLimits.resolveEffectiveLimitsAsync(sender);
            CompletableFuture<DailyLimitEffective> targetLimitsFuture = profile.dailyLimits.resolveEffectiveLimitsAsync(target);

            return conditionsFuture.thenCombineAsync(
                    senderLimitsFuture.thenCombineAsync(targetLimitsFuture,
                            (sLimits, tLimits) -> new DailyLimitPair(sLimits, tLimits)),
                    (conditionsOk, limitPair) ->
                            new CombinedPrecheckResult(conditionsOk, limitPair.senderLimits(), limitPair.targetLimits())
            ).thenApplyAsync(pre -> {
                if (!pre.conditionsOk()) {
                    return PaymentResult.error(
                            ErrorCode.CONDITIONS_FAILED,
                            "Top-level payment conditions failed.",
                            amount, null, senderId, targetId
                    );
                }

                // Per-transaction min/max
                if (amount.compareTo(profile.minPerTransaction) < 0) {
                    return PaymentResult.error(
                            ErrorCode.BELOW_MIN,
                            "Amount below per-transaction minimum.",
                            amount, profile.minPerTransaction, senderId, targetId
                    );
                }
                if (amount.compareTo(profile.maxPerTransaction) > 0) {
                    return PaymentResult.error(
                            ErrorCode.ABOVE_MAX,
                            "Amount above per-transaction maximum.",
                            amount, profile.maxPerTransaction, senderId, targetId
                    );
                }

                long remainingCooldown = getRemainingCooldownSeconds(profile, senderId);
                if (remainingCooldown > 0L) {
                    return PaymentResult.error(
                            ErrorCode.COOLDOWN,
                            "Payment cooldown not yet expired for this sender & currency.",
                            amount,
                            BigDecimal.valueOf(remainingCooldown),
                            senderId,
                            targetId
                    );
                }

                // Daily limits based on DB totals (sync, but we are in async thread)
                int dayUtc = getCurrentDayUtc();
                DailyTotals senderTotals = loadDailyTotals(senderId, currencyKey, dayUtc);
                DailyTotals targetTotals = loadDailyTotals(targetId, currencyKey, dayUtc);

                DailyLimitEffective senderLimits = pre.senderLimits();
                DailyLimitEffective targetLimits = pre.targetLimits();

                BigDecimal remainingSend = senderLimits.remainingSend(senderTotals.sent);
                BigDecimal remainingReceive = targetLimits.remainingReceive(targetTotals.received);

                BigDecimal effectiveAmount = amount;

                if (remainingSend != null) {
                    effectiveAmount = effectiveAmount.min(remainingSend.max(BigDecimal.ZERO));
                }
                if (remainingReceive != null) {
                    effectiveAmount = effectiveAmount.min(remainingReceive.max(BigDecimal.ZERO));
                }

                // No capacity left at all -> daily limit enforced
                if (effectiveAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    if (remainingSend != null && remainingSend.signum() <= 0) {
                        return PaymentResult.error(
                                ErrorCode.DAILY_LIMIT_SENDER,
                                "Sender exceeded daily send limit.",
                                amount, senderLimits.maxSend, senderId, targetId
                        );
                    }
                    if (remainingReceive != null && remainingReceive.signum() <= 0) {
                        return PaymentResult.error(
                                ErrorCode.DAILY_LIMIT_TARGET,
                                "Target exceeded daily receive limit.",
                                amount, targetLimits.maxReceive, senderId, targetId
                        );
                    }
                    return PaymentResult.error(
                            ErrorCode.DAILY_LIMIT_SENDER,
                            "Daily limit exceeded (no remaining capacity).",
                            amount, senderLimits.maxSend, senderId, targetId
                    );
                }

                boolean partial = effectiveAmount.compareTo(amount) < 0;

                if (!eco.has(senderId, currencyKey, effectiveAmount)) {
                    return PaymentResult.error(
                            ErrorCode.NOT_ENOUGH_BALANCE,
                            "Sender does not have enough balance.",
                            effectiveAmount, null, senderId, targetId
                    );
                }

                // Economic operations (still synchronous, but on async thread)
                NexEcoResponse withdrawRes = eco.withdraw(senderId, currencyKey, effectiveAmount);
                if (!withdrawRes.isSuccess()) {
                    return PaymentResult.error(
                            ErrorCode.INTERNAL_ERROR,
                            "Withdraw failed: " + withdrawRes.responseType() + " (" + withdrawRes.errorMessage() + ")",
                            effectiveAmount, null, senderId, targetId
                    );
                }

                NexEcoResponse depositRes = eco.deposit(targetId, currencyKey, effectiveAmount);
                if (!depositRes.isSuccess()) {
                    // Best-effort rollback
                    eco.deposit(senderId, currencyKey, effectiveAmount);
                    return PaymentResult.error(
                            ErrorCode.INTERNAL_ERROR,
                            "Deposit failed: " + depositRes.responseType() + " (" + depositRes.errorMessage() + ")",
                            effectiveAmount, null, senderId, targetId
                    );
                }

                // Update cooldown
                touchCooldown(profile, senderId);

                // Persist daily totals
                addToDailyTotals(senderId, currencyKey, dayUtc, effectiveAmount, BigDecimal.ZERO);
                addToDailyTotals(targetId, currencyKey, dayUtc, BigDecimal.ZERO, effectiveAmount);

                // Logging
                txLogger.log(senderId, currencyKey, "PAY",
                        "target=" + targetId + " amount=" + effectiveAmount
                                + " senderBalance=" + withdrawRes.balance()
                                + " targetBalance=" + depositRes.balance()
                                + " result=SUCCESS"
                                + (partial ? " (PARTIAL, requested=" + amount + ")" : ""));

                // Redis event (informational)
                publishRedisPayment(senderId, targetId, currencyKey, effectiveAmount);

                BigDecimal limit = (partial && senderLimits.enabled() ? senderLimits.maxSend() : null);
                return new PaymentResult(true, ErrorCode.NONE, null, effectiveAmount, limit, senderId, targetId);
            });
        } catch (Exception ex) {
            NexEconomy.nexusLogger.error("PaymentController.payAsync failed: " + ex.getMessage());
            txLogger.logRaw("ERROR", "PAY async failed: " + ex.getMessage());
            return CompletableFuture.completedFuture(
                    PaymentResult.error(
                            ErrorCode.INTERNAL_ERROR,
                            ex.getMessage(),
                            rawAmount, null,
                            sender.getUniqueId(),
                            target != null ? target.getUniqueId() : null
                    )
            );
        }
    }

    // ------------------- Cooldowns -------------------

    private boolean checkCooldown(PaymentProfile profile, UUID senderId) {
        if (profile.cooldownSeconds <= 0) return true;
        String mapKey = profile.currencyKey + "|" + senderId;
        long now = System.currentTimeMillis();
        Long last = lastPayTimestamps.get(mapKey);
        if (last == null) return true;
        long elapsed = now - last;
        long required = profile.cooldownSeconds * 1000L;
        return elapsed >= required;
    }

    private long getRemainingCooldownSeconds(PaymentProfile profile, UUID senderId) {
        if (profile.cooldownSeconds <= 0) return 0L;
        String mapKey = profile.currencyKey + "|" + senderId;
        long now = System.currentTimeMillis();
        Long last = lastPayTimestamps.get(mapKey);
        if (last == null) return 0L;

        long elapsed = now - last;
        long required = profile.cooldownSeconds * 1000L;
        long remainingMillis = required - elapsed;
        if (remainingMillis <= 0L) return 0L;

        // Round up to full seconds so the player never sees "0" while still blocked
        return (remainingMillis + 999L) / 1000L;
    }

    private void touchCooldown(PaymentProfile profile, UUID senderId) {
        if (profile.cooldownSeconds <= 0) return;
        String mapKey = profile.currencyKey + "|" + senderId;
        lastPayTimestamps.put(mapKey, System.currentTimeMillis());
    }

    // ------------------- Daily limits (DB) -------------------

    private void ensureDailyTable() {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            String ddl = product.contains("sqlite")
                    ? EcoDailyLimitsTable.ddlSqlite()
                    : EcoDailyLimitsTable.ddlMySql();
            try (Statement st = c.createStatement()) {
                st.executeUpdate(ddl);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure eco_daily_limits table: " + e.getMessage(), e);
        }
    }

    private int getCurrentDayUtc() {
        LocalDate utcDate = LocalDate.now(ZoneOffset.UTC);
        return utcDate.getYear() * 10000 + utcDate.getMonthValue() * 100 + utcDate.getDayOfMonth();
    }

    private DailyTotals loadDailyTotals(UUID playerId, String currencyKey, int dayUtc) {
        if (playerId == null) {
            return new DailyTotals(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        String sql = "SELECT " + EcoDailyLimitsTable.COL_SENT + ", " + EcoDailyLimitsTable.COL_RECEIVED
                + " FROM " + EcoDailyLimitsTable.TABLE
                + " WHERE " + EcoDailyLimitsTable.COL_PLAYER + " = ?"
                + " AND " + EcoDailyLimitsTable.COL_CURRENCY + " = ?"
                + " AND " + EcoDailyLimitsTable.COL_DAY + " = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, currencyKey.toLowerCase(Locale.ROOT));
            ps.setInt(3, dayUtc);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal sent = rs.getBigDecimal(1);
                    BigDecimal received = rs.getBigDecimal(2);
                    return new DailyTotals(
                            sent != null ? sent : BigDecimal.ZERO,
                            received != null ? received : BigDecimal.ZERO
                    );
                }
            }
        } catch (SQLException e) {
            NexEconomy.nexusLogger.error("Failed to load daily totals: " + e.getMessage());
        }
        return new DailyTotals(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private void addToDailyTotals(UUID playerId,
                                  String currencyKey,
                                  int dayUtc,
                                  BigDecimal deltaSent,
                                  BigDecimal deltaReceived) {
        if (playerId == null) return;
        if ((deltaSent == null || deltaSent.signum() == 0)
                && (deltaReceived == null || deltaReceived.signum() == 0)) return;

        BigDecimal ds = deltaSent == null ? BigDecimal.ZERO : deltaSent;
        BigDecimal dr = deltaReceived == null ? BigDecimal.ZERO : deltaReceived;

        String product;
        try (Connection c = dataSource.getConnection()) {
            product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        } catch (SQLException e) {
            NexEconomy.nexusLogger.error("Failed to resolve DB product for daily limits: " + e.getMessage());
            return;
        }

        boolean sqlite = product.contains("sqlite");
        String sql;
        if (sqlite) {
            sql = "INSERT INTO " + EcoDailyLimitsTable.TABLE + " ("
                    + EcoDailyLimitsTable.COL_PLAYER + ", "
                    + EcoDailyLimitsTable.COL_CURRENCY + ", "
                    + EcoDailyLimitsTable.COL_DAY + ", "
                    + EcoDailyLimitsTable.COL_SENT + ", "
                    + EcoDailyLimitsTable.COL_RECEIVED + ", "
                    + EcoDailyLimitsTable.COL_UPDATED_AT + ") "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(" + EcoDailyLimitsTable.COL_PLAYER + ", "
                    + EcoDailyLimitsTable.COL_CURRENCY + ", "
                    + EcoDailyLimitsTable.COL_DAY + ") DO UPDATE SET "
                    + EcoDailyLimitsTable.COL_SENT + " = " + EcoDailyLimitsTable.COL_SENT + " + excluded." + EcoDailyLimitsTable.COL_SENT + ", "
                    + EcoDailyLimitsTable.COL_RECEIVED + " = " + EcoDailyLimitsTable.COL_RECEIVED + " + excluded." + EcoDailyLimitsTable.COL_RECEIVED + ", "
                    + EcoDailyLimitsTable.COL_UPDATED_AT + " = excluded." + EcoDailyLimitsTable.COL_UPDATED_AT;
        } else {
            sql = "INSERT INTO " + EcoDailyLimitsTable.TABLE + " ("
                    + EcoDailyLimitsTable.COL_PLAYER + ", "
                    + EcoDailyLimitsTable.COL_CURRENCY + ", "
                    + EcoDailyLimitsTable.COL_DAY + ", "
                    + EcoDailyLimitsTable.COL_SENT + ", "
                    + EcoDailyLimitsTable.COL_RECEIVED + ", "
                    + EcoDailyLimitsTable.COL_UPDATED_AT + ") "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE "
                    + EcoDailyLimitsTable.COL_SENT + " = " + EcoDailyLimitsTable.COL_SENT + " + VALUES(" + EcoDailyLimitsTable.COL_SENT + "), "
                    + EcoDailyLimitsTable.COL_RECEIVED + " = " + EcoDailyLimitsTable.COL_RECEIVED + " + VALUES(" + EcoDailyLimitsTable.COL_RECEIVED + "), "
                    + EcoDailyLimitsTable.COL_UPDATED_AT + " = VALUES(" + EcoDailyLimitsTable.COL_UPDATED_AT + ")";
        }

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, currencyKey.toLowerCase(Locale.ROOT));
            ps.setInt(3, dayUtc);
            ps.setBigDecimal(4, ds);
            ps.setBigDecimal(5, dr);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            NexEconomy.nexusLogger.error("Failed to upsert daily totals: " + e.getMessage());
        }
    }

    // ------------------- Redis integration -------------------

    private void initRedisListener() {
        if (!NexusRedisApi.isServicePresent()) {
            return;
        }

        // Listener currently reserved for possible future cross-server payment notifications.
        NexusRedisListener listener = (channel, message) -> {
            // No-op for now.
        };

        NexusRedisApi.subscribe(redisChannelPayments, listener);
    }

    private void publishRedisPayment(UUID senderId,
                                     UUID targetId,
                                     String currencyKey,
                                     BigDecimal amount) {
        if (!NexusRedisApi.isServicePresent() || !NexusRedisApi.isConnected()) {
            return;
        }
        try {
            NexusRedisPayload payload = NexusRedisPayload.create()
                    .put("mode", "PLAYER_PAY")
                    .put("sender", senderId.toString())
                    .put("target", targetId.toString())
                    .put("currencyKey", currencyKey.toLowerCase(Locale.ROOT))
                    .put("amount", amount.toPlainString());

            NexusRedisMessage msg = payload.toMessage("PLAYER_PAY", NexusPlugin.getInstance().getCrossServerName());
            NexusRedisApi.publish(redisChannelPayments, msg);
        } catch (Throwable t) {
            NexEconomy.nexusLogger.warning("Failed to publish Redis payment event: " + t.getMessage());
        }
    }

    private boolean isRedisActive() {
        return NexusPlugin.getInstance().isCrossServerEnabled()
                && NexusRedisApi.isServicePresent()
                && NexusRedisApi.isConnected();
    }

    // ------------------- Helpers -------------------

    private static long getCooldown(Map<String, Object> map) {
        Object raw = getNested(map, "cooldown");
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private static BigDecimal getBigDecimal(Map<String, Object> map, String path, BigDecimal def) {
        Object raw = getNested(map, path);
        if (raw instanceof Number n) {
            return BigDecimal.valueOf(n.longValue());
        }
        if (raw instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    private static Object getNested(Map<String, Object> map, String path) {
        String[] parts = path.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = ((Map<String, Object>) (Map<?, ?>) m).get(part);
            if (current == null) return null;
        }
        return current;
    }

    private record DailyTotals(BigDecimal sent, BigDecimal received) { }

    private record PaymentProfile(String currencyKey,
                                  List<Map<String, Object>> conditions,
                                  long cooldownSeconds,
                                  BigDecimal minPerTransaction,
                                  BigDecimal maxPerTransaction,
                                  DailyLimits dailyLimits) {

        /**
         * Asynchronously evaluates top-level conditions for the sender.
         */
        CompletableFuture<Boolean> checkConditionsAsync(Player player) {
            if (conditions == null || conditions.isEmpty()) {
                return CompletableFuture.completedFuture(true);
            }

            return NexusPlugin.getInstance()
                    .getConditionFactory()
                    .newBuilder()
                    .player(player)
                    .location(player.getLocation())
                    .conditions(conditions)
                    .evaluateAsync();
        }
    }

    /**
     * Encapsulates configuration of daily limits from settings.yml:
     * - enable flag
     * - default max-send / max-receive
     * - optional groups with additional conditions overriding defaults
     */
    private record DailyLimits(boolean enabled,
                               BigDecimal defaultMaxSend,
                               BigDecimal defaultMaxReceive,
                               List<Group> groups) {

        @SuppressWarnings("unchecked")
        static DailyLimits fromConfig(Map<String, Object> paymentMap) {
            Object dailyObj = paymentMap.get("daily-limits");
            if (!(dailyObj instanceof Map<?, ?> outer)) {
                return new DailyLimits(false, BigDecimal.valueOf(-1), BigDecimal.valueOf(-1), List.of());
            }
            Map<String, Object> daily = (Map<String, Object>) (Map<?, ?>) outer;
            boolean enable = Boolean.TRUE.equals(daily.get("enable"));

            BigDecimal defSend = getBigDecimal(daily, "default.max-send", BigDecimal.valueOf(-1));
            BigDecimal defReceive = getBigDecimal(daily, "default.max-receive", BigDecimal.valueOf(-1));

            List<Group> groups = new ArrayList<>();
            Object gObj = daily.get("group");
            if (gObj instanceof List<?> rawGroups) {
                for (Object ro : rawGroups) {
                    if (!(ro instanceof Map<?, ?> gm)) continue;
                    Map<String, Object> m = (Map<String, Object>) (Map<?, ?>) gm;
                    BigDecimal gSend = getBigDecimal(m, "max-send", defSend);
                    BigDecimal gReceive = getBigDecimal(m, "max-receive", defReceive);

                    List<Map<String, Object>> conds = List.of();
                    Object condObj = m.get("conditions");
                    if (condObj instanceof List<?> rawConds && !rawConds.isEmpty()) {
                        conds = (List<Map<String, Object>>) (List<?>) rawConds;
                    }
                    groups.add(new Group(gSend, gReceive, conds));
                }
            }

            return new DailyLimits(enable, defSend, defReceive, List.copyOf(groups));
        }

        /**
         * Asynchronously resolves the effective daily limits for a given player.
         * If the player is not online on this server, group-based conditions cannot be evaluated
         * and the default limits are used.
         */
        CompletableFuture<DailyLimitEffective> resolveEffectiveLimitsAsync(OfflinePlayer player) {
            if (!enabled || player == null) {
                return CompletableFuture.completedFuture(
                        new DailyLimitEffective(false, BigDecimal.valueOf(-1), BigDecimal.valueOf(-1))
                );
            }

            Player bukkitPlayer = player.getPlayer();
            if (bukkitPlayer == null) {
                // Player is not online on this server; fall back to default limits.
                return CompletableFuture.completedFuture(
                        new DailyLimitEffective(true, defaultMaxSend, defaultMaxReceive)
                );
            }

            if (groups == null || groups.isEmpty()) {
                return CompletableFuture.completedFuture(
                        new DailyLimitEffective(true, defaultMaxSend, defaultMaxReceive)
                );
            }

            // Process groups sequentially: first group whose conditions evaluate to true wins.
            CompletableFuture<DailyLimitEffective> future = CompletableFuture.completedFuture(null);

            for (Group g : groups) {
                future = future.thenCompose(current -> {
                    if (current != null) {
                        // A previous group already matched; carry it through.
                        return CompletableFuture.completedFuture(current);
                    }

                    if (g.conditions == null || g.conditions.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    return NexusPlugin.getInstance()
                            .getConditionFactory()
                            .newBuilder()
                            .player(bukkitPlayer)
                            .location(bukkitPlayer.getLocation())
                            .conditions(g.conditions)
                            .evaluateAsync()
                            .thenApply(ok -> ok
                                    ? new DailyLimitEffective(true, g.maxSend, g.maxReceive)
                                    : null);
                });
            }

            // If no group matched, use default limits.
            return future.thenApply(result ->
                    result != null
                            ? result
                            : new DailyLimitEffective(true, defaultMaxSend, defaultMaxReceive)
            );
        }

        private record Group(BigDecimal maxSend,
                             BigDecimal maxReceive,
                             List<Map<String, Object>> conditions) {
        }
    }

    private record DailyLimitEffective(boolean enabled,
                                       BigDecimal maxSend,
                                       BigDecimal maxReceive) {
        boolean canSend(BigDecimal newTotalSent) {
            if (!enabled) return true;
            if (maxSend == null || maxSend.signum() < 0) return true;
            return newTotalSent.compareTo(maxSend) <= 0;
        }

        boolean canReceive(BigDecimal newTotalReceived) {
            if (!enabled) return true;
            if (maxReceive == null || maxReceive.signum() < 0) return true;
            return newTotalReceived.compareTo(maxReceive) <= 0;
        }

        /**
         * Returns remaining send capacity for today (maxSend - currentSent) or null if unlimited.
         */
        BigDecimal remainingSend(BigDecimal currentSent) {
            if (!enabled || maxSend == null || maxSend.signum() < 0) return null;
            return maxSend.subtract(currentSent);
        }

        /**
         * Returns remaining receive capacity for today (maxReceive - currentReceived) or null if unlimited.
         */
        BigDecimal remainingReceive(BigDecimal currentReceived) {
            if (!enabled || maxReceive == null || maxReceive.signum() < 0) return null;
            return maxReceive.subtract(currentReceived);
        }
    }

    private record DailyLimitPair(DailyLimitEffective senderLimits, DailyLimitEffective targetLimits) { }

    private record CombinedPrecheckResult(boolean conditionsOk,
                                          DailyLimitEffective senderLimits,
                                          DailyLimitEffective targetLimits) { }

    // ------------------- Message helpers -------------------

    /**
     * Builds a TagResolver for common payment messages.
     * This method is optional convenience for Commands if they want to reuse it.
     */
    public TagResolver buildCommonResolver(OfflinePlayer target,
                                           NexCurrency currency,
                                           BigDecimal amount) {
        String symbol = amount.compareTo(BigDecimal.ONE) == 0
                ? plugin.getNexEcoFactory().plain(currency.getSingularSymbol())
                : plugin.getNexEcoFactory().plain(currency.getPluralSymbol());
        return TagResolver.resolver(
                Placeholder.parsed("target", target != null && target.getName() != null ? target.getName() : "unknown"),
                Placeholder.parsed("amount", EcoMath.scale(currency, amount).toPlainString()),
                Placeholder.parsed("currency", symbol)
        );
    }
}