package io.nexstudios.economy.commands;
import io.nexstudios.economy.NexEconomy;
import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.storage.NexEcoResponse;
import io.nexstudios.economy.storage.NexEcoService;
import io.nexstudios.economy.storage.support.EcoMath;
import io.nexstudios.economy.storage.support.TransactionLogger;
import io.nexstudios.nexus.bukkit.language.NexusLanguage;
import io.nexstudios.nexus.libs.commands.BaseCommand;
import io.nexstudios.nexus.libs.commands.PaperCommandManager;
import io.nexstudios.nexus.libs.commands.annotation.*;
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
import java.util.UUID;

/**
 * Per-currency command; root alias wird zur Registrierzeit via %currency% Replacement gesetzt.
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
                )
                ,false);

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
                    )
                    , false);
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

        if (!redisEnabled && !isLocalOnline(target)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.cross-server-error");
            return;
        }

        if (!isLocalOnline(target)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.player-not-found");
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

        if(isSilent(silentFlag) && sender instanceof ConsoleCommandSender) {
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

        if(res.isSuccess()) {
            NexEconomy.getInstance().getMessageSender().send(sender, "currency.set", resolve);
            if(!isSilent(silentFlag) && target.isOnline()) {
                NexEconomy.getInstance().getMessageSender().send(target.getPlayer(), "currency.set-target", resolve);
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

        if (!redisEnabled && !isLocalOnline(target)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.cross-server-error");
            return;
        }

        if (!isLocalOnline(target)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.player-not-found");
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
        
        if(isSilent(silentFlag) && sender instanceof ConsoleCommandSender) {
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

        if(res.isSuccess()) {
            NexEconomy.getInstance().getMessageSender().send(sender, "currency.deposit", resolve);
            if(!isSilent(silentFlag) && target.isOnline()) {
                NexEconomy.getInstance().getMessageSender().send(target.getPlayer(), "currency.deposit-other", resolve);
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

        if (!redisEnabled && !isLocalOnline(target)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.cross-server-error");
            return;
        }

        if (!isLocalOnline(target)) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.player-not-found");
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

        if(isSilent(silentFlag) && sender instanceof ConsoleCommandSender) {
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

        if(res.isSuccess()) {
            NexEconomy.getInstance().getMessageSender().send(sender, "currency.withdraw", resolve);
            if(!isSilent(silentFlag) && target.isOnline()) {
                NexEconomy.getInstance().getMessageSender().send(target.getPlayer(), "currency.withdraw-target", resolve);
            }
        } else {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.response-error", resolve);
        }
    }

    private boolean hasPermission(CommandSender sender, Permissions specific, Permissions global) {
        String specificNode = specific.withKey(key);
        return sender.hasPermission(specificNode) || sender.hasPermission(global.raw());
    }

    private String formatAmount(BigDecimal v) {
        int fd = Math.max(0, currency.getFractionDigits());
        return v.setScale(fd, java.math.RoundingMode.DOWN).toPlainString();
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
}