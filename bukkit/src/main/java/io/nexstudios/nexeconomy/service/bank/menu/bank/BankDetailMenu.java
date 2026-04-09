package io.nexstudios.nexeconomy.service.bank.menu.bank;

import io.nexstudios.dialogservice.api.ConfirmDialog;
import io.nexstudios.dialogservice.api.TextRequestDialog;
import io.nexstudios.dialogservice.service.ConfirmDialogService;
import io.nexstudios.dialogservice.service.TextRequestDialogService;
import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.menuservice.common.api.*;
import io.nexstudios.menuservice.common.api.builder.MenuDefinitionBuilder;
import io.nexstudios.menuservice.common.api.interaction.InteractionPolicies;
import io.nexstudios.menuservice.common.api.item.MenuItem;
import io.nexstudios.menuservice.common.api.registry.DuplicateStrategy;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.effects.BankClickEffectService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.level.BankLevelService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankWithdrawUsageEntity;
import io.nexstudios.nexlogic.bukkit.services.items.config.ConfigItemService;
import io.nexstudios.nexlogic.bukkit.services.heads.HeadService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
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

import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Dependencies({
    ItemService.class,
    MenuService.class,
    ComponentService.class,
    BankService.class,
    BankRegistryService.class,
    BankAccountCacheService.class,
    EconomyPlayerCacheService.class,
    BankLevelService.class,
    CurrencyRegistryService.class,
    FileReaderService.class,
    TextRequestDialogService.class,
    ConfirmDialogService.class
})
public final class BankDetailMenu {

  public static final MenuKey KEY = MenuKey.of("nexeconomy", "bank-detail");

  private static final Path CONFIG_PATH = Path.of("inventories/bank-detail.yml");
  private static final String CONFIG_FILE = "inventories/bank-detail.yml";

  private static final int DEFAULT_ROWS = 6;
  private static final int DEFAULT_INFO_SLOT = 4;
  private static final int DEFAULT_DEPOSIT_SLOT = 20;
  private static final int DEFAULT_DEPOSIT_ALL_SLOT = 21;
  private static final int DEFAULT_TRANSACTIONS_SLOT = 22;
  private static final int DEFAULT_LEVEL_SLOT = 31;
  private static final int DEFAULT_WITHDRAW_SLOT = 23;
  private static final int DEFAULT_WITHDRAW_ALL_SLOT = 24;
  private static final int DEFAULT_BACK_SLOT = 49;

  private static int SLOT_INFO = DEFAULT_INFO_SLOT;
  private static int SLOT_DEPOSIT = DEFAULT_DEPOSIT_SLOT;
  private static int SLOT_DEPOSIT_ALL = DEFAULT_DEPOSIT_ALL_SLOT;
  private static int SLOT_TRANSACTIONS = DEFAULT_TRANSACTIONS_SLOT;
  private static int SLOT_LEVEL = DEFAULT_LEVEL_SLOT;
  private static int SLOT_WITHDRAW = DEFAULT_WITHDRAW_SLOT;
  private static int SLOT_WITHDRAW_ALL = DEFAULT_WITHDRAW_ALL_SLOT;
  private static int SLOT_BACK = DEFAULT_BACK_SLOT;

  private static final Map<UUID, BankContext> CONTEXTS = new ConcurrentHashMap<>();
  private static final Map<UUID, BankData> LAST_DATA = new ConcurrentHashMap<>();
  private static ServiceAccessor servicesRef;
  private static org.bukkit.plugin.Plugin plugin;
  private static HeadService headService;
  private static LoggerService logger;
  private static ComponentService componentService;
  private static ItemService itemService;
  private static FileReaderService fileReaderService;
  private static ConfigItemService configItemService;
  private static FileConfiguration bankConfig;
  private static ItemStack infoTemplate;
  private static ItemStack depositTemplate;
  private static ItemStack depositDisabledTemplate;
  private static ItemStack depositAllTemplate;
  private static ItemStack depositAllDisabledTemplate;
  private static ItemStack transactionsTemplate;
  private static ItemStack levelTemplate;
  private static ItemStack withdrawTemplate;
  private static ItemStack withdrawDisabledTemplate;
  private static ItemStack withdrawAllTemplate;
  private static ItemStack withdrawAllDisabledTemplate;
  private static ItemStack backTemplate;

  private BankDetailMenu() {}

  public static void register(@NotNull ServiceAccessor services) {
    servicesRef = services;
    logger = services.getService(LoggerService.class);
    headService = NexEconomyPlugin.getNexLogicService().getService(HeadService.class);
    plugin = services.getService(PaperPluginService.class).plugin();
    componentService = services.getService(ComponentService.class);
    itemService = services.getService(ItemService.class);
    fileReaderService = services.getService(FileReaderService.class);
    configItemService = NexEconomyPlugin.getNexLogicService().getService(ConfigItemService.class);

    bankConfig = loadConfig();

    SLOT_INFO = bankConfig.getInt("layout.slots.info", DEFAULT_INFO_SLOT);
    SLOT_DEPOSIT = bankConfig.getInt("layout.slots.deposit", DEFAULT_DEPOSIT_SLOT);
    SLOT_DEPOSIT_ALL = bankConfig.getInt("layout.slots.deposit-all", DEFAULT_DEPOSIT_ALL_SLOT);
    SLOT_TRANSACTIONS = bankConfig.getInt("layout.slots.transactions", DEFAULT_TRANSACTIONS_SLOT);
    SLOT_LEVEL = bankConfig.getInt("layout.slots.level", DEFAULT_LEVEL_SLOT);
    SLOT_WITHDRAW = bankConfig.getInt("layout.slots.withdraw", DEFAULT_WITHDRAW_SLOT);
    SLOT_WITHDRAW_ALL = bankConfig.getInt("layout.slots.withdraw-all", DEFAULT_WITHDRAW_ALL_SLOT);
    SLOT_BACK = bankConfig.getInt("layout.slots.back", DEFAULT_BACK_SLOT);

    infoTemplate = configuredItem(bankConfig, "items.info", Material.PLAYER_HEAD);
    depositTemplate = configuredItem(bankConfig, "items.deposit", Material.IRON_INGOT);
    depositDisabledTemplate = configuredItem(bankConfig, "items.deposit-disabled", Material.BARRIER);
    depositAllTemplate = configuredItem(bankConfig, "items.deposit-all", Material.IRON_BLOCK);
    depositAllDisabledTemplate = configuredItem(bankConfig, "items.deposit-all-disabled", Material.BARRIER);
    transactionsTemplate = configuredItem(bankConfig, "items.transactions", Material.BOOK);
    levelTemplate = configuredItem(bankConfig, "items.level", Material.EXPERIENCE_BOTTLE);
    withdrawTemplate = configuredItem(bankConfig, "items.withdraw", Material.GOLD_INGOT);
    withdrawDisabledTemplate = configuredItem(bankConfig, "items.withdraw-disabled", Material.BARRIER);
    withdrawAllTemplate = configuredItem(bankConfig, "items.withdraw-all", Material.GOLD_BLOCK);
    withdrawAllDisabledTemplate = configuredItem(bankConfig, "items.withdraw-all-disabled", Material.BARRIER);
    backTemplate = configuredItem(bankConfig, "items.back", Material.ARROW);

    MenuService menuService = services.getService(MenuService.class);

    var def = MenuDefinitionBuilder.create()
        .key(KEY)
        .title(toLegacyTitle(bankConfig.getString("menu.title", "<yellow>Bank Details</yellow>")))
        .rows(bankConfig.getInt("menu.rows", DEFAULT_ROWS))
        .refreshInterval(parseRefreshInterval(bankConfig.getString("menu.refresh-interval", "1")))
        .interactionPolicy(InteractionPolicies.locked())
        .fillEmptySlotsWith(MenuItem.of(configuredItem(bankConfig, "items.fill", Material.BLACK_STAINED_GLASS_PANE)))
        .interactionHooks(new MenuInteractionHooks() {
          @Override
          public void onClose(MenuKey key, ViewerRef viewer, CloseReason reason) {
            CONTEXTS.remove(viewer.uniqueId());
            LAST_DATA.remove(viewer.uniqueId());
          }
        })
        .populator(BankDetailMenu::populate)
        .build();

    menuService.registry().register(def, DuplicateStrategy.REPLACE);
  }

  public static void open(@NotNull ServiceAccessor services, @NotNull ViewerRef viewer, @NotNull String bankId, @NotNull UUID ownerUuid, boolean ownerBank) {
    CONTEXTS.put(viewer.uniqueId(), new BankContext(bankId, ownerUuid, ownerBank));
    services.getService(MenuService.class).open(viewer, KEY);
  }

  private static void populate(MenuPopulateContext ctx) {
    ViewerRef viewer = ctx.viewer();
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    BankContext context = CONTEXTS.get(viewer.uniqueId());

    if (player == null || context == null) {
      setFallbackButton(ctx, SLOT_INFO, Material.BARRIER, "Bank not available", "Open this menu from the bank overview.", null);
      setFallbackButton(ctx, SLOT_BACK, Material.ARROW, "Back", "Return to overview", clickCtx -> {
        clickCtx.cancel();
        triggerGeneralClick(clickCtx.viewer().uniqueId());
        if (servicesRef != null) {
          BankOverviewMenu.open(servicesRef, clickCtx.viewer());
        }
      });
      return;
    }

    BankData data = loadBankData(player, context);
    if (data == null) {
      setFallbackButton(ctx, SLOT_INFO, Material.BARRIER, "Bank not available", "The bank data could not be loaded.", null);
      setFallbackButton(ctx, SLOT_BACK, Material.ARROW, "Back", "Return to overview", clickCtx -> {
        clickCtx.cancel();
        triggerGeneralClick(clickCtx.viewer().uniqueId());
        if (servicesRef != null) {
          BankOverviewMenu.open(servicesRef, clickCtx.viewer());
        }
      });
      return;
    }

    setInfoPanel(ctx, data);
    setDepositButtons(ctx, data);
    setTransactionButton(ctx, data);
    setLevelButton(ctx, data);
    setWithdrawButtons(ctx, data);
    setConfiguredButton(ctx, SLOT_BACK, backTemplate, "items.back", "Back", detailResolver(data), clickCtx -> {
      clickCtx.cancel();
      triggerGeneralClick(clickCtx.viewer().uniqueId());
      if (servicesRef != null) {
        BankOverviewMenu.open(servicesRef, clickCtx.viewer());
      }
    });
  }

  private static BankData loadBankData(Player player, BankContext context) {
    BankRegistryService bankRegistry = servicesRef.getService(BankRegistryService.class);
    BankAccountCacheService bankCache = servicesRef.getService(BankAccountCacheService.class);
    CurrencyRegistryService currencies = servicesRef.getService(CurrencyRegistryService.class);
    BankLevelService levelService = servicesRef.getService(BankLevelService.class);

    BankDefinition definition = bankRegistry.bank(context.bankId()).orElse(null);
    if (definition == null) return null;

    CurrencyDefinition currency = currencies.currency(definition.currencyIdLower());
    if (currency == null) return null;

    BankAccountCacheService.View bankView = bankCache.get(context.bankId(), context.ownerUuid());
    if (bankView == null) {
      bankCache.loadOrCreate(context.bankId(), context.ownerUuid()).whenComplete((view, error) -> scheduleRefresh(player));

      BankData cached = LAST_DATA.get(player.getUniqueId());
      if (cached != null && cached.context() != null
          && cached.context().bankId().equalsIgnoreCase(context.bankId())
          && cached.context().ownerUuid().equals(context.ownerUuid())
          && cached.context().ownerBank() == context.ownerBank()) {
        return cached;
      }

      return null;
    }

    UUID viewerUuid = player.getUniqueId();

    MantissaAmount bankBalance = bankView.balance() == null ? MantissaAmount.zero() : bankView.balance();
    MantissaAmount walletBalance = currentWalletBalance(player.getUniqueId(), currency);

    List<BankMemberEntity> members = bankView.members() == null ? List.of() : bankView.members();

    BankMemberEntity viewerMember = findMember(members, viewerUuid);
    BankDefinition.RoleDefinition role = resolveRole(definition, context, viewerUuid, viewerMember);

    boolean canDeposit = role != null && role.canDeposit();
    boolean canWithdraw = role != null && role.withdraw() != null && role.withdraw().canWithdraw();

    int level = bankView.account() == null || bankView.account().getLevel() <= 0
        ? 1
        : bankView.account().getLevel();
    int maxLevel = levelService.getMaxLevel(context.bankId());
    MantissaAmount maxBalance = levelService.getMaxBalance(context.bankId(), level);

    MantissaAmount remainingCapacity = maxBalance.subtract(bankBalance);
    if (remainingCapacity.compareTo(MantissaAmount.zero()) < 0) remainingCapacity = MantissaAmount.zero();

    boolean bankFull = maxBalance.compareTo(MantissaAmount.zero()) > 0
        && bankBalance.compareTo(maxBalance) >= 0;

    boolean depositEnabled = canDeposit && walletBalance.compareTo(MantissaAmount.zero()) > 0 && !bankFull && remainingCapacity.compareTo(MantissaAmount.zero()) > 0;
    String depositDisabledReason = !canDeposit
        ? "Your role cannot deposit."
        : walletBalance.compareTo(MantissaAmount.zero()) <= 0
            ? "Your wallet is empty."
            : bankFull
                ? "This bank is full."
                : "No deposit capacity left.";

    boolean withdrawEnabled = canWithdraw && bankBalance.compareTo(MantissaAmount.zero()) > 0;
    String withdrawDisabledReason = !canWithdraw
        ? "Your role cannot withdraw."
        : bankBalance.compareTo(MantissaAmount.zero()) <= 0
            ? "The bank is empty."
            : "Withdraw is unavailable.";

    String hourlyLimitText = formatLimitDisplay(currency, role == null || role.withdraw() == null ? null : role.withdraw().hourlyLimitRaw());
    String dailyLimitText = formatLimitDisplay(currency, role == null || role.withdraw() == null ? null : role.withdraw().dailyLimitRaw());
    String hourlyUsedText = "…";
    String dailyUsedText = "…";

    BankData data = new BankData(
        context,
        definition,
        currency,
        role,
        level,
        maxLevel,
        bankBalance,
        walletBalance,
        maxBalance,
        remainingCapacity,
        bankFull,
        depositEnabled,
        depositDisabledReason,
        withdrawEnabled,
        withdrawDisabledReason,
        hourlyLimitText,
        hourlyUsedText,
        dailyLimitText,
        dailyUsedText,
        0L
    );

    BankData cached = LAST_DATA.get(player.getUniqueId());
    if (cached != null
        && cached.context() != null
        && cached.context().bankId().equalsIgnoreCase(context.bankId())
        && cached.context().ownerUuid().equals(context.ownerUuid())
        && cached.context().ownerBank() == context.ownerBank()) {
      data = data.withLimitTexts(
          cached.hourlyLimitText() == null ? data.hourlyLimitText() : cached.hourlyLimitText(),
          cached.hourlyUsedText() == null ? data.hourlyUsedText() : cached.hourlyUsedText(),
          cached.dailyLimitText() == null ? data.dailyLimitText() : cached.dailyLimitText(),
          cached.dailyUsedText() == null ? data.dailyUsedText() : cached.dailyUsedText(),
          cached.usageRefreshedAtMs()
      );
    }

    LAST_DATA.put(player.getUniqueId(), data);

    if (bankView.account() != null && role != null && role.withdraw() != null) {
      requestWithdrawUsageRefresh(player, context, definition, currency, bankView.account().getId(), role, data, bankCache);
    }

    return data;
  }

  private static void setInfoPanel(MenuPopulateContext ctx, BankData data) {
    TagResolver resolver = detailResolver(data);
    ItemStack stack = renderConfiguredItem(infoTemplate, "items.info", data.definition().nameMiniMessage(), resolver);
    ctx.slot(SLOT_INFO).setPlannedHead(MenuItem.of(stack), headService.loadHead(data.context().ownerUuid()));
  }

  private static void setDepositButtons(MenuPopulateContext ctx, BankData data) {
    TagResolver resolver = detailResolver(data);
    if (!data.depositEnabled()) {
      setConfiguredButton(ctx, SLOT_DEPOSIT, depositDisabledTemplate, "items.deposit-disabled", "Deposit", resolver, clickCtx -> {
        clickCtx.cancel();
        triggerGeneralClick(clickCtx.viewer().uniqueId());
      });
      setConfiguredButton(ctx, SLOT_DEPOSIT_ALL, depositAllDisabledTemplate, "items.deposit-all-disabled", "Deposit all", resolver, clickCtx -> {
        clickCtx.cancel();
        triggerGeneralClick(clickCtx.viewer().uniqueId());
      });
      return;
    }

    setConfiguredButton(ctx, SLOT_DEPOSIT, depositTemplate, "items.deposit", "Deposit", resolver, clickCtx -> {
      clickCtx.cancel();
      triggerGeneralClick(clickCtx.viewer().uniqueId());
      openAmountDialog(clickCtx.viewer(), data, false);
    });

    if (data.remainingCapacity().compareTo(MantissaAmount.zero()) > 0 && data.walletBalance().compareTo(MantissaAmount.zero()) > 0) {
      setConfiguredButton(ctx, SLOT_DEPOSIT_ALL, depositAllTemplate, "items.deposit-all", "Deposit all", resolver, clickCtx -> {
        clickCtx.cancel();
        triggerGeneralClick(clickCtx.viewer().uniqueId());
        openAllDepositDialog(clickCtx.viewer(), data);
      });
    } else {
      setConfiguredButton(ctx, SLOT_DEPOSIT_ALL, depositAllDisabledTemplate, "items.deposit-all-disabled", "Deposit all", resolver, clickCtx -> {
        clickCtx.cancel();
        triggerGeneralClick(clickCtx.viewer().uniqueId());
      });
    }
  }

  private static void setTransactionButton(MenuPopulateContext ctx, BankData data) {
    TagResolver resolver = detailResolver(data);
    setConfiguredButton(ctx, SLOT_TRANSACTIONS, transactionsTemplate, "items.transactions", "Transactions", resolver, clickCtx -> {
      clickCtx.cancel();
      triggerGeneralClick(clickCtx.viewer().uniqueId());
      if (servicesRef != null) {
        BankTransactionMenu.open(servicesRef, clickCtx.viewer(), data.context().bankId(), data.context().ownerUuid(), data.context().ownerBank(), data.currency());
      }
    });
  }

  private static void setLevelButton(MenuPopulateContext ctx, BankData data) {
    TagResolver resolver = detailResolver(data);
    setConfiguredButton(ctx, SLOT_LEVEL, levelTemplate, "items.level", "Levels", resolver, clickCtx -> {
      clickCtx.cancel();
      triggerGeneralClick(clickCtx.viewer().uniqueId());
      if (servicesRef != null) {
        BankLevelMenu.open(servicesRef, clickCtx.viewer(), data.context().bankId(), data.context().ownerUuid(), data.context().ownerBank());
      }
    });
  }

  private static void setWithdrawButtons(MenuPopulateContext ctx, BankData data) {
    TagResolver resolver = detailResolver(data);
    if (!data.withdrawEnabled()) {
      setConfiguredButton(ctx, SLOT_WITHDRAW, withdrawDisabledTemplate, "items.withdraw-disabled", "Withdraw", resolver, clickCtx -> {
        clickCtx.cancel();
        triggerGeneralClick(clickCtx.viewer().uniqueId());
      });
      setConfiguredButton(ctx, SLOT_WITHDRAW_ALL, withdrawAllDisabledTemplate, "items.withdraw-all-disabled", "Withdraw all", resolver, clickCtx -> {
        clickCtx.cancel();
        triggerGeneralClick(clickCtx.viewer().uniqueId());
      });
      return;
    }

    setConfiguredButton(ctx, SLOT_WITHDRAW, withdrawTemplate, "items.withdraw", "Withdraw", resolver, clickCtx -> {
      clickCtx.cancel();
      triggerGeneralClick(clickCtx.viewer().uniqueId());
      openAmountDialog(clickCtx.viewer(), data, true);
    });

    if (data.bankBalance().compareTo(MantissaAmount.zero()) > 0) {
      setConfiguredButton(ctx, SLOT_WITHDRAW_ALL, withdrawAllTemplate, "items.withdraw-all", "Withdraw all", resolver, clickCtx -> {
        clickCtx.cancel();
        triggerGeneralClick(clickCtx.viewer().uniqueId());
        openAllWithdrawDialog(clickCtx.viewer(), data);
      });
    } else {
      setConfiguredButton(ctx, SLOT_WITHDRAW_ALL, withdrawAllDisabledTemplate, "items.withdraw-all-disabled", "Withdraw all", resolver, clickCtx -> {
        clickCtx.cancel();
        triggerGeneralClick(clickCtx.viewer().uniqueId());
      });
    }
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

  private static void setFallbackButton(MenuPopulateContext ctx,
                                        int slot,
                                        Material material,
                                        String name,
                                        String lore,
                                        MenuSlot.MenuClickHandler clickHandler) {
    ItemStack stack = itemService.builder(new ItemStack(material))
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

  private static TagResolver detailResolver(BankData data) {
    String ownerName = nameOrUuid(data.context().ownerUuid());
    String roleName = data.role() == null ? "unknown" : data.role().nameMiniMessage();
    String bankBalance = AmountNotation.formatShort(data.bankBalance(), data.currency().fractionDigits());
    String walletBalance = AmountNotation.formatShort(data.walletBalance(), data.currency().fractionDigits());
    String maxBalance = AmountNotation.formatShort(data.maxBalance(), data.currency().fractionDigits());
    String remainingCapacity = AmountNotation.formatShort(data.remainingCapacity(), data.currency().fractionDigits());
    String interestRate = currentInterestRate(data);

    return TagResolver.resolver(List.of(
        Placeholder.parsed("bank-name", data.definition().nameMiniMessage()),
        Placeholder.parsed("bank-id", data.definition().idLower()),
        Placeholder.parsed("owner-name", ownerName),
        Placeholder.parsed("owner-uuid", data.context().ownerUuid().toString()),
        Placeholder.parsed("bank-type", data.context().ownerBank() ? "Owner" : "Member"),
        Placeholder.parsed("role-name", roleName),
        Placeholder.parsed("current-level", String.valueOf(data.currentLevel())),
        Placeholder.parsed("max-level", String.valueOf(data.maxLevel())),
        Placeholder.parsed("interest-rate", interestRate),
        Placeholder.parsed("bank-balance", bankBalance),
        Placeholder.parsed("wallet-balance", walletBalance),
        Placeholder.parsed("max-balance", maxBalance),
        Placeholder.parsed("remaining-capacity", remainingCapacity),
        Placeholder.parsed("hourly-limit", data.hourlyLimitText()),
        Placeholder.parsed("hourly-used", data.hourlyUsedText()),
        Placeholder.parsed("daily-limit", data.dailyLimitText()),
        Placeholder.parsed("daily-used", data.dailyUsedText()),
        Placeholder.parsed("deposit-status", data.depositEnabled() ? "Enabled" : "Disabled"),
        Placeholder.parsed("deposit-reason", data.depositDisabledReason()),
        Placeholder.parsed("deposit-disabled-reason", data.depositDisabledReason()),
        Placeholder.parsed("withdraw-status", data.withdrawEnabled() ? "Enabled" : "Disabled"),
        Placeholder.parsed("withdraw-reason", data.withdrawDisabledReason()),
        Placeholder.parsed("withdraw-disabled-reason", data.withdrawDisabledReason()),
        Placeholder.parsed("can-deposit", data.depositEnabled() ? "Yes" : "No"),
        Placeholder.parsed("can-withdraw", data.withdrawEnabled() ? "Yes" : "No")
    ));
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

  private static Duration parseRefreshInterval(String raw) {
    if (raw == null || raw.isBlank()) {
      return Duration.ofSeconds(1);
    }

    String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
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

  private static String toLegacyTitle(String raw) {
    String value = raw == null ? "<yellow>Bank Details</yellow>" : raw;
    return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
        .serialize(MiniMessage.miniMessage().deserialize(value));
  }

  private static void requestWithdrawUsageRefresh(Player player,
                                                  BankContext context,
                                                  BankDefinition definition,
                                                  CurrencyDefinition currency,
                                                  UUID bankAccountId,
                                                  BankDefinition.RoleDefinition role,
                                                  BankData seed,
                                                  BankAccountCacheService bankCache) {
    if (player == null || context == null || definition == null || currency == null || bankAccountId == null || role == null || bankCache == null || role.withdraw() == null) {
      return;
    }

    BankDefinition.WithdrawDefinition wd = role.withdraw();
    MantissaAmount hourlyLimit = parseLimit(currency, wd.hourlyLimitRaw());
    MantissaAmount dailyLimit = parseLimit(currency, wd.dailyLimitRaw());

    ZoneId zone = resolveBankZone(definition);
    long hourStart = windowStartEpoch(zone, true);
    long dayStart = windowStartEpoch(zone, false);

    CompletableFuture<MantissaAmount> hourlyUsedF = isUnlimited(hourlyLimit)
        ? CompletableFuture.completedFuture(MantissaAmount.zero())
        : bankCache.loadWithdrawUsage(bankAccountId, player.getUniqueId(), BankWithdrawUsageEntity.WindowType.HOURLY, hourStart);

    CompletableFuture<MantissaAmount> dailyUsedF = isUnlimited(dailyLimit)
        ? CompletableFuture.completedFuture(MantissaAmount.zero())
        : bankCache.loadWithdrawUsage(bankAccountId, player.getUniqueId(), BankWithdrawUsageEntity.WindowType.DAILY, dayStart);

    CompletableFuture.allOf(hourlyUsedF, dailyUsedF).whenComplete((ignored, error) -> {
      if (error != null) {
        return;
      }

      BankContext current = CONTEXTS.get(player.getUniqueId());
      if (!context.equals(current)) {
        return;
      }

      MantissaAmount hourlyUsed = zeroSafe(hourlyUsedF.getNow(MantissaAmount.zero()));
      MantissaAmount dailyUsed = zeroSafe(dailyUsedF.getNow(MantissaAmount.zero()));

      String hourlyUsedText = formatUsedDisplay(currency, hourlyUsed);
      String dailyUsedText = formatUsedDisplay(currency, dailyUsed);

      BankData base = seed == null ? LAST_DATA.get(player.getUniqueId()) : seed;
      if (base == null) {
        return;
      }

      BankData refreshed = base.withLimitTexts(
          formatLimitDisplay(currency, wd.hourlyLimitRaw()),
          hourlyUsedText,
          formatLimitDisplay(currency, wd.dailyLimitRaw()),
          dailyUsedText,
          System.currentTimeMillis()
      );

      LAST_DATA.put(player.getUniqueId(), refreshed);
    });
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

  private static String formatLimitDisplay(CurrencyDefinition currency, String raw) {
    MantissaAmount limit = parseLimit(currency, raw);
    if (isUnlimited(limit)) return "∞";
    return AmountNotation.formatShort(limit, currency == null ? 0 : currency.fractionDigits());
  }

  private static MantissaAmount zeroSafe(MantissaAmount amount) {
    return amount == null ? MantissaAmount.zero() : amount;
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


  private static void openAmountDialog(ViewerRef viewer, BankData data, boolean withdraw) {
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    if (player == null) return;

    TextRequestDialogService textService = servicesRef.getService(TextRequestDialogService.class);
    TextRequestDialog dialog = textService.create()
        .title(localizedLegacy(player, withdraw ? "bank.detail.dialog.withdraw.title" : "bank.detail.dialog.deposit.title"))
        .body(localizedLegacy(player, withdraw ? "bank.detail.dialog.withdraw.body" : "bank.detail.dialog.deposit.body"))
        .placeholder(localizedLegacy(player, "bank.detail.dialog.amount.placeholder"))
        .minCharacters(1)
        .maxCharacters(32)
        .submitButton(localizedLegacy(player, withdraw ? "bank.detail.dialog.withdraw.submit" : "bank.detail.dialog.deposit.submit"));

    dialog.show(player).thenAccept(raw -> {
      if (raw == null || raw.isBlank()) return;

      MantissaAmount amount = parseAmount(data.currency(), raw);
      if (amount == null || amount.compareTo(MantissaAmount.zero()) <= 0) {
        sendMessage(player, "general.wrong-amount", TagResolver.resolver(List.of()));
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

    MantissaAmount balance = currentWalletBalance(player.getUniqueId(), data.currency());
    if (balance == null || balance.compareTo(MantissaAmount.zero()) <= 0) {
      sendMessage(player, "bank.detail.wallet-empty", TagResolver.resolver(List.of()));
      return;
    }

    String shown = AmountNotation.formatShort(balance, data.currency().fractionDigits());
    ConfirmDialogService confirmService = servicesRef.getService(ConfirmDialogService.class);
    ConfirmDialog confirm = confirmService.create()
        .title(localizedLegacy(player, "bank.detail.dialog.deposit-all.title"))
        .body(localizedLegacy(player, "bank.detail.dialog.deposit-all.body", Placeholder.parsed("amount", shown)))
        .confirmButton(localizedLegacy(player, "bank.detail.dialog.deposit-all.confirm"))
        .cancelButton(localizedLegacy(player, "bank.detail.dialog.cancel"));

    confirm.show(player).thenAccept(result -> {
      if (!Boolean.TRUE.equals(result)) return;
      executeDeposit(player, data, balance);
    });
  }

  private static void openAllWithdrawDialog(ViewerRef viewer, BankData data) {
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    if (player == null) return;

    MantissaAmount balance = data.bankBalance();
    if (balance == null || balance.compareTo(MantissaAmount.zero()) <= 0) {
      sendMessage(player, "bank.detail.bank-empty", TagResolver.resolver(List.of()));
      return;
    }

    String shown = AmountNotation.formatShort(balance, data.currency().fractionDigits());
    ConfirmDialogService confirmService = servicesRef.getService(ConfirmDialogService.class);
    ConfirmDialog confirm = confirmService.create()
        .title(localizedLegacy(player, "bank.detail.dialog.withdraw-all.title"))
        .body(localizedLegacy(player, "bank.detail.dialog.withdraw-all.body", Placeholder.parsed("amount", shown)))
        .confirmButton(localizedLegacy(player, "bank.detail.dialog.withdraw-all.confirm"))
        .cancelButton(localizedLegacy(player, "bank.detail.dialog.cancel"));

    confirm.show(player).thenAccept(result -> {
      if (!Boolean.TRUE.equals(result)) return;
      executeWithdraw(player, data, balance);
    });
  }

  private static void executeDeposit(Player player, BankData data, MantissaAmount amount) {
    BankService bankService = servicesRef.getService(BankService.class);
    bankService.deposit(data.context().bankId(), data.context().ownerUuid(), player.getUniqueId(), amount)
        .thenAccept(done -> Bukkit.getScheduler().runTask(plugin, () -> {
          String shown = AmountNotation.formatShort(done, data.currency().fractionDigits());
          sendMessage(player, "bank.deposit.self", TagResolver.resolver(List.of(
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("bank", data.definition().nameMiniMessage())
          )));
          triggerDepositSuccess(player);
          refreshOpenView(player);
        }))
        .exceptionally(ex -> {
          Bukkit.getScheduler().runTask(plugin,
              () -> {
                triggerDepositFailed(player);
                sendMessage(player, "bank.detail.deposit-failed", TagResolver.resolver(List.of()));
                sendBankDetailError(player, ex);
              });
          return null;
        });
  }

  private static void executeWithdraw(Player player, BankData data, MantissaAmount amount) {
    BankService bankService = servicesRef.getService(BankService.class);
    bankService.withdraw(data.context().bankId(), data.context().ownerUuid(), player.getUniqueId(), amount)
        .thenAccept(done -> Bukkit.getScheduler().runTask(plugin, () -> {
          applyWithdrawUsageOptimistically(player.getUniqueId(), data, done);
          String shown = AmountNotation.formatShort(done, data.currency().fractionDigits());
          sendMessage(player, "bank.withdraw.self", TagResolver.resolver(List.of(
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("bank", data.definition().nameMiniMessage())
          )));
          triggerWithdrawSuccess(player);
          refreshOpenView(player);
        }))
        .exceptionally(ex -> {
          Bukkit.getScheduler().runTask(plugin,
              () -> {
                triggerWithdrawFailed(player);
                sendMessage(player, "bank.detail.withdraw-failed", TagResolver.resolver(List.of()));
                sendBankDetailError(player, ex);
              });
          return null;
        });
  }

  private static void triggerGeneralClick(UUID viewerUuid) {
    if (viewerUuid == null || servicesRef == null) {
      return;
    }

    Player player = Bukkit.getPlayer(viewerUuid);
    if (player != null) {
      triggerClick(player, ClickEffectType.GENERAL);
    }
  }

  private static void triggerDepositSuccess(Player player) {
    triggerClick(player, ClickEffectType.DEPOSIT_SUCCESS);
  }

  private static void triggerDepositFailed(Player player) {
    triggerClick(player, ClickEffectType.DEPOSIT_FAILED);
  }

  private static void triggerWithdrawSuccess(Player player) {
    triggerClick(player, ClickEffectType.WITHDRAW_SUCCESS);
  }

  private static void triggerWithdrawFailed(Player player) {
    triggerClick(player, ClickEffectType.WITHDRAW_FAILED);
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
      case DEPOSIT_SUCCESS -> effects.executeDepositSuccess(player);
      case DEPOSIT_FAILED -> effects.executeDepositFailed(player);
      case WITHDRAW_SUCCESS -> effects.executeWithdrawSuccess(player);
      case WITHDRAW_FAILED -> effects.executeWithdrawFailed(player);
    }
  }

  private static void sendMessage(Player player, String key, TagResolver resolver) {
    if (componentService == null || player == null) {
      return;
    }

    player.sendMessage(componentService.builder(player, key, "NotDefined", true)
        .resolver(resolver)
        .build());
  }

  private static String localizedLegacy(Player player, String key, TagResolver... extraResolvers) {
    if (componentService == null || player == null) {
      return key;
    }

    var builder = componentService.builder(player, key, "NotDefined", true);
    if (extraResolvers != null) {
      for (TagResolver resolver : extraResolvers) {
        if (resolver != null) {
          builder.resolver(resolver);
        }
      }
    }

    return LegacyComponentSerializer.legacySection().serialize(builder.build());
  }

  private enum ClickEffectType {
    GENERAL,
    DEPOSIT_SUCCESS,
    DEPOSIT_FAILED,
    WITHDRAW_SUCCESS,
    WITHDRAW_FAILED
  }

   private static MantissaAmount currentWalletBalance(UUID playerUuid, CurrencyDefinition currency) {
     EconomyPlayerCacheService economyCache = servicesRef.getService(EconomyPlayerCacheService.class);
     if (economyCache == null || playerUuid == null || currency == null) return MantissaAmount.zero();

     EconomyPlayer econ = economyCache.getOnline(playerUuid);
     if (econ == null) return MantissaAmount.zero();

     EconomyPlayer.BalanceEntry entry = econ.entry(currency.id());
     return entry == null || entry.amount() == null ? MantissaAmount.zero() : entry.amount();
   }

  private static void refreshOpenView(Player player) {
    MenuService menuService = servicesRef.getService(MenuService.class);
    menuService.findOpenView(ViewerRef.of(player.getUniqueId(), player.getName()))
        .ifPresent(MenuView::requestRefresh);
  }

  private static void scheduleRefresh(Player player) {
    if (player == null || plugin == null) return;

    Bukkit.getScheduler().runTask(plugin, () -> {
      if (player.isOnline()) {
        refreshOpenView(player);
      }
    });
  }

  private static void applyWithdrawUsageOptimistically(UUID playerUuid, BankData current, MantissaAmount withdrawn) {
    if (playerUuid == null || current == null || withdrawn == null || withdrawn.compareTo(MantissaAmount.zero()) <= 0) {
      return;
    }

    BankData latest = LAST_DATA.get(playerUuid);
    if (latest == null || latest.context() == null || !latest.context().equals(current.context())) {
      latest = current;
    }

    MantissaAmount nextHourlyUsed = addDisplayedUsed(latest.currency(), latest.hourlyUsedText(), withdrawn);
    MantissaAmount nextDailyUsed = addDisplayedUsed(latest.currency(), latest.dailyUsedText(), withdrawn);

    LAST_DATA.put(playerUuid, latest.withLimitTexts(
        latest.hourlyLimitText(),
        formatUsedDisplay(latest.currency(), nextHourlyUsed),
        latest.dailyLimitText(),
        formatUsedDisplay(latest.currency(), nextDailyUsed),
        System.currentTimeMillis()
    ));
  }

  private static MantissaAmount addDisplayedUsed(CurrencyDefinition currency, String usedText, MantissaAmount withdrawn) {
    if (usedText == null || usedText.isBlank() || "∞".equals(usedText.trim())) return null;

    MantissaAmount used = parseAmountLikeDisplay(currency, usedText);
    if (used == null) return null;

    MantissaAmount next = used.add(withdrawn);
    return next.compareTo(MantissaAmount.zero()) < 0 ? MantissaAmount.zero() : next;
  }

  private static String formatUsedDisplay(CurrencyDefinition currency, MantissaAmount used) {
    if (used == null) return "…";
    return AmountNotation.formatShort(used, currency == null ? 0 : currency.fractionDigits());
  }

  private static MantissaAmount parseAmountLikeDisplay(CurrencyDefinition currency, String raw) {
    if (raw == null) return null;

    String input = raw.trim();
    if (input.isBlank()) return null;

    if (currency != null && currency.type() != CurrencyType.VAULT) {
      MantissaAmount virtual = AmountNotation.parseVirtualMantissaAmount(input);
      if (virtual != null) return virtual;
    }

    BigDecimal human = AmountNotation.parseVaultHuman(input);
    if (human != null) return MantissaAmount.of(human, 0);

    return AmountNotation.parseVirtualMantissaAmount(input);
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

  private static BankDefinition.RoleDefinition resolveRole(BankDefinition def,
                                                           BankContext context,
                                                           UUID viewerUuid,
                                                           BankMemberEntity member) {
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

  private static BankMemberEntity findMember(List<BankMemberEntity> members, UUID viewerUuid) {
    if (members == null || viewerUuid == null) return null;
    for (BankMemberEntity member : members) {
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

  private static void sendBankDetailError(Player player, Throwable ex) {
    Throwable root = ex;
    for (int i = 0; i < 6 && root != null && root.getCause() != null; i++) {
      root = root.getCause();
    }

    if (isMarker(root, "bank not available")) {
      sendMessage(player, "bank.errors.bank-not-available", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "member system disabled")) {
      sendMessage(player, "bank.errors.member-system-disabled", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "no permission")) {
      sendMessage(player, "bank.errors.no-permission", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "player must be online")) {
      sendMessage(player, "bank.errors.player-must-be-online", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "invalid amount")) {
      sendMessage(player, "general.wrong-amount", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "insufficient funds")) {
      sendMessage(player, "bank.errors.insufficient-funds", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "max balance reached")) {
      sendMessage(player, "bank.errors.max-balance-reached", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "bank empty")) {
      sendMessage(player, "bank.errors.bank-empty", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "limit reached")) {
      sendMessage(player, "bank.errors.withdraw-limit-reached", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "not a member")) {
      sendMessage(player, "bank.errors.not-a-member", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "unknown role")) {
      sendMessage(player, "bank.errors.unknown-role", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "already a member")) {
      sendMessage(player, "bank.errors.already-a-member", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "cannot invite owner")) {
      sendMessage(player, "bank.errors.cannot-invite-self", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "bank locked")) {
      sendMessage(player, "bank.errors.bank-locked", TagResolver.resolver(List.of()));
      return;
    }

    if (isMarker(root, "bank accounts locked")) {
      sendMessage(player, "bank.errors.bank-accounts-locked", TagResolver.resolver(List.of()));
      return;
    }

    sendMessage(player, "bank.errors.internal", TagResolver.resolver(List.of(
        Placeholder.parsed("error", rootMessage(root))
    )));
  }

  private static boolean isMarker(Throwable ex, String marker) {
    if (ex == null || marker == null || marker.isBlank()) return false;
    String message = ex.getMessage();
    return message != null && message.toLowerCase().contains(marker.trim().toLowerCase());
  }

  private static String currentInterestRate(BankData data) {
    if (data == null || data.definition() == null || data.definition().levels() == null) {
      return "—";
    }

    for (BankDefinition.LevelDefinition levelDef : data.definition().levels()) {
      if (levelDef != null && levelDef.level() == data.currentLevel()) {
        return formatInterestRate(levelDef.interestRateRaw());
      }
    }

    return "—";
  }

  private static String formatInterestRate(String raw) {
    if (raw == null || raw.isBlank()) {
      return "—";
    }

    try {
      String cleaned = raw.trim().replace("%", "");
      BigDecimal rate = new BigDecimal(cleaned);
      if (rate.compareTo(BigDecimal.ONE) > 0) {
        rate = rate.divide(new BigDecimal("100"), 8, java.math.RoundingMode.HALF_UP);
      }
      return rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
    } catch (Exception ignored) {
      return raw.trim();
    }
  }

  private record BankContext(String bankId, UUID ownerUuid, boolean ownerBank) {}

  private record BankData(
      BankContext context,
      BankDefinition definition,
      CurrencyDefinition currency,
      BankDefinition.RoleDefinition role,
      int currentLevel,
      int maxLevel,
      MantissaAmount bankBalance,
      MantissaAmount walletBalance,
      MantissaAmount maxBalance,
      MantissaAmount remainingCapacity,
      boolean bankFull,
      boolean depositEnabled,
      String depositDisabledReason,
      boolean withdrawEnabled,
      String withdrawDisabledReason,
      String hourlyLimitText,
      String hourlyUsedText,
      String dailyLimitText,
      String dailyUsedText,
      long usageRefreshedAtMs
  ) {

    BankData withLimitTexts(String newHourlyLimitText,
                            String newHourlyUsedText,
                            String newDailyLimitText,
                            String newDailyUsedText,
                            long newUsageRefreshedAtMs) {
      return new BankData(
          context,
          definition,
          currency,
          role,
          currentLevel,
          maxLevel,
          bankBalance,
          walletBalance,
          maxBalance,
          remainingCapacity,
          bankFull,
          depositEnabled,
          depositDisabledReason,
          withdrawEnabled,
          withdrawDisabledReason,
          newHourlyLimitText,
          newHourlyUsedText,
          newDailyLimitText,
          newDailyUsedText,
          newUsageRefreshedAtMs
      );
    }
  }
}


