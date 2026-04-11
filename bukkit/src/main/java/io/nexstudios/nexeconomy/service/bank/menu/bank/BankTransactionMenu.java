package io.nexstudios.nexeconomy.service.bank.menu.bank;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
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
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.provider.bank.BankProviderService;
import io.nexstudios.nexeconomy.service.bank.effects.BankClickEffectService;
import io.nexstudios.nexeconomy.service.bank.menu.extra.BankExtraItemSupport;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import io.nexstudios.nexlogic.bukkit.services.items.config.ConfigItemService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@NoArgsConstructor
@Dependencies({
    ItemService.class,
    MenuService.class,
    BankProviderService.class,
    LoggerService.class,
    FileReaderService.class
})
public class BankTransactionMenu {

  public static final MenuKey KEY = MenuKey.of("nexeconomy", "bank-transactions");
  private static final String AREA_ID = "transactions";
  private static final String SORT_ID = "transaction-sort";
  private static final String FILTER_ID = "transaction-filter";
  private static final Path CONFIG_PATH = Path.of("inventories/bank-transactions.yml");
  private static final String CONFIG_FILE = "inventories/bank-transactions.yml";

  private static final int DEFAULT_ROWS = 6;
  private static final int DEFAULT_FILTER_SLOT = 0;
  private static final int DEFAULT_SORT_SLOT = 8;
  private static final int DEFAULT_BACK_SLOT = 49;
  private static final int DEFAULT_ENTRY_X = 1;
  private static final int DEFAULT_ENTRY_Y = 1;
  private static final int DEFAULT_ENTRY_WIDTH = 7;
  private static final int DEFAULT_ENTRY_HEIGHT = 4;
  private static final int DEFAULT_PREVIOUS_SLOT = 45;
  private static final int DEFAULT_NEXT_SLOT = 53;

  private static LoggerService logger;
  private static ServiceAccessor servicesRef;
  private static ItemService itemService;
  private static BankProviderService bankProvider;
  private static FileReaderService fileReaderService;
  private static ConfigItemService configItemService;
  private static FileConfiguration bankConfig;
  private static int SLOT_FILTER = DEFAULT_FILTER_SLOT;
  private static int SLOT_SORT = DEFAULT_SORT_SLOT;
  private static int SLOT_BACK = DEFAULT_BACK_SLOT;
  private static ItemStack TRANSACTION_DEPOSIT_TEMPLATE;
  private static ItemStack TRANSACTION_WITHDRAW_TEMPLATE;
  private static List<BankExtraItemSupport.ExtraItemBinding> EXTRA_ITEMS = List.of();
  private static final Map<UUID, TransactionContext> CONTEXTS = new ConcurrentHashMap<>();
  private static final Map<UUID, List<TransactionEntry>> SNAPSHOTS = new ConcurrentHashMap<>();
  private static final Map<UUID, CompletableFuture<List<TransactionEntry>>> LOADS = new ConcurrentHashMap<>();

  public static void register(@NotNull ServiceAccessor services) {
    MenuService menuService = services.getService(MenuService.class);
    logger = services.getService(LoggerService.class);
    servicesRef = services;
    itemService = services.getService(ItemService.class);
    bankProvider = services.getService(BankProviderService.class);
    fileReaderService = services.getService(FileReaderService.class);
    configItemService = NexEconomyPlugin.getNexLogicService().getService(ConfigItemService.class);

    bankConfig = loadConfig();
    SLOT_FILTER = bankConfig.getInt("layout.slots.filter", DEFAULT_FILTER_SLOT);
    SLOT_SORT = bankConfig.getInt("layout.slots.sort", DEFAULT_SORT_SLOT);
    SLOT_BACK = bankConfig.getInt("layout.slots.back", DEFAULT_BACK_SLOT);

    ItemStack fillTemplate = configuredItem(bankConfig, "items.fill", Material.BLACK_STAINED_GLASS_PANE);
    ItemStack sortTemplate = configuredItem(bankConfig, "items.sort-button", Material.COMPARATOR);
    ItemStack filterTemplate = configuredItem(bankConfig, "items.filter-button", Material.HOPPER);
    ItemStack transactionFallbackTemplate = new ItemStack(Material.PAPER);
    TRANSACTION_DEPOSIT_TEMPLATE = configuredItem(bankConfig, "items.deposit", Material.LIME_DYE);
    TRANSACTION_WITHDRAW_TEMPLATE = configuredItem(bankConfig, "items.withdraw", Material.RED_DYE);
    ItemStack previousTemplate = configuredItem(bankConfig, "items.navigation.previous", Material.SPECTRAL_ARROW);
    ItemStack nextTemplate = configuredItem(bankConfig, "items.navigation.next", Material.SPECTRAL_ARROW);
    Map<String, String> sortModes = readSortModes(bankConfig);
    Map<String, String> filterModes = readFilterModes(bankConfig);
    PageSortControl<TransactionEntry> sortControl = buildSortControl(bankConfig, sortModes);
    PageFilterControl<TransactionEntry> filterControl = buildFilterControl(bankConfig, filterModes);
    EXTRA_ITEMS = BankExtraItemSupport.loadBindings(bankConfig, configItemService);

    var def = MenuDefinitionBuilder.create()
        .key(KEY)
        .title(toLegacyTitle(bankConfig.getString("menu.title", "<yellow>Bank Transactions</yellow>")))
        .rows(bankConfig.getInt("menu.rows", DEFAULT_ROWS))
        .refreshInterval(parseRefreshInterval(bankConfig.getString("menu.refresh-interval", "2")))
        .interactionPolicy(InteractionPolicies.locked())
        .fillEmptySlotsWith(MenuItem.of(fillTemplate))
        .interactionHooks(new MenuInteractionHooks() {
          @Override
          public void onClose(MenuKey key, ViewerRef viewer, CloseReason reason) {
            CONTEXTS.remove(viewer.uniqueId());
            SNAPSHOTS.remove(viewer.uniqueId());
            LOADS.remove(viewer.uniqueId());
          }
        })
        .addSortControl(AREA_ID, sortControl)
        .addFilterControl(AREA_ID, filterControl)
        .addControlButton(buildSortControlButton(sortTemplate, sortControl, sortModes, bankConfig))
        .addControlButton(buildFilterControlButton(filterTemplate, filterControl, filterModes, bankConfig))
        .addPagedArea(buildPagedArea(transactionFallbackTemplate, previousTemplate, nextTemplate, bankConfig))
        .populator(ctx -> populate(ctx, bankConfig))
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

  private static void populate(MenuPopulateContext ctx, FileConfiguration config) {
    ctx.slot(SLOT_FILTER).setPlannedItem(() -> MenuItem.of(buildFilterButton()));
    ctx.slot(SLOT_SORT).setPlannedItem(() -> MenuItem.of(buildSortButton()));
    ctx.slot(SLOT_BACK).setPlannedItem(() -> MenuItem.of(buildBackButton(config)));
    ctx.slot(SLOT_BACK).onClick(clickCtx -> {
      clickCtx.cancel();
      triggerGeneralClick(clickCtx.viewer().uniqueId());
      if (servicesRef == null) {
        return;
      }

      TransactionContext transCtx = CONTEXTS.get(clickCtx.viewer().uniqueId());
      if (transCtx != null) {
        BankDetailMenu.open(servicesRef, clickCtx.viewer(), transCtx.bankId(), transCtx.ownerUuid(), transCtx.ownerBank());
      }
    });

    BankExtraItemSupport.populate(ctx, servicesRef, EXTRA_ITEMS, "bank-transactions");
  }

  private static PagedAreaDefinition<TransactionEntry> buildPagedArea(ItemStack transactionTemplate,
                                                                     ItemStack previousTemplate,
                                                                     ItemStack nextTemplate,
                                                                     FileConfiguration config) {
    PageSource<TransactionEntry> source = (menuKey, viewer) -> {
      try {
        TransactionContext ctx = CONTEXTS.get(viewer.uniqueId());
        if (ctx == null) {
          logger.logger().warning("Transaction context not found for viewer " + viewer.uniqueId());
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

    PageBounds bounds = readBounds(config);
    PageNavigation nav = buildNavigation(config, previousTemplate, nextTemplate);

    return new PagedAreaDefinition<>(
        AREA_ID,
        bounds,
        source,
        (entry, index) -> () -> MenuItem.of(renderTransactionItem(transactionTemplate, entry, config)),
        nav,
        Optional.empty()
    );
  }

  private static ItemStack renderTransactionItem(ItemStack template, TransactionEntry entry, FileConfiguration config) {
    BankTransactionEntity tx = entry.transaction();
    String typeLabel = getTransactionType(tx);
    String actorName = getActorName(tx);
    String amountFormatted = getAmount(entry);
    String timestamp = getTimestamp(tx);
    String bankType = entry.ownerBank() ? "Owner" : "Member";

    String itemPath = transactionItemPath(tx);
    ItemStack base = transactionTemplateFor(tx, template);
    String rawName = itemPath == null
        ? "<dark_gray>» <yellow><type-label></yellow>"
        : config.getString(itemPath + ".display-name", "<dark_gray>» <yellow><type-label></yellow>");
    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("type-label", typeLabel),
        Placeholder.parsed("actor-name", actorName),
        Placeholder.parsed("amount", amountFormatted),
        Placeholder.parsed("time", timestamp),
        Placeholder.parsed("bank-id", entry.bankId()),
        Placeholder.parsed("owner-name", nameOrUuid(entry.ownerUuid())),
        Placeholder.parsed("owner-uuid", entry.ownerUuid().toString()),
        Placeholder.parsed("bank-type", bankType),
        Placeholder.parsed("currency", entry.currency() == null ? "" : entry.currency().id())
    ));

    return itemService.builder(base)
        .amount(1)
        .name(MiniMessage.miniMessage().deserialize(rawName, resolver))
        .lore(l -> {
          l.tagResolver(resolver);
          l.build();
        })
        .build();
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

    BankProviderService provider = bankProvider;
    if (provider == null) {
      if (logger != null) {
        logger.logger().warning("BankProviderService not available");
      }
      return;
    }

    CompletableFuture<List<TransactionEntry>> load = provider.transactions(
            ctx.bankId(),
            ctx.ownerUuid(),
            viewerUuid,
            100
        ).thenApply(response -> {
          if (response == null || response.isFailure()) return List.of();
          List<BankTransactionEntity> transactions = response.payload();
          if (transactions == null) return List.of();
          return transactions.stream()
              .filter(java.util.Objects::nonNull)
              .map(tx -> new TransactionEntry(tx, ctx.currency(), ctx.bankId(), ctx.ownerUuid(), ctx.ownerBank()))
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

  private static PageControlButton buildSortControlButton(ItemStack template,
                                                          PageSortControl<TransactionEntry> sortControl,
                                                          Map<String, String> sortModes,
                                                          FileConfiguration config) {
    String defaultColor = config.getString("sorting.default-color", "<gray>");
    String activeColor = config.getString("sorting.active-color", "<yellow>");

    return new PageControlButton() {
      @Override public String areaId() { return AREA_ID; }
      @Override public String controlId() { return SORT_ID; }
      @Override public int slot() { return SLOT_SORT; }

      @Override
      public MenuItem render(RenderContext ctx) {
        String mode = ctx.activeModeId().orElse(ctx.control().defaultModeId());
        List<Component> modeComponents = new ArrayList<>();
        for (String modeId : sortControl.modeIds()) {
          boolean active = normalizeMode(modeId).equals(normalizeMode(mode));
          String label = sortModes.getOrDefault(normalizeMode(modeId), sortControl.labelForMode(modeId));
          String colorPrefix = active ? activeColor : defaultColor;
          modeComponents.add(MiniMessage.miniMessage().deserialize(colorPrefix + label));
        }

        String rawName = config.getString("items.sort-button.display-name", "<dark_gray>» <yellow>Transaction Sorting</yellow>");
        TagResolver resolver = TagResolver.resolver(List.of(
            Placeholder.parsed("current-mode", sortControl.labelForMode(mode))
        ));

        ItemStack base = template == null ? new ItemStack(Material.COMPARATOR) : template.clone();
        ItemStack stack = itemService.builder(base)
            .amount(1)
            .name(MiniMessage.miniMessage().deserialize(rawName, resolver))
            .lore(l -> {
              l.tagResolver(resolver);
              l.replaceToken("#modes#", modeComponents);
              l.build();
            })
            .build();

        return MenuItem.of(stack);
      }

      @Override
      public void onClick(ClickContext ctx) {
        triggerGeneralClick(ctx.viewer().uniqueId());
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

  private static PageControlButton buildFilterControlButton(ItemStack template,
                                                            PageFilterControl<TransactionEntry> filterControl,
                                                            Map<String, String> filterModes,
                                                            FileConfiguration config) {
    return new PageControlButton() {
      @Override public String areaId() { return AREA_ID; }
      @Override public String controlId() { return FILTER_ID; }
      @Override public int slot() { return SLOT_FILTER; }

      @Override
      public MenuItem render(RenderContext ctx) {
        String mode = ctx.activeModeId().orElse(ctx.control().defaultModeId());
        List<Component> modeComponents = new ArrayList<>();
        for (String modeId : filterControl.modeIds()) {
          boolean active = normalizeMode(modeId).equals(normalizeMode(mode));
          String label = filterModes.getOrDefault(normalizeMode(modeId), filterControl.labelForMode(modeId));
          String colorPrefix = active ? "<yellow>" : "<gray>";
          modeComponents.add(MiniMessage.miniMessage().deserialize(colorPrefix + label));
        }

        String rawName = config.getString("items.filter-button.display-name", "<dark_gray>» <yellow>Transaction Filter</yellow>");
        TagResolver resolver = TagResolver.resolver(List.of(
            Placeholder.parsed("current-mode", filterControl.labelForMode(mode))
        ));

        ItemStack base = template == null ? new ItemStack(Material.HOPPER) : template.clone();
        ItemStack stack = itemService.builder(base)
            .amount(1)
            .name(MiniMessage.miniMessage().deserialize(rawName, resolver))
            .lore(l -> {
              l.tagResolver(resolver);
              l.replaceToken("#modes#", modeComponents);
              l.build();
            })
            .build();

        return MenuItem.of(stack);
      }

      @Override
      public void onClick(ClickContext ctx) {
        triggerGeneralClick(ctx.viewer().uniqueId());
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

  private static ItemStack buildSortButton() {
    return configuredItem(bankConfig, "items.sort-button", Material.COMPARATOR);
  }

  private static ItemStack buildFilterButton() {
    return configuredItem(bankConfig, "items.filter-button", Material.HOPPER);
  }

  private static ItemStack buildBackButton(FileConfiguration config) {
    ItemStack template = configuredItem(config, "items.back", Material.ARROW);
    String rawName = config.getString("items.back.display-name", "<yellow>Back</yellow>");
    TagResolver resolver = TagResolver.resolver(List.of());

    return itemService.builder(template.clone())
        .amount(1)
        .name(MiniMessage.miniMessage().deserialize(rawName, resolver))
        .lore(l -> {
          l.tagResolver(resolver);
          l.build();
        })
        .build();
  }

  private static void triggerGeneralClick(UUID viewerUuid) {
    if (servicesRef == null || viewerUuid == null) {
      return;
    }

    Player player = Bukkit.getPlayer(viewerUuid);
    if (player == null) {
      return;
    }

    BankClickEffectService effects = servicesRef.getService(BankClickEffectService.class);
    if (effects != null) {
      effects.executeGeneralClick(player);
    }
  }

  private static PageSortControl<TransactionEntry> buildSortControl(FileConfiguration config,
                                                                     Map<String, String> sortModes) {
    String defaultMode = normalizeMode(config.getString("sorting.default-mode", "latest-last"));

    return new PageSortControl<>() {
      @Override public String controlId() { return SORT_ID; }
      @Override public List<String> modeIds() { return new ArrayList<>(sortModes.keySet()); }
      @Override public String defaultModeId() { return defaultMode; }

      @Override
      public String labelForMode(String modeId) {
        return sortModes.getOrDefault(normalizeMode(modeId), formatModeLabel(modeId));
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

  private static PageFilterControl<TransactionEntry> buildFilterControl(FileConfiguration config,
                                                                        Map<String, String> filterModes) {
    String defaultMode = normalizeMode(config.getString("filtering.default-mode", "all"));

    return new PageFilterControl<>() {
      @Override public String controlId() { return FILTER_ID; }
      @Override public List<String> modeIds() { return new ArrayList<>(filterModes.keySet()); }
      @Override public String defaultModeId() { return defaultMode; }

      @Override
      public String labelForMode(String modeId) {
        return filterModes.getOrDefault(normalizeMode(modeId), formatFilterLabel(modeId));
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

  private static ItemStack configuredItem(FileConfiguration cfg, String path, Material fallback) {
    if (cfg == null || configItemService == null) {
      return new ItemStack(fallback);
    }

    ConfigurationSection section = cfg.getSection(path);
    if (section == null) {
      return new ItemStack(fallback);
    }

    return configItemService.convertSectionToItem(section)
        .orElseGet(() -> new ItemStack(fallback));
  }

  private static ItemStack transactionTemplateFor(BankTransactionEntity tx, ItemStack fallback) {
    ItemStack selected = null;
    if (tx != null && tx.getType() == BankTransactionEntity.Type.DEPOSIT) {
      selected = TRANSACTION_DEPOSIT_TEMPLATE;
    } else if (tx != null && tx.getType() == BankTransactionEntity.Type.WITHDRAW) {
      selected = TRANSACTION_WITHDRAW_TEMPLATE;
    }

    if (selected != null) {
      return selected.clone();
    }

    return fallback == null ? new ItemStack(Material.PAPER) : fallback.clone();
  }

  private static String transactionItemPath(BankTransactionEntity tx) {
    if (tx == null || tx.getType() == null) {
      return null;
    }

    return switch (tx.getType()) {
      case DEPOSIT -> "items.deposit";
      case WITHDRAW -> "items.withdraw";
      default -> null;
    };
  }

  private static FileConfiguration loadConfig() {
    return fileReaderService.load(CONFIG_PATH, CONFIG_FILE, true);
  }

  private static PageBounds readBounds(FileConfiguration config) {
    ConfigurationSection bounds = config == null ? null : config.getSection("layout.transactions.bounds");
    int x = bounds != null ? bounds.getInt("x", DEFAULT_ENTRY_X) : DEFAULT_ENTRY_X;
    int y = bounds != null ? bounds.getInt("y", DEFAULT_ENTRY_Y) : DEFAULT_ENTRY_Y;
    int width = bounds != null ? bounds.getInt("width", DEFAULT_ENTRY_WIDTH) : DEFAULT_ENTRY_WIDTH;
    int height = bounds != null ? bounds.getInt("height", DEFAULT_ENTRY_HEIGHT) : DEFAULT_ENTRY_HEIGHT;
    String alignmentRaw = bounds != null ? bounds.getString("alignment", "LEFT") : "LEFT";
    return new PageBounds(x, y, width, height, parseAlignment(alignmentRaw));
  }

  private static PageNavigation buildNavigation(FileConfiguration config, ItemStack previousTemplate, ItemStack nextTemplate) {
    ConfigurationSection navigation = config == null ? null : config.getSection("layout.transactions.navigation");
    int previousSlot = navigation != null ? navigation.getInt("previous-slot", DEFAULT_PREVIOUS_SLOT) : DEFAULT_PREVIOUS_SLOT;
    int nextSlot = navigation != null ? navigation.getInt("next-slot", DEFAULT_NEXT_SLOT) : DEFAULT_NEXT_SLOT;
    boolean showCurrentPageAmount = navigation == null || navigation.getBoolean("show-current-page-amount", true);
    boolean hidePreviousOnFirstPage = navigation == null || navigation.getBoolean("hide-previous-on-first-page", true);
    boolean hideNextOnLastPage = navigation == null || navigation.getBoolean("hide-next-on-last-page", true);

    return PageNavigation.builder()
        .previousSlot(previousSlot)
        .nextSlot(nextSlot)
        .previousItem(previousTemplate == null ? new ItemStack(Material.SPECTRAL_ARROW) : previousTemplate)
        .nextItem(nextTemplate == null ? new ItemStack(Material.SPECTRAL_ARROW) : nextTemplate)
        .showCurrentPageAmount(showCurrentPageAmount)
        .hidePreviousOnFirstPage(hidePreviousOnFirstPage)
        .hideNextOnLastPage(hideNextOnLastPage)
        .build();
  }

  private static Map<String, String> readSortModes(FileConfiguration config) {
    Map<String, String> modes = new LinkedHashMap<>();
    ConfigurationSection section = config == null ? null : config.getSection("sorting.modes");

    if (section != null) {
      for (String key : section.getKeys(false)) {
        String modeId = normalizeMode(key);
        modes.put(modeId, section.getString(key, formatModeLabel(modeId)));
      }
    }

    if (modes.isEmpty()) {
      modes.put("deposit-withdraw", "Deposit → Withdraw");
      modes.put("withdraw-deposit", "Withdraw → Deposit");
      modes.put("latest-last", "Latest → Last");
      modes.put("last-latest", "Last → Latest");
    }

    return modes;
  }

  private static Map<String, String> readFilterModes(FileConfiguration config) {
    Map<String, String> modes = new LinkedHashMap<>();
    ConfigurationSection section = config == null ? null : config.getSection("filtering.modes");

    if (section != null) {
      for (String key : section.getKeys(false)) {
        String modeId = normalizeMode(key);
        modes.put(modeId, section.getString(key, formatFilterLabel(modeId)));
      }
    }

    if (modes.isEmpty()) {
      modes.put("all", "All Transactions");
      modes.put("withdraw", "Withdraw Only");
      modes.put("deposit", "Deposit Only");
    }

    return modes;
  }

  private static String formatFilterLabel(String modeId) {
    return switch (normalizeMode(modeId)) {
      case "withdraw" -> "Withdraw Only";
      case "deposit" -> "Deposit Only";
      default -> "All Transactions";
    };
  }

  private static String formatModeLabel(String modeId) {
    return switch (normalizeMode(modeId)) {
      case "deposit-withdraw" -> "Deposit → Withdraw";
      case "withdraw-deposit" -> "Withdraw → Deposit";
      case "latest-last" -> "Latest → Last";
      case "last-latest" -> "Last → Latest";
      default -> modeId;
    };
  }

  private static String normalizeMode(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static PageAlignment parseAlignment(String raw) {
    if (raw == null || raw.isBlank()) {
      return PageAlignment.LEFT;
    }

    try {
      return PageAlignment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (Exception ignored) {
      return PageAlignment.LEFT;
    }
  }

  private static String toLegacyTitle(String raw) {
    String value = raw == null || raw.isBlank() ? "<yellow>Bank Transactions</yellow>" : raw;
    return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(value));
  }

  private static Duration parseRefreshInterval(String raw) {
    if (raw == null || raw.isBlank()) {
      return Duration.ofSeconds(1);
    }

    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    try {
      if (normalized.endsWith("ms")) {
        return Duration.ofMillis(Long.parseLong(normalized.substring(0, normalized.length() - 2).trim()));
      }

      long value = Long.parseLong(normalized.substring(0, normalized.length() - 1).trim());
      if (normalized.endsWith("s")) {
        return Duration.ofSeconds(value);
      }
      if (normalized.endsWith("m")) {
        return Duration.ofMinutes(value);
      }

      return Duration.ofSeconds(Long.parseLong(normalized));
    } catch (Exception ignored) {
      return Duration.ofSeconds(1);
    }
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

  private record TransactionEntry(BankTransactionEntity transaction,
                                  CurrencyDefinition currency,
                                  String bankId,
                                  UUID ownerUuid,
                                  boolean ownerBank) {}
}
