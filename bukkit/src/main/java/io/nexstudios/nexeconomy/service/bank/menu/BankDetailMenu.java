package io.nexstudios.nexeconomy.service.bank.menu;

import io.nexstudios.dialogservice.api.ConfirmDialog;
import io.nexstudios.dialogservice.api.TextRequestDialog;
import io.nexstudios.dialogservice.service.ConfirmDialogService;
import io.nexstudios.dialogservice.service.TextRequestDialogService;
import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.menuservice.common.api.CloseReason;
import io.nexstudios.menuservice.common.api.MenuInteractionHooks;
import io.nexstudios.menuservice.common.api.MenuKey;
import io.nexstudios.menuservice.common.api.MenuService;
import io.nexstudios.menuservice.common.api.MenuSlot;
import io.nexstudios.menuservice.common.api.MenuView;
import io.nexstudios.menuservice.common.api.ViewerRef;
import io.nexstudios.menuservice.common.api.builder.MenuDefinitionBuilder;
import io.nexstudios.menuservice.common.api.interaction.InteractionPolicies;
import io.nexstudios.menuservice.common.api.item.MenuItem;
import io.nexstudios.menuservice.common.api.registry.DuplicateStrategy;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.level.BankLevelService;
import io.nexstudios.nexeconomy.service.economy.EconomyService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Dependencies({
    ItemService.class,
    MenuService.class,
    BankService.class,
    EconomyService.class,
    BankLevelService.class,
    CurrencyRegistryService.class,
    TextRequestDialogService.class,
    ConfirmDialogService.class
})
public final class BankDetailMenu {

  public static final MenuKey KEY = MenuKey.of("nexeconomy", "bank-detail");

  private static final int SLOT_INFO = 4;
  private static final int SLOT_DEPOSIT = 20;
  private static final int SLOT_DEPOSIT_ALL = 21;
  private static final int SLOT_WITHDRAW = 23;
  private static final int SLOT_WITHDRAW_ALL = 24;
  private static final int SLOT_BACK = 49;

  private static final Map<UUID, BankContext> CONTEXTS = new ConcurrentHashMap<>();
  private static ServiceAccessor servicesRef;

  private BankDetailMenu() {}

  public static void register(@NotNull ServiceAccessor services) {
    servicesRef = services;

    MenuService menuService = services.getService(MenuService.class);
    ItemService items = services.getService(ItemService.class);

    var def = MenuDefinitionBuilder.create()
        .key(KEY)
        .title("§6Bank Details")
        .rows(6)
        .refreshInterval(Duration.ofSeconds(1))
        .interactionPolicy(InteractionPolicies.locked())
        .fillEmptySlotsWith(MenuItem.of(items.builder(Material.BLACK_STAINED_GLASS_PANE)
            .amount(1)
            .name(Component.text(" "))
            .build()))
        .interactionHooks(new MenuInteractionHooks() {
          @Override
          public void onClose(MenuKey key, ViewerRef viewer, CloseReason reason) {
            CONTEXTS.remove(viewer.uniqueId());
          }
        })
        .populator(ctx -> populate(ctx, items))
        .build();

    menuService.registry().register(def, DuplicateStrategy.REPLACE);
  }

  public static void open(@NotNull ServiceAccessor services, @NotNull ViewerRef viewer, @NotNull String bankId, @NotNull UUID ownerUuid, boolean ownerBank) {
    CONTEXTS.put(viewer.uniqueId(), new BankContext(bankId, ownerUuid, ownerBank));
    services.getService(MenuService.class).open(viewer, KEY);
  }

  private static void populate(io.nexstudios.menuservice.common.api.MenuPopulateContext ctx, ItemService items) {
    ViewerRef viewer = ctx.viewer();
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    BankContext context = CONTEXTS.get(viewer.uniqueId());

    if (player == null || context == null) {
      setButton(ctx, items, SLOT_INFO, Material.BARRIER, "Bank not available", "Open this menu from the bank overview.", null);
      setButton(ctx, items, SLOT_BACK, Material.ARROW, "Back", "Return to overview", clickCtx -> {
        clickCtx.cancel();
        if (servicesRef != null) {
          BankOverviewMenu.open(servicesRef, clickCtx.viewer());
        }
      });
      return;
    }

    BankData data = loadBankData(player, context);
    if (data == null) {
      setButton(ctx, items, SLOT_INFO, Material.BARRIER, "Bank not available", "The bank data could not be loaded.", null);
      setButton(ctx, items, SLOT_BACK, Material.ARROW, "Back", "Return to overview", clickCtx -> {
        clickCtx.cancel();
        if (servicesRef != null) {
          BankOverviewMenu.open(servicesRef, clickCtx.viewer());
        }
      });
      return;
    }

    setInfoPanel(ctx, items, data);
    setDepositButtons(ctx, items, data);
    setWithdrawButtons(ctx, items, data);
    setButton(ctx, items, SLOT_BACK, Material.ARROW, "Back", "Return to overview", clickCtx -> {
      clickCtx.cancel();
      if (servicesRef != null) {
        BankOverviewMenu.open(servicesRef, clickCtx.viewer());
      }
    });
  }

  private static BankData loadBankData(Player player, BankContext context) {
    BankService bankService = servicesRef.getService(BankService.class);
    EconomyService economy = servicesRef.getService(EconomyService.class);
    CurrencyRegistryService currencies = servicesRef.getService(CurrencyRegistryService.class);

    Optional<BankDefinition> bankOpt;
    try {
      bankOpt = bankService.bank(context.bankId()).join();
    } catch (Exception ex) {
      return null;
    }
    if (bankOpt.isEmpty()) return null;

    BankDefinition definition = bankOpt.get();
    CurrencyDefinition currency = currencies.currency(definition.currencyIdLower());
    if (currency == null) return null;

    UUID viewerUuid = player.getUniqueId();
    MantissaAmount bankBalance;
    try {
      bankBalance = bankService.balanceVisibleTo(context.bankId(), context.ownerUuid(), viewerUuid).join();
    } catch (Exception ex) {
      bankBalance = MantissaAmount.zero();
    }

    MantissaAmount walletBalance;
    try {
      walletBalance = economy.balance(player, currency.id()).join();
    } catch (Exception ex) {
      walletBalance = MantissaAmount.zero();
    }

    List<BankRepositoryService.BankAccountRef> accounts;
    try {
      accounts = bankService.allBanks(viewerUuid).join();
    } catch (Exception ex) {
      accounts = List.of();
    }

    BankRepositoryService.BankAccountRef accountRef = null;
    for (BankRepositoryService.BankAccountRef ref : accounts) {
      if (ref == null) continue;
      if (context.bankId().equalsIgnoreCase(ref.bankIdLower()) && context.ownerUuid().equals(ref.ownerUuid())) {
        accountRef = ref;
        break;
      }
    }

    List<io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity> members;
    try {
      members = bankService.members(context.bankId(), context.ownerUuid()).join();
    } catch (Exception ex) {
      members = List.of();
    }

    io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity viewerMember = findMember(members, viewerUuid);
    BankDefinition.RoleDefinition role = resolveRole(definition, context, viewerUuid, viewerMember);

    boolean canDeposit = role != null && role.canDeposit();
    boolean canWithdraw = role != null && role.withdraw() != null && role.withdraw().canWithdraw();

    BankLevelService levelService = servicesRef.getService(BankLevelService.class);
    MantissaAmount maxBalance = MantissaAmount.zero();
    UUID bankAccountId = accountRef == null ? null : accountRef.bankAccountId();
    try {
      if (bankAccountId != null) {
        int level = levelService.getLevel(bankAccountId).join();
        maxBalance = levelService.getMaxBalance(context.bankId(), level);
      }
    } catch (Exception ignored) {
      maxBalance = MantissaAmount.zero();
    }

    MantissaAmount remainingCapacity = maxBalance == null ? MantissaAmount.zero() : maxBalance.subtract(bankBalance == null ? MantissaAmount.zero() : bankBalance);
    if (remainingCapacity.compareTo(MantissaAmount.zero()) < 0) remainingCapacity = MantissaAmount.zero();

    boolean bankFull = maxBalance != null
        && maxBalance.compareTo(MantissaAmount.zero()) > 0
        && bankBalance != null
        && bankBalance.compareTo(maxBalance) >= 0;

    boolean depositEnabled = canDeposit && walletBalance.compareTo(MantissaAmount.zero()) > 0 && !bankFull && remainingCapacity.compareTo(MantissaAmount.zero()) > 0;
    String depositDisabledReason = !canDeposit
        ? "Your role cannot deposit."
        : walletBalance.compareTo(MantissaAmount.zero()) <= 0
            ? "Your wallet is empty."
            : bankFull
                ? "This bank is full."
                : "No deposit capacity left.";

    MantissaAmount withdrawRemaining = computeWithdrawRemaining(definition, currency, accountRef, role, viewerUuid, bankBalance);
    boolean withdrawEnabled = canWithdraw && bankBalance.compareTo(MantissaAmount.zero()) > 0 && withdrawRemaining.compareTo(MantissaAmount.zero()) > 0;
    String withdrawDisabledReason = !canWithdraw
        ? "Your role cannot withdraw."
        : bankBalance.compareTo(MantissaAmount.zero()) <= 0
            ? "The bank is empty."
            : withdrawRemaining.compareTo(MantissaAmount.zero()) <= 0
                ? "Your withdraw limit is reached."
                : "Withdraw is unavailable.";

    return new BankData(
        context,
        definition,
        currency,
        accountRef,
        role,
        bankBalance == null ? MantissaAmount.zero() : bankBalance,
        walletBalance == null ? MantissaAmount.zero() : walletBalance,
        maxBalance,
        remainingCapacity,
        bankFull,
        withdrawRemaining,
        depositEnabled,
        depositDisabledReason,
        withdrawEnabled,
        withdrawDisabledReason
    );
  }

  private static void setInfoPanel(io.nexstudios.menuservice.common.api.MenuPopulateContext ctx, ItemService items, BankData data) {
    String ownerName = nameOrUuid(data.context().ownerUuid());
    String roleName = data.role() == null ? "unknown" : data.role().nameMiniMessage();
    String bankBalance = AmountNotation.formatShort(data.bankBalance(), data.currency().fractionDigits());
    String walletBalance = AmountNotation.formatShort(data.walletBalance(), data.currency().fractionDigits());

    ItemStack stack = items.builder(Material.PAPER)
        .amount(1)
        .name(MiniMessage.miniMessage().deserialize(data.definition().nameMiniMessage()))
        .lore(l -> l
            .line("&7Bank ID: &f" + data.definition().idLower())
            .line("&7Owner: &f" + ownerName)
            .line("&7Role: &f" + roleName)
            .line("&7Bank balance: &e" + bankBalance)
            .line("&7Max balance: &e" + AmountNotation.formatShort(data.maxBalance(), data.currency().fractionDigits()))
            .line("&7Remaining capacity: &e" + AmountNotation.formatShort(data.remainingCapacity(), data.currency().fractionDigits()))
            .line("&7Wallet balance: &e" + walletBalance)
            .line("&8Deposit all uses your full wallet balance")
            .line("&8Withdraw all may be capped by role limits")
        )
        .build();

    ctx.slot(SLOT_INFO).setPlannedItem(() -> MenuItem.of(stack));
  }

  private static void setDepositButtons(io.nexstudios.menuservice.common.api.MenuPopulateContext ctx, ItemService items, BankData data) {
    if (!data.depositEnabled()) {
      setBarrier(ctx, items, SLOT_DEPOSIT, "Deposit", data.depositDisabledReason());
      setBarrier(ctx, items, SLOT_DEPOSIT_ALL, "Deposit all", data.depositDisabledReason());
      return;
    }

    setButton(ctx, items, SLOT_DEPOSIT, Material.IRON_INGOT, "Deposit", "Enter an amount like 5k or 15ab", clickCtx -> {
      clickCtx.cancel();
      openAmountDialog(clickCtx.viewer(), data, false);
    });

    if (data.remainingCapacity().compareTo(MantissaAmount.zero()) > 0 && data.walletBalance().compareTo(MantissaAmount.zero()) > 0) {
      setButton(ctx, items, SLOT_DEPOSIT_ALL, Material.IRON_BLOCK, "Deposit all", "Deposit your full wallet balance", clickCtx -> {
        clickCtx.cancel();
        openAllDepositDialog(clickCtx.viewer(), data);
      });
    } else {
      String reason = data.bankFull() ? "This bank is full." : data.walletBalance().compareTo(MantissaAmount.zero()) <= 0 ? "Your wallet is empty." : "No deposit capacity left.";
      setBarrier(ctx, items, SLOT_DEPOSIT_ALL, "Deposit all", reason);
    }
  }

  private static void setWithdrawButtons(io.nexstudios.menuservice.common.api.MenuPopulateContext ctx, ItemService items, BankData data) {
    if (!data.withdrawEnabled()) {
      setBarrier(ctx, items, SLOT_WITHDRAW, "Withdraw", data.withdrawDisabledReason());
      setBarrier(ctx, items, SLOT_WITHDRAW_ALL, "Withdraw all", data.withdrawDisabledReason());
      return;
    }

    setButton(ctx, items, SLOT_WITHDRAW, Material.GOLD_INGOT, "Withdraw", "Enter an amount like 5k or 15ab", clickCtx -> {
      clickCtx.cancel();
      openAmountDialog(clickCtx.viewer(), data, true);
    });

    if (data.withdrawRemaining().compareTo(MantissaAmount.zero()) > 0) {
      setButton(ctx, items, SLOT_WITHDRAW_ALL, Material.GOLD_BLOCK, "Withdraw all", "Withdraw as much as the role allows", clickCtx -> {
        clickCtx.cancel();
        openAllWithdrawDialog(clickCtx.viewer(), data);
      });
    } else {
      setBarrier(ctx, items, SLOT_WITHDRAW_ALL, "Withdraw all", data.withdrawDisabledReason());
    }
  }

  private static void setButton(io.nexstudios.menuservice.common.api.MenuPopulateContext ctx,
                                ItemService items,
                                int slot,
                                Material material,
                                String name,
                                String lore,
                                io.nexstudios.menuservice.common.api.MenuSlot.MenuClickHandler clickHandler) {
    ItemStack stack = items.builder(material)
        .amount(1)
        .name(Component.text(name))
        .lore(l -> {
          if (lore != null && !lore.isBlank()) {
            l.line("&7" + lore);
          }
        })
        .build();

    MenuSlot menuSlot = ctx.slot(slot);
    menuSlot.setPlannedItem(() -> MenuItem.of(stack));
    if (clickHandler != null) {
      menuSlot.onClick(clickHandler);
    }
  }

  private static void setBarrier(io.nexstudios.menuservice.common.api.MenuPopulateContext ctx, ItemService items, int slot, String name, String reason) {
    setButton(ctx, items, slot, Material.BARRIER, name, reason, null);
  }

  private static void openAmountDialog(ViewerRef viewer, BankData data, boolean withdraw) {
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    if (player == null) return;

    TextRequestDialogService textService = servicesRef.getService(TextRequestDialogService.class);
    TextRequestDialog dialog = textService.create()
        .title(withdraw ? "Withdraw amount" : "Deposit amount")
        .body("Enter an amount like 5k or 15ab")
        .placeholder("Amount...")
        .minCharacters(1)
        .maxCharacters(32)
        .submitButton(withdraw ? "Withdraw" : "Deposit");

    dialog.show(player).thenAccept(raw -> {
      if (raw == null || raw.isBlank()) return;

      MantissaAmount amount = parseAmount(data.currency(), raw);
      if (amount == null || amount.compareTo(MantissaAmount.zero()) <= 0) {
        player.sendMessage(Component.text("Invalid amount."));
        return;
      }

      if (withdraw) {
        executeWithdraw(player, data, amount);
      } else {
        executeDeposit(player, data, amount);
      }
    });
  }

  private static void openAllDepositDialog(ViewerRef viewer, BankData data) {
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    if (player == null) return;

    currentWalletBalance(player, data.currency()).thenCompose(balance -> {
      if (balance == null || balance.compareTo(MantissaAmount.zero()) <= 0) {
        player.sendMessage(Component.text("Your wallet is empty."));
        return CompletableFuture.completedFuture(null);
      }

      String shown = AmountNotation.formatShort(balance, data.currency().fractionDigits());
      ConfirmDialogService confirmService = servicesRef.getService(ConfirmDialogService.class);
      ConfirmDialog confirm = confirmService.create()
          .title("Deposit all?")
          .body("Do you really want to deposit " + shown + " from your wallet into this bank?")
          .confirmButton("Deposit")
          .cancelButton("Cancel");

      return confirm.show(player).thenAccept(result -> {
        if (!Boolean.TRUE.equals(result)) return;
        executeDeposit(player, data, balance);
      });
    });
  }

  private static void openAllWithdrawDialog(ViewerRef viewer, BankData data) {
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    if (player == null) return;

    currentBankBalance(viewer, data).thenCompose(balance -> {
      if (balance == null || balance.compareTo(MantissaAmount.zero()) <= 0) {
        player.sendMessage(Component.text("The bank is empty."));
        return CompletableFuture.completedFuture(null);
      }

      String shown = AmountNotation.formatShort(balance, data.currency().fractionDigits());
      ConfirmDialogService confirmService = servicesRef.getService(ConfirmDialogService.class);
      ConfirmDialog confirm = confirmService.create()
          .title("Withdraw all?")
          .body("Do you really want to withdraw up to " + shown + " from this bank? The role limit may reduce the amount automatically.")
          .confirmButton("Withdraw")
          .cancelButton("Cancel");

      return confirm.show(player).thenAccept(result -> {
        if (!Boolean.TRUE.equals(result)) return;
        executeWithdraw(player, data, balance);
      });
    });
  }

  private static void executeDeposit(Player player, BankData data, MantissaAmount amount) {
    BankService bankService = servicesRef.getService(BankService.class);
    bankService.deposit(data.context().bankId(), data.context().ownerUuid(), player.getUniqueId(), amount)
        .thenAccept(done -> {
          String shown = AmountNotation.formatShort(done, data.currency().fractionDigits());
          player.sendMessage(Component.text("Deposited " + shown + "."));
          refreshOpenView(player);
        })
        .exceptionally(ex -> {
          player.sendMessage(Component.text("Deposit failed: " + rootMessage(ex)));
          return null;
        });
  }

  private static void executeWithdraw(Player player, BankData data, MantissaAmount amount) {
    BankService bankService = servicesRef.getService(BankService.class);
    bankService.withdraw(data.context().bankId(), data.context().ownerUuid(), player.getUniqueId(), amount)
        .thenAccept(done -> {
          String shown = AmountNotation.formatShort(done, data.currency().fractionDigits());
          player.sendMessage(Component.text("Withdrew " + shown + "."));
          refreshOpenView(player);
        })
        .exceptionally(ex -> {
          player.sendMessage(Component.text("Withdraw failed: " + rootMessage(ex)));
          return null;
        });
  }

  private static CompletableFuture<MantissaAmount> currentWalletBalance(Player player, CurrencyDefinition currency) {
    EconomyService economy = servicesRef.getService(EconomyService.class);
    return economy.balance(player, currency.id());
  }

  private static CompletableFuture<MantissaAmount> currentBankBalance(ViewerRef viewer, BankData data) {
    BankService bankService = servicesRef.getService(BankService.class);
    return bankService.balanceVisibleTo(data.context().bankId(), data.context().ownerUuid(), viewer.uniqueId());
  }

  private static MantissaAmount computeWithdrawRemaining(BankDefinition definition,
                                                         CurrencyDefinition currency,
                                                         BankRepositoryService.BankAccountRef accountRef,
                                                         BankDefinition.RoleDefinition role,
                                                         UUID viewerUuid,
                                                         MantissaAmount currentBankBalance) {
    if (definition == null || accountRef == null || accountRef.bankAccountId() == null || role == null || role.withdraw() == null) {
      return currentBankBalance == null ? MantissaAmount.zero() : currentBankBalance;
    }

    BankDefinition.WithdrawDefinition wd = role.withdraw();
    MantissaAmount bankBalance = currentBankBalance == null ? MantissaAmount.zero() : currentBankBalance;
    MantissaAmount dailyLimit = parseLimit(currency, wd.dailyLimitRaw());
    MantissaAmount hourlyLimit = parseLimit(currency, wd.hourlyLimitRaw());

    ZoneId zone = resolveBankZone(definition);
    long hourStart = windowStartEpoch(zone, true);
    long dayStart = windowStartEpoch(zone, false);

    BankRepositoryService repo = servicesRef.getService(BankRepositoryService.class);
    MantissaAmount remaining = bankBalance;

    if (!isUnlimited(hourlyLimit)) {
      MantissaAmount used = zeroSafe(repo.addWithdrawUsage(accountRef.bankAccountId(), viewerUuid, io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankWithdrawUsageEntity.WindowType.HOURLY, hourStart, MantissaAmount.zero()).join());
      remaining = min(remaining, max(hourlyLimit.subtract(used), MantissaAmount.zero()));
    }

    if (!isUnlimited(dailyLimit)) {
      MantissaAmount used = zeroSafe(repo.addWithdrawUsage(accountRef.bankAccountId(), viewerUuid, io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankWithdrawUsageEntity.WindowType.DAILY, dayStart, MantissaAmount.zero()).join());
      remaining = min(remaining, max(dailyLimit.subtract(used), MantissaAmount.zero()));
    }

    return remaining;
  }

  private static void refreshOpenView(Player player) {
    MenuService menuService = servicesRef.getService(MenuService.class);
    menuService.findOpenView(ViewerRef.of(player.getUniqueId(), player.getName()))
        .ifPresent(MenuView::requestRefresh);
  }

  private static MantissaAmount parseAmount(CurrencyDefinition currency, String raw) {
    if (raw == null) return null;

    String input = raw.trim();
    if (input.isBlank()) return null;

    if (currency != null && currency.type() == CurrencyType.VAULT) {
      BigDecimal human = AmountNotation.parseVaultHuman(input);
      if (human != null) return MantissaAmount.of(human, 0);
    }

    MantissaAmount virtual = AmountNotation.parseVirtualMantissaAmount(input);
    if (virtual != null) return virtual;

    BigDecimal human = AmountNotation.parseVaultHuman(input);
    return human == null ? null : MantissaAmount.of(human, 0);
  }

  private static MantissaAmount parseLimit(CurrencyDefinition currency, String raw) {
    if (raw == null) return MantissaAmount.zero();
    String s = raw.trim();
    if (s.isBlank()) return MantissaAmount.zero();
    if ("-1".equals(s)) return MantissaAmount.of(BigDecimal.valueOf(-1), 0);

    if (currency != null && currency.type() == CurrencyType.VAULT) {
      BigDecimal human = AmountNotation.parseVaultHuman(s);
      return human == null ? MantissaAmount.zero() : MantissaAmount.of(human, 0);
    }

    MantissaAmount virtual = AmountNotation.parseVirtualMantissaAmount(s);
    return virtual == null ? MantissaAmount.zero() : virtual;
  }

  private static boolean isUnlimited(MantissaAmount limit) {
    return limit != null && limit.toHuman().compareTo(BigDecimal.valueOf(-1)) == 0;
  }

  private static MantissaAmount zeroSafe(MantissaAmount amount) {
    return amount == null ? MantissaAmount.zero() : amount;
  }

  private static MantissaAmount max(MantissaAmount a, MantissaAmount b) {
    MantissaAmount x = zeroSafe(a);
    MantissaAmount y = zeroSafe(b);
    return x.compareTo(y) >= 0 ? x : y;
  }

  private static MantissaAmount min(MantissaAmount a, MantissaAmount b) {
    MantissaAmount x = zeroSafe(a);
    MantissaAmount y = zeroSafe(b);
    return x.compareTo(y) <= 0 ? x : y;
  }

  private static ZoneId resolveBankZone(BankDefinition def) {
    try {
      String tz = def == null || def.interestSystem() == null ? null : def.interestSystem().timezone();
      if (tz == null || tz.isBlank()) return ZoneId.of("UTC");
      return ZoneId.of(tz.trim());
    } catch (Exception ignored) {
      return ZoneId.of("UTC");
    }
  }

  private static long windowStartEpoch(ZoneId zone, boolean hourly) {
    ZonedDateTime now = ZonedDateTime.now(zone == null ? ZoneId.of("UTC") : zone);
    if (hourly) {
      return now.withMinute(0).withSecond(0).withNano(0).toEpochSecond();
    }
    return now.toLocalDate().atStartOfDay(zone == null ? ZoneId.of("UTC") : zone).toEpochSecond();
  }

  private static BankDefinition.RoleDefinition resolveRole(BankDefinition def,
                                                           BankContext context,
                                                           UUID viewerUuid,
                                                           io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity member) {
    BankDefinition.MemberSystem memberSystem = def.memberSystem();
    Map<String, BankDefinition.RoleDefinition> roles = memberSystem == null || memberSystem.rolesByIdLower() == null
        ? Map.of()
        : memberSystem.rolesByIdLower();

    if (viewerUuid != null && viewerUuid.equals(context.ownerUuid())) {
      BankDefinition.RoleDefinition ownerRole = roles.get("owner");
      if (ownerRole != null) return ownerRole;
    }

    if (member != null) {
      String roleId = member.getRoleIdLower() == null ? "" : member.getRoleIdLower().trim().toLowerCase();
      BankDefinition.RoleDefinition role = roles.get(roleId);
      if (role != null) return role;
    }

    return roles.get("member");
  }

  private static io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity findMember(List<io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity> members,
                                                                                                      UUID viewerUuid) {
    if (members == null || viewerUuid == null) return null;
    for (io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity member : members) {
      if (member == null) continue;
      if (viewerUuid.equals(member.getMemberUuid())) return member;
    }
    return null;
  }

  private static String nameOrUuid(UUID uuid) {
    if (uuid == null) return "unknown";
    OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
    String name = off.getName();
    return name == null || name.isBlank() ? uuid.toString() : name;
  }

  private static String rootMessage(Throwable ex) {
    Throwable root = ex;
    for (int i = 0; i < 6 && root != null && root.getCause() != null; i++) {
      root = root.getCause();
    }
    return root == null || root.getMessage() == null ? "unknown error" : root.getMessage();
  }

  private record BankContext(String bankId, UUID ownerUuid, boolean ownerBank) {}

  private record BankData(
      BankContext context,
      BankDefinition definition,
      CurrencyDefinition currency,
      BankRepositoryService.BankAccountRef bankRef,
      BankDefinition.RoleDefinition role,
      MantissaAmount bankBalance,
      MantissaAmount walletBalance,
      MantissaAmount maxBalance,
      MantissaAmount remainingCapacity,
      boolean bankFull,
      MantissaAmount withdrawRemaining,
      boolean depositEnabled,
      String depositDisabledReason,
      boolean withdrawEnabled,
      String withdrawDisabledReason
  ) {}
}



