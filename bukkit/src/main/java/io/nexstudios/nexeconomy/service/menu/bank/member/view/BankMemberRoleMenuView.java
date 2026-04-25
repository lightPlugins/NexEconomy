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
import io.nexstudios.nexeconomy.domain.EcoPlayer;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.menu.bank.member.BankMemberRoleMenuDefinition;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.items.ItemProviderService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Paged role-selection view for changing a member's role.
 */
public final class BankMemberRoleMenuView extends ControlledPagedMenuView<Map.Entry<String, BankDefinition.RoleDefinition>> {

  private static final MenuKey KEY = BankMemberRoleMenuDefinition.KEY;
  private static final String CONFIG_PATH = "inventories/bank-member-role.yml";
  private static final MiniMessage MINI = MiniMessage.miniMessage();

  private final ServiceAccessor accessor;
  private final String bankId;
  private final UUID ownerUuid;
  private final UUID viewerUuid;
  private final UUID targetMemberUuid;
  private final BankDefinition def;
  private final BankService bankService;
  private final FileConfiguration config;
  private final ItemProviderService itemProvider;
  private final ItemService itemService;
  private final String currentRoleId;

  public BankMemberRoleMenuView(ServiceAccessor accessor, String bankId, UUID ownerUuid,
                                 UUID viewerUuid, UUID targetMemberUuid) {
    this(accessor, bankId, ownerUuid, viewerUuid, targetMemberUuid, new PageItemRenderer[1]);
  }

  @SuppressWarnings("unchecked")
  private BankMemberRoleMenuView(ServiceAccessor accessor, String bankId, UUID ownerUuid,
                                  UUID viewerUuid, UUID targetMemberUuid,
                                  PageItemRenderer<Map.Entry<String, BankDefinition.RoleDefinition>>[] rendererBox) {
    super(KEY, rowsToSize(6), PageBounds.of(1, 2, 7, 3), "bank-member-role",
        List.of(), (ctx, entry, idx) -> rendererBox[0].render(ctx, entry, idx));

    this.accessor          = accessor;
    this.bankId            = bankId.toLowerCase(Locale.ROOT);
    this.ownerUuid         = ownerUuid;
    this.viewerUuid        = viewerUuid;
    this.targetMemberUuid  = targetMemberUuid;
    this.bankService       = accessor.getService(BankService.class);

    FileReaderService fileReader      = accessor.getService(FileReaderService.class);
    this.itemService                  = accessor.getService(ItemService.class);
    this.itemProvider                 = NexEconomyPlugin.getNexLogicService().getService(ItemProviderService.class);

    this.config = fileReader.load(Path.of(CONFIG_PATH), CONFIG_PATH, false);
    EcoPlayer ownerEco = EcoPlayer.of(ownerUuid);
    this.def    = ownerEco != null ? ownerEco.banks().definition(this.bankId) : null;

    // Resolve current role of the target member
    List<BankMemberEntity> memberList = ownerEco != null ? ownerEco.banks().members(this.bankId) : null;
    String foundRole = "member";
    if (memberList != null) {
      for (BankMemberEntity m : memberList) {
        if (targetMemberUuid.equals(m.getMemberUuid()) && m.getRoleIdLower() != null) {
          foundRole = m.getRoleIdLower();
          break;
        }
      }
    }
    this.currentRoleId = foundRole;

    rendererBox[0] = this::renderEntry;

    setTitle(MINI.deserialize(config.getString("menu.title", "<yellow>Select Role</yellow>")));

    ConfigurationSection fillCfg = config.getSection("items.fill");
    if (fillCfg != null) fill(buildItem(fillCfg, Map.of()));

    // Info item
    int infoSlot = config.getInt("layout.slots.info", 4);
    String bankName    = def != null ? MINI.stripTags(def.nameMiniMessage()) : this.bankId;
    String ownerName   = resolvePlayerName(ownerUuid);
    String targetName  = resolvePlayerName(targetMemberUuid);
    String currentRole = resolveRoleName(currentRoleId);
    ConfigurationSection infoCfg = config.getSection("items.info");
    if (infoCfg != null) {
      Map<String, String> infoPh = Map.of(
          "bank-name",    bankName,
          "owner-name",   ownerName,
          "target-name",  targetName,
          "current-role", currentRole
      );
      addElement(infoSlot, new StaticMenuElement(buildItem(infoCfg, infoPh), (ctx, event) -> {}));
    }

    int prevSlot = config.getInt("layout.roles.navigation.previous-slot", 45);
    int nextSlot = config.getInt("layout.roles.navigation.next-slot", 53);
    addElement(prevSlot, new PreviousPageElement());
    addElement(nextSlot, new NextPageElement());

    int backSlot = config.getInt("layout.slots.back", 49);
    ConfigurationSection backCfg = config.getSection("items.back");
    ItemStack backItem = backCfg != null ? buildItem(backCfg, Map.of()) : new ItemStack(Material.ARROW);
    addElement(backSlot, new StaticMenuElement(backItem,
        (ctx, event) -> ctx.menuService().open(ctx.viewer(),
            new BankMemberMenuView(accessor, bankId, ownerUuid, viewerUuid))));
  }

  @Override
  protected List<Map.Entry<String, BankDefinition.RoleDefinition>> resolveItems(MenuContext context) {
    if (def == null || def.memberSystem() == null || !def.memberSystem().enabled()) return List.of();
    if (def.memberSystem().rolesByIdLower() == null) return List.of();
    List<Map.Entry<String, BankDefinition.RoleDefinition>> result =
        new ArrayList<>(def.memberSystem().rolesByIdLower().entrySet());
    result.sort(Comparator.comparingInt(e -> -e.getValue().priority()));
    return result;
  }

  private MenuElement renderEntry(MenuContext context, Map.Entry<String, BankDefinition.RoleDefinition> entry, int idx) {
    BankDefinition.RoleDefinition role = entry.getValue();
    String roleName    = MINI.stripTags(role.nameMiniMessage());
    String currentRole = resolveRoleName(currentRoleId);
    boolean isCurrent  = entry.getKey().equalsIgnoreCase(currentRoleId);

    Map<String, String> ph = new HashMap<>();
    ph.put("role-name",     roleName);
    ph.put("role-id",       entry.getKey());
    ph.put("priority",      String.valueOf(role.priority()));
    ph.put("can-deposit",   role.canDeposit() ? "✔" : "✗");
    ph.put("can-withdraw",  role.withdraw() != null && role.withdraw().canWithdraw() ? "✔" : "✗");
    ph.put("can-invite",    role.canInvite() ? "✔" : "✗");
    ph.put("can-kick",      role.canKick() ? "✔" : "✗");
    ph.put("current-role",  isCurrent ? "✔ Current" : currentRole);

    ConfigurationSection entryCfg = config.getSection("items.role-entry");
    ItemStack item = entryCfg != null ? buildItem(entryCfg, ph) : new ItemStack(Material.NAME_TAG);

    final String roleId = entry.getKey();
    return new StaticMenuElement(item, (ctx, event) -> {
      Player p = ctx.viewer();
      // fire-and-forget – no future chain
      bankService.changeMemberRole(bankId, ownerUuid, viewerUuid, targetMemberUuid, roleId);
      p.sendMessage(Component.text(resolvePlayerName(targetMemberUuid) + "'s role changed to " + roleName + "."));
      ctx.menuService().open(p, new BankMemberMenuView(accessor, bankId, ownerUuid, viewerUuid));
    });
  }

  private String resolveRoleName(String roleId) {
    if (def == null || def.memberSystem() == null || def.memberSystem().rolesByIdLower() == null) return roleId;
    BankDefinition.RoleDefinition r = def.memberSystem().rolesByIdLower().get(roleId.toLowerCase(Locale.ROOT));
    return r != null ? MINI.stripTags(r.nameMiniMessage()) : roleId;
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

