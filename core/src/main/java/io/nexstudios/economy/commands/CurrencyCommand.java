package io.nexstudios.economy.commands;

import io.nexstudios.economy.NexEconomy;
import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.logic.PaymentController;
import io.nexstudios.economy.storage.NexEcoResponse;
import io.nexstudios.economy.storage.NexEcoService;
import io.nexstudios.economy.storage.support.EcoMath;
import io.nexstudios.economy.storage.support.TransactionLogger;
import io.nexstudios.nexus.bukkit.language.NexusLanguage;
import io.nexstudios.nexus.libs.commands.BaseCommand;
import io.nexstudios.nexus.libs.commands.PaperCommandManager;
import io.nexstudios.nexus.libs.commands.annotation.CommandAlias;
import io.nexstudios.nexus.libs.commands.annotation.CommandCompletion;
import io.nexstudios.nexus.libs.commands.annotation.CommandPermission;
import io.nexstudios.nexus.libs.commands.annotation.Description;
import io.nexstudios.nexus.libs.commands.annotation.Optional;
import io.nexstudios.nexus.libs.commands.annotation.Subcommand;
import io.nexstudios.nexus.libs.commands.annotation.Syntax;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-currency command; root alias is set via %currency% replacement at registration time.
 * When Redis is enabled, commands may target cross-server players (by UUID/name).
 * When Redis is disabled, modifying commands only allow players that are currently
 * online on this server.
 */
@CommandAlias("%currency")
@Description("Economy commands for a specific currency")
public class CurrencyCommand extends BaseCommand {

    private NexCurrency currency;
    private final String key;
    private final NexEcoService eco;
    private final NexusLanguage lang;
    private final boolean redisEnabled;
    private final TransactionLogger txLogger;

    public CurrencyCommand(NexCurrency currency,
                           String key,
                           NexEcoService eco,
                           PaperCommandManager acf,
                           NexusLanguage lang,
                           boolean redisEnabled,
                           TransactionLogger txLogger) {
        this.currency = currency;
        this.key = key.toLowerCase(Locale.ROOT);
        this.eco = eco;
        this.lang = lang;
        this.redisEnabled = redisEnabled;
        this.txLogger = txLogger;
        // Global completions are registered in NexEconomy.registerCommands()
    }

    public void updateCurrency(NexCurrency newCurrency) {
        this.currency = newCurrency;
    }

    // balance (self)
    @Subcommand("balance")
    @CommandCompletion(" ") // no args
    @Description("Show your balance")
    public void balanceSelf(CommandSender sender) {
        if (!hasPermission(sender, Permissions.CURRENCY_BALANCE_SELF, Permissions.GLOBAL_BALANCE_SELF)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.no-permission");
            return;
        }
        if (!(sender instanceof Player player)) {
            NexEconomy.nexusLogger.error("Please specify a player: <dark_red>" + rootLabel() + "<red balance <player>");
            return;
        }
        BigDecimal bal = eco.getBalance(player.getUniqueId(), key);
        TagResolver resolve = TagResolver.resolver(
                Placeholder.parsed("amount", formatAmount(bal)),
                Placeholder.parsed("currency", bal.doubleValue() == 1 ?
                        PlainTextComponentSerializer.plainText().serialize(currency.getSingularSymbol()) :
                        PlainTextComponentSerializer.plainText().serialize(currency.getPluralSymbol()))
        );
        NexEconomy.getInstance().getMessageSender().send(sender, "currency.balance", resolve);
    }

    // balance other
    @Subcommand("balance")
    @CommandCompletion("@ecoPlayers")
    @Syntax("<player>")
    @Description("Show balance of another player")
    public void balanceOther(CommandSender sender, OfflinePlayer target) {
        if (!hasPermission(sender, Permissions.CURRENCY_BALANCE_OTHER, Permissions.GLOBAL_BALANCE_OTHER)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.no-permission");
            return;
        }

        // When Redis is disabled, only allow balances for players that are online on this server.
        if (!redisEnabled && !isLocalOnline(target)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.cross-server-error");
            return;
        }

        BigDecimal bal = eco.getBalance(target.getUniqueId(), key);
        TagResolver resolve = TagResolver.resolver(
                Placeholder.parsed("amount", formatAmount(bal)),
                Placeholder.parsed("currency", bal.doubleValue() == 1 ?
                        PlainTextComponentSerializer.plainText().serialize(currency.getSingularSymbol()) :
                        PlainTextComponentSerializer.plainText().serialize(currency.getPluralSymbol())),
                Placeholder.parsed("target", safeName(target))
        );
        NexEconomy.getInstance().getMessageSender().send(sender, "currency.balance-other", resolve);
    }

    @Subcommand("baltop")
    @CommandCompletion(" ")
    @Description("Show top 10 players with highest balance")
    public void balanceTop(CommandSender sender) {
        if (!hasPermission(sender, Permissions.CURRENCY_BALANCE_TOP, Permissions.GLOBAL_BALANCE_TOP)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.no-permission");
            return;
        }

        List<NexEcoService.AccountView> topAccounts = eco.getTopBalances(key, 10);

        if (topAccounts.isEmpty()) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.response-error", TagResolver.resolver(
                    Placeholder.parsed("error", "No stored player found")
            ));
            return;
        }

        BigDecimal totalBalance = topAccounts.stream()
                .map(NexEcoService.AccountView::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String currencySymbol = PlainTextComponentSerializer.plainText()
                .serialize(totalBalance.compareTo(BigDecimal.ONE) == 0
                        ? currency.getSingularSymbol()
                        : currency.getPluralSymbol());

        // Header
        NexEconomy.getInstance().getMessageSender().send(sender, "currency.baltop.header",
                TagResolver.resolver(
                        Placeholder.parsed("overall", formatAmount(totalBalance)),
                        Placeholder.parsed("currency", currencySymbol)
                ),
                false
        );

        // Content
        int position = 1;
        for (NexEcoService.AccountView account : topAccounts) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(account.playerId());
            String playerName = safeName(player);

            String symbol = PlainTextComponentSerializer.plainText()
                    .serialize(account.balance().compareTo(BigDecimal.ONE) == 0
                            ? currency.getSingularSymbol()
                            : currency.getPluralSymbol());

            NexEconomy.getInstance().getMessageSender().send(sender, "currency.baltop.content",
                    TagResolver.resolver(
                            Placeholder.parsed("number", String.valueOf(position)),
                            Placeholder.parsed("name", playerName),
                            Placeholder.parsed("amount", formatAmount(account.balance())),
                            Placeholder.parsed("currency", symbol)
                    ),
                    false
            );
            position++;
        }

        // Footer
        NexEconomy.getInstance().getMessageSender().send(sender, "currency.baltop.footer", false);
    }

    @Subcommand("set")
    @CommandCompletion("@ecoPlayers @ecoAmounts @ecoFlags")
    @Syntax("<player> <amount> [-s]")
    @Description("Set balance of another player")
    public void set(CommandSender sender, OfflinePlayer target, String amountStr, @Optional String silentFlag) {
        if (!hasPermission(sender, Permissions.CURRENCY_SET, Permissions.GLOBAL_SET)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.no-permission");
            return;
        }

        // If Redis is disabled, do not allow modifying players that are not currently online on this server.
        if (!redisEnabled && !isLocalOnline(target)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.cross-server-error");
            return;
        }

        BigDecimal amount = parseAmount(amountStr);
        if (amount == null) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.wrong-amount");
            return;
        }

        NexEcoResponse res = eco.setBalance(target.getUniqueId(), key, amount);

        txLogger.log(senderUuid(sender), key, "CMD_SET",
                "target=" + safeName(target) + " amount=" + amount + " res=" + res.responseType() + " bal=" + res.balance());

        if (isSilent(silentFlag) && sender instanceof ConsoleCommandSender) {
            return;
        }

        TagResolver resolve = TagResolver.resolver(
                Placeholder.parsed("amount", formatAmount(amount)),
                Placeholder.parsed("currency", amount.doubleValue() == 1 ?
                        PlainTextComponentSerializer.plainText().serialize(currency.getSingularSymbol()) :
                        PlainTextComponentSerializer.plainText().serialize(currency.getPluralSymbol())),
                Placeholder.parsed("target", safeName(target)),
                Placeholder.parsed("player", sender.getName()),
                Placeholder.parsed("error", res.errorMessage() == null ? "" : res.errorMessage())
        );

        if (res.isSuccess()) {
            NexEconomy.getInstance().getMessageSender().send(sender, "currency.set", resolve);
            if (!isSilent(silentFlag) && target.isOnline()) {
                NexEconomy.getInstance().getMessageSender().send(target.getPlayer(), "currency.set-other", resolve);
            }

            // Cross-server notification when Redis is enabled
            if (redisEnabled && NexEconomy.getInstance().getEconomyRedisSync() != null) {
                NexEconomy.getInstance().getEconomyRedisSync()
                        .publishCurrencyNotification(
                                target.getUniqueId(),
                                key,
                                amount,
                                "SET",
                                sender.getName()
                        );
            }
        } else {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.response-error", resolve);
        }
    }

    // deposit
    @Subcommand("deposit")
    @CommandCompletion("@ecoPlayers @ecoAmounts @ecoFlags")
    @Syntax("<player> <amount> [-s]")
    @Description("Deposit amount to a player")
    public void deposit(CommandSender sender, OfflinePlayer target, String amountStr, @Optional String silentFlag) {
        if (!hasPermission(sender, Permissions.CURRENCY_DEPOSIT, Permissions.GLOBAL_DEPOSIT)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.no-permission");
            return;
        }

        // If Redis is disabled, do not allow modifying players that are not currently online on this server.
        if (!redisEnabled && !isLocalOnline(target)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.cross-server-error");
            return;
        }

        BigDecimal amount = parseAmount(amountStr);
        if (amount == null) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.wrong-amount");
            return;
        }

        NexEcoResponse res = eco.deposit(target, key, amount);
        txLogger.log(senderUuid(sender), key, "CMD_DEPOSIT",
                "target=" + safeName(target) + " amount=" + amount + " res=" + res.responseType() + " bal=" + res.balance());

        if (isSilent(silentFlag) && sender instanceof ConsoleCommandSender) {
            return;
        }

        TagResolver resolve = TagResolver.resolver(
                Placeholder.parsed("amount", formatAmount(amount)),
                Placeholder.parsed("currency", amount.doubleValue() == 1 ?
                        PlainTextComponentSerializer.plainText().serialize(currency.getSingularSymbol()) :
                        PlainTextComponentSerializer.plainText().serialize(currency.getPluralSymbol())),
                Placeholder.parsed("target", safeName(target)),
                Placeholder.parsed("player", sender.getName()),
                Placeholder.parsed("error", res.errorMessage() == null ? "" : res.errorMessage())
        );

        if (res.isSuccess()) {
            NexEconomy.getInstance().getMessageSender().send(sender, "currency.deposit", resolve);
            if (!isSilent(silentFlag) && target.isOnline()) {
                NexEconomy.getInstance().getMessageSender().send(target.getPlayer(), "currency.deposit-other", resolve);
            }

            // Cross-server notification when Redis is enabled
            if (redisEnabled && NexEconomy.getInstance().getEconomyRedisSync() != null) {
                NexEconomy.getInstance().getEconomyRedisSync()
                        .publishCurrencyNotification(
                                target.getUniqueId(),
                                key,
                                amount,
                                "DEPOSIT",
                                sender.getName()
                        );
            }
        } else {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.response-error", resolve);
        }
    }

    // withdraw
    @Subcommand("withdraw")
    @CommandCompletion("@ecoPlayers @ecoAmounts @ecoFlags")
    @Syntax("<player> <amount> [-s]")
    @Description("Withdraw amount from a player")
    public void withdraw(CommandSender sender, OfflinePlayer target, String amountStr, @Optional String silentFlag) {
        if (!hasPermission(sender, Permissions.CURRENCY_WITHDRAW, Permissions.GLOBAL_WITHDRAW)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.no-permission");
            return;
        }

        // If Redis is disabled, do not allow modifying players that are not currently online on this server.
        if (!redisEnabled && !isLocalOnline(target)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.cross-server-error");
            return;
        }

        BigDecimal amount = parseAmount(amountStr);
        if (amount == null) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.wrong-amount");
            return;
        }

        NexEcoResponse res = eco.withdraw(target, key, amount);
        txLogger.log(senderUuid(sender), key, "CMD_WITHDRAW",
                "target=" + safeName(target) + " amount=" + amount + " res=" + res.responseType() + " bal=" + res.balance());

        if (isSilent(silentFlag) && sender instanceof ConsoleCommandSender) {
            return;
        }

        TagResolver resolve = TagResolver.resolver(
                Placeholder.parsed("amount", formatAmount(amount)),
                Placeholder.parsed("currency", amount.doubleValue() == 1 ?
                        PlainTextComponentSerializer.plainText().serialize(currency.getSingularSymbol()) :
                        PlainTextComponentSerializer.plainText().serialize(currency.getPluralSymbol())),
                Placeholder.parsed("target", safeName(target)),
                Placeholder.parsed("player", sender.getName()),
                Placeholder.parsed("error", res.errorMessage() == null ? "" : res.errorMessage())
        );

        if (res.isSuccess()) {
            NexEconomy.getInstance().getMessageSender().send(sender, "currency.withdraw", resolve);
            if (!isSilent(silentFlag) && target.isOnline()) {
                NexEconomy.getInstance().getMessageSender().send(target.getPlayer(), "currency.withdraw-other", resolve);
            }

            // Cross-server notification when Redis is enabled
            if (redisEnabled && NexEconomy.getInstance().getEconomyRedisSync() != null) {
                NexEconomy.getInstance().getEconomyRedisSync()
                        .publishCurrencyNotification(
                                target.getUniqueId(),
                                key,
                                amount,
                                "WITHDRAW",
                                sender.getName()
                        );
            }
        } else {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.response-error", resolve);
        }
    }

    @Subcommand("pay")
    @CommandCompletion("@ecoPlayers @ecoAmounts")
    @Syntax("<player> <amount>")
    @Description("Pay another player using this currency")
    public void pay(CommandSender sender, String targetName, String amountStr) {
        if (!(sender instanceof Player player)) {
            NexEconomy.nexusLogger.error("Only players can use the pay command.");
            return;
        }

        PaymentController paymentController = NexEconomy.getInstance().getPaymentController();
        if (paymentController == null) {
            NexEconomy.getInstance().getMessageSender().send(sender, "currency.payment.pay-failed",
                    TagResolver.resolver(
                            Placeholder.parsed("target", targetName),
                            Placeholder.parsed("amount", amountStr),
                            Placeholder.parsed("currency", PlainTextComponentSerializer.plainText().serialize(currency.getPluralSymbol()))
                    ));
            return;
        }

        BigDecimal requestedAmount = parseAmount(amountStr);
        if (requestedAmount == null || requestedAmount.signum() <= 0) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.wrong-amount");
            return;
        }

        // Check if the given name is part of the same set used for @ecoPlayers completion.
        boolean targetAllowed;

        if (!redisEnabled) {
            // Without Redis: only local online players are considered valid.
            targetAllowed = Bukkit.getOnlinePlayers().stream()
                    .anyMatch(p -> p.getName() != null && p.getName().equalsIgnoreCase(targetName));
        } else {
            // With Redis: any known player (online or offline) is allowed.
            targetAllowed = Bukkit.getOnlinePlayers().stream()
                    .anyMatch(p -> p.getName() != null && p.getName().equalsIgnoreCase(targetName));

            if (!targetAllowed) {
                for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                    if (op.getName() != null && op.getName().equalsIgnoreCase(targetName)) {
                        targetAllowed = true;
                        break;
                    }
                }
            }
        }

        if (!targetAllowed) {
            TagResolver resolver = TagResolver.resolver(Placeholder.parsed("player", targetName));
            // Name is not in the @ecoPlayers universe -> treat as "player not found".
            NexEconomy.getInstance().getMessageSender().send(sender, "general.player-not-found", resolver);
            return;
        }

        // First, try to resolve the player as a locally online player
        Player onlineTarget = Bukkit.getPlayerExact(targetName);

        // For the payment logic we still need an OfflinePlayer.
        // This can trigger a Mojang lookup for never-seen names
        OfflinePlayer target = Objects.requireNonNullElseGet(onlineTarget, () -> Bukkit.getOfflinePlayer(targetName));

        // Explicit self-pay check before calling PaymentController:
        if (target.getUniqueId().equals(player.getUniqueId())) {
            NexEconomy.getInstance().getMessageSender().send(sender, "currency.payment.pay-yourself");
            return;
        }

        // Run the entire payment flow asynchronously to avoid blocking the main thread with DB access and conditions.
        Bukkit.getScheduler().runTaskAsynchronously(NexEconomy.getInstance(), () -> {
            // Use the fully async PaymentController API
            PaymentController.PaymentResult res = paymentController
                    .payAsync(player, target, currency, key, requestedAmount)
                    .join();

            // Switch back to main thread for message sending
            Bukkit.getScheduler().runTask(NexEconomy.getInstance(), () -> {
                switch (res.error()) {
                    case NONE -> {
                        BigDecimal paidAmount = res.amount() != null ? res.amount() : requestedAmount;
                        boolean partial = paidAmount.compareTo(requestedAmount) < 0;

                        if (partial) {
                            BigDecimal limit = res.minOrMaxLimit() != null ? res.minOrMaxLimit() : paidAmount;
                            TagResolver resolver = TagResolver.resolver(
                                    Placeholder.parsed("target", safeName(target)),
                                    Placeholder.parsed("requested", formatAmount(requestedAmount)),
                                    Placeholder.parsed("paid", formatAmount(paidAmount)),
                                    Placeholder.parsed("limit", formatAmount(limit)),
                                    Placeholder.parsed("currency", formatCurrencySymbol(limit))
                            );
                            NexEconomy.getInstance().getMessageSender()
                                    .send(sender, "currency.payment.pay-daily-limit-exceeded", resolver);
                        } else {
                            // Normal full-amount success
                            TagResolver senderResolver = buildSuccessResolver(target, paidAmount);
                            NexEconomy.getInstance().getMessageSender()
                                    .send(sender, "currency.payment.pay-success", senderResolver);
                        }

                        // Message for the target (receiver) always uses the actually paid amount
                        if (target.isOnline()) {
                            TagResolver receiverResolver = TagResolver.resolver(
                                    Placeholder.parsed("player", player.getName()),
                                    Placeholder.parsed("amount", formatAmount(paidAmount)),
                                    Placeholder.parsed("currency", formatCurrencySymbol(paidAmount))
                            );
                            NexEconomy.getInstance().getMessageSender()
                                    .send(target.getPlayer(), "currency.payment.pay-success-target", receiverResolver);
                        }
                    }
                    case DISABLED_FOR_CURRENCY -> NexEconomy.getInstance().getMessageSender()
                            .send(sender, "currency.payment.pay-disabled");
                    case BELOW_MIN -> {
                        BigDecimal min = res.minOrMaxLimit() != null ? res.minOrMaxLimit() : BigDecimal.ZERO;
                        TagResolver resolver = TagResolver.resolver(
                                Placeholder.parsed("min", formatAmount(min)),
                                Placeholder.parsed("currency", formatCurrencySymbol(min))
                        );
                        NexEconomy.getInstance().getMessageSender().send(sender, "currency.payment.pay-min-amount", resolver);
                    }
                    case ABOVE_MAX -> {
                        BigDecimal max = res.minOrMaxLimit() != null ? res.minOrMaxLimit() : BigDecimal.ZERO;
                        TagResolver resolver = TagResolver.resolver(
                                Placeholder.parsed("max", formatAmount(max)),
                                Placeholder.parsed("currency", formatCurrencySymbol(max))
                        );
                        NexEconomy.getInstance().getMessageSender().send(sender, "currency.payment.pay-max-amount", resolver);
                    }
                    case DAILY_LIMIT_SENDER -> {
                        BigDecimal limit = res.minOrMaxLimit();
                        TagResolver resolver = TagResolver.resolver(
                                Placeholder.parsed("amount", formatAmount(limit != null ? limit : BigDecimal.ZERO)),
                                Placeholder.parsed("currency", formatCurrencySymbol(limit != null ? limit : BigDecimal.ZERO))
                        );
                        NexEconomy.getInstance().getMessageSender().send(sender, "currency.payment.pay-daily-limit-pay", resolver);
                    }
                    case DAILY_LIMIT_TARGET -> {
                        BigDecimal limit = res.minOrMaxLimit();
                        TagResolver resolver = TagResolver.resolver(
                                Placeholder.parsed("amount", formatAmount(limit != null ? limit : BigDecimal.ZERO)),
                                Placeholder.parsed("currency", formatCurrencySymbol(limit != null ? limit : BigDecimal.ZERO))
                        );
                        NexEconomy.getInstance().getMessageSender().send(sender, "currency.payment.pay-daily-limit-target", resolver);
                    }
                    case NOT_ENOUGH_BALANCE -> NexEconomy.getInstance().getMessageSender()
                            .send(sender, "general.response-error",
                                    TagResolver.resolver(Placeholder.parsed("error", "Not enough balance.")));
                    case COOLDOWN -> {
                        BigDecimal limit = res.minOrMaxLimit();
                        long seconds = (limit != null ? limit.longValue() : 0L);
                        if (seconds < 0L) seconds = 0L;

                        TagResolver resolver = TagResolver.resolver(
                                Placeholder.parsed("time", String.valueOf(seconds))
                        );
                        NexEconomy.getInstance().getMessageSender()
                                .send(sender, "currency.payment.pay-cooldown", resolver);
                    }
                    case INVALID_AMOUNT -> {
                        NexEconomy.getInstance().getMessageSender().send(sender, "general.response-error",
                                TagResolver.resolver(Placeholder.parsed("error", "Invalid amount.")));
                    }
                    default -> {
                        TagResolver resolver = TagResolver.resolver(
                                Placeholder.parsed("target", targetName),
                                Placeholder.parsed("amount", formatAmount(requestedAmount)),
                                Placeholder.parsed("currency", formatCurrencySymbol(requestedAmount))
                        );
                        NexEconomy.getInstance().getMessageSender().send(sender, "currency.payment.pay-failed", resolver);
                        if (res.errorDetail() != null) {
                            NexEconomy.getInstance().getMessageSender().send(sender, "general.response-error",
                                    TagResolver.resolver(Placeholder.parsed("error", res.errorDetail())));
                        }
                    }
                }
            });
        });
    }

    private boolean hasPermission(CommandSender sender, Permissions specific, Permissions global) {
        String specificNode = specific.withKey(key);
        return sender.hasPermission(specificNode) || sender.hasPermission(global.raw());
    }

    private String formatAmount(BigDecimal v) {
        int fd = Math.max(0, currency.getFractionDigits());
        return v.setScale(fd, java.math.RoundingMode.DOWN).toPlainString();
    }

    private String formatCurrencySymbol(BigDecimal amount) {
        return PlainTextComponentSerializer.plainText().serialize(
                amount.compareTo(BigDecimal.ONE) == 0 ? currency.getSingularSymbol() : currency.getPluralSymbol()
        );
    }

    private BigDecimal parseAmount(String s) {
        try {
            BigDecimal bd = new BigDecimal(s.replace(",", "."));
            return EcoMath.scale(currency, bd);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isLocalOnline(OfflinePlayer p) {
        return p != null && p.isOnline();
    }

    private boolean isSilent(String flag) {
        return flag != null && flag.equalsIgnoreCase("-s");
    }

    private String rootLabel() {
        return NexEconomy.getInstance().getNexEcoFactory().keyOf(currency);
    }

    private static String safeName(OfflinePlayer p) {
        return p == null ? "unknown" : (p.getName() != null ? p.getName() : p.getUniqueId().toString());
    }

    private static UUID senderUuid(CommandSender sender) {
        if (sender instanceof Player pl) return pl.getUniqueId();
        return new UUID(0, 0);
    }

    private TagResolver buildSuccessResolver(OfflinePlayer target, BigDecimal amount) {
        return TagResolver.resolver(
                Placeholder.parsed("target", safeName(target)),
                Placeholder.parsed("amount", formatAmount(amount)),
                Placeholder.parsed("currency", formatCurrencySymbol(amount))
        );
    }
}