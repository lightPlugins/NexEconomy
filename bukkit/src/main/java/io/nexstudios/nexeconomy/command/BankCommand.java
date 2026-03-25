package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.Arg;
import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.annotations.Suggest;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.command.suggestions.AmountSuggestion;
import io.nexstudios.nexeconomy.command.suggestions.BankSuggestion;
import io.nexstudios.nexeconomy.command.suggestions.PlayerSuggestion;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.repo.InviteLookupRow;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
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
    CurrencyRegistryService.class
})
public final class BankCommand implements Service {

  private final ComponentService components;
  private final BankService bankService;
  private final CurrencyRegistryService currencies;

  public BankCommand(ServiceAccessor accessor) {
    this.components = accessor.getService(ComponentService.class);
    this.bankService = accessor.getService(BankService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
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

  @Command(value = "invite <bank> <player> [role]", permission = "nexeconomy.bank.invite")
  public int inviteSelf(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("player") @Suggest(PlayerSuggestion.class) Player player,
      @Arg("role") String role
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    if (player == null || !player.isOnline()) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", "unknown")))
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
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  // --- other (member) commands ---

  @Command(value = "other balance <bank> <ownerUuid>", permission = "nexeconomy.bank.other.balance")
  public int balanceOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("ownerUuid") String ownerUuidRaw
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = parseUuid(ownerUuidRaw);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.uuid.invalid", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("value", ownerUuidRaw == null ? "" : ownerUuidRaw)))
          .build());
      return 0;
    }

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

    bankService.balance(bankId, ownerUuid).thenCompose(bal ->
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

  @Command(value = "other members <bank> <ownerUuid>", permission = "nexeconomy.bank.other.members")
  public int membersOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("ownerUuid") String ownerUuidRaw
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = parseUuid(ownerUuidRaw);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.uuid.invalid", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("value", ownerUuidRaw == null ? "" : ownerUuidRaw)))
          .build());
      return 0;
    }

    String bankId = normalize(bank);
    if (bankId.isBlank()) return 0;

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

  @Command(value = "other deposit <bank> <ownerUuid> <amount>", permission = "nexeconomy.bank.other.deposit")
  public int depositOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("ownerUuid") String ownerUuidRaw,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amountRaw
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = parseUuid(ownerUuidRaw);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.uuid.invalid", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("value", ownerUuidRaw == null ? "" : ownerUuidRaw)))
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

  @Command(value = "other withdraw <bank> <ownerUuid> <amount>", permission = "nexeconomy.bank.other.withdraw")
  public int withdrawOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("ownerUuid") String ownerUuidRaw,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amountRaw
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = parseUuid(ownerUuidRaw);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.uuid.invalid", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("value", ownerUuidRaw == null ? "" : ownerUuidRaw)))
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

  @Command(value = "other invite <bank> <ownerUuid> <player> [role]", permission = "nexeconomy.bank.other.invite")
  public int inviteOther(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("ownerUuid") String ownerUuidRaw,
      @Arg("player") @Suggest(PlayerSuggestion.class) Player player,
      @Arg("role") String role
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = parseUuid(ownerUuidRaw);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.uuid.invalid", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("value", ownerUuidRaw == null ? "" : ownerUuidRaw)))
          .build());
      return 0;
    }

    if (player == null || !player.isOnline()) {
      sender.sendMessage(components.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", "unknown")))
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
                Placeholder.parsed("ownerUuid", row.ownerUuid() == null ? "" : row.ownerUuid().toString()),
                Placeholder.parsed("role", row.roleIdLower() == null ? "" : row.roleIdLower()),
                Placeholder.parsed("invitedBy", invitedByName)
            ))
            .build());
      }
    }).exceptionally(ex -> {
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other accept <ownerUuid>", permission = "nexeconomy.bank.other.accept")
  public int acceptOther(
      NexPaperCommandSource source,
      @Arg("ownerUuid") String ownerUuidRaw
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = parseUuid(ownerUuidRaw);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.uuid.invalid", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("value", ownerUuidRaw == null ? "" : ownerUuidRaw)))
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
      sendBankError(sender, ex);
      return null;
    });

    return 1;
  }

  @Command(value = "other deny <ownerUuid>", permission = "nexeconomy.bank.other.deny")
  public int denyOther(
      NexPaperCommandSource source,
      @Arg("ownerUuid") String ownerUuidRaw
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID ownerUuid = parseUuid(ownerUuidRaw);
    if (ownerUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.uuid.invalid", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("value", ownerUuidRaw == null ? "" : ownerUuidRaw)))
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
    String msg = ex == null
        ? "Unknown"
        : (ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage());

    player.sendMessage(components.builder(player, "bank.error", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("error", msg)))
        .build());
  }

  private record AmountView(MantissaAmount amount, CurrencyDefinition currency) {}
  private record BalanceView(MantissaAmount balance, CurrencyDefinition currency) {}

  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}