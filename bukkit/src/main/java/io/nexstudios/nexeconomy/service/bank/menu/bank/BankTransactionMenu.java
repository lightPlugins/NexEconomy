package io.nexstudios.nexeconomy.service.bank.menu.bank;

import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.menuservice.common.api.*;
import io.nexstudios.menuservice.common.api.builder.MenuDefinitionBuilder;
import io.nexstudios.menuservice.common.api.interaction.ClickAction;
import io.nexstudios.menuservice.common.api.interaction.InteractionPolicies;
import io.nexstudios.menuservice.common.api.item.MenuItem;
import io.nexstudios.menuservice.common.api.page.*;
import io.nexstudios.menuservice.common.api.page.control.PageControlButton;
import io.nexstudios.menuservice.common.api.page.control.PageFilterControl;
import io.nexstudios.menuservice.common.api.page.control.PageSortControl;
import io.nexstudios.menuservice.common.api.registry.DuplicateStrategy;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.transaction.BankTransactionService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Dependencies({
    ItemService.class,
    MenuService.class,
    BankService.class,
    BankTransactionService.class,
    LoggerService.class
})
public class BankTransactionMenu {

  public static final MenuKey KEY = MenuKey.of("nexeconomy", "bank-transactions");
  private static final String AREA_ID = "transactions";
  private static final String SORT_ID = "transaction-sort";
  private static final String FILTER_ID = "transaction-filter";
  private static final int SLOT_FILTER = 0;
  private static final int SLOT_SORT = 8;
  private static final int SLOT_BACK = 49;

  private static LoggerService logger;
  private static ServiceAccessor servicesRef;
  private static final java.util.Map<UUID, TransactionContext> CONTEXTS = new java.util.concurrent.ConcurrentHashMap<>();
  private static final Map<UUID, List<TransactionEntry>> SNAPSHOTS = new ConcurrentHashMap<>();
  private static final Map<UUID, CompletableFuture<List<TransactionEntry>>> LOADS = new ConcurrentHashMap<>();

  private BankTransactionMenu() {}

  public static void register(@NotNull ServiceAccessor services) {
    MenuService menuService = services.getService(MenuService.class);
    ItemService items = services.getService(ItemService.class);
    logger = services.getService(LoggerService.class);
    servicesRef = services;

    var def = MenuDefinitionBuilder.create()
        .key(KEY)
        .title("§6Bank Transactions")
        .rows(6)
        .refreshInterval(java.time.Duration.ofSeconds(2))
        .interactionPolicy(InteractionPolicies.locked())
        .fillEmptySlotsWith(MenuItem.of(items.builder(Material.BLACK_STAINED_GLASS_PANE)
            .amount(1)
            .name(Component.text(" "))
            .build()))
        .interactionHooks(new MenuInteractionHooks() {
          @Override
          public void onClose(MenuKey key, ViewerRef viewer, CloseReason reason) {
            CONTEXTS.remove(viewer.uniqueId(), CONTEXTS.get(viewer.uniqueId()));
            SNAPSHOTS.remove(viewer.uniqueId());
            LOADS.remove(viewer.uniqueId());
          }
        })
        .addSortControl(AREA_ID, buildSortControl())
        .addFilterControl(AREA_ID, buildFilterControl())
        .addControlButton(buildSortControlButton(items))
        .addControlButton(buildFilterControlButton(items))
        .addPagedArea(buildPagedArea(items))
        .populator(ctx -> {
          ctx.slot(SLOT_FILTER).setPlannedItem(() -> MenuItem.of(buildFilterButton(items)));
          ctx.slot(SLOT_SORT).setPlannedItem(() -> MenuItem.of(buildSortButton(items)));
          ctx.slot(SLOT_BACK).setPlannedItem(() -> MenuItem.of(items.builder(Material.ARROW)
              .amount(1)
              .name(Component.text("Back", NamedTextColor.GOLD))
              .build()));
          ctx.slot(SLOT_BACK).onClick(clickCtx -> {
            clickCtx.cancel();
            if (servicesRef != null) {
              TransactionContext transCtx = CONTEXTS.get(clickCtx.viewer().uniqueId());
              if (transCtx != null) {
                BankDetailMenu.open(servicesRef, clickCtx.viewer(), transCtx.bankId(), transCtx.ownerUuid(), transCtx.ownerBank());
              }
            }
          });
        })
        .build();

    menuService.registry().register(def, DuplicateStrategy.REPLACE);
  }

  public static void open(@NotNull ServiceAccessor services,
                          @NotNull ViewerRef viewer,
                          @NotNull String bankId,
                          @NotNull UUID ownerUuid,
                          boolean ownerBank,
                          CurrencyDefinition currency) {
    TransactionContext context = new TransactionContext(bankId, ownerUuid, ownerBank, currency);
    CONTEXTS.put(viewer.uniqueId(), context);
    SNAPSHOTS.remove(viewer.uniqueId());
    loadTransactionsAsync(viewer, context);
    services.getService(MenuService.class).open(viewer, KEY);
  }

  private static PagedAreaDefinition<TransactionEntry> buildPagedArea(ItemService itemService) {
    PageSource<TransactionEntry> source = (menuKey, viewer) -> {
      try {
        TransactionContext ctx = CONTEXTS.get(viewer.uniqueId());
        if (ctx == null) {
          logger.logger().warning("Transaction context not found for viewer " + viewer.uniqueId());
          return List.of();
        }

        BankTransactionService transService = servicesRef.getService(BankTransactionService.class);
        if (transService == null) {
          logger.logger().warning("BankTransactionService not available");
          return List.of();
        }

        List<TransactionEntry> cached = SNAPSHOTS.get(viewer.uniqueId());
        if (cached != null) {
          return cached;
        }

        loadTransactionsAsync(viewer, ctx);
        return List.of();
      } catch (Exception e) {
        logger.logger().warning("Failed to build transaction entries: " + e.getMessage());
        return List.of();
      }
    };

    PageBounds bounds = new PageBounds(1, 1, 7, 4, PageAlignment.LEFT);

    PageNavigation nav = PageNavigation.builder()
        .previousSlot(45)
        .nextSlot(53)
        .previousItem(new ItemStack(Material.SPECTRAL_ARROW))
        .nextItem(new ItemStack(Material.SPECTRAL_ARROW))
        .showCurrentPageAmount(true)
        .hidePreviousOnFirstPage(true)
        .hideNextOnLastPage(true)
        .build();

    return new PagedAreaDefinition<>(
        AREA_ID,
        bounds,
        source,
        (entry, index) -> () -> MenuItem.of(renderTransactionItem(itemService, entry)),
        nav,
        Optional.empty()
    );
  }

  private static ItemStack renderTransactionItem(ItemService items, TransactionEntry entry) {
    BankTransactionEntity tx = entry.transaction();
    String typeLabel = getTransactionType(tx);
    String actorName = getActorName(tx);
    String amountFormatted = getAmount(entry);
    String timestamp = getTimestamp(tx);

    Material material = getMaterialForType(tx);

    return items.builder(material)
        .amount(1)
        .name(Component.text(typeLabel, NamedTextColor.GOLD))
        .lore(l -> l
            .line("&7Type: &f" + typeLabel)
            .line("&7Actor: &f" + actorName)
            .line("&7Amount: &e" + amountFormatted)
            .line("&7Time: &8" + timestamp)
        )
        .build();
  }

  private static Material getMaterialForType(BankTransactionEntity tx) {
    if (tx == null || tx.getType() == null) return Material.PAPER;
    return switch (tx.getType()) {
      case DEPOSIT -> Material.GREEN_HARNESS;
      case WITHDRAW -> Material.RED_HARNESS;
      default -> Material.PAPER;
    };
  }

  private static String getTransactionType(BankTransactionEntity tx) {
    if (tx == null || tx.getType() == null) return "Unknown";
    return formatType(tx.getType().name());
  }

  private static String getActorName(BankTransactionEntity tx) {
    if (tx == null) return "Unknown";
    UUID actorUuid = tx.getActorUuid();
    if (actorUuid == null) return "Unknown";
    return nameOrUuid(actorUuid);
  }

  private static String getAmount(TransactionEntry entry) {
    if (entry == null || entry.transaction() == null) return "0";

    BankTransactionEntity tx = entry.transaction();
    String mantissa = tx.getAmountMantissa();
    int exp3 = tx.getAmountExp3();
    if (mantissa == null) return "0";

    MantissaAmount amount = MantissaAmount.of(new BigDecimal(mantissa), exp3);
    BankService bankService = servicesRef == null ? null : servicesRef.getService(BankService.class);
    if (bankService != null) {
      return bankService.formatBalanceWithCurrency(amount, entry.currency());
    }

    String shown = AmountNotation.formatShort(amount, fractionDigits(entry.currency()));
    String symbol = symbolFor(entry.currency(), amount.toHuman());
    return symbol.isBlank() ? shown : shown + " " + symbol;
  }

  private static String getTimestamp(BankTransactionEntity tx) {
    if (tx == null || tx.getCreatedAt() == null) return "Unknown time";
    return formatTimestamp(tx.getCreatedAt().toEpochMilli());
  }

  private static String formatTimestamp(long timestampMillis) {
    try {
      Instant instant = Instant.ofEpochMilli(timestampMillis);
      ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
      return zdt.format(formatter);
    } catch (Exception e) {
      return "Unknown time";
    }
  }

  private static String nameOrUuid(UUID uuid) {
    if (uuid == null) return "Unknown";
    OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
    String name = off.getName();
    return name == null || name.isBlank() ? uuid.toString() : name;
  }

  private static void loadTransactionsAsync(ViewerRef viewer, TransactionContext ctx) {
    if (viewer == null || ctx == null || servicesRef == null) return;

    UUID viewerUuid = viewer.uniqueId();
    if (LOADS.containsKey(viewerUuid) || SNAPSHOTS.containsKey(viewerUuid)) {
      return;
    }

    BankTransactionService transService = servicesRef.getService(BankTransactionService.class);
    if (transService == null) {
      if (logger != null) {
        logger.logger().warning("BankTransactionService not available");
      }
      return;
    }

    CompletableFuture<List<TransactionEntry>> load = transService.transactionsVisibleTo(
            ctx.bankId(),
            ctx.ownerUuid(),
            viewerUuid,
            100
        )
        .thenApply(transactions -> {
          if (transactions == null) return List.of();
          return transactions.stream()
              .filter(java.util.Objects::nonNull)
              .map(tx -> new TransactionEntry(tx, ctx.currency()))
              .toList();
        });

    LOADS.put(viewerUuid, load);
    load.whenComplete((entries, error) -> {
      LOADS.remove(viewerUuid, load);

      if (error != null) {
        if (logger != null) {
          logger.logger().warning("Failed to load transactions: " + error.getMessage());
        }
        return;
      }

      TransactionContext current = CONTEXTS.get(viewerUuid);
      if (!ctx.equals(current)) {
        return;
      }

      SNAPSHOTS.put(viewerUuid, entries == null ? List.of() : List.copyOf(entries));
      refreshOpenView(viewer);
    });
  }

  private static void refreshOpenView(ViewerRef viewer) {
    if (servicesRef == null || viewer == null) return;

    MenuService menuService = servicesRef.getService(MenuService.class);
    if (menuService == null) return;

    menuService.findOpenView(viewer).ifPresent(MenuView::requestRefresh);
  }

  private static int fractionDigits(CurrencyDefinition currency) {
    return currency == null ? 0 : Math.max(0, currency.fractionDigits());
  }

  private static String symbolFor(CurrencyDefinition currency, BigDecimal humanAmount) {
    if (currency == null) return "";
    String singular = currency.symbolSingular();
    String plural = currency.symbolPlural();
    if (singular == null && plural == null) return "";
    boolean singularAmount = humanAmount != null && humanAmount.compareTo(BigDecimal.ONE) == 0;
    String symbol = singularAmount ? singular : plural;
    return symbol == null ? "" : symbol;
  }

  private static PageControlButton buildSortControlButton(ItemService items) {
    return new PageControlButton() {
      @Override public String areaId() { return AREA_ID; }
      @Override public String controlId() { return SORT_ID; }
      @Override public int slot() { return SLOT_SORT; }

      @Override
      public MenuItem render(RenderContext ctx) {
        String mode = ctx.activeModeId().orElse(ctx.control().defaultModeId());
        String label = ctx.control().labelForMode(mode);

        return MenuItem.of(items.builder(Material.COMPARATOR)
            .amount(1)
            .name(Component.text("Sort: " + label, NamedTextColor.GOLD))
            .lore(l -> l
                .line("&7Available options:")
                .line("&" + ("deposit-withdraw".equals(mode) ? "c" : "7") + "▶ Deposit → Withdraw")
                .line("&" + ("withdraw-deposit".equals(mode) ? "c" : "7") + "▶ Withdraw → Deposit")
                .line("&" + ("latest-last".equals(mode) ? "c" : "7") + "▶ Latest → Last")
                .line("&" + ("last-latest".equals(mode) ? "c" : "7") + "▶ Last → Latest")
                .line("&8Click to cycle")
            )
            .build());
      }

      @Override
      public void onClick(ClickContext ctx) {
        if (ctx.action() == ClickAction.RIGHT_CLICK) {
          ctx.stateStore().cycleToPreviousMode(
              ctx.viewer(),
              ctx.menuKey(),
              ctx.areaId(),
              ctx.control()
          );
        } else if(ctx.action() == ClickAction.LEFT_CLICK) {
          ctx.stateStore().cycleToNextMode(
              ctx.viewer(),
              ctx.menuKey(),
              ctx.areaId(),
              ctx.control()
          );
        }

        ctx.requestAreaRefresh();
      }
    };
  }

  private static PageControlButton buildFilterControlButton(ItemService items) {
    return new PageControlButton() {
      @Override public String areaId() { return AREA_ID; }
      @Override public String controlId() { return FILTER_ID; }
      @Override public int slot() { return SLOT_FILTER; }

      @Override
      public MenuItem render(RenderContext ctx) {
        String mode = ctx.activeModeId().orElse(ctx.control().defaultModeId());
        String label = ctx.control().labelForMode(mode);

        return MenuItem.of(items.builder(Material.HOPPER)
            .amount(1)
            .name(Component.text("Filter: " + label, NamedTextColor.GOLD))
            .lore(l -> l
                .line("&7Available options:")
                .line("&" + ("all".equals(mode) ? "c" : "7") + "▶ All")
                .line("&" + ("withdraw".equals(mode) ? "c" : "7") + "▶ Withdraw only")
                .line("&" + ("deposit".equals(mode) ? "c" : "7") + "▶ Deposit only")
                .line("&8Click to cycle")
            )
            .build());
      }

      @Override
      public void onClick(ClickContext ctx) {
        if (ctx.action() == ClickAction.RIGHT_CLICK) {
          ctx.stateStore().cycleToPreviousMode(
              ctx.viewer(),
              ctx.menuKey(),
              ctx.areaId(),
              ctx.control()
          );
        } else if(ctx.action() == ClickAction.LEFT_CLICK) {
          ctx.stateStore().cycleToNextMode(
              ctx.viewer(),
              ctx.menuKey(),
              ctx.areaId(),
              ctx.control()
          );
        }

        ctx.requestAreaRefresh();
      }
    };
  }

  private static ItemStack buildSortButton(ItemService items) {
    return items.builder(Material.COMPARATOR)
        .amount(1)
        .name(Component.text("Sort", NamedTextColor.GOLD))
        .lore(l -> l.line("&7Wird vom Control-Button gerendert"))
        .build();
  }

  private static ItemStack buildFilterButton(ItemService items) {
    return items.builder(Material.HOPPER)
        .amount(1)
        .name(Component.text("Filter", NamedTextColor.GOLD))
        .lore(l -> l.line("&7Wird vom Control-Button gerendert"))
        .build();
  }

  private static PageSortControl<TransactionEntry> buildSortControl() {
    return new PageSortControl<>() {
      @Override public String controlId() { return SORT_ID; }
      @Override public List<String> modeIds() { return List.of(
          "deposit-withdraw",
          "withdraw-deposit",
          "latest-last",
          "last-latest"
      ); }
      @Override public String defaultModeId() { return "latest-last"; }

      @Override
      public String labelForMode(String modeId) {
        return switch (modeId) {
          case "deposit-withdraw" -> "Deposit → Withdraw";
          case "latest-last" -> "Latest → Last";
          case "last-latest" -> "Last → Latest";
          default -> "Withdraw → Deposit";
        };
      }

      @Override
      public Comparator<TransactionEntry> comparatorFor(String modeId, MenuKey menuKey, ViewerRef viewer) {
        return switch (modeId) {
          case "deposit-withdraw" -> Comparator.comparingInt(entry -> transactionRank(entry.transaction(), false));
          case "latest-last" -> Comparator.comparingLong((TransactionEntry entry) -> createdAtEpoch(entry.transaction())).reversed();
          case "last-latest" -> Comparator.comparingLong((TransactionEntry entry) -> createdAtEpoch(entry.transaction()));
          default -> Comparator.comparingInt(entry -> transactionRank(entry.transaction(), true));
        };
      }
    };
  }

  private static PageFilterControl<TransactionEntry> buildFilterControl() {
    return new PageFilterControl<>() {
      @Override public String controlId() { return FILTER_ID; }
      @Override public List<String> modeIds() { return List.of("all", "withdraw", "deposit"); }
      @Override public String defaultModeId() { return "all"; }

      @Override
      public String labelForMode(String modeId) {
        return switch (modeId) {
          case "withdraw" -> "Withdraw only";
          case "deposit" -> "Deposit only";
          default -> "All";
        };
      }

      @Override
      public java.util.function.Predicate<TransactionEntry> predicateFor(String modeId, MenuKey menuKey, ViewerRef viewer) {
        return entry -> switch (modeId) {
          case "withdraw" -> entry != null && entry.transaction() != null && entry.transaction().getType() == BankTransactionEntity.Type.WITHDRAW;
          case "deposit" -> entry != null && entry.transaction() != null && entry.transaction().getType() == BankTransactionEntity.Type.DEPOSIT;
          default -> true;
        };
      }
    };
  }

  private static int transactionRank(BankTransactionEntity tx, boolean withdrawFirst) {
    if (tx == null || tx.getType() == null) return 2;
    return switch (tx.getType()) {
      case WITHDRAW -> withdrawFirst ? 0 : 1;
      case DEPOSIT -> withdrawFirst ? 1 : 0;
      default -> 2;
    };
  }

  private static long createdAtEpoch(BankTransactionEntity tx) {
    return tx == null || tx.getCreatedAt() == null ? 0L : tx.getCreatedAt().toEpochMilli();
  }

  private static String formatType(String raw) {
    if (raw == null || raw.isBlank()) return "Unknown";
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    if (normalized.equals("withdraw")) return "Withdraw";
    if (normalized.equals("deposit")) return "Deposit";
    return raw;
  }

  private record TransactionContext(String bankId, UUID ownerUuid, boolean ownerBank, CurrencyDefinition currency) {}

  private record TransactionEntry(BankTransactionEntity transaction, CurrencyDefinition currency) {}
}
