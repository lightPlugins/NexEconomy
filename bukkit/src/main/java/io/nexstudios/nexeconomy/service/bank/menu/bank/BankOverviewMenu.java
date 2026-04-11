package io.nexstudios.nexeconomy.service.bank.menu.bank;

import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.menuservice.common.api.MenuKey;
import io.nexstudios.menuservice.common.api.MenuService;
import io.nexstudios.menuservice.common.api.ViewerRef;
import io.nexstudios.menuservice.common.api.builder.MenuDefinitionBuilder;
import io.nexstudios.menuservice.common.api.interaction.InteractionPolicies;
import io.nexstudios.menuservice.common.api.item.MenuItem;
import io.nexstudios.menuservice.common.api.item.PlannedMenuItemSupplier;
import io.nexstudios.menuservice.common.api.page.PageAlignment;
import io.nexstudios.menuservice.common.api.page.PageBounds;
import io.nexstudios.menuservice.common.api.page.PageNavigation;
import io.nexstudios.menuservice.common.api.page.PageSource;
import io.nexstudios.menuservice.common.api.page.PagedAreaDefinition;
import io.nexstudios.menuservice.common.api.page.control.PageControlButton;
import io.nexstudios.menuservice.common.api.page.control.PageSortControl;
import io.nexstudios.menuservice.common.api.registry.DuplicateStrategy;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.provider.bank.BankProviderService;
import io.nexstudios.nexeconomy.provider.bank.BankResponse;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.effects.BankClickEffectService;
import io.nexstudios.nexeconomy.service.bank.menu.extra.BankExtraItemSupport;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.bukkit.services.heads.HeadService;
import io.nexstudios.nexlogic.bukkit.services.items.config.ConfigItemService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Dependencies({
    ItemService.class,
    MenuService.class,
    ComponentService.class,
    BankProviderService.class,
    CurrencyRegistryService.class,
    LoggerService.class,
    FileReaderService.class
})
public class BankOverviewMenu {

  public static final MenuKey KEY = MenuKey.of("nexeconomy", "bank-overview");

  private static final String SORT_ID = "sort";
  private static final String AREA_ID = "entries";
  private static final Path CONFIG_PATH = Path.of("inventories/bank-overview.yml");
  private static final String CONFIG_FILE = "inventories/bank-overview.yml";

  private static final int DEFAULT_ROWS = 6;
  private static final int DEFAULT_SORT_SLOT = 8;
  private static final int DEFAULT_BACK_SLOT = 49;

  private static LoggerService logger;
  private static ServiceAccessor accessor;
  private static HeadService headService;
  private static ConfigItemService configItemService;
  private static FileReaderService fileReaderService;
  private static ItemService itemService;
  private static BankProviderService bankProvider;
  private static ItemStack backTemplate;
  private static List<BankExtraItemSupport.ExtraItemBinding> EXTRA_ITEMS = List.of();
  private static int SLOT_BACK = DEFAULT_BACK_SLOT;

  public static void register(@NotNull ServiceAccessor services) {
    MenuService menuService = services.getService(MenuService.class);
    logger = services.getService(LoggerService.class);
    accessor = services;
    fileReaderService = services.getService(FileReaderService.class);
    itemService = services.getService(ItemService.class);
    headService = NexEconomyPlugin.getNexLogicService().getService(HeadService.class);
    configItemService = NexEconomyPlugin.getNexLogicService().getService(ConfigItemService.class);
    bankProvider = services.getService(BankProviderService.class);

    FileConfiguration bankConfig = loadConfig();

    Map<String, String> sortModes = readSortModes(bankConfig);
    PageSortControl<BankEntry> sortControl = buildSortControl(bankConfig, sortModes);
    int sortButtonSlot = bankConfig.getInt("layout.slots.sort-button", DEFAULT_SORT_SLOT);
    SLOT_BACK = bankConfig.getInt("layout.slots.back", DEFAULT_BACK_SLOT);

    ItemStack sortButtonTemplate = configuredItem(bankConfig, "items.sort-button", Material.COMPARATOR);
    ItemStack bankEntryTemplate = configuredItem(bankConfig, "items.bank-entry", Material.PLAYER_HEAD);
    ItemStack previousTemplate = configuredItem(bankConfig, "items.navigation.previous", Material.ARROW);
    ItemStack nextTemplate = configuredItem(bankConfig, "items.navigation.next", Material.ARROW);
    backTemplate = configuredItem(bankConfig, "items.back", Material.ARROW);
    EXTRA_ITEMS = BankExtraItemSupport.loadBindings(bankConfig, configItemService);

    var def = MenuDefinitionBuilder.create()
        .key(KEY)
        .title(toLegacyTitle(bankConfig.getString("menu.title", "<yellow>Bank Overview</yellow>")))
        .rows(bankConfig.getInt("menu.rows", DEFAULT_ROWS))
        .refreshInterval(parseRefreshInterval(bankConfig.getString("menu.refresh-interval", "1")))
        .interactionPolicy(InteractionPolicies.locked())
        .addSortControl(AREA_ID, sortControl)
        .addControlButton(buildSortControlButton(sortButtonTemplate, sortControl, sortButtonSlot, bankConfig))
        .addPagedArea(buildPagedArea(bankConfig, bankEntryTemplate, previousTemplate, nextTemplate))
        .populator(ctx -> {
          ctx.slot(SLOT_BACK).setPlannedItem(() -> MenuItem.of(backTemplate == null ? new ItemStack(Material.ARROW) : backTemplate.clone()));
          ctx.slot(SLOT_BACK).onClick(clickCtx -> {
            clickCtx.cancel();
            triggerGeneralClick(clickCtx.viewer().uniqueId());

            Player player = Bukkit.getPlayer(clickCtx.viewer().uniqueId());
            if (player != null) {
              player.closeInventory();
            }
          });

          BankExtraItemSupport.populate(ctx, accessor, EXTRA_ITEMS, "bank-overview");
        })
        .build();

    menuService.registry().register(def, DuplicateStrategy.REPLACE);
  }

  public static void open(@NotNull ServiceAccessor services, @NotNull ViewerRef viewer) {
    services.getService(MenuService.class).open(viewer, KEY);
  }

  private static PagedAreaDefinition<BankEntry> buildPagedArea(FileConfiguration bankConfig,
                                                               ItemStack bankEntryTemplate,
                                                               ItemStack previousTemplate,
                                                               ItemStack nextTemplate) {
    CurrencyRegistryService currencyRegistry = accessor.getService(CurrencyRegistryService.class);

    PageSource<BankEntry> source = (menuKey, viewer) -> {
      UUID viewerUuid = viewer.uniqueId();
      List<BankEntry> entries = new ArrayList<>();

      try {
        BankProviderService provider = bankProvider;
        if (provider == null) {
          return entries;
        }

        BankResponse<List<BankRepositoryService.BankAccountRef>> bankListResponse = provider.banks(viewerUuid).join();
        List<BankRepositoryService.BankAccountRef> allBanks = bankListResponse == null ? null : bankListResponse.payload();
        if (allBanks == null) {
          return entries;
        }

        for (BankRepositoryService.BankAccountRef ref : allBanks) {
          if (ref == null) {
            continue;
          }

          boolean isOwner = ref.ownerUuid().equals(viewerUuid);
          Category category = isOwner ? Category.OWN_BANK : Category.MEMBER_BANK;

          BankDefinition bankDefinition = null;
          String displayName = ref.bankIdLower();
          try {
            BankResponse<BankDefinition> bankResponse = provider.bank(ref.bankIdLower()).join();
            bankDefinition = bankResponse == null ? null : bankResponse.payload();
            if (bankDefinition != null) {
              displayName = bankDefinition.nameMiniMessage();
            }
          } catch (Exception e) {
            if (logger != null) {
              logger.logger().warning("Failed to load bank definition for: " + ref.bankIdLower());
            }
          }

          String ownerName = Optional.ofNullable(Bukkit.getOfflinePlayer(ref.ownerUuid()).getName())
              .orElse(ref.ownerUuid().toString());
          MantissaAmount balanceAmount = loadVisibleBalance(provider, ref.bankIdLower(), ref.ownerUuid(), viewerUuid);
          String currency = resolveCurrencySymbol(currencyRegistry.currency(bankDefinition == null ? null : bankDefinition.currencyIdLower()), balanceAmount);
          CurrencyDefinition currencyDefinition = bankDefinition == null ? null : currencyRegistry.currency(bankDefinition.currencyIdLower());
          String balanceText = formatBalanceText(balanceAmount, currencyDefinition, currency);

          entries.add(new BankEntry(
              ref.bankIdLower(),
              ref.ownerUuid(),
              category,
              displayName,
              ownerName,
              currency,
              balanceText
          ));
        }
      } catch (Exception e) {
        if (logger != null) {
          logger.logger().warning("Failed to load banks for overview: " + e.getMessage());
        }
      }

      return entries;
    };

    PageBounds bounds = readBounds(bankConfig);
    PageNavigation navigation = buildNavigation(bankConfig, previousTemplate, nextTemplate);

    return new PagedAreaDefinition<>(
        AREA_ID,
        bounds,
        source,
        (entry, index) -> PlannedMenuItemSupplier.withHead(
            MenuItem.of(renderBankPlaceholder(bankConfig, bankEntryTemplate, entry)),
            headService.loadHead(entry.ownerUuid())
        ),
        navigation,
        Optional.of((entry, index, clickCtx) -> {
          clickCtx.cancel();
          triggerGeneralClick(clickCtx.viewer().uniqueId());
          if (accessor != null) {
            BankDetailMenu.open(accessor, clickCtx.viewer(), entry.bankName(), entry.ownerUuid(), entry.category() == Category.OWN_BANK);
          }
        })
    );
  }

  private static void triggerGeneralClick(UUID viewerUuid) {
    if (accessor == null || viewerUuid == null) {
      return;
    }

    Player player = Bukkit.getPlayer(viewerUuid);
    if (player == null) {
      return;
    }

    BankClickEffectService effects = accessor.getService(BankClickEffectService.class);
    if (effects != null) {
      effects.executeGeneralClick(player);
    }
  }

  private static ItemStack renderBankPlaceholder(FileConfiguration bankConfig,
                                                  ItemStack template,
                                                  BankEntry entry) {
    String rawName = bankConfig.getString("items.bank-entry.display-name", entry.displayName());
    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("bank-name", entry.bankName()),
        Placeholder.parsed("display-name", entry.displayName()),
        Placeholder.parsed("owner-name", entry.ownerName()),
        Placeholder.parsed("owner-uuid", entry.ownerUuid().toString()),
        Placeholder.parsed("type-label", entry.category().label()),
        Placeholder.parsed("balance", entry.balanceText()),
        Placeholder.parsed("currency", entry.currency())
    ));

    return itemService.builder(template.clone())
        .name(MiniMessage.miniMessage().deserialize(rawName, resolver))
        .lore(l -> {
          l.tagResolver(resolver);
          l.build();
        })
        .build();
  }

  private static PageControlButton buildSortControlButton(ItemStack template,
                                                          PageSortControl<BankEntry> sortControl,
                                                          int slot,
                                                          FileConfiguration bankConfig) {
    String defaultColor = bankConfig.getString("sorting.default-color", "<gray>");
    String activeColor = bankConfig.getString("sorting.active-color", "<yellow>");

    return new PageControlButton() {
      @Override
      public String areaId() {
        return AREA_ID;
      }

      @Override
      public String controlId() {
        return SORT_ID;
      }

      @Override
      public int slot() {
        return slot;
      }

      @Override
      public MenuItem render(RenderContext ctx) {
        String activeMode = ctx.activeModeId().orElse(sortControl.defaultModeId());
        List<Component> modeComponents = new ArrayList<>();

        for (String modeId : sortControl.modeIds()) {
          boolean active = modeId.equalsIgnoreCase(activeMode);
          String label = sortControl.labelForMode(modeId);
          String colorPrefix = active ? activeColor : defaultColor;
          modeComponents.add(MiniMessage.miniMessage().deserialize(colorPrefix + label));
        }

        ItemStack stack = itemService.builder(template.clone())
            .lore(l ->  {
              l.replaceToken("#modes#", modeComponents);
              l.build();
            })
            .build();

        return MenuItem.of(stack);
      }

      @Override
      public void onClick(ClickContext ctx) {
        if (ctx.action() == io.nexstudios.menuservice.common.api.interaction.ClickAction.RIGHT_CLICK) {
          ctx.stateStore().cycleToPreviousMode(ctx.viewer(), ctx.menuKey(), ctx.areaId(), ctx.control());
        } else if (ctx.action() == io.nexstudios.menuservice.common.api.interaction.ClickAction.LEFT_CLICK) {
          ctx.stateStore().cycleToNextMode(ctx.viewer(), ctx.menuKey(), ctx.areaId(), ctx.control());
        }

        ctx.requestAreaRefresh();
      }
    };
  }

  private static PageSortControl<BankEntry> buildSortControl(FileConfiguration bankConfig,
                                                             Map<String, String> sortModes) {
    String defaultMode = normalizeMode(bankConfig.getString("sorting.default-mode", "all"));

    return new PageSortControl<>() {
      @Override
      public String controlId() {
        return SORT_ID;
      }

      @Override
      public List<String> modeIds() {
        return new ArrayList<>(sortModes.keySet());
      }

      @Override
      public String defaultModeId() {
        return defaultMode;
      }

      @Override
      public String labelForMode(String modeId) {
        return sortModes.getOrDefault(normalizeMode(modeId), formatModeLabel(modeId));
      }

      @Override
      public Comparator<BankEntry> comparatorFor(String modeId, MenuKey menuKey, ViewerRef viewer) {
        return switch (normalizeMode(modeId)) {
          case "owner" -> Comparator
              .comparing((BankEntry entry) -> entry.category() == Category.OWN_BANK ? 0 : 1)
              .thenComparing(BankEntry::displayName, String.CASE_INSENSITIVE_ORDER);
          case "member" -> Comparator
              .comparing((BankEntry entry) -> entry.category() == Category.MEMBER_BANK ? 0 : 1)
              .thenComparing(BankEntry::displayName, String.CASE_INSENSITIVE_ORDER);
          default -> Comparator.comparing(BankEntry::displayName, String.CASE_INSENSITIVE_ORDER);
        };
      }
    };
  }

  private static PageNavigation buildNavigation(FileConfiguration bankConfig,
                                                ItemStack previousTemplate,
                                                ItemStack nextTemplate) {
    ConfigurationSection navigation = bankConfig.getSection("layout.entries.navigation");

    int previousSlot = navigation != null ? navigation.getInt("previous-slot", 45) : 45;
    int nextSlot = navigation != null ? navigation.getInt("next-slot", 53) : 53;
    boolean showCurrentPageAmount = navigation == null || navigation.getBoolean("show-current-page-amount", true);
    boolean hidePreviousOnFirstPage = navigation == null || navigation.getBoolean("hide-previous-on-first-page", true);
    boolean hideNextOnLastPage = navigation == null || navigation.getBoolean("hide-next-on-last-page", true);

    return PageNavigation.builder()
        .previousSlot(previousSlot)
        .nextSlot(nextSlot)
        .previousItem(previousTemplate)
        .nextItem(nextTemplate)
        .showCurrentPageAmount(showCurrentPageAmount)
        .hidePreviousOnFirstPage(hidePreviousOnFirstPage)
        .hideNextOnLastPage(hideNextOnLastPage)
        .build();
  }

  private static PageBounds readBounds(FileConfiguration bankConfig) {
    ConfigurationSection bounds = bankConfig.getSection("layout.entries.bounds");
    int x = bounds != null ? bounds.getInt("x", 1) : 1;
    int y = bounds != null ? bounds.getInt("y", 1) : 1;
    int width = bounds != null ? bounds.getInt("width", 7) : 7;
    int height = bounds != null ? bounds.getInt("height", 4) : 4;
    String alignmentRaw = bounds != null ? bounds.getString("alignment", "LEFT") : "LEFT";
    PageAlignment alignment = parseAlignment(alignmentRaw);
    return new PageBounds(x, y, width, height, alignment);
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
      long parseLong = Long.parseLong(normalized.substring(0, normalized.length() - 1).trim());
      if (normalized.endsWith("s")) {
        return Duration.ofSeconds(parseLong);
      }
      if (normalized.endsWith("m")) {
        return Duration.ofMinutes(parseLong);
      }
      return Duration.ofSeconds(Long.parseLong(normalized));
    } catch (Exception ignored) {
      return Duration.ofSeconds(1);
    }
  }

  private static String formatBalanceText(MantissaAmount balance,
                                         CurrencyDefinition definition,
                                         String currencySymbol) {
    String formatted = io.nexstudios.nexeconomy.definition.AmountNotation.formatShort(balance, definition == null ? 0 : definition.fractionDigits());
    return (currencySymbol == null || currencySymbol.isBlank()) ? formatted : formatted + " " + currencySymbol;
  }

  private static String resolveCurrencySymbol(CurrencyDefinition currency, MantissaAmount balance) {
    if (currency == null) {
      return "";
    }

    return balance != null && balance.toHuman().compareTo(java.math.BigDecimal.ONE) == 0
        ? currency.symbolSingular()
        : currency.symbolPlural();
  }

  private static MantissaAmount loadVisibleBalance(BankProviderService provider, String bankId, UUID ownerUuid, UUID viewerUuid) {
    if (provider == null || bankId == null || ownerUuid == null || viewerUuid == null) {
      return MantissaAmount.zero();
    }

    try {
      BankResponse<MantissaAmount> response = provider.visibleBalance(bankId, ownerUuid, viewerUuid).join();
      MantissaAmount amount = response == null ? null : response.payload();
      return amount == null ? MantissaAmount.zero() : amount;
    } catch (Exception ignored) {
      return MantissaAmount.zero();
    }
  }

  private static FileConfiguration loadConfig() {
    return fileReaderService.load(CONFIG_PATH, CONFIG_FILE, true);
  }

  private static ItemStack configuredItem(FileConfiguration bankConfig, String path, Material fallback) {
    ConfigurationSection section = bankConfig.getSection(path);
    if (section == null) {
      return new ItemStack(fallback);
    }

    return configItemService.convertSectionToItem(section)
        .orElseGet(() -> new ItemStack(fallback));
  }

  private static Map<String, String> readSortModes(FileConfiguration bankConfig) {
    Map<String, String> sortModes = new LinkedHashMap<>();
    String defaultMode = normalizeMode(bankConfig.getString("sorting.default-mode", "all"));
    ConfigurationSection modesSection = bankConfig.getSection("sorting.modes");

    if (modesSection != null) {
      for (String key : modesSection.getKeys(false)) {
        String modeId = normalizeMode(key);
        String label = modesSection.getString(key, formatModeLabel(modeId));
        sortModes.put(modeId, label);
      }
    }

    sortModes.putIfAbsent(defaultMode, formatModeLabel(defaultMode));
    return sortModes;
  }

  private static String formatModeLabel(String modeId) {
    if (modeId == null || modeId.isBlank()) {
      return "All Banks";
    }

    return switch (normalizeMode(modeId)) {
      case "owner" -> "Owner Banks";
      case "member" -> "Member Banks";
      case "all" -> "All Banks";
      default -> modeId;
    };
  }

  private static String normalizeMode(String value) {
    return value == null ? "all" : value.trim().toLowerCase(Locale.ROOT);
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
    String value = raw == null ? "<yellow>Bank Overview</yellow>" : raw;
    return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(value));
  }

  private enum Category {
    OWN_BANK("Owner"),
    MEMBER_BANK("Member");

    private final String label;

    Category(String label) {
      this.label = label;
    }

    String label() {
      return label;
    }
  }

  private record BankEntry(
      String bankName,
      UUID ownerUuid,
      Category category,
      String displayName,
      String ownerName,
      String currency,
      String balanceText
  ) {}
}
