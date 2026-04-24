package io.nexstudios.nexeconomy.service.menu.bank.level.view;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.menuservice.api.MenuContext;
import io.nexstudios.menuservice.api.MenuElement;
import io.nexstudios.menuservice.api.MenuKey;
import io.nexstudios.menuservice.api.page.PageBounds;
import io.nexstudios.menuservice.api.page.PageItemRenderer;
import io.nexstudios.menuservice.core.element.StaticMenuElement;
import io.nexstudios.menuservice.core.page.ControlledPagedMenuView;
import io.nexstudios.menuservice.core.page.element.NextPageElement;
import io.nexstudios.menuservice.core.page.element.PreviousPageElement;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.domain.EcoPlayer;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.level.BankLevelService;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.economy.EconomyService;
import io.nexstudios.nexeconomy.service.menu.bank.detail.view.BankDetailMenuView;
import io.nexstudios.nexeconomy.service.menu.bank.level.BankLevelMenuDefinition;
import io.nexstudios.nexlogic.bukkit.services.items.ItemProviderService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Paged view of bank levels for a single bank account.
 * Allows upgrading to the next level when requirements are met.
 */
public final class BankLevelMenuView extends ControlledPagedMenuView<BankDefinition.LevelDefinition> {

  private static final MenuKey KEY = BankLevelMenuDefinition.KEY;
  private static final String CONFIG_PATH = "inventories/bank-level.yml";
  private static final MiniMessage MINI = MiniMessage.miniMessage();

  private final ServiceAccessor accessor;
  private final String bankId;
  private final UUID ownerUuid;
  private final UUID viewerUuid;
  private final int currentLevel;
  private final UUID bankAccountId;
  private final BankDefinition def;
  private final FileConfiguration config;
  private final ItemProviderService itemProvider;
  private final ItemService itemService;
  private final BankLevelService levelService;
  private final EconomyService econService;

  public BankLevelMenuView(ServiceAccessor accessor, String bankId, UUID ownerUuid, UUID viewerUuid) {
    this(accessor, bankId, ownerUuid, viewerUuid, new PageItemRenderer[1]);
  }

  @SuppressWarnings("unchecked")
  private BankLevelMenuView(ServiceAccessor accessor, String bankId, UUID ownerUuid, UUID viewerUuid,
                             PageItemRenderer<BankDefinition.LevelDefinition>[] rendererBox) {
    super(KEY, rowsToSize(6), PageBounds.of(1, 2, 7, 1), "bank-level",
        List.of(), (ctx, entry, idx) -> rendererBox[0].render(ctx, entry, idx));

    this.accessor   = accessor;
    this.bankId     = bankId.toLowerCase(Locale.ROOT);
    this.ownerUuid  = ownerUuid;
    this.viewerUuid = viewerUuid;

    FileReaderService fileReader        = accessor.getService(FileReaderService.class);
    this.itemService                    = accessor.getService(ItemService.class);
    this.levelService                   = accessor.getService(BankLevelService.class);
    this.econService                    = accessor.getService(EconomyService.class);
    BankRegistryService bankRegistry    = accessor.getService(BankRegistryService.class);
    BankAccountCacheService bankCache   = accessor.getService(BankAccountCacheService.class);
    this.itemProvider                   = NexEconomyPlugin.getNexLogicService().getService(ItemProviderService.class);

    this.config = fileReader.load(Path.of(CONFIG_PATH), CONFIG_PATH, false);
    this.def    = bankRegistry.bank(this.bankId).orElse(null);

    BankAccountCacheService.View view = bankCache.get(this.bankId, ownerUuid);
    int lvl = (view != null && view.account() != null) ? view.account().getLevel() : 1;
    this.currentLevel   = lvl <= 0 ? 1 : lvl;
    this.bankAccountId  = (view != null && view.account() != null) ? view.account().getId() : null;

    rendererBox[0] = this::renderEntry;

    setTitle(MINI.deserialize(config.getString("menu.title", "<yellow>Bank Levels</yellow>")));

    ConfigurationSection fillCfg = config.getSection("items.fill");
    if (fillCfg != null) fill(buildItem(fillCfg, Map.of()));

    int prevSlot = config.getInt("layout.levels.navigation.previous-slot", 45);
    int nextSlot = config.getInt("layout.levels.navigation.next-slot", 53);
    addElement(prevSlot, new PreviousPageElement());
    addElement(nextSlot, new NextPageElement());

    int backSlot = config.getInt("layout.slots.back", 49);
    ConfigurationSection backCfg = config.getSection("items.back");
    ItemStack backItem = backCfg != null ? buildItem(backCfg, Map.of()) : new ItemStack(Material.ARROW);
    addElement(backSlot, new StaticMenuElement(backItem,
        (ctx, event) -> ctx.menuService().open(ctx.viewer(),
            new BankDetailMenuView(accessor, bankId, ownerUuid, viewerUuid))));
  }

  @Override
  protected List<BankDefinition.LevelDefinition> resolveItems(MenuContext context) {
    if (def == null || def.levels() == null || def.levels().isEmpty()) return List.of();
    List<BankDefinition.LevelDefinition> sorted = new ArrayList<>(def.levels());
    sorted.sort(Comparator.comparingInt(BankDefinition.LevelDefinition::level));
    return sorted;
  }

  private MenuElement renderEntry(MenuContext context, BankDefinition.LevelDefinition levelDef, int idx) {
    int level = levelDef.level();
    Player viewer = context.viewer();

    String bankName  = def != null ? MINI.stripTags(def.nameMiniMessage()) : bankId;
    MantissaAmount cost = levelService.getUpgradeCost(bankId, level);
    String costStr   = AmountNotation.formatShort(cost, 2);
    String maxBalStr = levelDef.maxBalanceRaw() != null ? levelDef.maxBalanceRaw() : "∞";
    String interestStr = levelDef.interestRateRaw() != null ? levelDef.interestRateRaw() : "0%";
    int maxLevel     = levelService.getMaxLevel(bankId);

    EcoPlayer eco = EcoPlayer.of(viewer.getUniqueId());
    MantissaAmount walletBal = (eco != null && def != null)
        ? eco.vault().balance(def.currencyIdLower())
        : MantissaAmount.zero();
    String walletStr = AmountNotation.formatShort(walletBal, 2);

    MantissaAmount diff = cost != null ? cost.subtract(walletBal) : MantissaAmount.zero();
    String missingStr = diff.compareTo(MantissaAmount.zero()) > 0
        ? AmountNotation.formatShort(diff, 2) : "0";

    Map<String, String> ph = new HashMap<>();
    ph.put("bank-name",      bankName);
    ph.put("level",          String.valueOf(level));
    ph.put("current-level",  String.valueOf(currentLevel));
    ph.put("max-level",      String.valueOf(maxLevel));
    ph.put("required-level", String.valueOf(level - 1));
    ph.put("max-balance",    maxBalStr);
    ph.put("interest-rate",  interestStr);
    ph.put("upgrade-cost",   costStr);
    ph.put("wallet-balance", walletStr);
    ph.put("missing-funds",  missingStr);
    ph.put("upgrade-reason", "");
    ph.put("permission",     levelDef.permission() != null ? levelDef.permission() : "None");
    ph.put("role-name",      "Owner");

    // ── Already unlocked ────────────────────────────────────────────────
    if (level <= currentLevel) {
      ConfigurationSection cfg = config.getSection("items.unlocked");
      ItemStack item = cfg != null ? buildItem(cfg, ph) : new ItemStack(Material.LIME_STAINED_GLASS_PANE);
      return new StaticMenuElement(item, (ctx, event) -> {});
    }

    // ── Previous level not yet unlocked ─────────────────────────────────
    if (level > currentLevel + 1) {
      ph.put("upgrade-reason", "Unlock level " + (level - 1) + " first");
      ConfigurationSection cfg = config.getSection("items.previous-needs-to-be-unlocked");
      ItemStack item = cfg != null ? buildItem(cfg, ph) : new ItemStack(Material.RED_STAINED_GLASS_PANE);
      return new StaticMenuElement(item, (ctx, event) -> {});
    }

    // ── Next upgradeable level ───────────────────────────────────────────
    boolean hasPermission = levelDef.permission() == null || levelDef.permission().isBlank()
        || viewer.hasPermission(levelDef.permission());
    boolean hasFunds = walletBal.compareTo(cost) >= 0;

    if (!hasPermission || !hasFunds) {
      ph.put("upgrade-reason", !hasPermission ? "Missing permission" : "Insufficient funds");
      ConfigurationSection cfg = config.getSection("items.conditions-not-met");
      ItemStack item = cfg != null ? buildItem(cfg, ph) : new ItemStack(Material.RED_STAINED_GLASS_PANE);
      return new StaticMenuElement(item, (ctx, event) -> {});
    }

    // ── Ready for unlock ─────────────────────────────────────────────────
    ConfigurationSection cfg = config.getSection("items.ready-for-unlock");
    ItemStack item = cfg != null ? buildItem(cfg, ph) : new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);

    final UUID accId          = bankAccountId;
    final MantissaAmount upgradeCost = cost;
    final int targetLevel     = level;
    final String currencyId   = def != null ? def.currencyIdLower() : "";

    return new StaticMenuElement(item, (ctx, event) -> {
      Player p = ctx.viewer();
      if (accId == null) { p.sendMessage(Component.text("Bank account not found.")); return; }

      econService.remove(p, currencyId, upgradeCost)
          .thenCompose(ok -> {
            if (!Boolean.TRUE.equals(ok)) {
              runOnMain(() -> p.sendMessage(Component.text("Insufficient funds to upgrade!")));
              return CompletableFuture.completedFuture(false);
            }
            return levelService.upgrade(accId, targetLevel);
          })
          .thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
              runOnMain(() -> {
                p.sendMessage(Component.text("Bank upgraded to level " + targetLevel + "!"));
                ctx.menuService().open(p, new BankLevelMenuView(accessor, bankId, ownerUuid, viewerUuid));
              });
            } else {
              econService.add(p, currencyId, upgradeCost);
              runOnMain(() -> p.sendMessage(Component.text("Upgrade failed. Cost has been refunded.")));
            }
          })
          .exceptionally(ex -> {
            econService.add(p, currencyId, upgradeCost);
            runOnMain(() -> p.sendMessage(Component.text("Upgrade failed: " + rootMessage(ex))));
            return null;
          });
    });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  /** Runs a task on the Bukkit main thread. Safe to call from any thread. */
  private static void runOnMain(Runnable task) {
    Bukkit.getScheduler().runTask(JavaPlugin.getPlugin(NexEconomyPlugin.class), task);
  }

  // ── Item builder ─────────────────────────────────────────────────────────

  private ItemStack buildItem(ConfigurationSection section, Map<String, String> ph) {
    String itemId = section.getString("item", "minecraft:stone");
    ItemStack init = itemProvider.getItem(itemId).orElseGet(() -> new ItemStack(Material.STONE));
    var builder = itemService.builder(init);
    String name = applyPh(section.getString("display-name", ""), ph);
    if (!name.isBlank()) builder.name(name);
    List<String> lore = section.getStringList("lore");
    if (!lore.isEmpty()) builder.lore(b -> lore.forEach(l -> b.line(applyPh(l, ph))));
    return builder.build();
  }

  private static String applyPh(String text, Map<String, String> ph) {
    if (text == null) return "";
    for (Map.Entry<String, String> e : ph.entrySet()) text = text.replace("<" + e.getKey() + ">", e.getValue());
    return text;
  }

  private static String rootMessage(Throwable t) {
    Throwable r = t;
    for (int i = 0; i < 8 && r != null && r.getCause() != null; i++) r = r.getCause();
    return r != null && r.getMessage() != null ? r.getMessage() : "Unknown error";
  }
}



