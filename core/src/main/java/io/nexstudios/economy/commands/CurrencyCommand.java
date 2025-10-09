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
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * Per-currency command; root alias wird zur Registrierzeit via %currency% Replacement gesetzt.
 */
@CommandAlias("%currency")
@Description("Economy commands for a specific currency")
public class CurrencyCommand extends BaseCommand {

    private final NexCurrency currency;
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
        // Keep per-instance logic minimal here.
    }

    // balance (self)
    @Subcommand("balance")
    @CommandCompletion(" ") // no args
    @Description("Show your balance")
    public void balanceSelf(CommandSender sender) {
        if (!hasPermission(sender, Permissions.CURRENCY_BALANCE_SELF, Permissions.GLOBAL_BALANCE_SELF)) {
            sender.sendMessage("You lack permission.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Console: Please specify a player: /" + rootLabel() + " balance <player>");
            return;
        }
        BigDecimal bal = eco.getBalance(player.getUniqueId(), key);
        sender.sendMessage("Balance (" + key + "): " + formatAmount(bal));
        txLogger.log(player.getUniqueId(), key, "CMD_BALANCE_SELF", "balance=" + bal);
    }

    // balance other
    @Subcommand("balance")
    @CommandCompletion("@ecoPlayers")
    @Syntax("<player>")
    @Description("Show balance of another player")
    public void balanceOther(CommandSender sender, OfflinePlayer target) {
        if (!hasPermission(sender, Permissions.CURRENCY_BALANCE_OTHER, Permissions.GLOBAL_BALANCE_OTHER)) {
            sender.sendMessage("You lack permission.");
            return;
        }
        BigDecimal bal = eco.getBalance(target.getUniqueId(), key);
        sender.sendMessage("Balance (" + key + ") of " + safeName(target) + ": " + formatAmount(bal));
        txLogger.log(senderUuid(sender), key, "CMD_BALANCE_OTHER", "target=" + safeName(target) + " balance=" + bal);
    }

    // deposit
    @Subcommand("deposit")
    @CommandCompletion("@ecoPlayers @ecoAmounts @ecoFlags")
    @Syntax("<player> <amount> [-s]")
    @Description("Deposit amount to a player")
    public void deposit(CommandSender sender, OfflinePlayer target, String amountStr, @Optional String silentFlag) {
        if (!hasPermission(sender, Permissions.CURRENCY_DEPOSIT, Permissions.GLOBAL_DEPOSIT)) {
            sender.sendMessage("You lack permission.");
            return;
        }

        if (!redisEnabled && !isLocalOnline(target)) {
            sender.sendMessage("Target is not online on this server (cross-server disabled).");
            return;
        }

        BigDecimal amount = parseAmount(amountStr);
        if (amount == null) {
            sender.sendMessage("Invalid amount.");
            return;
        }

        NexEcoResponse res = eco.deposit(target, key, amount);
        txLogger.log(senderUuid(sender), key, "CMD_DEPOSIT",
                "target=" + safeName(target) + " amount=" + amount + " res=" + res.responseType() + " bal=" + res.balance());

        switch (res.responseType()) {
            case SUCCESS -> {
                sender.sendMessage("Deposited " + formatAmount(res.amount()) + " to " + safeName(target) + ". New balance: " + formatAmount(res.balance()));
                if (!isSilent(silentFlag) && target.isOnline()) {
                    target.getPlayer().sendMessage("You received " + formatAmount(res.amount()) + " (" + key + "). New balance: " + formatAmount(res.balance()));
                }
            }
            case NOT_NEGATIVE -> sender.sendMessage("Amount must be positive.");
            case MAX_BALANCE -> sender.sendMessage("Clamped to max balance. Applied: " + formatAmount(res.amount()) + ". New balance: " + formatAmount(res.balance()));
            case FAILURE, UNKNOWN -> sender.sendMessage("Operation failed.");
            default -> sender.sendMessage("Unsupported result: " + res.responseType());
        }
    }

    // withdraw
    @Subcommand("withdraw")
    @CommandCompletion("@ecoPlayers @ecoAmounts @ecoFlags")
    @Syntax("<player> <amount> [-s]")
    @Description("Withdraw amount from a player")
    public void withdraw(CommandSender sender, OfflinePlayer target, String amountStr, @Optional String silentFlag) {
        if (!hasPermission(sender, Permissions.CURRENCY_WITHDRAW, Permissions.GLOBAL_WITHDRAW)) {
            sender.sendMessage("You lack permission.");
            return;
        }

        if (!redisEnabled && !isLocalOnline(target)) {
            sender.sendMessage("Target is not online on this server (cross-server disabled).");
            return;
        }

        BigDecimal amount = parseAmount(amountStr);
        if (amount == null) {
            sender.sendMessage("Invalid amount.");
            return;
        }

        NexEcoResponse res = eco.withdraw(target, key, amount);
        txLogger.log(senderUuid(sender), key, "CMD_WITHDRAW",
                "target=" + safeName(target) + " amount=" + amount + " res=" + res.responseType() + " bal=" + res.balance());

        switch (res.responseType()) {
            case SUCCESS -> {
                sender.sendMessage("Withdrew " + formatAmount(res.amount()) + " from " + safeName(target) + ". New balance: " + formatAmount(res.balance()));
                if (!isSilent(silentFlag) && target.isOnline()) {
                    target.getPlayer().sendMessage("You paid " + formatAmount(res.amount()) + " (" + key + "). New balance: " + formatAmount(res.balance()));
                }
            }
            case NOT_NEGATIVE -> sender.sendMessage("Amount must be positive.");
            case NOT_ENOUGH -> sender.sendMessage("Not enough balance.");
            case FAILURE, UNKNOWN -> sender.sendMessage("Operation failed.");
            default -> sender.sendMessage("Unsupported result: " + res.responseType());
        }
    }

    // ----- helpers -----

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