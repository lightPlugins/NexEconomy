package io.nexstudios.nexeconomy.service.menu.bank.member.view;

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
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.menu.bank.detail.view.BankDetailMenuView;
import io.nexstudios.nexeconomy.service.menu.bank.member.BankMemberMenuDefinition;
import io.nexstudios.nexeconomy.service.menu.bank.member.view.BankMemberRoleMenuView;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.items.ItemProviderService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Paged view of current bank members.
 * Left-click → change role; Right-click → kick member.
 */
public final class BankMemberMenuView extends ControlledPagedMenuView<BankMemberEntity> {

  private static final MenuKey KEY = BankMemberMenuDefinition.KEY;
  private static final String CONFIG_PATH = "inventories/bank-member.yml";
  private static final MiniMessage MINI = MiniMessage.miniMessage();

  private final ServiceAccessor accessor;
  private final String bankId;
  private final UUID ownerUuid;
  private final UUID viewerUuid;
  private final BankDefinition def;
  private final BankAccountCacheService bankCache;
  private final BankService bankService;
  private final FileConfiguration config;
  private final ItemProviderService itemProvider;
  private final ItemService itemService;

  public BankMemberMenuView(ServiceAccessor accessor, String bankId, UUID ownerUuid, UUID viewerUuid) {
    this(accessor, bankId, ownerUuid, viewerUuid, new PageItemRenderer[1]);
  }

  @SuppressWarnings("unchecked")
  private BankMemberMenuView(ServiceAccessor accessor, String bankId, UUID ownerUuid, UUID viewerUuid,
                              PageItemRenderer<BankMemberEntity>[] rendererBox) {
    super(KEY, rowsToSize(6), PageBounds.of(1, 2, 7, 3), "bank-member",
        List.of(), (ctx, entry, idx) -> rendererBox[0].render(ctx, entry, idx));

    this.accessor    = accessor;
    this.bankId      = bankId.toLowerCase(Locale.ROOT);
    this.ownerUuid   = ownerUuid;
    this.viewerUuid  = viewerUuid;
    this.bankService = accessor.getService(BankService.class);

    FileReaderService fileReader     = accessor.getService(FileReaderService.class);
    this.itemService                 = accessor.getService(ItemService.class);
    BankRegistryService bankRegistry = accessor.getService(BankRegistryService.class);
    this.bankCache                   = accessor.getService(BankAccountCacheService.class);
    this.itemProvider                = NexEconomyPlugin.getNexLogicService().getService(ItemProviderService.class);

    this.config = fileReader.load(Path.of(CONFIG_PATH), CONFIG_PATH, false);
    this.def    = bankRegistry.bank(this.bankId).orElse(null);

    rendererBox[0] = this::renderEntry;

    setTitle(MINI.deserialize(config.getString("menu.title", "<yellow>Manage Members</yellow>")));

    ConfigurationSection fillCfg = config.getSection("items.fill");
    if (fillCfg != null) fill(buildItem(fillCfg, Map.of()));

    // Info item
    int infoSlot = config.getInt("layout.slots.info", 4);
    BankAccountCacheService.View view = bankCache.get(this.bankId, ownerUuid);
    int memberCount = (view != null && view.members() != null) ? view.members().size() : 0;
    String bankName  = def != null ? MINI.stripTags(def.nameMiniMessage()) : this.bankId;
    String ownerName = resolvePlayerName(ownerUuid);
    ConfigurationSection infoCfg = config.getSection("items.info");
    if (infoCfg != null) {
      Map<String, String> infoPh = Map.of(
          "bank-name",    bankName,
          "owner-name",   ownerName,
          "member-count", String.valueOf(memberCount)
      );
      addElement(infoSlot, new StaticMenuElement(buildItem(infoCfg, infoPh), (ctx, event) -> {}));
    }

    // Navigation
    int prevSlot = config.getInt("layout.members.navigation.previous-slot", 45);
    int nextSlot = config.getInt("layout.members.navigation.next-slot", 53);
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
  protected List<BankMemberEntity> resolveItems(MenuContext context) {
    BankAccountCacheService.View view = bankCache.get(bankId, ownerUuid);
    if (view == null || view.members() == null) return List.of();
    List<BankMemberEntity> result = new ArrayList<>(view.members());
    result.removeIf(m -> m == null || ownerUuid.equals(m.getMemberUuid()));
    return result;
  }

  private MenuElement renderEntry(MenuContext context, BankMemberEntity member, int idx) {
    UUID memberUuid = member.getMemberUuid();
    String memberName = resolvePlayerName(memberUuid);
    String roleId  = member.getRoleIdLower() != null ? member.getRoleIdLower() : "member";
    String roleName = roleId;

    BankDefinition.RoleDefinition roleDef = null;
    if (def != null && def.memberSystem() != null && def.memberSystem().rolesByIdLower() != null) {
      roleDef = def.memberSystem().rolesByIdLower().get(roleId.toLowerCase(Locale.ROOT));
      if (roleDef != null) roleName = MINI.stripTags(roleDef.nameMiniMessage());
    }

    Map<String, String> ph = new HashMap<>();
    ph.put("member-name",  memberName);
    ph.put("member-uuid",  memberUuid != null ? memberUuid.toString() : "");
    ph.put("role-name",    roleName);
    ph.put("priority",     roleDef != null ? String.valueOf(roleDef.priority()) : "0");
    ph.put("can-deposit",  roleDef != null && roleDef.canDeposit() ? "✔" : "✗");
    ph.put("can-withdraw", roleDef != null && roleDef.withdraw() != null && roleDef.withdraw().canWithdraw() ? "✔" : "✗");
    ph.put("can-invite",   roleDef != null && roleDef.canInvite() ? "✔" : "✗");
    ph.put("can-kick",     roleDef != null && roleDef.canKick() ? "✔" : "✗");

    ConfigurationSection entryCfg = config.getSection("items.member-entry");
    ItemStack item = entryCfg != null ? buildItem(entryCfg, ph) : new ItemStack(Material.PLAYER_HEAD);
    if (item.getItemMeta() instanceof SkullMeta skull && memberUuid != null) {
      skull.setOwningPlayer(Bukkit.getOfflinePlayer(memberUuid));
      item.setItemMeta(skull);
    }

    return new StaticMenuElement(item, (ctx, event) -> {
      Player p = ctx.viewer();
      if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) {
        // Kick
        bankService.leave(bankId, ownerUuid, memberUuid)
            .thenAccept(ok -> p.sendMessage(Component.text(
                ok ? memberName + " has been removed from the bank." : "Could not remove " + memberName + ".")))
            .exceptionally(ex -> { p.sendMessage(Component.text("Kick failed: " + rootMessage(ex))); return null; });
        ctx.menuService().open(p, new BankMemberMenuView(accessor, bankId, ownerUuid, viewerUuid));
      } else {
        // Open role change
        ctx.menuService().open(p,
            new BankMemberRoleMenuView(accessor, bankId, ownerUuid, viewerUuid, memberUuid));
      }
    });
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
    if (uuid == null) return "unknown";
    Player p = Bukkit.getPlayer(uuid);
    if (p != null) return p.getName();
    String name = Bukkit.getOfflinePlayer(uuid).getName();
    return name != null ? name : uuid.toString();
  }

  private static String rootMessage(Throwable t) {
    Throwable r = t;
    for (int i = 0; i < 8 && r != null && r.getCause() != null; i++) r = r.getCause();
    return r != null && r.getMessage() != null ? r.getMessage() : "Unknown error";
  }
}




