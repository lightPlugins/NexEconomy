package io.nexstudios.nexeconomy.service.menu.bank.transactions.view;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.menuservice.api.MenuContext;
import io.nexstudios.menuservice.api.MenuElement;
import io.nexstudios.menuservice.api.page.PageBounds;
import io.nexstudios.menuservice.api.page.PageItemRenderer;
import io.nexstudios.menuservice.api.page.control.PageSortControl;
import io.nexstudios.menuservice.core.element.StaticMenuElement;
import io.nexstudios.menuservice.core.page.ControlledPagedMenuView;
import io.nexstudios.menuservice.core.page.control.BasicPageSortControl;
import io.nexstudios.menuservice.core.page.element.NextPageElement;
import io.nexstudios.menuservice.core.page.element.PreviousPageElement;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.domain.EcoPlayer;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.menu.bank.detail.view.BankDetailMenuView;
import io.nexstudios.nexeconomy.service.menu.bank.transactions.BankTransactionsMenuDefinition;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import io.nexstudios.nexlogic.bukkit.services.items.ItemProviderService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Paged view of bank transactions for a single account.
 * Supports filtering (all / deposit-only / withdraw-only) and four sort modes.
 * Transaction data must be preloaded before opening the menu;
 * no CompletableFuture is used inside this view.
 */
public final class BankTransactionsMenuView extends ControlledPagedMenuView<BankTransactionEntity> {

  private static final MiniMessage MINI        = MiniMessage.miniMessage();
  private static final String      CONFIG_PATH = "inventories/bank-transactions.yml";
  private static final DateTimeFormatter TIME_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

  private final String                          bankId;
  private final UUID                            ownerUuid;
  private final UUID                            viewerUuid;
  private final FileConfiguration               config;
  private final ItemProviderService             itemProvider;
  private final ItemService                     itemService;
  private final BankRegistryService             bankRegistry;
  private final PageSortControl<BankTransactionEntity> filterControl;
  private final PageSortControl<BankTransactionEntity> sortControl;

  // ─── Public constructor ──────────────────────────────────────────────────

  public BankTransactionsMenuView(
      ServiceAccessor accessor,
      String bankId,
      UUID ownerUuid,
      UUID viewerUuid) {
    this(accessor, bankId, ownerUuid, viewerUuid, new PageItemRenderer[1]);
  }

  // ─── Private delegate constructor ───────────────────────────────────────

  private BankTransactionsMenuView(
      ServiceAccessor accessor,
      String bankId,
      UUID ownerUuid,
      UUID viewerUuid,
      PageItemRenderer<BankTransactionEntity>[] rendererBox) {

    super(
        BankTransactionsMenuDefinition.KEY,
        rowsToSize(6),
        PageBounds.of(1, 1, 7, 4),
        "bank-transactions",
        List.of(),
        (ctx, entry, idx) -> rendererBox[0].render(ctx, entry, idx)
    );

    this.bankId        = bankId.toLowerCase(Locale.ROOT);
    this.ownerUuid     = ownerUuid;
    this.viewerUuid    = viewerUuid;
    this.itemProvider  = NexEconomyPlugin.getNexLogicService().getService(ItemProviderService.class);
    this.itemService   = accessor.getService(ItemService.class);
    this.bankRegistry  = accessor.getService(BankRegistryService.class);

    FileReaderService fileReader = accessor.getService(FileReaderService.class);
    this.config = fileReader.load(Path.of(CONFIG_PATH), CONFIG_PATH, false);

    // Wire renderer now that `this` is available.
    rendererBox[0] = this::renderEntry;

    // ── Title ────────────────────────────────────────────────────────────
    setTitle(MINI.deserialize(config.getString("menu.title", "<yellow>Bank Transactions</yellow>")));

    // ── Background fill ──────────────────────────────────────────────────
    fill(buildFill());

    // ── Filter control ───────────────────────────────────────────────────
    ConfigurationSection filteringCfg = config.getSection("filtering");
    String defaultFilter = filteringCfg != null ? filteringCfg.getString("default-mode", "all") : "all";
    ConfigurationSection filterModes = filteringCfg != null ? filteringCfg.getSection("modes") : null;
    String filterAll      = filterModes != null ? filterModes.getString("all",      "All Transactions") : "All Transactions";
    String filterDeposit  = filterModes != null ? filterModes.getString("deposit",  "Deposit Only")     : "Deposit Only";
    String filterWithdraw = filterModes != null ? filterModes.getString("withdraw", "Withdraw Only")    : "Withdraw Only";

    int    filterSlot        = config.getInt("layout.slots.filter", 0);
    String filterDisplayName = config.getString("items.filter-button.display-name",
        "<dark_gray>» <yellow>Transaction Filter</yellow>");

    this.filterControl = addSortControl(
        filterSlot,
        filterDisplayName,
        Material.HOPPER,
        BasicPageSortControl.<BankTransactionEntity>builder("bank-tx-filter")
            .mode("all",      filterAll,      (a, b) -> 0)
            .mode("deposit",  filterDeposit,  (a, b) -> 0)
            .mode("withdraw", filterWithdraw, (a, b) -> 0)
            .defaultMode(defaultFilter)
            .build()
    );

    // ── Sort control ─────────────────────────────────────────────────────
    ConfigurationSection sortingCfg = config.getSection("sorting");
    String defaultSort = sortingCfg != null ? sortingCfg.getString("default-mode", "latest-last") : "latest-last";
    ConfigurationSection sortModes = sortingCfg != null ? sortingCfg.getSection("modes") : null;
    String latestLast  = sortModes != null ? sortModes.getString("latest-last",        "Latest → Last")       : "Latest → Last";
    String lastLatest  = sortModes != null ? sortModes.getString("last-latest",        "Last → Latest")       : "Last → Latest";
    String depWithdraw = sortModes != null ? sortModes.getString("deposit-withdraw",   "Deposit → Withdraw")  : "Deposit → Withdraw";
    String withDeposit = sortModes != null ? sortModes.getString("withdraw-deposit",   "Withdraw → Deposit")  : "Withdraw → Deposit";

    int    sortSlot        = config.getInt("layout.slots.sort", 8);
    String sortDisplayName = config.getString("items.sort-button.display-name",
        "<dark_gray>» <yellow>Transaction Sorting</yellow>");

    this.sortControl = addSortControl(
        sortSlot,
        sortDisplayName,
        Material.COMPARATOR,
        BasicPageSortControl.<BankTransactionEntity>builder("bank-tx-sort")
            .mode("latest-last",      latestLast,  epochComparator().reversed())
            .mode("last-latest",      lastLatest,  epochComparator())
            .mode("deposit-withdraw", depWithdraw, Comparator.comparingInt(tx -> typeOrder(tx.getType())))
            .mode("withdraw-deposit", withDeposit, Comparator.comparingInt((BankTransactionEntity tx) -> typeOrder(tx.getType())).reversed())
            .defaultMode(defaultSort)
            .build()
    );

    // ── Navigation ───────────────────────────────────────────────────────
    ConfigurationSection navCfg = config.getSection("layout.transactions.navigation");
    int prevSlot = navCfg != null ? navCfg.getInt("previous-slot", 45) : 45;
    int nextSlot = navCfg != null ? navCfg.getInt("next-slot",     53) : 53;
    addElement(prevSlot, new PreviousPageElement());
    addElement(nextSlot, new NextPageElement());

    // ── Back button ──────────────────────────────────────────────────────
    int backSlot = config.getInt("layout.slots.back", 49);
    ConfigurationSection backCfg = config.getSection("items.back");
    ItemStack backItem = backCfg != null ? buildItem(backCfg, Map.of()) : new ItemStack(Material.ARROW);
    addElement(backSlot, new StaticMenuElement(backItem,
        (ctx, event) -> ctx.menuService().open(ctx.viewer(),
            new BankDetailMenuView(accessor, bankId, ownerUuid, viewerUuid))));
  }

  // ─── ControlledPagedMenuView ─────────────────────────────────────────────

  @Override
  protected List<BankTransactionEntity> resolveItems(MenuContext context) {
    String filterMode = activeModeId(filterControl, context);
    String sortMode   = activeModeId(sortControl,   context);

    // 1. Load transactions from the viewer's BankContainer.
    //    If the viewer is the owner, transactions(bankId) works directly.
    //    If the viewer is a member, use the overload with the bank owner's UUID.
    List<BankTransactionEntity> source = List.of();
    EcoPlayer eco = EcoPlayer.of(viewerUuid);
    if (eco != null) {
      if (viewerUuid.equals(ownerUuid)) {
        source = eco.banks().transactions(bankId);
      } else {
        source = eco.banks().transactions(bankId, ownerUuid);
      }
    }

    // 2. Filter
    List<BankTransactionEntity> filtered = source.stream()
        .filter(tx -> matchesFilter(tx, filterMode))
        .collect(Collectors.toList());

    // 3. Sort
    Comparator<BankTransactionEntity> comparator =
        sortControl.comparatorFor(sortMode, key(), context.viewer().getUniqueId());
    if (comparator != null) filtered.sort(comparator);

    return filtered;
  }

  // ─── Entry renderer ──────────────────────────────────────────────────────

  private MenuElement renderEntry(MenuContext context, BankTransactionEntity tx, int idx) {
    boolean isDeposit = isDeposit(tx.getType());
    ConfigurationSection section = config.getSection(isDeposit ? "items.deposit" : "items.withdraw");

    BankDefinition def = bankRegistry.bank(bankId).orElse(null);
    String bankType = def != null && def.memberSystem().enabled() ? "Shared" : "Private";

    MantissaAmount amount    = MantissaAmount.parseStorage(tx.getAmountMantissa(), tx.getAmountExp3());
    String         amountStr = AmountNotation.formatShort(amount, 2);
    String         typeLabel = isDeposit ? "Deposit" : "Withdraw";
    String         actorName = resolvePlayerName(tx.getActorUuid());
    String         time      = tx.getCreatedAt() != null ? TIME_FMT.format(tx.getCreatedAt()) : "-";
    String         ownerName = resolvePlayerName(ownerUuid);

    Map<String, String> ph = Map.of(
        "type-label",  typeLabel,
        "actor-name",  actorName,
        "amount",      amountStr,
        "time",        time,
        "bank-id",     bankId,
        "owner-name",  ownerName,
        "owner-uuid",  ownerUuid.toString(),
        "bank-type",   bankType
    );

    ItemStack item = section != null ? buildItem(section, ph) : new ItemStack(Material.PAPER);
    return new StaticMenuElement(item, (ctx, event) -> {});
  }

  // ─── Filter / sort helpers ───────────────────────────────────────────────

  private static boolean matchesFilter(BankTransactionEntity tx, String mode) {
    return switch (mode) {
      case "deposit"  -> isDeposit(tx.getType());
      case "withdraw" -> !isDeposit(tx.getType());
      default         -> true;
    };
  }

  private static boolean isDeposit(Object type) {
    return type != null && "DEPOSIT".equalsIgnoreCase(String.valueOf(type).trim());
  }

  private static int typeOrder(Object type) {
    return isDeposit(type) ? 0 : 1;
  }

  private static Comparator<BankTransactionEntity> epochComparator() {
    return Comparator.comparingLong(tx -> tx.getCreatedAt() != null ? tx.getCreatedAt().toEpochMilli() : 0L);
  }

  // ─── Item builders ───────────────────────────────────────────────────────

  private ItemStack buildFill() {
    ConfigurationSection fillCfg = config.getSection("items.fill");
    if (fillCfg != null) return buildItem(fillCfg, Map.of());
    ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
    ItemMeta  meta = pane.getItemMeta();
    if (meta != null) {
      meta.displayName(Component.empty());
      pane.setItemMeta(meta);
    }
    return pane;
  }

  private ItemStack buildItem(ConfigurationSection section, Map<String, String> ph) {
    String    itemId = section.getString("item", "minecraft:stone");
    ItemStack init   = itemProvider.getItem(itemId).orElseGet(() -> new ItemStack(Material.STONE));
    var builder = itemService.builder(init);
    String name = applyPh(section.getString("display-name", ""), ph);
    if (!name.isBlank()) builder.name(name);
    List<String> lore = section.getStringList("lore");
    if (!lore.isEmpty()) builder.lore(b -> lore.forEach(l -> b.line(applyPh(l, ph))));
    return builder.build();
  }

  private static String applyPh(String text, Map<String, String> ph) {
    if (text == null) return "";
    for (Map.Entry<String, String> e : ph.entrySet()) {
      text = text.replace("<" + e.getKey() + ">", e.getValue());
    }
    return text;
  }

  private static String resolvePlayerName(UUID uuid) {
    if (uuid == null) return "unknown";
    Player online = Bukkit.getPlayer(uuid);
    if (online != null) return online.getName();
    String name = Bukkit.getOfflinePlayer(uuid).getName();
    return name != null ? name : uuid.toString();
  }
}


