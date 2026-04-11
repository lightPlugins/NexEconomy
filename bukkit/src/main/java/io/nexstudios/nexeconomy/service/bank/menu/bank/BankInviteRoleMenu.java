package io.nexstudios.nexeconomy.service.bank.menu.bank;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.dialogservice.api.ConfirmDialog;
import io.nexstudios.dialogservice.service.ConfirmDialogService;
import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.menuservice.common.api.CloseReason;
import io.nexstudios.menuservice.common.api.MenuInteractionHooks;
import io.nexstudios.menuservice.common.api.MenuKey;
import io.nexstudios.menuservice.common.api.MenuPopulateContext;
import io.nexstudios.menuservice.common.api.MenuService;
import io.nexstudios.menuservice.common.api.MenuSlot;
import io.nexstudios.menuservice.common.api.ViewerRef;
import io.nexstudios.menuservice.common.api.builder.MenuDefinitionBuilder;
import io.nexstudios.menuservice.common.api.interaction.InteractionPolicies;
import io.nexstudios.menuservice.common.api.item.MenuItem;
import io.nexstudios.menuservice.common.api.page.PageAlignment;
import io.nexstudios.menuservice.common.api.page.PageBounds;
import io.nexstudios.menuservice.common.api.page.PageNavigation;
import io.nexstudios.menuservice.common.api.page.PageSource;
import io.nexstudios.menuservice.common.api.page.PagedAreaDefinition;
import io.nexstudios.menuservice.common.api.registry.DuplicateStrategy;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.provider.bank.BankProviderService;
import io.nexstudios.nexeconomy.provider.bank.BankResponse;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.effects.BankClickEffectService;
import io.nexstudios.nexeconomy.service.bank.menu.BankInviteFlowState;
import io.nexstudios.nexeconomy.service.bank.menu.extra.BankExtraItemSupport;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexlogic.bukkit.services.items.config.ConfigItemService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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

@Dependencies({
    ItemService.class,
    MenuService.class,
    ComponentService.class,
    BankService.class,
    BankProviderService.class,
    CurrencyRegistryService.class,
    ConfirmDialogService.class,
    FileReaderService.class
})
public final class BankInviteRoleMenu {

  public static final MenuKey KEY = MenuKey.of("nexeconomy", "bank-invite-role");

  private static final Path CONFIG_PATH = Path.of("inventories/bank-invite-role.yml");
  private static final String CONFIG_FILE = "inventories/bank-invite-role.yml";
  private static final String AREA_ID = "roles";

  private static final int DEFAULT_ROWS = 6;
  private static final int DEFAULT_INFO_SLOT = 4;
  private static final int DEFAULT_BACK_SLOT = 49;

  private static ServiceAccessor servicesRef;
  private static Plugin plugin;
  private static ItemService itemService;
  private static FileReaderService fileReaderService;
  private static ConfigItemService configItemService;
  private static ComponentService componentService;
  private static BankService bankService;
  private static BankProviderService bankProvider;
  private static CurrencyRegistryService currencyRegistry;
  private static ConfirmDialogService confirmDialogs;
  private static FileConfiguration config;
  private static ItemStack infoTemplate;
  private static ItemStack roleEntryTemplate;
  private static ItemStack previousTemplate;
  private static ItemStack nextTemplate;
  private static ItemStack backTemplate;
  private static List<BankExtraItemSupport.ExtraItemBinding> EXTRA_ITEMS = List.of();
  private static int SLOT_INFO = DEFAULT_INFO_SLOT;
  private static int SLOT_BACK = DEFAULT_BACK_SLOT;

  private BankInviteRoleMenu() {}

  public static void register(@NotNull ServiceAccessor services) {
    MenuService menuService = services.getService(MenuService.class);
    servicesRef = services;
    plugin = services.getService(io.nexstudios.framework.paper.services.plugin.PaperPluginService.class).plugin();
    itemService = services.getService(ItemService.class);
    fileReaderService = services.getService(FileReaderService.class);
    configItemService = NexEconomyPlugin.getNexLogicService().getService(ConfigItemService.class);
    componentService = services.getService(ComponentService.class);
    bankService = services.getService(BankService.class);
    bankProvider = services.getService(BankProviderService.class);
    currencyRegistry = services.getService(CurrencyRegistryService.class);
    confirmDialogs = services.getService(ConfirmDialogService.class);
    config = loadConfig();

    SLOT_INFO = config.getInt("layout.slots.info", DEFAULT_INFO_SLOT);
    SLOT_BACK = config.getInt("layout.slots.back", DEFAULT_BACK_SLOT);

    ItemStack fillTemplate = configuredItem(config, "items.fill", Material.BLACK_STAINED_GLASS_PANE);
    infoTemplate = configuredItem(config, "items.info", Material.PAPER);
    roleEntryTemplate = configuredItem(config, "items.role-entry", Material.NAME_TAG);
    previousTemplate = configuredItem(config, "items.navigation.previous", Material.ARROW);
    nextTemplate = configuredItem(config, "items.navigation.next", Material.ARROW);
    backTemplate = configuredItem(config, "items.back", Material.ARROW);
    EXTRA_ITEMS = BankExtraItemSupport.loadBindings(config, configItemService);

    var definition = MenuDefinitionBuilder.create()
        .key(KEY)
        .title(toLegacyTitle(config.getString("menu.title", "<yellow>Select Role</yellow>")))
        .rows(config.getInt("menu.rows", DEFAULT_ROWS))
        .refreshInterval(parseRefreshInterval(config.getString("menu.refresh-interval", "1")))
        .interactionPolicy(InteractionPolicies.locked())
        .fillEmptySlotsWith(MenuItem.of(fillTemplate))
        .interactionHooks(new MenuInteractionHooks() {
          @Override
          public void onClose(MenuKey key, ViewerRef viewer, CloseReason reason) {
            if (!BankInviteFlowState.consumeTransition(viewer.uniqueId())) {
              BankInviteFlowState.clear(viewer.uniqueId());
            }
          }
        })
        .addPagedArea(buildPagedArea())
        .populator(BankInviteRoleMenu::populate)
        .build();

    menuService.registry().register(definition, DuplicateStrategy.REPLACE);
  }

  public static void open(@NotNull ServiceAccessor services, @NotNull ViewerRef viewer) {
    services.getService(MenuService.class).open(viewer, KEY);
  }

  private static void populate(MenuPopulateContext ctx) {
    ViewerRef viewer = ctx.viewer();
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    BankInviteFlowState.InviteContext state = BankInviteFlowState.get(viewer.uniqueId());

    if (player == null || state == null) {
      setInfo(ctx, Component.text("Role selection"), List.of("No invite context is available."));
      setBackButton(ctx, null);
      return;
    }

    setInfo(ctx, state);
    setBackButton(ctx, state);
    BankExtraItemSupport.populate(ctx, servicesRef, EXTRA_ITEMS, "bank-invite-role");
  }

  private static void setInfo(MenuPopulateContext ctx, BankInviteFlowState.InviteContext state) {
    BankDefinition definition = resolveDefinition(state);
    String bankName = definition == null ? state.bankId() : definition.nameMiniMessage();
    String ownerName = nameOrUuid(state.ownerUuid());
    String targetName = state.targetName() == null || state.targetName().isBlank() ? nameOrUuid(state.targetUuid()) : state.targetName();

    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("bank-name", bankName),
        Placeholder.parsed("owner-name", ownerName),
        Placeholder.parsed("target-name", targetName)
    ));

    ItemStack stack = renderConfiguredItem(infoTemplate, "items.info", "Select role", resolver);
    ctx.slot(SLOT_INFO).setPlannedItem(() -> MenuItem.of(stack));
  }

  private static void setInfo(MenuPopulateContext ctx, Component title, List<String> lore) {
    ItemStack stack = itemService.builder(new ItemStack(Material.PAPER))
        .amount(1)
        .name(title)
        .lore(builder -> {
          if (lore != null) {
            for (String line : lore) {
              builder.line(Component.text(line));
            }
          }
        })
        .build();
    ctx.slot(SLOT_INFO).setPlannedItem(() -> MenuItem.of(stack));
  }

  private static void setBackButton(MenuPopulateContext ctx, BankInviteFlowState.InviteContext state) {
    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("bank-name", state == null ? "Unknown" : state.bankId())
    ));

    ItemStack stack = renderConfiguredItem(backTemplate, "items.back", "Back", resolver);
    MenuSlot slot = ctx.slot(SLOT_BACK);
    slot.setPlannedItem(() -> MenuItem.of(stack));
    slot.onClick(clickCtx -> {
      clickCtx.cancel();
      triggerGeneralClick(clickCtx.viewer().uniqueId());
      BankInviteFlowState.clearRole(clickCtx.viewer().uniqueId());
      BankInviteFlowState.markTransition(clickCtx.viewer().uniqueId());
      if (servicesRef != null) {
        if (state != null) {
          BankInvitePlayerMenu.open(servicesRef, clickCtx.viewer(), state.bankId(), state.ownerUuid(), state.ownerBank());
        } else {
          BankOverviewMenu.open(servicesRef, clickCtx.viewer());
        }
      }
    });
  }

  private static PagedAreaDefinition<RoleEntry> buildPagedArea() {
    PageSource<RoleEntry> source = (menuKey, viewer) -> buildEntries(viewer.uniqueId());
    PageBounds bounds = readBounds(config);
    PageNavigation navigation = buildNavigation(config, previousTemplate, nextTemplate);

    return new PagedAreaDefinition<>(
        AREA_ID,
        bounds,
        source,
        (entry, index) -> () -> MenuItem.of(renderRoleItem(entry)),
        navigation,
        Optional.of((entry, index, clickCtx) -> {
          clickCtx.cancel();
          triggerGeneralClick(clickCtx.viewer().uniqueId());
          BankInviteFlowState.selectRole(clickCtx.viewer().uniqueId(), entry.role().idLower(), entry.role().nameMiniMessage());
          openConfirmDialog(clickCtx.viewer(), entry);
        })
    );
  }

  private static List<RoleEntry> buildEntries(UUID viewerUuid) {
    BankInviteFlowState.InviteContext state = BankInviteFlowState.get(viewerUuid);
    if (state == null) {
      return List.of();
    }

    BankDefinition definition = resolveDefinition(state);
    if (definition == null || definition.memberSystem() == null || !definition.memberSystem().enabled()) {
      return List.of();
    }

    CurrencyDefinition currency = currencyRegistry == null ? null : currencyRegistry.currency(definition.currencyIdLower());
    Map<String, BankDefinition.RoleDefinition> roles = definition.memberSystem().rolesByIdLower();
    if (roles == null || roles.isEmpty()) {
      return List.of();
    }

    List<BankMemberEntity> members = loadMembers(state);
    BankDefinition.RoleDefinition actorRole = resolveActorRole(state, members, roles, viewerUuid);
    boolean isOwner = viewerUuid != null && viewerUuid.equals(state.ownerUuid());
    if (!isOwner && (actorRole == null || !actorRole.canInvite())) {
      return List.of();
    }

    List<RoleEntry> entries = new ArrayList<>();
    for (BankDefinition.RoleDefinition role : roles.values()) {
      if (role == null || role.idLower() == null || role.idLower().isBlank()) {
        continue;
      }
      if (!isOwner && actorRole != null && role.priority() >= actorRole.priority()) {
        continue;
      }

      BankDefinition.WithdrawDefinition withdraw = role.withdraw();
      boolean canWithdraw = withdraw != null && withdraw.canWithdraw();
      String hourlyLimit = canWithdraw ? formatLimit(currency, withdraw.hourlyLimitRaw()) : "Disabled";
      String dailyLimit = canWithdraw ? formatLimit(currency, withdraw.dailyLimitRaw()) : "Disabled";

      entries.add(new RoleEntry(
          role,
          canText(role.canDeposit()),
          canText(canWithdraw),
          hourlyLimit,
          dailyLimit,
          canText(role.canInvite()),
          canText(role.canKick()),
          canText(role.canUpgrade()),
          canText(role.canViewLog())
      ));
    }

    entries.sort(Comparator
        .comparingInt((RoleEntry entry) -> entry.role().priority()).reversed()
        .thenComparing(entry -> normalized(entry.role().nameMiniMessage()), String.CASE_INSENSITIVE_ORDER));

    return entries;
  }

  private static ItemStack renderRoleItem(RoleEntry entry) {
    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("role-name", entry.role().nameMiniMessage()),
        Placeholder.parsed("role-id", entry.role().idLower()),
        Placeholder.parsed("priority", String.valueOf(entry.role().priority())),
        Placeholder.parsed("can-deposit", entry.canDeposit()),
        Placeholder.parsed("can-withdraw", entry.canWithdraw()),
        Placeholder.parsed("hourly-limit", entry.hourlyLimit()),
        Placeholder.parsed("daily-limit", entry.dailyLimit()),
        Placeholder.parsed("can-invite", entry.canInvite()),
        Placeholder.parsed("can-kick", entry.canKick()),
        Placeholder.parsed("can-upgrade", entry.canUpgrade()),
        Placeholder.parsed("can-view-log", entry.canViewLog())
    ));

    return renderConfiguredItem(roleEntryTemplate, "items.role-entry", entry.role().nameMiniMessage(), resolver);
  }

  private static void openConfirmDialog(ViewerRef viewer, RoleEntry entry) {
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    BankInviteFlowState.InviteContext state = BankInviteFlowState.get(viewer.uniqueId());
    if (player == null || state == null || entry == null || confirmDialogs == null) {
      return;
    }

    String bankName = resolveBankName(state);
    String targetName = state.targetName() == null || state.targetName().isBlank() ? nameOrUuid(state.targetUuid()) : state.targetName();
    String roleName = resolveSelectedRoleName(state, entry.role());

    ConfirmDialog confirm = confirmDialogs.create()
        .title(legacy("<yellow>Send bank invite?</yellow>"))
        .body(legacy("<gray>Do you really want to invite <yellow>" + escape(targetName) + "</yellow> to <yellow>" + escape(bankName) + "</yellow> as <yellow>" + escape(roleName) + "</yellow>?</gray>"))
        .confirmButton(legacy("Invite"))
        .cancelButton(legacy("Cancel"));

    confirm.show(player).thenAccept(result -> {
      if (!Boolean.TRUE.equals(result)) {
        return;
      }

      BankProviderService provider = bankProvider;
      if (provider == null) {
        sendMessage(player, "<red>Bank provider is not available.</red>");
        return;
      }

      provider.invite(state.bankId(), state.ownerUuid(), player.getUniqueId(), state.targetUuid(), entry.role().idLower())
          .thenAccept(response -> Bukkit.getScheduler().runTask(plugin, () -> handleInviteResult(player, state, entry, response)))
          .exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> sendMessage(player, "<red>Could not send the invite.</red>"));
            return null;
          });
    });
  }

  private static void handleInviteResult(Player player,
                                         BankInviteFlowState.InviteContext state,
                                         RoleEntry entry,
                                         BankResponse<?> response) {
    if (player == null || state == null || entry == null) {
      return;
    }

    if (response != null && response.isSuccess()) {
      String targetName = state.targetName() == null || state.targetName().isBlank() ? nameOrUuid(state.targetUuid()) : state.targetName();
      sendInviteNotification(state, targetName, entry.role(), player.getName());
      sendMessage(player, "<gray>Invite sent to <yellow>" + escape(targetName) + "</yellow> as <yellow>" + escape(resolveSelectedRoleName(state, entry.role())) + "</yellow>.</gray>");
      BankInviteFlowState.clear(player.getUniqueId());
      if (servicesRef != null) {
        BankDetailMenu.open(servicesRef, ViewerRef.of(player.getUniqueId(), player.getName()), state.bankId(), state.ownerUuid(), state.ownerBank());
      }
      return;
    }

    String error = response == null ? "Unknown error" : response.message();
    sendMessage(player, "<red>Could not send the invite: " + escape(error) + "</red>");
  }

  private static BankDefinition resolveDefinition(BankInviteFlowState.InviteContext state) {
    if (state == null || bankProvider == null) {
      return null;
    }

    try {
      BankResponse<BankDefinition> response = bankProvider.bank(state.bankId()).join();
      return response == null ? null : response.payload();
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String resolveBankName(BankInviteFlowState.InviteContext state) {
    BankDefinition definition = resolveDefinition(state);
    return definition == null ? state.bankId() : plainText(definition.nameMiniMessage());
  }

  private static List<BankMemberEntity> loadMembers(BankInviteFlowState.InviteContext state) {
    if (state == null || bankProvider == null) {
      return List.of();
    }

    try {
      BankResponse<List<BankMemberEntity>> response = bankProvider.members(state.bankId(), state.ownerUuid()).join();
      return response == null || response.payload() == null ? List.of() : response.payload();
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static BankDefinition.RoleDefinition resolveActorRole(BankInviteFlowState.InviteContext state,
                                                                List<BankMemberEntity> members,
                                                                Map<String, BankDefinition.RoleDefinition> roles,
                                                                UUID viewerUuid) {
    if (state == null || roles == null || roles.isEmpty() || viewerUuid == null) {
      return null;
    }

    if (viewerUuid.equals(state.ownerUuid())) {
      return syntheticOwnerRole();
    }

    BankMemberEntity member = findMember(members, viewerUuid);
    if (member == null) {
      return null;
    }

    String roleId = member.getRoleIdLower() == null ? "" : member.getRoleIdLower().trim().toLowerCase(Locale.ROOT);
    return roles.get(roleId);
  }

  private static BankDefinition.RoleDefinition syntheticOwnerRole() {
    return new BankDefinition.RoleDefinition(
        "owner",
        "<red>Owner</red>",
        Integer.MAX_VALUE,
        true,
        new BankDefinition.WithdrawDefinition(true, "-1", "-1"),
        true,
        true,
        true,
        true
    );
  }

  private static BankMemberEntity findMember(List<BankMemberEntity> members, UUID uuid) {
    if (members == null || members.isEmpty() || uuid == null) {
      return null;
    }

    for (BankMemberEntity member : members) {
      if (member != null && uuid.equals(member.getMemberUuid())) {
        return member;
      }
    }

    return null;
  }

  private static void sendInviteNotification(BankInviteFlowState.InviteContext state, String targetName, BankDefinition.RoleDefinition role, String inviterName) {
    if (state == null || state.targetUuid() == null || componentService == null) {
      return;
    }

    Player target = Bukkit.getPlayer(state.targetUuid());
    if (target == null) {
      return;
    }

    BankDefinition definition = resolveDefinition(state);
    String bankName = definition == null ? state.bankId() : plainText(definition.nameMiniMessage());
    String roleName = resolveSelectedRoleName(state, role);
    String ownerName = nameOrUuid(state.ownerUuid());
    String inviterShown = inviterName == null || inviterName.isBlank() ? ownerName : inviterName;

    Component hover = componentService.builder(target, "bank.invite.received-hover", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("bank", bankName),
            Placeholder.parsed("owner", ownerName),
            Placeholder.parsed("role", roleName)
        ))
        .build();

    Component base = componentService.builder(target, "bank.invite.received", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("bank", bankName),
            Placeholder.parsed("owner", ownerName),
            Placeholder.parsed("inviter", inviterShown),
            Placeholder.parsed("role", roleName)
        ))
        .build();

    base = base.hoverEvent(HoverEvent.showText(hover));
    base = base.clickEvent(ClickEvent.runCommand("/bank other accept " + state.ownerUuid() + " " + state.bankId()));
    target.sendMessage(base);
  }

  private static ItemStack renderConfiguredItem(ItemStack template, String path, String fallbackName, TagResolver resolver) {
    String rawName = config == null ? fallbackName : config.getString(path + ".display-name", fallbackName);
    if (rawName == null || rawName.isBlank()) {
      rawName = fallbackName;
    }

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
    if (section == null) {
      return new ItemStack(fallback);
    }

    return configItemService.convertSectionToItem(section)
        .orElseGet(() -> new ItemStack(fallback));
  }

  private static PageBounds readBounds(FileConfiguration cfg) {
    ConfigurationSection bounds = cfg.getSection("layout.roles.bounds");
    int x = bounds != null ? bounds.getInt("x", 1) : 1;
    int y = bounds != null ? bounds.getInt("y", 2) : 2;
    int width = bounds != null ? bounds.getInt("width", 7) : 7;
    int height = bounds != null ? bounds.getInt("height", 3) : 3;
    String alignmentRaw = bounds != null ? bounds.getString("alignment", "LEFT") : "LEFT";
    return new PageBounds(x, y, width, height, parseAlignment(alignmentRaw));
  }

  private static PageNavigation buildNavigation(FileConfiguration cfg, ItemStack previous, ItemStack next) {
    ConfigurationSection navigation = cfg.getSection("layout.roles.navigation");
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
    if (raw == null || raw.isBlank()) {
      return PageAlignment.LEFT;
    }

    try {
      return PageAlignment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (Exception ignored) {
      return PageAlignment.LEFT;
    }
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
    String value = raw == null || raw.isBlank() ? "<yellow>Select Role</yellow>" : raw;
    return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(value));
  }

  private static void triggerGeneralClick(UUID viewerUuid) {
    if (servicesRef == null || viewerUuid == null) {
      return;
    }

    Player player = Bukkit.getPlayer(viewerUuid);
    if (player == null) {
      return;
    }

    BankClickEffectService effects = servicesRef.getService(BankClickEffectService.class);
    if (effects != null) {
      effects.executeGeneralClick(player);
    }
  }

  private static String formatLimit(CurrencyDefinition currency, String raw) {
    if (raw == null || raw.isBlank()) {
      return "Disabled";
    }

    String normalized = raw.trim();
    if ("-1".equals(normalized)) {
      return "Unlimited";
    }

    MantissaAmount amount = parseLimit(currency, normalized);
    if (amount == null || amount.compareTo(MantissaAmount.zero()) <= 0) {
      return "Disabled";
    }

    return bankService.formatBalanceWithCurrency(amount, currency);
  }

  private static MantissaAmount parseLimit(CurrencyDefinition currency, String raw) {
    if (raw == null || raw.isBlank()) {
      return MantissaAmount.zero();
    }

    String input = raw.trim();
    if ("-1".equals(input)) {
      return MantissaAmount.of(java.math.BigDecimal.valueOf(-1), 0);
    }

    if (currency != null) {
      java.math.BigDecimal human = AmountNotation.parseVaultHuman(input);
      if (human != null) {
        return MantissaAmount.of(human, 0);
      }
    }

    MantissaAmount virtual = AmountNotation.parseVirtualMantissaAmount(input);
    if (virtual != null) {
      return virtual;
    }

    java.math.BigDecimal human = AmountNotation.parseVaultHuman(input);
    return human == null ? MantissaAmount.zero() : MantissaAmount.of(human, 0);
  }

  private static String canText(boolean value) {
    return value ? "Yes" : "No";
  }

  private static String normalized(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static String legacy(String miniMessage) {
    return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
  }

  private static String plainText(String miniMessage) {
    if (miniMessage == null || miniMessage.isBlank()) {
      return "";
    }

    return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("<", "&lt;").replace(">", "&gt;");
  }

  private static void sendMessage(Player player, String message) {
    if (player == null || message == null || message.isBlank()) {
      return;
    }

    player.sendMessage(MiniMessage.miniMessage().deserialize(message));
  }

  private static String nameOrUuid(UUID uuid) {
    if (uuid == null) {
      return "Unknown";
    }

    Player player = Bukkit.getPlayer(uuid);
    if (player != null && player.getName() != null && !player.getName().isBlank()) {
      return player.getName();
    }

    return uuid.toString();
  }

  private static String resolveSelectedRoleName(BankInviteFlowState.InviteContext state, BankDefinition.RoleDefinition role) {
    if (state != null && state.roleName() != null && !state.roleName().isBlank()) {
      return plainText(state.roleName());
    }

    if (role != null) {
      return plainText(role.nameMiniMessage());
    }

    return "unknown";
  }

  private record RoleEntry(BankDefinition.RoleDefinition role,
                           String canDeposit,
                           String canWithdraw,
                           String hourlyLimit,
                           String dailyLimit,
                           String canInvite,
                           String canKick,
                           String canUpgrade,
                           String canViewLog) {}
}


