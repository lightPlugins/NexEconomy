package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.Arg;
import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.annotations.Suggest;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.command.suggestions.*;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.repo.InviteLookupRow;
import io.nexstudios.nexeconomy.service.bank.transaction.BankTransactionService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@CommandRoot(
    name = "bank",
    description = "Bank commands"
)
@Dependencies({
    ComponentService.class,
    BankService.class,
    CurrencyRegistryService.class,
    BankTransactionService.class
})
public final class EconomyBankCommand implements Service {

  private static final int TX_LIMIT = 10;

  private static final DateTimeFormatter TX_TIME_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

  private final ComponentService components;
  private final BankService bankService;
  private final CurrencyRegistryService currencies;
  private final BankTransactionService txService;

  public EconomyBankCommand(ServiceAccessor accessor) {
    this.components = accessor.getService(ComponentService.class);
    this.bankService = accessor.getService(BankService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.txService = accessor.getService(BankTransactionService.class);
  }

  @Command(value = "balance <bank>", permission = "nexeconomy.bank.balance")
  public int balanceSelf(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    UUID ownerUuid = sender.getUniqueId();

    bankService.balance(bankId, ownerUuid).thenCompose(bal ->
        resolveCurrency(bankId).thenApply(cur -> new BalanceView(bal, cur))
    ).thenAccept(view -> {
      String shown = AmountNotation.formatShort(view.balance, view.currency == null ? 0 : view.currency.fractionDigits());

      sender.sendMessage(components.builder(sender, "bank.balance.self", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("currency", view.currency == null ? "" : view.currency.symbolPlural())
          ))
          .build());
    }).exceptionally(ex -> {
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "transactions <bank>", permission = "nexeconomy.bank.transactions")
  public int transactionsSelf(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    UUID ownerUuid = sender.getUniqueId();
    UUID viewerUuid = sender.getUniqueId();

    resolveCurrency(bankId).thenCompose(cur ->
        txService.transactionsVisibleTo(bankId, ownerUuid, viewerUuid, TX_LIMIT)
            .thenApply(list -> new TxListView(list, cur))
    ).thenAccept(view -> {
      var list = view.transactions;
      if (list == null || list.isEmpty()) {
        sender.sendMessage(components.builder(sender, "bank.transactions.empty", "NotDefined", true)
            .resolver(TagResolver.resolver(Placeholder.parsed("bank", bankId)))
            .build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.transactions.header", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("owner", sender.getName())
          ))
          .build());

      int fd = view.currency == null ? 0 : view.currency.fractionDigits();

      for (var tx : list) {
        if (tx == null) continue;

        String time = formatTime(tx.getCreatedAt());
        String actor = nameOrUuid(tx.getActorUuid());
        String amountColored = formatAmountColored(tx.getType(), MantissaAmount.parseStorage(tx.getAmountMantissa(), tx.getAmountExp3()), fd);

        sender.sendMessage(components.builder(sender, "bank.transactions.row", "NotDefined", false)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("time", time),
                Placeholder.parsed("actor", actor),
                Placeholder.parsed("amount", amountColored)
            ))
            .build());
      }
    }).exceptionally(ex -> {
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "members <bank>", permission = "nexeconomy.bank.members")
  public int membersSelf(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    UUID ownerUuid = sender.getUniqueId();

    bankService.members(bankId, ownerUuid).thenAccept(list -> {
      String members = (list == null || list.isEmpty())
          ? "-"
          : list.stream().map(m -> {
        UUID id = m == null ? null : m.getMemberUuid();
        if (id == null) return "unknown";
        OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        String name = off.getName();
        return name == null ? id.toString() : name;
      }).collect(Collectors.joining(", "));

      sender.sendMessage(components.builder(sender, "bank.members.self", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("members", members)
          ))
          .build());
    }).exceptionally(ex -> {
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "deposit <bank> <amount>", permission = "nexeconomy.bank.deposit")
  public int depositSelf(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amountRaw
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    UUID ownerUuid = sender.getUniqueId();
    UUID actorUuid = sender.getUniqueId();

    resolveCurrency(bankId).thenCompose(cur -> {
      MantissaAmount parsed = parseAmount(cur, amountRaw);
      if (parsed == null || parsed.isNegative() || parsed.compareTo(MantissaAmount.zero()) == 0) {
        return CompletableFuture.failedFuture(new IllegalArgumentException("invalid amount"));
      }
      return bankService.deposit(bankId, ownerUuid, actorUuid, parsed).thenApply(done -> new AmountView(done, cur));
    }).thenAccept(view -> {
      String shown = AmountNotation.formatShort(view.amount, view.currency == null ? 0 : view.currency.fractionDigits());

      sender.sendMessage(components.builder(sender, "bank.deposit.self", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("amount", shown)
          ))
          .build());
    }).exceptionally(ex -> {
      if (ex.getCause() instanceof IllegalArgumentException) {
        sender.sendMessage(components.builder(sender, "general.wrong-amount", "NotDefined", true).build());
        return null;
      }
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "withdraw <bank> <amount>", permission = "nexeconomy.bank.withdraw")
  public int withdrawSelf(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amountRaw
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    UUID ownerUuid = sender.getUniqueId();
    UUID actorUuid = sender.getUniqueId();

    resolveCurrency(bankId).thenCompose(cur -> {
      MantissaAmount parsed = parseAmount(cur, amountRaw);
      if (parsed == null || parsed.isNegative() || parsed.compareTo(MantissaAmount.zero()) == 0) {
        return CompletableFuture.failedFuture(new IllegalArgumentException("invalid amount"));
      }
      return bankService.withdraw(bankId, ownerUuid, actorUuid, parsed).thenApply(done -> new AmountView(done, cur));
    }).thenAccept(view -> {
      String shown = AmountNotation.formatShort(view.amount, view.currency == null ? 0 : view.currency.fractionDigits());

      sender.sendMessage(components.builder(sender, "bank.withdraw.self", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("amount", shown)
          ))
          .build());
    }).exceptionally(ex -> {
      if (ex.getCause() instanceof IllegalArgumentException) {
        sender.sendMessage(components.builder(sender, "general.wrong-amount", "NotDefined", true).build());
        return null;
      }
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "invite <bank> <player> <role>", permission = "nexeconomy.bank.invite")
  public int inviteSelf(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("player") @Suggest(PlayerSuggestion.class) String playerName,
      @Arg("role") @Suggest(BankRoleSuggestion.class) String role
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    Player player = (playerName == null || playerName.isBlank()) ? null : Bukkit.getPlayerExact(playerName.trim());
    if (player == null || !player.isOnline()) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", playerName == null ? "unknown" : playerName)))
          .build());
      return 0;
    }

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    UUID ownerUuid = sender.getUniqueId();
    UUID actorUuid = sender.getUniqueId();
    UUID inviteeUuid = player.getUniqueId();

    String roleId = normalize(role);
    if (roleId.isBlank()) roleId = "member";

    String finalRoleId = roleId;
    bankService.invite(bankId, ownerUuid, actorUuid, inviteeUuid, roleId).thenAccept(inv -> {
      sender.sendMessage(components.builder(sender, "bank.invite.sent", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("player", player.getName()),
              Placeholder.parsed("role", finalRoleId)
          ))
          .build());

      player.sendMessage(components.builder(player, "bank.invite.received", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("owner", sender.getName()),
              Placeholder.parsed("role", finalRoleId)
          ))
          .build());
    }).exceptionally(ex -> {
      if (isMarker(ex, "invitee_already_in_another_bank")) {
        sender.sendMessage(components.builder(sender, "bank.invite.invitee-already-member", "NotDefined", true)
            .resolver(TagResolver.resolver(Placeholder.parsed("player", player.getName())))
            .build());
        return null;
      }
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  // --- other (member) commands ---

  @Command(value = "other list", permission = "nexeconomy.bank.other.list")
  public int otherList(NexPaperCommandSource source) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID uuid = sender.getUniqueId();

    bankService.otherBanks(uuid).thenAccept(list -> {
      if (list == null || list.isEmpty()) {
        sender.sendMessage(components.builder(sender, "bank.other.list.empty", "NotDefined", true).build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.other.list.header", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("amount", String.valueOf(list.size()))))
          .build());

      for (BankRepositoryService.BankAccountRef ref : list) {
        if (ref == null) continue;

        String ownerShown = nameOrUuid(ref.ownerUuid());
        String bankShown = ref.bankIdLower() == null ? "" : ref.bankIdLower();

        sender.sendMessage(components.builder(sender, "bank.other.list.row", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("bank", bankShown),
                Placeholder.parsed("owner", ownerShown),
                Placeholder.parsed("owner-uuid", ref.ownerUuid() == null ? "" : ref.ownerUuid().toString())
            ))
            .build());
      }
    }).exceptionally(ex -> {
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other transactions <bank> <owner>", permission = "nexeconomy.bank.other.transactions")
  public int transactionsOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("owner") @Suggest(BankOwnerSuggestion.class) String ownerName
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = resolvePlayerUuidByName(ownerName);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", ownerName == null ? "unknown" : ownerName)))
          .build());
      return 0;
    }

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    UUID viewerUuid = sender.getUniqueId();

    resolveCurrency(bankId).thenCompose(cur ->
        txService.transactionsVisibleTo(bankId, ownerUuid, viewerUuid, TX_LIMIT)
            .thenApply(list -> new TxListView(list, cur))
    ).thenAccept(view -> {
      var list = view.transactions;
      String ownerShown = nameOrUuid(ownerUuid);

      if (list == null || list.isEmpty()) {
        sender.sendMessage(components.builder(sender, "bank.transactions.other.empty", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("bank", bankId),
                Placeholder.parsed("owner", ownerShown)
            ))
            .build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.transactions.other.header", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("owner", ownerShown)
          ))
          .build());

      int fd = view.currency == null ? 0 : view.currency.fractionDigits();

      for (var tx : list) {
        if (tx == null) continue;

        String time = formatTime(tx.getCreatedAt());
        String actor = nameOrUuid(tx.getActorUuid());
        String amountColored = formatAmountColored(tx.getType(), MantissaAmount.parseStorage(tx.getAmountMantissa(), tx.getAmountExp3()), fd);

        sender.sendMessage(components.builder(sender, "bank.transactions.row", "NotDefined", false)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("time", time),
                Placeholder.parsed("actor", actor),
                Placeholder.parsed("amount", amountColored)
            ))
            .build());
      }
    }).exceptionally(ex -> {
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other balance <bank> <owner>", permission = "nexeconomy.bank.other.balance")
  public int balanceOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("owner") @Suggest(BankOwnerSuggestion.class) String ownerName
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = resolvePlayerUuidByName(ownerName);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", ownerName == null ? "unknown" : ownerName)))
          .build());
      return 0;
    }

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    UUID viewerUuid = sender.getUniqueId();

    bankService.balanceVisibleTo(bankId, ownerUuid, viewerUuid).thenCompose(bal ->
        resolveCurrency(bankId).thenApply(cur -> new BalanceView(bal, cur))
    ).thenAccept(view -> {
      String shown = AmountNotation.formatShort(view.balance, view.currency == null ? 0 : view.currency.fractionDigits());

      sender.sendMessage(components.builder(sender, "bank.balance.other", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("owner", nameOrUuid(ownerUuid)),
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("currency", view.currency == null ? "" : view.currency.symbolPlural())
          ))
          .build());
    }).exceptionally(ex -> {
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other members <bank> <owner>", permission = "nexeconomy.bank.other.members")
  public int membersOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("owner") @Suggest(BankOwnerSuggestion.class) String ownerName
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = resolvePlayerUuidByName(ownerName);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", ownerName == null ? "unknown" : ownerName)))
          .build());
      return 0;
    }

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    UUID viewerUuid = sender.getUniqueId();

    bankService.membersVisibleTo(bankId, ownerUuid, viewerUuid).thenAccept(list -> {
      String members = (list == null || list.isEmpty())
          ? "-"
          : list.stream().map(m -> {
        UUID id = m == null ? null : m.getMemberUuid();
        if (id == null) return "unknown";
        OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        String name = off.getName();
        return name == null ? id.toString() : name;
      }).collect(Collectors.joining(", "));

      sender.sendMessage(components.builder(sender, "bank.members.other", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("owner", nameOrUuid(ownerUuid)),
              Placeholder.parsed("members", members)
          ))
          .build());
    }).exceptionally(ex -> {
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other deposit <bank> <owner> <amount>", permission = "nexeconomy.bank.other.deposit")
  public int depositOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("owner") @Suggest(BankOwnerSuggestion.class) String ownerName,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amountRaw
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = resolvePlayerUuidByName(ownerName);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", ownerName == null ? "unknown" : ownerName)))
          .build());
      return 0;
    }

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    resolveCurrency(bankId).thenCompose(cur -> {
      MantissaAmount parsed = parseAmount(cur, amountRaw);
      if (parsed == null || parsed.isNegative() || parsed.compareTo(MantissaAmount.zero()) == 0) {
        return CompletableFuture.failedFuture(new IllegalArgumentException("invalid amount"));
      }
      return bankService.deposit(bankId, ownerUuid, sender.getUniqueId(), parsed).thenApply(done -> new AmountView(done, cur));
    }).thenAccept(view -> {
      String shown = AmountNotation.formatShort(view.amount, view.currency == null ? 0 : view.currency.fractionDigits());

      sender.sendMessage(components.builder(sender, "bank.deposit.other", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("owner", nameOrUuid(ownerUuid)),
              Placeholder.parsed("amount", shown)
          ))
          .build());
    }).exceptionally(ex -> {
      if (ex.getCause() instanceof IllegalArgumentException) {
        sender.sendMessage(components.builder(sender, "general.wrong-amount", "NotDefined", true).build());
        return null;
      }
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other withdraw <bank> <owner> <amount>", permission = "nexeconomy.bank.other.withdraw")
  public int withdrawOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("owner") @Suggest(BankOwnerSuggestion.class) String ownerName,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amountRaw
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = resolvePlayerUuidByName(ownerName);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", ownerName == null ? "unknown" : ownerName)))
          .build());
      return 0;
    }

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    resolveCurrency(bankId).thenCompose(cur -> {
      MantissaAmount parsed = parseAmount(cur, amountRaw);
      if (parsed == null || parsed.isNegative() || parsed.compareTo(MantissaAmount.zero()) == 0) {
        return CompletableFuture.failedFuture(new IllegalArgumentException("invalid amount"));
      }
      return bankService.withdraw(bankId, ownerUuid, sender.getUniqueId(), parsed).thenApply(done -> new AmountView(done, cur));
    }).thenAccept(view -> {
      String shown = AmountNotation.formatShort(view.amount, view.currency == null ? 0 : view.currency.fractionDigits());

      sender.sendMessage(components.builder(sender, "bank.withdraw.other", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("owner", nameOrUuid(ownerUuid)),
              Placeholder.parsed("amount", shown)
          ))
          .build());
    }).exceptionally(ex -> {
      if (ex.getCause() instanceof IllegalArgumentException) {
        sender.sendMessage(components.builder(sender, "general.wrong-amount", "NotDefined", true).build());
        return null;
      }
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other invite <bank> <owner> <player> [role]", permission = "nexeconomy.bank.other.invite")
  public int inviteOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("owner") @Suggest(BankOwnerSuggestion.class) String ownerName,
      @Arg("player") @Suggest(PlayerSuggestion.class) String playerName,
      @Arg("role") @Suggest(BankRoleSuggestion.class) String role
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = resolvePlayerUuidByName(ownerName);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", ownerName == null ? "unknown" : ownerName)))
          .build());
      return 0;
    }

    Player player = (playerName == null || playerName.isBlank()) ? null : Bukkit.getPlayerExact(playerName.trim());
    if (player == null || !player.isOnline()) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", playerName == null ? "unknown" : playerName)))
          .build());
      return 0;
    }

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    String roleId = normalize(role);
    if (roleId.isBlank()) roleId = "member";
    String finalRoleId = roleId;

    bankService.invite(bankId, ownerUuid, sender.getUniqueId(), player.getUniqueId(), roleId).thenAccept(inv -> {
      sender.sendMessage(components.builder(sender, "bank.invite.sent-other", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("owner", nameOrUuid(ownerUuid)),
              Placeholder.parsed("player", player.getName()),
              Placeholder.parsed("role", finalRoleId)
          ))
          .build());

      player.sendMessage(components.builder(player, "bank.invite.received-other", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("owner", nameOrUuid(ownerUuid)),
              Placeholder.parsed("inviter", sender.getName()),
              Placeholder.parsed("role", finalRoleId)
          ))
          .build());
    }).exceptionally(ex -> {
      if (isMarker(ex, "invitee_already_in_another_bank")) {
        sender.sendMessage(components.builder(sender, "bank.invite.invitee-already-member", "NotDefined", true)
            .resolver(TagResolver.resolver(Placeholder.parsed("player", player.getName())))
            .build());
        return null;
      }
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other invites", permission = "nexeconomy.bank.other.invites")
  public int invitesOther(NexPaperCommandSource source) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    bankService.invites(sender.getUniqueId()).thenAccept(list -> {
      if (list == null || list.isEmpty()) {
        sender.sendMessage(components.builder(sender, "bank.other.invites.empty", "NotDefined", true).build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.other.invites.header", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("amount", String.valueOf(list.size()))))
          .build());

      for (InviteLookupRow row : list) {
        if (row == null) continue;
        String ownerName = nameOrUuid(row.ownerUuid());
        String invitedByName = nameOrUuid(row.invitedByUuid());

        sender.sendMessage(components.builder(sender, "bank.other.invites.row", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("bank", row.bankIdLower() == null ? "" : row.bankIdLower()),
                Placeholder.parsed("owner", ownerName),
                Placeholder.parsed("owner-uuid", row.ownerUuid() == null ? "" : row.ownerUuid().toString()),
                Placeholder.parsed("role", row.roleIdLower() == null ? "" : row.roleIdLower()),
                Placeholder.parsed("invited-by", invitedByName)
            ))
            .build());
      }
    }).exceptionally(ex -> {
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "leave <bank> <owner>", permission = "nexeconomy.bank.leave")
  public int leave(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("owner") @Suggest(BankOwnerSuggestion.class) String ownerName
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    UUID ownerUuid = resolvePlayerUuidByName(ownerName);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", ownerName == null ? "unknown" : ownerName)))
          .build());
      return 0;
    }

    bankService.leave(bankId, ownerUuid, sender.getUniqueId()).thenAccept(left -> {
      if (!Boolean.TRUE.equals(left)) {
        sender.sendMessage(components.builder(sender, "bank.leave.not-a-member", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("bank", bankId),
                Placeholder.parsed("owner", nameOrUuid(ownerUuid))
            ))
            .build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.leave.success", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankId),
              Placeholder.parsed("owner", nameOrUuid(ownerUuid))
          ))
          .build());
    }).exceptionally(ex -> {
      if (isMarker(ex, "owner_cannot_leave")) {
        sender.sendMessage(components.builder(sender, "bank.leave.owner-cannot-leave", "NotDefined", true)
            .resolver(TagResolver.resolver(Placeholder.parsed("bank", bankId)))
            .build());
        return null;
      }

      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other accept <owner>", permission = "nexeconomy.bank.other.accept")
  public int acceptOther(
      NexPaperCommandSource source,
      @Arg("owner") @Suggest(BankOwnerSuggestion.class) String ownerName
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = resolvePlayerUuidByName(ownerName);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", ownerName == null ? "unknown" : ownerName)))
          .build());
      return 0;
    }

    bankService.acceptInviteFromOwner(ownerUuid, sender.getUniqueId()).thenAccept(ok -> {
      String ownerShown = nameOrUuid(ownerUuid);

      if (!ok) {
        sender.sendMessage(components.builder(sender, "bank.other.accept.none", "NotDefined", true)
            .resolver(TagResolver.resolver(Placeholder.parsed("owner", ownerShown)))
            .build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.other.accept.success", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("owner", ownerShown)))
          .build());
    }).exceptionally(ex -> {
      if (isMarker(ex, "invitee_already_member_somewhere")) {
        sender.sendMessage(components.builder(sender, "bank.accept.already-member", "NotDefined", true).build());
        return null;
      }
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other deny <owner>", permission = "nexeconomy.bank.other.deny")
  public int denyOther(
      NexPaperCommandSource source,
      @Arg("owner") @Suggest(BankOwnerSuggestion.class) String ownerName
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = resolvePlayerUuidByName(ownerName);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", ownerName == null ? "unknown" : ownerName)))
          .build());
      return 0;
    }

    bankService.denyInviteFromOwner(ownerUuid, sender.getUniqueId()).thenAccept(ok -> {
      String ownerShown = nameOrUuid(ownerUuid);

      if (!ok) {
        sender.sendMessage(components.builder(sender, "bank.other.deny.none", "NotDefined", true)
            .resolver(TagResolver.resolver(Placeholder.parsed("owner", ownerShown)))
            .build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.other.deny.success", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("owner", ownerShown)))
          .build());
    }).exceptionally(ex -> {
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  // --- helpers ---

  private static UUID resolvePlayerUuidByName(String name) {
    if (name == null) return null;
    String n = name.trim();
    if (n.isBlank()) return null;

    // Allow direct UUID input (no lookup, fully local)
    UUID parsed = parseUuid(n);
    if (parsed != null) return parsed;

    // Only resolve from local cache. DO NOT call Bukkit.getOfflinePlayer(name) here:
    // it may trigger a Mojang profile lookup and cause lag spikes.
    OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(n);
    return cached == null ? null : cached.getUniqueId();
  }

  private static boolean isMarker(Throwable ex, String marker) {
    if (marker == null || marker.isBlank()) return false;

    Throwable t = ex;
    for (int i = 0; i < 6 && t != null; i++) {
      String msg = t.getMessage();
      if (msg != null && msg.equalsIgnoreCase(marker)) return true;
      t = t.getCause();
    }
    return false;
  }

  // --- helpers ---

  private static UUID parseUuid(String raw) {
    if (raw == null) return null;
    try {
      return UUID.fromString(raw.trim());
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String nameOrUuid(UUID uuid) {
    if (uuid == null) return "unknown";
    OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
    String name = off.getName();
    return (name == null || name.isBlank()) ? uuid.toString() : name;
  }

  private CompletableFuture<CurrencyDefinition> resolveCurrency(String bankIdLower) {
    String id = normalize(bankIdLower);
    if (id.isBlank()) return CompletableFuture.completedFuture(null);

    return bankService.bank(id).thenApply(opt -> opt.orElse(null)).thenApply(def -> {
      if (def == null) return null;
      return currencies.currency(def.currencyIdLower());
    });
  }

  private static MantissaAmount parseAmount(CurrencyDefinition cur, String raw) {
    if (raw == null) return null;
    if (cur != null && cur.type() == CurrencyType.VAULT) {
      BigDecimal human = AmountNotation.parseVaultHuman(raw);
      return human == null ? null : MantissaAmount.of(human, 0);
    }
    return AmountNotation.parseVirtualMantissaAmount(raw);
  }

  private void sendBankError(Player player, Throwable ex) {
    if (player == null) return;

    Throwable root = ex;
    for (int i = 0; i < 6 && root != null && root.getCause() != null; i++) {
      root = root.getCause();
    }

    if (isMarker(root, "bank not available")) {
      player.sendMessage(components.builder(player, "bank.errors.bank-not-available", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "member system disabled")) {
      player.sendMessage(components.builder(player, "bank.errors.member-system-disabled", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "no permission")) {
      player.sendMessage(components.builder(player, "bank.errors.no-permission", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "player must be online")) {
      player.sendMessage(components.builder(player, "bank.errors.player-must-be-online", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "insufficient funds")) {
      player.sendMessage(components.builder(player, "bank.errors.insufficient-funds", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "max balance reached")) {
      player.sendMessage(components.builder(player, "bank.errors.max-balance-reached", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "bank empty")) {
      player.sendMessage(components.builder(player, "bank.errors.bank-empty", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "limit reached")) {
      player.sendMessage(components.builder(player, "bank.errors.withdraw-limit-reached", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "not a member")) {
      player.sendMessage(components.builder(player, "bank.errors.not-a-member", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "unknown role")) {
      player.sendMessage(components.builder(player, "bank.errors.unknown-role", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "already a member")) {
      player.sendMessage(components.builder(player, "bank.errors.already-a-member", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "cannot invite owner")) {
      player.sendMessage(components.builder(player, "bank.errors.cannot-invite-self", "NotDefined", true).build());
      return;
    }

    if (isMarker(root, "bank locked")) {
      player.sendMessage(components.builder(player, "bank.errors.bank-locked", "NotDefined", true).build());
      return;
    }

    String msg = root == null
        ? "Unknown"
        : (root.getMessage() == null || root.getMessage().isBlank() ? root.getClass().getSimpleName() : root.getMessage());

    // add existing error messages (remove me after resolving the missing errors!!)
    ex.printStackTrace();

    player.sendMessage(components.builder(player, "bank.errors.internal", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("error", msg)))
        .build());
  }

  private static String formatTime(Instant createdAt) {
    if (createdAt == null) return "-";
    return TX_TIME_FMT.format(createdAt);
  }

  private static String formatAmountColored(Object type, MantissaAmount amount, int fd) {
    MantissaAmount a = amount == null ? MantissaAmount.zero() : amount;
    String shown = AmountNotation.formatShort(a, Math.max(0, fd));

    String typeStr = type == null ? "" : String.valueOf(type).trim().toUpperCase(Locale.ROOT);
    boolean deposit = "DEPOSIT".equals(typeStr);

    if (deposit) {
      return "<green>+" + shown + "</green>";
    }
    return "<red>-" + shown + "</red>";
  }

  private record TxListView(List<BankTransactionEntity> transactions, CurrencyDefinition currency) {}
  private record AmountView(MantissaAmount amount, CurrencyDefinition currency) {}
  private record BalanceView(MantissaAmount balance, CurrencyDefinition currency) {}

  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}