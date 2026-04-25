package io.nexstudios.nexeconomy.service.menu.bank.invite.view;

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
import io.nexstudios.nexeconomy.domain.EcoPlayer;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.menu.bank.detail.view.BankDetailMenuView;
import io.nexstudios.nexeconomy.service.menu.bank.invite.BankInvitePlayerMenuDefinition;
import io.nexstudios.nexeconomy.service.menu.bank.invite.view.BankInviteRoleMenuView;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.items.ItemProviderService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Paged view of all online players eligible to be invited to a bank.
 * Clicking a player head opens {@link BankInviteRoleMenuView} for role selection.
 * All reads go through {@link EcoPlayer} / BankContainer – no direct cache/registry service calls.
 */
public final class BankInvitePlayerMenuView extends ControlledPagedMenuView<Player> {

  private static final MenuKey KEY = BankInvitePlayerMenuDefinition.KEY;
  private static final String CONFIG_PATH = "inventories/bank-invite-player.yml";
  private static final MiniMessage MINI = MiniMessage.miniMessage();

  private final ServiceAccessor accessor;
  private final String bankId;
  private final UUID ownerUuid;
  private final UUID viewerUuid;
  private final BankDefinition def;
  private final FileConfiguration config;
  private final ItemProviderService itemProvider;
  private final ItemService itemService;

  public BankInvitePlayerMenuView(ServiceAccessor accessor, String bankId, UUID ownerUuid, UUID viewerUuid) {
    this(accessor, bankId, ownerUuid, viewerUuid, new PageItemRenderer[1]);
  }

  @SuppressWarnings("unchecked")
  private BankInvitePlayerMenuView(ServiceAccessor accessor, String bankId, UUID ownerUuid, UUID viewerUuid,
                                    PageItemRenderer<Player>[] rendererBox) {
    super(KEY, rowsToSize(6), PageBounds.of(1, 2, 7, 3), "bank-invite-player",
        List.of(), (ctx, entry, idx) -> rendererBox[0].render(ctx, entry, idx));

    this.accessor   = accessor;
    this.bankId     = bankId.toLowerCase(Locale.ROOT);
    this.ownerUuid  = ownerUuid;
    this.viewerUuid = viewerUuid;

    FileReaderService fileReader = accessor.getService(FileReaderService.class);
    this.itemService             = accessor.getService(ItemService.class);
    this.itemProvider            = NexEconomyPlugin.getNexLogicService().getService(ItemProviderService.class);

    this.config = fileReader.load(Path.of(CONFIG_PATH), CONFIG_PATH, false);

    EcoPlayer ownerEco = EcoPlayer.of(ownerUuid);
    this.def = ownerEco != null ? ownerEco.banks().definition(this.bankId) : null;

    rendererBox[0] = this::renderEntry;

    setTitle(MINI.deserialize(config.getString("menu.title", "<yellow>Invite Player</yellow>")));

    ConfigurationSection fillCfg = config.getSection("items.fill");
    if (fillCfg != null) fill(buildItem(fillCfg, Map.of()));

    // Info item
    int infoSlot = config.getInt("layout.slots.info", 4);
    String bankName  = def != null ? MINI.stripTags(def.nameMiniMessage()) : this.bankId;
    String ownerName = resolvePlayerName(ownerUuid);
    Map<String, String> infoPh = Map.of(
        "bank-name",    bankName,
        "owner-name",   ownerName,
        "online-count", String.valueOf(Bukkit.getOnlinePlayers().size())
    );
    ConfigurationSection infoCfg = config.getSection("items.info");
    if (infoCfg != null) addElement(infoSlot, new StaticMenuElement(buildItem(infoCfg, infoPh), (ctx, event) -> {}));

    // Navigation
    int prevSlot = config.getInt("layout.players.navigation.previous-slot", 45);
    int nextSlot = config.getInt("layout.players.navigation.next-slot", 53);
    addElement(prevSlot, new PreviousPageElement());
    addElement(nextSlot, new NextPageElement());

    // Back
    int backSlot = config.getInt("layout.slots.back", 49);
    ConfigurationSection backCfg = config.getSection("items.back");
    ItemStack backItem = backCfg != null ? buildItem(backCfg, Map.of()) : new ItemStack(Material.ARROW);
    addElement(backSlot, new StaticMenuElement(backItem,
        (ctx, event) -> ctx.menuService().open(ctx.viewer(),
            new BankDetailMenuView(accessor, bankId, ownerUuid, viewerUuid))));
  }

  @Override
  protected List<Player> resolveItems(MenuContext context) {
    EcoPlayer ownerEco = EcoPlayer.of(ownerUuid);
    List<BankMemberEntity> members = ownerEco != null ? ownerEco.banks().members(bankId) : null;
    List<UUID> memberUuids = new ArrayList<>();
    memberUuids.add(ownerUuid);
    if (members != null) {
      members.forEach(m -> { if (m != null && m.getMemberUuid() != null) memberUuids.add(m.getMemberUuid()); });
    }

    List<Player> result = new ArrayList<>();
    for (Player p : Bukkit.getOnlinePlayers()) {
      if (!memberUuids.contains(p.getUniqueId())) result.add(p);
    }
    return result;
  }

  private MenuElement renderEntry(MenuContext context, Player target, int idx) {
    Map<String, String> ph = Map.of(
        "player-name", target.getName(),
        "player-uuid", target.getUniqueId().toString()
    );
    ConfigurationSection entryCfg = config.getSection("items.player-entry");
    ItemStack item = entryCfg != null ? buildItem(entryCfg, ph) : new ItemStack(Material.PLAYER_HEAD);
    if (item.getItemMeta() instanceof SkullMeta skull) {
      skull.setOwningPlayer(Bukkit.getOfflinePlayer(target.getUniqueId()));
      item.setItemMeta(skull);
    }
    return new StaticMenuElement(item, (ctx, event) ->
        ctx.menuService().open(ctx.viewer(),
            new BankInviteRoleMenuView(accessor, bankId, ownerUuid, viewerUuid, target.getUniqueId())));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

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

  private static String resolvePlayerName(UUID uuid) {
    Player p = Bukkit.getPlayer(uuid);
    if (p != null) return p.getName();
    String name = Bukkit.getOfflinePlayer(uuid).getName();
    return name != null ? name : uuid.toString();
  }
}

