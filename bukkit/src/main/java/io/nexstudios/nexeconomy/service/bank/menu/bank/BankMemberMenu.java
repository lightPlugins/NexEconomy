package io.nexstudios.nexeconomy.service.bank.menu.bank;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.dialogservice.api.ConfirmDialog;
import io.nexstudios.dialogservice.service.ConfirmDialogService;
import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.menuservice.common.api.*;
import io.nexstudios.menuservice.common.api.builder.MenuDefinitionBuilder;
import io.nexstudios.menuservice.common.api.interaction.ClickAction;
import io.nexstudios.menuservice.common.api.interaction.InteractionPolicies;
import io.nexstudios.menuservice.common.api.item.MenuItem;
import io.nexstudios.menuservice.common.api.item.PlannedMenuItemSupplier;
import io.nexstudios.menuservice.common.api.page.PageAlignment;
import io.nexstudios.menuservice.common.api.page.PageBounds;
import io.nexstudios.menuservice.common.api.page.PageNavigation;
import io.nexstudios.menuservice.common.api.page.PageSource;
import io.nexstudios.menuservice.common.api.page.PagedAreaDefinition;
import io.nexstudios.menuservice.common.api.registry.DuplicateStrategy;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.provider.bank.BankProviderService;
import io.nexstudios.nexeconomy.provider.bank.BankResponse;
import io.nexstudios.nexeconomy.service.bank.effects.BankClickEffectService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.menu.BankMemberFlowState;
import io.nexstudios.nexeconomy.service.bank.menu.extra.BankExtraItemSupport;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.heads.HeadService;
import io.nexstudios.nexlogic.bukkit.services.items.config.ConfigItemService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Dependencies({
    ItemService.class,
    MenuService.class,
    BankProviderService.class,
    LoggerService.class,
    FileReaderService.class,
    ConfirmDialogService.class
})
public final class BankMemberMenu {

  public static final MenuKey KEY = MenuKey.of("nexeconomy", "bank-members");

  private static final Path CONFIG_PATH = Path.of("inventories/bank-member.yml");
  private static final String CONFIG_FILE = "inventories/bank-member.yml";
  private static final String AREA_ID = "members";

  private static final int DEFAULT_ROWS = 6;
  private static final int DEFAULT_INFO_SLOT = 4;
  private static final int DEFAULT_BACK_SLOT = 49;

  private static ServiceAccessor servicesRef;
  private static Plugin plugin;
  private static ItemService itemService;
  private static FileReaderService fileReaderService;
  private static ConfigItemService configItemService;
  private static HeadService headService;
  private static BankProviderService bankProvider;
  private static BankClickEffectService clickEffects;
  private static ConfirmDialogService confirmDialogs;
  private static FileConfiguration config;
  private static ItemStack infoTemplate;
  private static ItemStack emptyTemplate;
  private static ItemStack memberEntryTemplate;
  private static ItemStack previousTemplate;
  private static ItemStack nextTemplate;
  private static ItemStack backTemplate;
  private static List<BankExtraItemSupport.ExtraItemBinding> EXTRA_ITEMS = List.of();
  private static int SLOT_INFO = DEFAULT_INFO_SLOT;
  private static int SLOT_BACK = DEFAULT_BACK_SLOT;

  private BankMemberMenu() {}

  public static void register(@NotNull ServiceAccessor services) {
    MenuService menuService = services.getService(MenuService.class);
    servicesRef = services;
    plugin = services.getService(io.nexstudios.framework.paper.services.plugin.PaperPluginService.class).plugin();
    itemService = services.getService(ItemService.class);
    fileReaderService = services.getService(FileReaderService.class);
    configItemService = NexEconomyPlugin.getNexLogicService().getService(ConfigItemService.class);
    headService = NexEconomyPlugin.getNexLogicService().getService(HeadService.class);
    bankProvider = services.getService(BankProviderService.class);
    clickEffects = services.getService(BankClickEffectService.class);
    confirmDialogs = services.getService(ConfirmDialogService.class);
    config = loadConfig();

    SLOT_INFO = config.getInt("layout.slots.info", DEFAULT_INFO_SLOT);
    SLOT_BACK = config.getInt("layout.slots.back", DEFAULT_BACK_SLOT);

    ItemStack fillTemplate = configuredItem(config, "items.fill", Material.BLACK_STAINED_GLASS_PANE);
    infoTemplate = configuredItem(config, "items.info", Material.PAPER);
    emptyTemplate = configuredItem(config, "items.empty", Material.BARRIER);
    memberEntryTemplate = configuredItem(config, "items.member-entry", Material.PLAYER_HEAD);
    previousTemplate = configuredItem(config, "items.navigation.previous", Material.ARROW);
    nextTemplate = configuredItem(config, "items.navigation.next", Material.ARROW);
    backTemplate = configuredItem(config, "items.back", Material.ARROW);
    EXTRA_ITEMS = BankExtraItemSupport.loadBindings(config, configItemService);

    var definition = MenuDefinitionBuilder.create()
        .key(KEY)
        .title(toLegacyTitle(config.getString("menu.title", "<yellow>Manage Members</yellow>")))
        .rows(config.getInt("menu.rows", DEFAULT_ROWS))
        .refreshInterval(parseRefreshInterval(config.getString("menu.refresh-interval", "1")))
        .interactionPolicy(InteractionPolicies.locked())
        .fillEmptySlotsWith(MenuItem.of(fillTemplate))
        .interactionHooks(new MenuInteractionHooks() {
          @Override
          public void onClose(MenuKey key, ViewerRef viewer, CloseReason reason) {
            if (!BankMemberFlowState.consumeTransition(viewer.uniqueId())) {
              BankMemberFlowState.clear(viewer.uniqueId());
            }
          }
        })
        .addPagedArea(buildPagedArea())
        .populator(BankMemberMenu::populate)
        .build();

    menuService.registry().register(definition, DuplicateStrategy.REPLACE);
  }

  public static void open(@NotNull ServiceAccessor services, @NotNull ViewerRef viewer, @NotNull String bankId, @NotNull UUID ownerUuid, boolean ownerBank) {
    BankMemberFlowState.start(viewer.uniqueId(), bankId, ownerUuid, ownerBank);
    services.getService(MenuService.class).open(viewer, KEY);
  }

  public static void refreshIfOpen(UUID viewerUuid) {
    if (viewerUuid == null || servicesRef == null) {
      return;
    }

    Player player = Bukkit.getPlayer(viewerUuid);
    if (player == null) {
      return;
    }

    MenuService menuService = servicesRef.getService(MenuService.class);
    if (menuService == null) {
      return;
    }

    menuService.findOpenView(ViewerRef.of(player.getUniqueId(), player.getName()))
        .ifPresent(MenuView::requestRefresh);
  }

  private static void populate(MenuPopulateContext ctx) {
    ViewerRef viewer = ctx.viewer();
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    BankMemberFlowState.MemberContext state = BankMemberFlowState.get(viewer.uniqueId());

    if (player == null || state == null) {
      setInfo(ctx, Component.text("Members"), List.of("No member context is available."));
      setBackButton(ctx, state);
      return;
    }

    setInfo(ctx, state);
    setBackButton(ctx, state);
    BankExtraItemSupport.populate(ctx, servicesRef, EXTRA_ITEMS, "bank-members");
  }

  private static void setInfo(MenuPopulateContext ctx, BankMemberFlowState.MemberContext state) {
    BankDefinition definition = resolveDefinition(state);
    String bankName = definition == null ? state.bankId() : definition.nameMiniMessage();
    String ownerName = nameOrUuid(state.ownerUuid());
    int memberCount = loadMembers(state, state.ownerUuid(), ctx.viewer().uniqueId()).size();

    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("bank-name", bankName),
        Placeholder.parsed("owner-name", ownerName),
        Placeholder.parsed("member-count", String.valueOf(memberCount))
    ));

    ItemStack stack = renderConfiguredItem(infoTemplate, "items.info", "Members", resolver);
    ctx.slot(SLOT_INFO).setPlannedItem(() -> MenuItem.of(stack));
  }

  private static void setInfo(MenuPopulateContext ctx, Component title, List<String> lore) {
    ItemStack stack = itemService.builder(new ItemStack(Material.PAPER))
        .amount(1)
        .name(title)
        .lore(builder -> {
          if (lore != null) {
            for (String line : lore) {
              builder.line(line);
            }
          }
        })
        .build();
    ctx.slot(SLOT_INFO).setPlannedItem(() -> MenuItem.of(stack));
  }

  private static void setBackButton(MenuPopulateContext ctx, BankMemberFlowState.MemberContext state) {
    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("bank-name", state == null ? "Unknown" : state.bankId())
    ));

    ItemStack stack = renderConfiguredItem(backTemplate, "items.back", "Back", resolver);
    MenuSlot slot = ctx.slot(SLOT_BACK);
    slot.setPlannedItem(() -> MenuItem.of(stack));
    slot.onClick(clickCtx -> {
      clickCtx.cancel();
      BankMemberFlowState.clear(clickCtx.viewer().uniqueId());
      triggerGeneralClick(clickCtx.viewer().uniqueId());
      if (servicesRef != null) {
        if (state != null) {
          BankDetailMenu.open(servicesRef, clickCtx.viewer(), state.bankId(), state.ownerUuid(), state.ownerBank());
        } else {
          BankOverviewMenu.open(servicesRef, clickCtx.viewer());
        }
      }
    });
  }

  private static PagedAreaDefinition<MemberEntry> buildPagedArea() {
    PageSource<MemberEntry> source = (menuKey, viewer) -> buildEntries(viewer.uniqueId());
    PageBounds bounds = readBounds(config);
    PageNavigation navigation = buildNavigation(config, previousTemplate, nextTemplate);

    return new PagedAreaDefinition<>(
        AREA_ID,
        bounds,
        source,
        (entry, index) -> entry.placeholder()
            ? () -> MenuItem.of(renderEmptyMemberItem())
            : PlannedMenuItemSupplier.withHead(
                MenuItem.of(renderMemberItem(entry)),
                headService.loadHead(entry.memberUuid())
            ),
        navigation,
        Optional.of((entry, index, clickCtx) -> handleMemberClick(clickCtx, entry))
    );
  }

  private static void handleMemberClick(MenuSlot.MenuClickContext clickCtx, MemberEntry entry) {
    if (clickCtx == null || entry == null) return;
    clickCtx.cancel();
    if (entry.placeholder()) {
      return;
    }
    triggerGeneralClick(clickCtx.viewer().uniqueId());

    BankMemberFlowState.MemberContext state = BankMemberFlowState.get(clickCtx.viewer().uniqueId());
    if (state == null) {
      return;
    }

    BankDefinition definition = resolveDefinition(state);
    if (definition == null || definition.memberSystem() == null || !definition.memberSystem().enabled()) {
      return;
    }

    BankDefinition.RoleDefinition actorRole = resolveActorRole(definition, state, clickCtx.viewer().uniqueId());
    BankDefinition.RoleDefinition targetRole = resolveRole(definition.memberSystem().rolesByIdLower(), entry.roleId());
    if (!canManageTarget(actorRole, targetRole) || !entry.canManage()) {
      return;
    }

    if (clickCtx.action() == ClickAction.LEFT_CLICK) {
      BankMemberFlowState.selectTarget(clickCtx.viewer().uniqueId(), entry.memberUuid(), entry.memberName());
      BankMemberFlowState.markTransition(clickCtx.viewer().uniqueId());
      if (servicesRef != null) {
        BankMemberRoleMenu.open(servicesRef, clickCtx.viewer());
      }
      return;
    }

    if (clickCtx.action() == ClickAction.RIGHT_CLICK) {
      openKickConfirm(clickCtx.viewer(), state, entry);
    }
  }

  private static void openKickConfirm(ViewerRef viewer, BankMemberFlowState.MemberContext state, MemberEntry entry) {
    if (confirmDialogs == null) return;
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    if (player == null || state == null || entry == null) return;

    String bankName = resolveBankName(state);
    ConfirmDialog confirm = confirmDialogs.create()
        .title(legacy("<yellow>Kick member?</yellow>"))
        .body(legacy("<gray>Do you really want to kick <yellow>" + escape(entry.memberName()) + "</yellow> from <yellow>" + escape(bankName) + "</yellow>?</gray>"))
        .confirmButton(legacy("Kick"))
        .cancelButton(legacy("Cancel"));

    confirm.show(player).thenAccept(result -> {
      if (!Boolean.TRUE.equals(result)) return;
      if (bankProvider == null) return;

      bankProvider.kick(state.bankId(), state.ownerUuid(), player.getUniqueId(), entry.memberUuid())
          .thenAccept(response -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (response != null && response.isSuccess()) {
              refreshOpenView(viewer);
            }
          }))
          .exceptionally(ex -> null);
    });
  }

  private static void refreshOpenView(ViewerRef viewer) {
    if (servicesRef == null || viewer == null) return;
    MenuService menuService = servicesRef.getService(MenuService.class);
    if (menuService == null) return;

    menuService.findOpenView(viewer).ifPresent(MenuView::requestRefresh);
  }

  private static List<MemberEntry> buildEntries(UUID viewerUuid) {
    BankMemberFlowState.MemberContext state = BankMemberFlowState.get(viewerUuid);
    if (state == null) return List.of();

    BankDefinition definition = resolveDefinition(state);
    if (definition == null || definition.memberSystem() == null || !definition.memberSystem().enabled()) return List.of();

    Map<String, BankDefinition.RoleDefinition> roles = definition.memberSystem().rolesByIdLower();
    List<BankMemberEntity> members = loadMembers(state, state.ownerUuid(), viewerUuid);
    BankDefinition.RoleDefinition actorRole = resolveActorRole(definition, state, viewerUuid);
    if (actorRole == null) return List.of();

    List<MemberEntry> entries = new ArrayList<>();
    for (BankMemberEntity member : members) {
      if (member == null || member.getMemberUuid() == null) continue;
      if (viewerUuid.equals(member.getMemberUuid())) continue;
      if (state.ownerUuid() != null && state.ownerUuid().equals(member.getMemberUuid())) continue;

      BankDefinition.RoleDefinition role = resolveRole(roles, member.getRoleIdLower());
      if (role == null) role = fallbackRole(member.getRoleIdLower());
      boolean canManage = canManageTarget(actorRole, role);

      entries.add(new MemberEntry(
          member.getMemberUuid(),
          nameOrUuid(member.getMemberUuid()),
          member.getRoleIdLower(),
          role.nameMiniMessage(),
          role.priority(),
          role.canDeposit(),
          role.withdraw() != null && role.withdraw().canWithdraw(),
          role.canInvite(),
          role.canKick(),
          canManage,
          false
      ));
    }

    entries.sort(Comparator
        .comparingInt(MemberEntry::priority).reversed()
        .thenComparing(MemberEntry::memberName, String.CASE_INSENSITIVE_ORDER));
    if (entries.isEmpty()) {
      entries.add(MemberEntry.empty());
    }
    return entries;
  }

  private static ItemStack renderMemberItem(MemberEntry entry) {
    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("member-name", entry.memberName()),
        Placeholder.parsed("member-uuid", entry.memberUuid().toString()),
        Placeholder.parsed("role-name", entry.roleName()),
        Placeholder.parsed("role-id", entry.roleId() == null ? "unknown" : entry.roleId()),
        Placeholder.parsed("priority", String.valueOf(entry.priority())),
        Placeholder.parsed("can-deposit", yesNo(entry.canDeposit())),
        Placeholder.parsed("can-withdraw", yesNo(entry.canWithdraw())),
        Placeholder.parsed("can-invite", yesNo(entry.canInvite())),
        Placeholder.parsed("can-kick", yesNo(entry.canKick())),
        Placeholder.parsed("can-manage", yesNo(entry.canManage()))
    ));

    return renderConfiguredItem(memberEntryTemplate, "items.member-entry", entry.memberName(), resolver);
  }

  private static ItemStack renderEmptyMemberItem() {
    TagResolver resolver = TagResolver.resolver(List.of());
    return renderConfiguredItem(emptyTemplate, "items.empty", "No members available", resolver);
  }

  private static BankDefinition resolveDefinition(BankMemberFlowState.MemberContext state) {
    if (state == null || bankProvider == null) return null;
    try {
      BankResponse<BankDefinition> response = bankProvider.bank(state.bankId()).join();
      return response == null ? null : response.payload();
    } catch (Exception ignored) {
      return null;
    }
  }

  private static List<BankMemberEntity> loadMembers(BankMemberFlowState.MemberContext state, UUID ownerUuid, UUID viewerUuid) {
    if (state == null || bankProvider == null) return List.of();
    try {
      BankResponse<List<BankMemberEntity>> response = bankProvider.visibleMembers(state.bankId(), ownerUuid, viewerUuid).join();
      return response == null || response.payload() == null ? List.of() : response.payload();
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static BankDefinition.RoleDefinition resolveActorRole(BankDefinition def, BankMemberFlowState.MemberContext state, UUID viewerUuid) {
    if (def == null || state == null || viewerUuid == null || def.memberSystem() == null || def.memberSystem().rolesByIdLower() == null) {
      return null;
    }

    if (viewerUuid.equals(state.ownerUuid())) {
      return syntheticOwnerRole();
    }

    BankMemberEntity member = null;
    List<BankMemberEntity> members = loadMembers(state, state.ownerUuid(), viewerUuid);
    for (BankMemberEntity m : members) {
      if (m != null && viewerUuid.equals(m.getMemberUuid())) {
        member = m;
        break;
      }
    }
    if (member == null) return null;
    return resolveRole(def.memberSystem().rolesByIdLower(), member.getRoleIdLower());
  }

  private static boolean canManageTarget(BankDefinition.RoleDefinition actorRole, BankDefinition.RoleDefinition targetRole) {
    if (actorRole == null || targetRole == null) return false;
    return actorRole.canKick() && actorRole.priority() > targetRole.priority();
  }

  private static BankDefinition.RoleDefinition resolveRole(Map<String, BankDefinition.RoleDefinition> roles, String roleId) {
    if (roles == null || roles.isEmpty()) return null;
    String key = roleId == null ? "" : roleId.trim().toLowerCase(Locale.ROOT);
    return roles.get(key);
  }

  private static BankDefinition.RoleDefinition fallbackRole(String roleId) {
    String name = roleId == null || roleId.isBlank() ? "unknown" : roleId;
    return new BankDefinition.RoleDefinition(name.toLowerCase(Locale.ROOT), name, -1, true, new BankDefinition.WithdrawDefinition(false, "-1", "-1"), false, false, false, false);
  }

  private static BankDefinition.RoleDefinition syntheticOwnerRole() {
    return new BankDefinition.RoleDefinition("owner", "<red>Owner</red>", Integer.MAX_VALUE, true, new BankDefinition.WithdrawDefinition(true, "-1", "-1"), true, true, true, true);
  }

  private static ItemStack renderConfiguredItem(ItemStack template, String path, String fallbackName, TagResolver resolver) {
    String rawName = config == null ? fallbackName : config.getString(path + ".display-name", fallbackName);
    if (rawName == null || rawName.isBlank()) rawName = fallbackName;

    ItemStack base = template == null ? new ItemStack(Material.PAPER) : template.clone();
    return itemService.builder(base)
        .amount(1)
        .name(MiniMessage.miniMessage().deserialize(rawName, resolver))
        .lore(lore -> {
          lore.tagResolver(resolver);
          lore.build();
        })
        .build();
  }

  private static FileConfiguration loadConfig() {
    return fileReaderService.load(CONFIG_PATH, CONFIG_FILE, true);
  }

  private static ItemStack configuredItem(FileConfiguration cfg, String path, Material fallback) {
    ConfigurationSection section = cfg.getSection(path);
    if (section == null) return new ItemStack(fallback);
    return configItemService.convertSectionToItem(section).orElseGet(() -> new ItemStack(fallback));
  }

  private static PageBounds readBounds(FileConfiguration cfg) {
    ConfigurationSection bounds = cfg.getSection("layout.members.bounds");
    int x = bounds != null ? bounds.getInt("x", 1) : 1;
    int y = bounds != null ? bounds.getInt("y", 2) : 2;
    int width = bounds != null ? bounds.getInt("width", 7) : 7;
    int height = bounds != null ? bounds.getInt("height", 3) : 3;
    String alignmentRaw = bounds != null ? bounds.getString("alignment", "LEFT") : "LEFT";
    return new PageBounds(x, y, width, height, parseAlignment(alignmentRaw));
  }

  private static PageNavigation buildNavigation(FileConfiguration cfg, ItemStack previous, ItemStack next) {
    ConfigurationSection navigation = cfg.getSection("layout.members.navigation");
    int previousSlot = navigation != null ? navigation.getInt("previous-slot", 45) : 45;
    int nextSlot = navigation != null ? navigation.getInt("next-slot", 53) : 53;
    boolean showCurrentPageAmount = navigation == null || navigation.getBoolean("show-current-page-amount", true);
    boolean hidePreviousOnFirstPage = navigation == null || navigation.getBoolean("hide-previous-on-first-page", true);
    boolean hideNextOnLastPage = navigation == null || navigation.getBoolean("hide-next-on-last-page", true);

    return PageNavigation.builder()
        .previousSlot(previousSlot)
        .nextSlot(nextSlot)
        .previousItem(previous == null ? new ItemStack(Material.ARROW) : previous)
        .nextItem(next == null ? new ItemStack(Material.ARROW) : next)
        .showCurrentPageAmount(showCurrentPageAmount)
        .hidePreviousOnFirstPage(hidePreviousOnFirstPage)
        .hideNextOnLastPage(hideNextOnLastPage)
        .build();
  }

  private static PageAlignment parseAlignment(String raw) {
    if (raw == null || raw.isBlank()) return PageAlignment.LEFT;
    try {
      return PageAlignment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (Exception ignored) {
      return PageAlignment.LEFT;
    }
  }

  private static Duration parseRefreshInterval(String raw) {
    if (raw == null || raw.isBlank()) return Duration.ofSeconds(1);
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    try {
      if (normalized.endsWith("ms")) return Duration.ofMillis(Long.parseLong(normalized.substring(0, normalized.length() - 2).trim()));
      long value = Long.parseLong(normalized.substring(0, normalized.length() - 1).trim());
      if (normalized.endsWith("s")) return Duration.ofSeconds(value);
      if (normalized.endsWith("m")) return Duration.ofMinutes(value);
      return Duration.ofSeconds(Long.parseLong(normalized));
    } catch (Exception ignored) {
      return Duration.ofSeconds(1);
    }
  }

  private static String toLegacyTitle(String raw) {
    String value = raw == null ? "<yellow>Manage Members</yellow>" : raw;
    return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(value));
  }

  private static void triggerGeneralClick(UUID viewerUuid) {
    if (viewerUuid == null || clickEffects == null) return;
    Player player = Bukkit.getPlayer(viewerUuid);
    if (player != null) {
      clickEffects.executeGeneralClick(player);
    }
  }

  private static String resolveBankName(BankMemberFlowState.MemberContext state) {
    BankDefinition definition = resolveDefinition(state);
    return definition == null ? state.bankId() : plainText(definition.nameMiniMessage());
  }

  private static String plainText(String raw) {
    return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(raw == null ? "" : raw));
  }

  private static String nameOrUuid(UUID uuid) {
    if (uuid == null) return "unknown";
    OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
    String name = off.getName();
    return name == null || name.isBlank() ? uuid.toString() : name;
  }

  private static String yesNo(boolean value) {
    return value ? "Yes" : "No";
  }

  private static String legacy(String raw) {
    return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(raw));
  }

  private static String escape(String raw) {
    return raw == null ? "" : raw.replace("<", "").replace(">", "");
  }

  private record MemberEntry(UUID memberUuid,
                             String memberName,
                             String roleId,
                             String roleName,
                             int priority,
                             boolean canDeposit,
                             boolean canWithdraw,
                             boolean canInvite,
                             boolean canKick,
                             boolean canManage,
                             boolean placeholder) {
    static MemberEntry empty() {
      return new MemberEntry(null, "No members available", null, null, -1, false, false, false, false, false, true);
    }
  }
}





