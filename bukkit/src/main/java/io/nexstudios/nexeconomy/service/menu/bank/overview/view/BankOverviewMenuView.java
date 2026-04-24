package io.nexstudios.nexeconomy.service.menu.bank.overview.view;

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
import io.nexstudios.menuservice.core.page.element.NextPageElement;
import io.nexstudios.menuservice.core.page.element.PreviousPageElement;
import io.nexstudios.menuservice.core.page.control.BasicPageSortControl;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.domain.EcoPlayer;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.menu.bank.overview.BankOverviewMenuDefinition;
import io.nexstudios.nexlogic.bukkit.services.items.ItemProviderService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paged overview of all bank accounts accessible to the viewer.
 * Supports sorting between owned and member banks.
 */
public final class BankOverviewMenuView extends ControlledPagedMenuView<BankOverviewMenuView.BankEntry> {

  private static final MiniMessage MINI = MiniMessage.miniMessage();
  private static final String CONFIG_PATH = "inventories/bank-overview.yml";

  private final ServiceAccessor accessor;
  private final ItemProviderService itemProviderService;
  private final ItemService itemService;
  private final FileConfiguration config;
  private final BankRegistryService bankRegistry;
  private final PageSortControl<BankEntry> sortControl;

  /** Represents a single bank entry in the overview list. */
  public record BankEntry(
      BankAccountCacheService.View view,
      BankDefinition def,
      boolean isOwner
  ) {}

  @SuppressWarnings({"unchecked", "rawtypes"})
  public BankOverviewMenuView(ServiceAccessor accessor) {
    // A mutable renderer box lets us reference `this` after super() returns.
    this(accessor, new PageItemRenderer[1]);
  }

  private BankOverviewMenuView(ServiceAccessor accessor, PageItemRenderer<BankEntry>[] rendererBox) {
    super(
        BankOverviewMenuDefinition.KEY,
        rowsToSize(6),
        PageBounds.of(1, 1, 7, 4),
        "bank-overview",
        List.of(),
        (ctx, entry, idx) -> rendererBox[0].render(ctx, entry, idx)
    );

    fill(createBackgroundPane());

    this.accessor = accessor;
    this.itemProviderService = NexEconomyPlugin.getNexLogicService().getService(ItemProviderService.class);
    this.itemService = accessor.getService(ItemService.class);
    this.bankRegistry = accessor.getService(BankRegistryService.class);

    FileReaderService fileReader = accessor.getService(FileReaderService.class);
    this.config = fileReader.load(Path.of(CONFIG_PATH), CONFIG_PATH, false);

    // Wire renderer now that `this` is available.
    rendererBox[0] = this::renderEntry;

    // Sort control from config
    ConfigurationSection sorting = config.getSection("sorting");
    String defaultMode = sorting != null ? sorting.getString("default-mode", "owner") : "owner";
    ConfigurationSection sortModes = sorting != null ? sorting.getSection("modes") : null;
    String ownerLabel = sortModes != null ? sortModes.getString("owner", "Owned → Member") : "Owned → Member";
    String memberLabel = sortModes != null ? sortModes.getString("member", "Member → Owned") : "Member → Owned";

    this.sortControl = addSortControl(
        config.getInt("layout.slots.sort-button", 8),
        config.getString("items.sort-button.display-name", "<yellow>Bank Sorting"),
        Material.COMPARATOR,
        BasicPageSortControl.<BankEntry>builder("bank-sort")
            .mode("owner", ownerLabel, (a, b) -> Boolean.compare(!a.isOwner(), !b.isOwner()))
            .mode("member", memberLabel, (a, b) -> Boolean.compare(!b.isOwner(), !a.isOwner()))
            .defaultMode(defaultMode)
            .build()
    );

    setTitle(MINI.deserialize(config.getString("menu.title", "<yellow>Bank Overview</yellow>")));

    // Close button
    int backSlot = config.getInt("layout.slots.back", 49);
    ConfigurationSection backCfg = config.getSection("items.back");
    ItemStack backItem = backCfg != null ? buildItem(backCfg, Map.of()) : new ItemStack(Material.REDSTONE);
    addElement(backSlot, new StaticMenuElement(backItem,
        (ctx, event) -> ctx.menuService().close(ctx.viewer())));

    // Paging navigation
    addElement(config.getInt("layout.entries.navigation.previous-slot", 45), new PreviousPageElement());
    addElement(config.getInt("layout.entries.navigation.next-slot", 53), new NextPageElement());
  }

  @Override
  protected List<BankEntry> resolveItems(MenuContext context) {
    Player viewer = context.viewer();
    List<BankEntry> live = buildEntries(viewer);
    String modeId = activeModeId(sortControl, context);
    Comparator<BankEntry> comparator =
        sortControl.comparatorFor(modeId, key(), viewer.getUniqueId());
    live.sort(comparator);
    return live;
  }

  // ─── Entry renderer ──────────────────────────────────────────────────────

  private MenuElement renderEntry(MenuContext context, BankEntry entry, int idx) {
    return new StaticMenuElement(buildBankEntryItem(entry), (ctx, event) -> {
      UUID ownerUuid = entry.view().account().getOwnerUuid();
      context.viewer().sendMessage(Component.text("Open Bank Detail Menu: " + entry.def().idLower() + " - " + ownerUuid));
      //ctx.menuService().open(ctx.viewer(), new BankDetailMenuView(accessor, entry.def().idLower(), ownerUuid));
    });
  }

  // ─── Item builders ───────────────────────────────────────────────────────

  private ItemStack buildBankEntryItem(BankEntry entry) {
    ConfigurationSection section = config.getSection("items.bank-entry");
    UUID ownerUuid = entry.view().account().getOwnerUuid();
    String balance = entry.view().balance() != null
        ? AmountNotation.formatShort(entry.view().balance(), 2) : "0";
    Map<String, String> ph = Map.of(
        "bank-name", stripMini(entry.def().nameMiniMessage()),
        "type-label", entry.def().memberSystem().enabled() ? "Shared" : "Private",
        "owner-name", resolvePlayerName(ownerUuid),
        "balance", balance,
        "currency", entry.def().currencyIdLower()
    );
    ItemStack item = section != null ? buildItem(section, ph) : new ItemStack(Material.PLAYER_HEAD);
    // Set skull owner for player-head items
    if (item.getItemMeta() instanceof SkullMeta skullMeta) {
      skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerUuid));
      item.setItemMeta(skullMeta);
    }
    return item;
  }

  /** Builds an {@link ItemStack} from a config section with optional placeholder substitution. */
  private ItemStack buildItem(ConfigurationSection section, Map<String, String> ph) {
    String itemId = section.getString("item", "minecraft:stone");
    ItemStack init = itemProviderService.getItem(itemId)
        .orElseGet(() -> new ItemStack(Material.STONE));
    var builder = itemService.builder(init);
    String name = applyPh(section.getString("display-name", ""), ph);
    if (!name.isBlank()) builder.name(name);
    List<String> lore = section.getStringList("lore");
    if (!lore.isEmpty()) builder.lore(b -> lore.forEach(l -> b.line(applyPh(l, ph))));
    return builder.build();
  }

  private static ItemStack createBackgroundPane() {
    ItemStack itemStack = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
    ItemMeta meta = itemStack.getItemMeta();
    if (meta != null) {
      meta.displayName(Component.empty());
      itemStack.setItemMeta(meta);
    }
    return itemStack;
  }

  // ─── Utilities ───────────────────────────────────────────────────────────

  private List<BankEntry> buildEntries(Player viewer) {
    EcoPlayer eco = EcoPlayer.of(viewer);
    List<BankEntry> entries = new ArrayList<>();
    if (eco == null) return entries;
    for (BankAccountCacheService.View v : eco.banks().allCached()) {
      if (v.account() == null) continue;
      bankRegistry.bank(v.account().getBankIdLower())
          .ifPresent(def -> entries.add(new BankEntry(v, def, true)));
    }
    for (BankAccountCacheService.View v : eco.banks().memberBankViews()) {
      if (v.account() == null) continue;
      bankRegistry.bank(v.account().getBankIdLower())
          .ifPresent(def -> entries.add(new BankEntry(v, def, false)));
    }
    return entries;
  }

  private static String applyPh(String text, Map<String, String> ph) {
    if (text == null) return "";
    for (Map.Entry<String, String> e : ph.entrySet()) {
      text = text.replace("<" + e.getKey() + ">", e.getValue());
    }
    return text;
  }

  private static String resolvePlayerName(UUID uuid) {
    Player online = Bukkit.getPlayer(uuid);
    if (online != null) return online.getName();
    String name = Bukkit.getOfflinePlayer(uuid).getName();
    return name != null ? name : uuid.toString();
  }

  private static String stripMini(String miniMessage) {
    return MINI.stripTags(miniMessage != null ? miniMessage : "");
  }
}

