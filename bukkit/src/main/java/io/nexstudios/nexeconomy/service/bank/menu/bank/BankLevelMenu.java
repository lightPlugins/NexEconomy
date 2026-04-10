package io.nexstudios.nexeconomy.service.bank.menu.bank;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.menuservice.common.api.*;
import io.nexstudios.menuservice.common.api.builder.MenuDefinitionBuilder;
import io.nexstudios.menuservice.common.api.interaction.InteractionPolicies;
import io.nexstudios.menuservice.common.api.item.MenuItem;
import io.nexstudios.menuservice.common.api.page.*;
import io.nexstudios.menuservice.common.api.registry.DuplicateStrategy;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.provider.bank.BankProviderService;
import io.nexstudios.nexeconomy.service.bank.effects.BankClickEffectService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.level.BankLevelService;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.bank.sync.BankRedisSyncService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.EconomyService;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.bukkit.services.items.config.ConfigItemService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Dependencies({
    ItemService.class,
    MenuService.class,
    ComponentService.class,
    BankProviderService.class,
    EconomyPlayerCacheService.class,
    CurrencyRegistryService.class,
    FileReaderService.class
})
public final class BankLevelMenu {

  public static final MenuKey KEY = MenuKey.of("nexeconomy", "bank-level");

  private static final String AREA_ID = "levels";
  private static final Path CONFIG_PATH = Path.of("inventories/bank-level.yml");
  private static final String CONFIG_FILE = "inventories/bank-level.yml";

  private static final int DEFAULT_ROWS = 6;
  private static final int DEFAULT_BACK_SLOT = 49;

  private static ServiceAccessor servicesRef;
  private static org.bukkit.plugin.Plugin plugin;
  private static ItemService itemService;
  private static FileReaderService fileReaderService;
  private static ConfigItemService configItemService;
  private static BankProviderService bankProvider;
  private static FileConfiguration bankConfig;
  private static ItemStack unlockedTemplate;
  private static ItemStack previousNeedsUnlockedTemplate;
  private static ItemStack conditionsNotMetTemplate;
  private static ItemStack readyForUnlockTemplate;
  private static ItemStack previousTemplate;
  private static ItemStack nextTemplate;
  private static ItemStack backTemplate;
  private static int SLOT_BACK = DEFAULT_BACK_SLOT;

  private static final Map<UUID, LevelContext> CONTEXTS = new ConcurrentHashMap<>();
  private static final Map<UUID, BankAccountCacheService.View> LAST_VIEWS = new ConcurrentHashMap<>();
  private static final Map<UUID, CompletableFuture<BankAccountCacheService.View>> REFRESH_TASKS = new ConcurrentHashMap<>();

  private BankLevelMenu() {}

  public static void register(@NotNull ServiceAccessor services) {
    MenuService menuService = services.getService(MenuService.class);
    servicesRef = services;
    plugin = services.getService(io.nexstudios.framework.paper.services.plugin.PaperPluginService.class).plugin();
    itemService = services.getService(ItemService.class);
    fileReaderService = services.getService(FileReaderService.class);
    configItemService = NexEconomyPlugin.getNexLogicService().getService(ConfigItemService.class);
    bankProvider = services.getService(BankProviderService.class);

    bankConfig = loadConfig();

    SLOT_BACK = bankConfig.getInt("layout.slots.back", DEFAULT_BACK_SLOT);
    ItemStack fillTemplate = configuredItem(bankConfig, "items.fill", Material.BLACK_STAINED_GLASS_PANE);
    unlockedTemplate = configuredItem(bankConfig, "items.unlocked", Material.LIME_STAINED_GLASS_PANE);
    previousNeedsUnlockedTemplate = configuredItem(bankConfig, "items.previous-needs-to-be-unlocked", Material.RED_STAINED_GLASS_PANE);
    conditionsNotMetTemplate = configuredItem(bankConfig, "items.conditions-not-met", Material.RED_STAINED_GLASS_PANE);
    readyForUnlockTemplate = configuredItem(bankConfig, "items.ready-for-unlock", Material.YELLOW_STAINED_GLASS_PANE);
    previousTemplate = configuredItem(bankConfig, "items.navigation.previous", Material.ARROW);
    nextTemplate = configuredItem(bankConfig, "items.navigation.next", Material.ARROW);
    backTemplate = configuredItem(bankConfig, "items.back", Material.ARROW);

    var def = MenuDefinitionBuilder.create()
        .key(KEY)
        .title(toLegacyTitle(bankConfig.getString("menu.title", "<yellow>Bank Levels</yellow>")))
        .rows(bankConfig.getInt("menu.rows", DEFAULT_ROWS))
        .refreshInterval(parseRefreshInterval(bankConfig.getString("menu.refresh-interval", "1")))
        .interactionPolicy(InteractionPolicies.locked())
        .fillEmptySlotsWith(MenuItem.of(fillTemplate))
        .interactionHooks(new MenuInteractionHooks() {
          @Override
          public void onClose(MenuKey key, ViewerRef viewer, CloseReason reason) {
            CONTEXTS.remove(viewer.uniqueId());
            LAST_VIEWS.remove(viewer.uniqueId());
            REFRESH_TASKS.remove(viewer.uniqueId());
          }
        })
        .addPagedArea(buildPagedArea())
        .populator(BankLevelMenu::populate)
        .build();

    menuService.registry().register(def, DuplicateStrategy.REPLACE);
  }

  public static void open(@NotNull ServiceAccessor services, @NotNull ViewerRef viewer, @NotNull String bankId, @NotNull UUID ownerUuid, boolean ownerBank) {
    CONTEXTS.put(viewer.uniqueId(), new LevelContext(bankId, ownerUuid, ownerBank));
    LAST_VIEWS.remove(viewer.uniqueId());
    REFRESH_TASKS.remove(viewer.uniqueId());
    services.getService(MenuService.class).open(viewer, KEY);
  }

  private static void populate(MenuPopulateContext ctx) {
    LevelContext context = CONTEXTS.get(ctx.viewer().uniqueId());
    if (context == null || Bukkit.getPlayer(ctx.viewer().uniqueId()) == null) {
      setBackButton(ctx, null);
      return;
    }

    setBackButton(ctx, context);
  }

  private static void setBackButton(MenuPopulateContext ctx, LevelContext context) {
    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("bank", context == null ? "Unknown" : context.bankId())
    ));

    setConfiguredButton(ctx, SLOT_BACK, backTemplate, "items.back", "Back", resolver, clickCtx -> {
      clickCtx.cancel();
      triggerGeneralClick(clickCtx.viewer().uniqueId());
      if (servicesRef != null && context != null) {
        BankDetailMenu.open(servicesRef, clickCtx.viewer(), context.bankId(), context.ownerUuid(), context.ownerBank());
      } else if (servicesRef != null) {
        BankOverviewMenu.open(servicesRef, clickCtx.viewer());
      }
    });
  }

  private static PagedAreaDefinition<LevelEntry> buildPagedArea() {
    BankService bankService = servicesRef.getService(BankService.class);
    BankLevelService levelService = servicesRef.getService(BankLevelService.class);
    BankAccountCacheService bankCache = servicesRef.getService(BankAccountCacheService.class);
    BankRegistryService bankRegistry = servicesRef.getService(BankRegistryService.class);
    CurrencyRegistryService currencyRegistry = servicesRef.getService(CurrencyRegistryService.class);
    EconomyPlayerCacheService economyCache = servicesRef.getService(EconomyPlayerCacheService.class);

    PageSource<LevelEntry> source = (menuKey, viewer) -> {
      UUID viewerUuid = viewer.uniqueId();
      LevelContext context = CONTEXTS.get(viewerUuid);
      Player player = Bukkit.getPlayer(viewerUuid);
      if (context == null || player == null) {
        return List.of();
      }

      BankDefinition definition = bankRegistry.bank(context.bankId()).orElse(null);
      if (definition == null) {
        return List.of();
      }

      CurrencyDefinition currency = currencyRegistry.currency(definition.currencyIdLower());
      BankAccountCacheService.View bankView = bankCache == null ? null : bankCache.get(context.bankId(), context.ownerUuid());
      if (bankView != null) {
        LAST_VIEWS.put(viewerUuid, bankView);
      } else {
        bankView = LAST_VIEWS.get(viewerUuid);
        if (bankCache != null) {
          refreshBankViewAsync(player, context.bankId(), context.ownerUuid(), viewerUuid);
        }

        if (bankView == null) {
          return List.of();
        }
      }

      BankDefinition.LevelDefinition[] levels = definition.levels() == null
          ? new BankDefinition.LevelDefinition[0]
          : definition.levels().toArray(new BankDefinition.LevelDefinition[0]);
      if (levels.length == 0) {
        return List.of();
      }

      int currentLevel = safeLevel(bankView.account() == null ? null : bankView.account().getLevel());
      int maxLevel = levelService.getMaxLevel(context.bankId());
      MantissaAmount walletBalance = currentWalletBalance(viewerUuid, currency, economyCache);
      String walletShown = bankService.formatBalanceWithCurrency(walletBalance, currency);
      List<BankMemberEntity> members = bankView.members() == null ? List.of() : bankView.members();
      BankMemberEntity viewerMember = findMember(members, viewerUuid);
      BankDefinition.RoleDefinition role = resolveRole(definition, context, viewerUuid, viewerMember);
      boolean roleCanUpgrade = context.ownerUuid().equals(viewerUuid) || (role != null && role.canUpgrade());
      String roleName = role == null ? "unknown" : role.nameMiniMessage();

      List<LevelEntry> out = new ArrayList<>(levels.length + 1);
      if (findLevel(definition, 1) == null) {
        out.add(buildDefaultLevelOneEntry(context, definition, bankView, currency, roleName, currentLevel, maxLevel, walletBalance, walletShown));
      }

      for (BankDefinition.LevelDefinition levelDef : levels) {
        if (levelDef == null) continue;

        int level = levelDef.level();
        MantissaAmount upgradeCost = levelService.getUpgradeCost(context.bankId(), level);
        MantissaAmount maxBalance = levelService.getMaxBalance(context.bankId(), level);
        String permission = levelDef.permission() == null ? "" : levelDef.permission().trim();
        String interestRate = formatInterestRate(levelDef.interestRateRaw());
        boolean next = level == currentLevel + 1;
        boolean hasPermission = permission.isBlank() || player.hasPermission(permission);
        boolean hasFunds = walletBalance.compareTo(upgradeCost) >= 0;
        boolean canUpgrade = next && level <= maxLevel && roleCanUpgrade && hasPermission && hasFunds;
        LevelState state = resolveState(level, currentLevel, roleCanUpgrade, hasPermission, hasFunds);
        String status = resolveStatus(state);
        String upgradeReason = resolveUpgradeReason(state, currentLevel, roleCanUpgrade, hasPermission, hasFunds, walletBalance, upgradeCost, currency);
        String missingFunds = formatMissingFunds(walletBalance, upgradeCost, currency);

        out.add(new LevelEntry(
            context.bankId(),
            context.ownerUuid(),
            definition.nameMiniMessage(),
            bankView.account() == null ? null : bankView.account().getId(),
            currency,
            roleName,
            level,
            currentLevel,
            maxLevel,
            state,
            canUpgrade,
            upgradeCost,
            maxBalance,
            walletBalance,
            walletShown,
            interestRate,
            permission,
            status,
            upgradeReason,
            missingFunds
        ));
      }

      return out;
    };

    PageBounds bounds = readBounds(bankConfig);
    PageNavigation navigation = buildNavigation(bankConfig, previousTemplate, nextTemplate);

    return new PagedAreaDefinition<>(
        AREA_ID,
        bounds,
        source,
        (entry, index) -> () -> MenuItem.of(renderLevelItem(entry)),
        navigation,
        Optional.of((entry, index, clickCtx) -> {
          clickCtx.cancel();
          triggerGeneralClick(clickCtx.viewer().uniqueId());
          if (!entry.canUpgrade()) {
            return;
          }
          attemptUpgrade(clickCtx.viewer(), entry);
        })
    );
  }

  private static ItemStack renderLevelItem(LevelEntry entry) {
    String path = entry.state().path;
    ItemStack template = switch (entry.state()) {
      case UNLOCKED -> unlockedTemplate;
      case PREVIOUS_NEEDS_TO_BE_UNLOCKED -> previousNeedsUnlockedTemplate;
      case CONDITIONS_NOT_MET -> conditionsNotMetTemplate;
      case READY_FOR_UNLOCK -> readyForUnlockTemplate;
    };

    String fallbackName = switch (entry.state()) {
      case UNLOCKED -> "<dark_gray>» <green>Unlocked <level></green>";
      case PREVIOUS_NEEDS_TO_BE_UNLOCKED -> "<dark_gray>» <red>Previous needs to be unlocked</red>";
      case CONDITIONS_NOT_MET -> "<dark_gray>» <red>Conditions not true</red>";
      case READY_FOR_UNLOCK -> "<dark_gray>» <yellow>Ready for unlock</yellow>";
    };

    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("bank-name", entry.bankName()),
        Placeholder.parsed("bank-id", entry.bankId()),
        Placeholder.parsed("role-name", entry.roleName()),
        Placeholder.parsed("level", String.valueOf(entry.level())),
        Placeholder.parsed("current-level", String.valueOf(entry.currentLevel())),
        Placeholder.parsed("max-level", String.valueOf(entry.maxLevel())),
        Placeholder.parsed("required-level", String.valueOf(entry.currentLevel() + 1)),
        Placeholder.parsed("status", entry.status()),
        Placeholder.parsed("upgrade-reason", entry.upgradeReason()),
        Placeholder.parsed("upgrade-cost", entry.upgradeCostText()),
        Placeholder.parsed("max-balance", entry.maxBalanceText()),
        Placeholder.parsed("wallet-balance", entry.walletBalanceText()),
        Placeholder.parsed("interest-rate", entry.interestRate()),
        Placeholder.parsed("permission", entry.permission().isBlank() ? "None" : entry.permission()),
        Placeholder.parsed("can-upgrade", entry.canUpgrade() ? "Yes" : "No"),
        Placeholder.parsed("missing-funds", entry.missingFundsText())
    ));

    String rawName = bankConfig.getString(path + ".display-name", fallbackName);
    if (rawName == null || rawName.isBlank()) {
      rawName = fallbackName;
    }

    ItemStack base = template == null ? new ItemStack(Material.PAPER) : template.clone();
    return itemService.builder(base)
        .amount(1)
        .name(MiniMessage.miniMessage().deserialize(rawName, resolver))
        .lore(l -> {
          l.tagResolver(resolver);
          l.build();
        })
        .build();
  }

  private static void attemptUpgrade(ViewerRef viewer, LevelEntry entry) {
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    BankProviderService provider = bankProvider;
    if (player == null || provider == null || entry == null) {
      return;
    }

    provider.levelUp(entry.bankId(), entry.ownerUuid(), player.getUniqueId())
        .thenAccept(response -> Bukkit.getScheduler().runTask(plugin, () -> {
          if (response == null || response.isFailure()) {
            triggerUpgradeFailed(player);
            sendMessage(player, "bank.errors.internal", Placeholder.parsed("error", response == null ? "Upgrade failed" : response.message()));
            return;
          }

          triggerUpgradeSuccess(player);
          sendMessage(player, "bank.level.upgraded", TagResolver.resolver(List.of(
              Placeholder.parsed("bank", entry.bankName()),
              Placeholder.parsed("level", String.valueOf(response.payload() == null ? entry.level() : response.payload())),
              Placeholder.parsed("cost", entry.upgradeCostText())
          )));
          refreshBankViewAsync(player, entry.bankId(), entry.ownerUuid(), player.getUniqueId());
          refreshOpenView(player);
        }))
        .exceptionally(ex -> {
          Bukkit.getScheduler().runTask(plugin, () -> {
            triggerUpgradeFailed(player);
            sendMessage(player, "bank.errors.internal", Placeholder.parsed("error", rootMessage(ex)));
          });
          return null;
        });
  }

  private static void triggerGeneralClick(UUID viewerUuid) {
    triggerClick(viewerUuid, ClickEffectType.GENERAL);
  }

  private static void triggerUpgradeSuccess(Player player) {
    triggerClick(player, ClickEffectType.UPGRADE_SUCCESS);
  }

  private static void triggerUpgradeFailed(Player player) {
    triggerClick(player, ClickEffectType.UPGRADE_FAILED);
  }

  private static void triggerClick(UUID viewerUuid, ClickEffectType type) {
    if (viewerUuid == null || type == null) {
      return;
    }

    Player player = Bukkit.getPlayer(viewerUuid);
    if (player != null) {
      triggerClick(player, type);
    }
  }

  private static void triggerClick(Player player, ClickEffectType type) {
    if (servicesRef == null || player == null || type == null) {
      return;
    }

    BankClickEffectService effects = servicesRef.getService(BankClickEffectService.class);
    if (effects == null) {
      return;
    }

    switch (type) {
      case GENERAL -> effects.executeGeneralClick(player);
      case UPGRADE_SUCCESS -> effects.executeBankUpgradeSuccess(player);
      case UPGRADE_FAILED -> effects.executeBankUpgradeFailed(player);
    }
  }

  private static void invalidateBankAccount(UUID bankAccountId) {
    BankAccountCacheService bankCache = servicesRef.getService(BankAccountCacheService.class);
    BankLevelService levelService = servicesRef.getService(BankLevelService.class);
    BankRedisSyncService redisSync = servicesRef.getService(BankRedisSyncService.class);

    if (levelService != null) {
      levelService.invalidate(bankAccountId);
    }
    if (bankCache != null) {
      bankCache.invalidate(bankAccountId);
    }
    if (redisSync != null) {
      redisSync.publishInvalidateAccount(bankAccountId);
    }
  }

  private static void sendMessage(Player player, String key, TagResolver resolver) {
    ComponentService components = servicesRef.getService(ComponentService.class);
    if (components == null || player == null) {
      return;
    }

    player.sendMessage(components.builder(player, key, "NotDefined", true)
        .resolver(resolver)
        .build());
  }

  private static void refreshOpenView(Player player) {
    MenuService menuService = servicesRef.getService(MenuService.class);
    if (menuService == null || player == null) {
      return;
    }

    menuService.findOpenView(ViewerRef.of(player.getUniqueId(), player.getName()))
        .ifPresent(MenuView::requestRefresh);
  }

  private static void refreshBankViewAsync(Player player, String bankId, UUID ownerUuid, UUID viewerUuid) {
    if (player == null || bankId == null || ownerUuid == null || viewerUuid == null) {
      return;
    }

    BankAccountCacheService bankCache = servicesRef.getService(BankAccountCacheService.class);
    if (bankCache == null) {
      return;
    }

    REFRESH_TASKS.computeIfAbsent(viewerUuid, ignored ->
        bankCache.loadOrCreate(bankId, ownerUuid).thenApply(view -> {
          if (view != null) {
            LAST_VIEWS.put(viewerUuid, view);
          }
          return view;
        }).whenComplete((view, error) -> {
          REFRESH_TASKS.remove(viewerUuid);
          if (error == null) {
            scheduleRefresh(player);
          }
        })
    );
  }

  private static void scheduleRefresh(Player player) {
    if (player == null || plugin == null) {
      return;
    }

    Bukkit.getScheduler().runTask(plugin, () -> {
      if (player.isOnline()) {
        refreshOpenView(player);
      }
    });
  }

  private static void setConfiguredButton(MenuPopulateContext ctx,
                                          int slot,
                                          ItemStack template,
                                          String path,
                                          String fallbackName,
                                          TagResolver resolver,
                                          MenuSlot.MenuClickHandler clickHandler) {
    ItemStack stack = renderConfiguredItem(template, path, fallbackName, resolver);

    MenuSlot menuSlot = ctx.slot(slot);
    menuSlot.setPlannedItem(() -> MenuItem.of(stack));
    if (clickHandler != null) {
      menuSlot.onClick(clickHandler);
    }
  }

  private static FileConfiguration loadConfig() {
    return fileReaderService.load(CONFIG_PATH, CONFIG_FILE, true);
  }

  private static ItemStack configuredItem(FileConfiguration cfg, String path, Material fallback) {
    ConfigurationSection section = cfg.getSection(path);
    if (section == null) {
      return new ItemStack(fallback);
    }

    return configItemService.convertSectionToItem(section)
        .orElseGet(() -> new ItemStack(fallback));
  }

  private static ItemStack renderConfiguredItem(ItemStack template, String path, String fallbackName, TagResolver resolver) {
    String rawName = bankConfig == null ? fallbackName : bankConfig.getString(path + ".display-name", fallbackName);
    if (rawName == null || rawName.isBlank()) {
      rawName = fallbackName;
    }

    return itemService.builder(template.clone())
        .name(MiniMessage.miniMessage().deserialize(rawName, resolver))
        .lore(l -> {
          l.tagResolver(resolver);
          l.build();
        })
        .build();
  }

  private static PageBounds readBounds(FileConfiguration cfg) {
    ConfigurationSection bounds = cfg.getSection("layout.levels.bounds");
    int x = bounds != null ? bounds.getInt("x", 1) : 1;
    int y = bounds != null ? bounds.getInt("y", 2) : 2;
    int width = bounds != null ? bounds.getInt("width", 7) : 7;
    int height = bounds != null ? bounds.getInt("height", 1) : 1;
    String alignmentRaw = bounds != null ? bounds.getString("alignment", "CENTER") : "CENTER";
    return new PageBounds(x, y, width, height, parseAlignment(alignmentRaw));
  }

  private static PageNavigation buildNavigation(FileConfiguration cfg, ItemStack previous, ItemStack next) {
    ConfigurationSection nav = cfg.getSection("layout.levels.navigation");
    int previousSlot = nav != null ? nav.getInt("previous-slot", 45) : 45;
    int nextSlot = nav != null ? nav.getInt("next-slot", 53) : 53;
    boolean showCurrentPageAmount = nav == null || nav.getBoolean("show-current-page-amount", true);
    boolean hidePreviousOnFirstPage = nav == null || nav.getBoolean("hide-previous-on-first-page", true);
    boolean hideNextOnLastPage = nav == null || nav.getBoolean("hide-next-on-last-page", true);

    return PageNavigation.builder()
        .previousSlot(previousSlot)
        .nextSlot(nextSlot)
        .previousItem(previous)
        .nextItem(next)
        .showCurrentPageAmount(showCurrentPageAmount)
        .hidePreviousOnFirstPage(hidePreviousOnFirstPage)
        .hideNextOnLastPage(hideNextOnLastPage)
        .build();
  }

  private static PageAlignment parseAlignment(String raw) {
    if (raw == null) {
      return PageAlignment.CENTER;
    }

    return switch (raw.trim().toUpperCase(Locale.ROOT)) {
      case "LEFT" -> PageAlignment.LEFT;
      case "RIGHT" -> PageAlignment.RIGHT;
      default -> PageAlignment.CENTER;
    };
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

      long duration = Long.parseLong(normalized.substring(0, normalized.length() - 1).trim());
      if (normalized.endsWith("s")) {
        return Duration.ofSeconds(duration);
      }

      if (normalized.endsWith("m")) {
        return Duration.ofMinutes(duration);
      }

      return Duration.ofSeconds(Long.parseLong(normalized));
    } catch (Exception ignored) {
      return Duration.ofSeconds(1);
    }
  }

  private static MantissaAmount currentWalletBalance(UUID playerUuid, CurrencyDefinition currency, EconomyPlayerCacheService economyCache) {
    if (economyCache == null || playerUuid == null || currency == null) {
      return MantissaAmount.zero();
    }

    EconomyPlayer player = economyCache.getOnline(playerUuid);
    if (player == null) {
      return MantissaAmount.zero();
    }

    EconomyPlayer.BalanceEntry entry = player.entry(currency.id());
    return entry == null || entry.amount() == null ? MantissaAmount.zero() : entry.amount();
  }

  private static String formatInterestRate(String raw) {
    BigDecimal rate = parseRate(raw);
    if (rate.compareTo(BigDecimal.ZERO) <= 0) {
      return "—";
    }

    BigDecimal percent = rate.multiply(new BigDecimal("100")).stripTrailingZeros();
    return percent.toPlainString() + "%";
  }

  private static BigDecimal parseRate(String raw) {
    if (raw == null || raw.isBlank()) {
      return BigDecimal.ZERO;
    }

    try {
      String cleaned = raw.trim().replace("%", "");
      BigDecimal value = new BigDecimal(cleaned);
      return value.compareTo(BigDecimal.ONE) > 0 ? value.divide(new BigDecimal("100"), 8, java.math.RoundingMode.HALF_UP) : value;
    } catch (Exception ignored) {
      return BigDecimal.ZERO;
    }
  }

  private static BankDefinition.LevelDefinition findLevel(BankDefinition definition, int level) {
    if (definition == null || definition.levels() == null) {
      return null;
    }

    for (BankDefinition.LevelDefinition levelDef : definition.levels()) {
      if (levelDef != null && levelDef.level() == level) {
        return levelDef;
      }
    }
    return null;
  }

  private static int safeLevel(Integer level) {
    if (level == null || level <= 0) {
      return 1;
    }
    return level;
  }

  private static LevelState resolveState(int level,
                                         int currentLevel,
                                         boolean roleCanUpgrade,
                                         boolean hasPermission,
                                         boolean hasFunds) {
    if (level <= currentLevel) {
      return LevelState.UNLOCKED;
    }
    if (level > currentLevel + 1) {
      return LevelState.PREVIOUS_NEEDS_TO_BE_UNLOCKED;
    }
    if (roleCanUpgrade && hasPermission && hasFunds) {
      return LevelState.READY_FOR_UNLOCK;
    }
    return LevelState.CONDITIONS_NOT_MET;
  }

  private static String resolveStatus(LevelState state) {
    return switch (state) {
      case UNLOCKED -> "Unlocked";
      case PREVIOUS_NEEDS_TO_BE_UNLOCKED -> "Previous needs to be unlocked";
      case CONDITIONS_NOT_MET -> "Conditions not true";
      case READY_FOR_UNLOCK -> "Ready for unlock";
    };
  }

  private static String resolveUpgradeReason(LevelState state,
                                             int currentLevel,
                                             boolean roleCanUpgrade,
                                             boolean hasPermission,
                                             boolean hasFunds,
                                             MantissaAmount walletBalance,
                                             MantissaAmount upgradeCost,
                                             CurrencyDefinition currency) {
    return switch (state) {
      case UNLOCKED -> "Already unlocked";
      case PREVIOUS_NEEDS_TO_BE_UNLOCKED -> "Unlock level " + (currentLevel + 1) + " first";
      case READY_FOR_UNLOCK -> "Click to upgrade";
      case CONDITIONS_NOT_MET -> {
        if (!roleCanUpgrade) {
          yield "Your role cannot upgrade";
        }
        if (!hasPermission) {
          yield "Missing permission";
        }
        if (!hasFunds) {
          yield "Need " + formatMissingFunds(walletBalance, upgradeCost, currency) + " more";
        }
        yield "Conditions not met";
      }
    };
  }

  private static String formatMissingFunds(MantissaAmount walletBalance, MantissaAmount upgradeCost, CurrencyDefinition currency) {
    if (walletBalance == null || upgradeCost == null || currency == null) {
      return "—";
    }

    if (walletBalance.compareTo(upgradeCost) >= 0) {
      return "—";
    }

    MantissaAmount missing = upgradeCost.subtract(walletBalance);
    return bankService().formatBalanceWithCurrency(missing, currency);
  }

  private static LevelEntry buildDefaultLevelOneEntry(LevelContext context,
                                                      BankDefinition definition,
                                                      BankAccountCacheService.View bankView,
                                                      CurrencyDefinition currency,
                                                      String roleName,
                                                      int currentLevel,
                                                      int maxLevel,
                                                      MantissaAmount walletBalance,
                                                      String walletShown) {
    String status = "Unlocked";
    String reason = "Already unlocked";
    String missingFunds = "-";

    return new LevelEntry(
        context.bankId(),
        context.ownerUuid(),
        definition.nameMiniMessage(),
        bankView.account() == null ? null : bankView.account().getId(),
        currency,
        roleName,
        1,
        currentLevel,
        maxLevel,
        LevelState.UNLOCKED,
        false,
        MantissaAmount.zero(),
        resolveLevelOneMaxBalance(definition),
        walletBalance,
        walletShown,
        resolveLevelOneInterestRate(definition),
        "",
        status,
        reason,
        missingFunds
    );
  }

  private static String resolveLevelOneInterestRate(BankDefinition definition) {
    if (definition == null || definition.interestSystem() == null || !definition.interestSystem().enabled()) {
      return "—";
    }

    double percentage = definition.interestSystem().percentage();
    if (percentage <= 0.0d) {
      return "—";
    }

    return formatInterestRate(String.valueOf(percentage));
  }

  private static MantissaAmount resolveLevelOneMaxBalance(BankDefinition definition) {
    if (definition == null) {
      return MantissaAmount.zero();
    }

    String raw = definition.defaultMaxBalanceRaw();
    if (raw == null || raw.isBlank()) {
      return MantissaAmount.zero();
    }

    MantissaAmount virtual = AmountNotation.parseVirtualMantissaAmount(raw);
    if (virtual != null) {
      return virtual;
    }

    BigDecimal vaultAmount = AmountNotation.parseVaultHuman(raw);
    if (vaultAmount != null) {
      return MantissaAmount.of(vaultAmount, 0);
    }

    return MantissaAmount.zero();
  }

  private static BankDefinition.RoleDefinition resolveRole(BankDefinition def,
                                                           LevelContext context,
                                                           UUID viewerUuid,
                                                           BankMemberEntity member) {
    BankDefinition.MemberSystem memberSystem = def.memberSystem();
    Map<String, BankDefinition.RoleDefinition> roles = memberSystem == null || memberSystem.rolesByIdLower() == null
        ? Map.of()
        : memberSystem.rolesByIdLower();

    if (viewerUuid != null && viewerUuid.equals(context.ownerUuid())) {
      BankDefinition.RoleDefinition ownerRole = roles.get("owner");
      if (ownerRole != null) {
        return ownerRole;
      }
    }

    if (member != null) {
      String roleId = member.getRoleIdLower() == null ? "" : member.getRoleIdLower().trim().toLowerCase(Locale.ROOT);
      BankDefinition.RoleDefinition role = roles.get(roleId);
      if (role != null) {
        return role;
      }
    }

    return roles.get("member");
  }

  private static BankMemberEntity findMember(List<BankMemberEntity> members, UUID viewerUuid) {
    if (members == null || viewerUuid == null) {
      return null;
    }

    for (BankMemberEntity member : members) {
      if (member == null) continue;
      if (viewerUuid.equals(member.getMemberUuid())) {
        return member;
      }
    }

    return null;
  }

  private static String rootMessage(Throwable ex) {
    Throwable root = ex;
    for (int i = 0; i < 6 && root != null && root.getCause() != null; i++) {
      root = root.getCause();
    }
    return root == null || root.getMessage() == null ? "unknown error" : root.getMessage();
  }

  private static String toLegacyTitle(String raw) {
    String value = raw == null ? "<yellow>Bank Levels</yellow>" : raw;
    return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
        .serialize(MiniMessage.miniMessage().deserialize(value));
  }

  private enum LevelState {
    UNLOCKED("items.unlocked"),
    PREVIOUS_NEEDS_TO_BE_UNLOCKED("items.previous-needs-to-be-unlocked"),
    CONDITIONS_NOT_MET("items.conditions-not-met"),
    READY_FOR_UNLOCK("items.ready-for-unlock");

    private final String path;

    LevelState(String path) {
      this.path = path;
    }
  }

  private enum ClickEffectType {
    GENERAL,
    UPGRADE_SUCCESS,
    UPGRADE_FAILED
  }

  private record LevelContext(String bankId, UUID ownerUuid, boolean ownerBank) {}

  private record LevelEntry(
      String bankId,
      UUID ownerUuid,
      String bankName,
      UUID bankAccountId,
      CurrencyDefinition currency,
      String roleName,
      int level,
      int currentLevel,
      int maxLevel,
      LevelState state,
      boolean canUpgrade,
      MantissaAmount upgradeCost,
      MantissaAmount maxBalance,
      MantissaAmount walletBalance,
      String walletBalanceText,
      String interestRate,
      String permission,
      String status,
      String upgradeReason,
      String missingFundsText
  ) {
    String upgradeCostText() {
      return bankService().formatBalanceWithCurrency(upgradeCost, currency);
    }

    String maxBalanceText() {
      return bankService().formatBalanceWithCurrency(maxBalance, currency);
    }
  }

   private static BankService bankService() {
     return servicesRef.getService(BankService.class);
   }
}





