package io.nexstudios.nexeconomy.service.bank.menu.bank;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.menuservice.common.api.MenuKey;
import io.nexstudios.menuservice.common.api.MenuPopulateContext;
import io.nexstudios.menuservice.common.api.MenuService;
import io.nexstudios.menuservice.common.api.MenuSlot;
import io.nexstudios.menuservice.common.api.ViewerRef;
import io.nexstudios.menuservice.common.api.builder.MenuDefinitionBuilder;
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
import io.nexstudios.nexeconomy.service.bank.menu.extra.BankExtraItemSupport;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.bukkit.services.heads.HeadService;
import io.nexstudios.nexlogic.bukkit.services.items.config.ConfigItemService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Dependencies({
    ItemService.class,
    MenuService.class,
    BankProviderService.class,
    CurrencyRegistryService.class,
    LoggerService.class,
    FileReaderService.class
})
public final class BankInvitePlayerMenu {

  public static final MenuKey KEY = MenuKey.of("nexeconomy", "bank-invite-player");

  private static final Path CONFIG_PATH = Path.of("inventories/bank-invite-player.yml");
  private static final String CONFIG_FILE = "inventories/bank-invite-player.yml";
  private static final String AREA_ID = "players";

  private static final int DEFAULT_ROWS = 6;
  private static final int DEFAULT_INFO_SLOT = 4;
  private static final int DEFAULT_BACK_SLOT = 49;

  private static ServiceAccessor servicesRef;
  private static ItemService itemService;
  private static FileReaderService fileReaderService;
  private static ConfigItemService configItemService;
  private static HeadService headService;
  private static BankProviderService bankProvider;
  private static FileConfiguration config;
  private static ItemStack fillTemplate;
  private static ItemStack infoTemplate;
  private static ItemStack playerEntryTemplate;
  private static ItemStack previousTemplate;
  private static ItemStack nextTemplate;
  private static ItemStack backTemplate;
  private static List<BankExtraItemSupport.ExtraItemBinding> EXTRA_ITEMS = List.of();
  private static int SLOT_INFO = DEFAULT_INFO_SLOT;
  private static int SLOT_BACK = DEFAULT_BACK_SLOT;

  private BankInvitePlayerMenu() {}

  public static void register(@NotNull ServiceAccessor services) {
    MenuService menuService = services.getService(MenuService.class);
    servicesRef = services;
    itemService = services.getService(ItemService.class);
    fileReaderService = services.getService(FileReaderService.class);
    configItemService = NexEconomyPlugin.getNexLogicService().getService(ConfigItemService.class);
    headService = NexEconomyPlugin.getNexLogicService().getService(HeadService.class);
    bankProvider = services.getService(BankProviderService.class);
    config = loadConfig();

    SLOT_INFO = config.getInt("layout.slots.info", DEFAULT_INFO_SLOT);
    SLOT_BACK = config.getInt("layout.slots.back", DEFAULT_BACK_SLOT);

    fillTemplate = configuredItem(config, "items.fill", Material.BLACK_STAINED_GLASS_PANE);
    infoTemplate = configuredItem(config, "items.info", Material.PAPER);
    playerEntryTemplate = configuredItem(config, "items.player-entry", Material.PLAYER_HEAD);
    previousTemplate = configuredItem(config, "items.navigation.previous", Material.ARROW);
    nextTemplate = configuredItem(config, "items.navigation.next", Material.ARROW);
    backTemplate = configuredItem(config, "items.back", Material.ARROW);
    EXTRA_ITEMS = BankExtraItemSupport.loadBindings(config, configItemService);

    var definition = MenuDefinitionBuilder.create()
        .key(KEY)
        .title(toLegacyTitle(config.getString("menu.title", "<yellow>Invite Player</yellow>")))
        .rows(config.getInt("menu.rows", DEFAULT_ROWS))
        .refreshInterval(parseRefreshInterval(config.getString("menu.refresh-interval", "1")))
        .interactionPolicy(InteractionPolicies.locked())
        .fillEmptySlotsWith(MenuItem.of(fillTemplate))
        .interactionHooks(new io.nexstudios.menuservice.common.api.MenuInteractionHooks() {
          @Override
          public void onClose(MenuKey key, ViewerRef viewer, io.nexstudios.menuservice.common.api.CloseReason reason) {
            if (!BankInviteFlowState.consumeTransition(viewer.uniqueId())) {
              BankInviteFlowState.clear(viewer.uniqueId());
            }
          }
        })
        .addPagedArea(buildPagedArea())
        .populator(BankInvitePlayerMenu::populate)
        .build();

    menuService.registry().register(definition, DuplicateStrategy.REPLACE);
  }

  public static void open(@NotNull ServiceAccessor services, @NotNull ViewerRef viewer, @NotNull String bankId, @NotNull UUID ownerUuid, boolean ownerBank) {
    BankInviteFlowState.start(viewer.uniqueId(), bankId, ownerUuid, ownerBank);
    services.getService(MenuService.class).open(viewer, KEY);
  }

  private static void populate(MenuPopulateContext ctx) {
    ViewerRef viewer = ctx.viewer();
    Player player = Bukkit.getPlayer(viewer.uniqueId());
    BankInviteFlowState.InviteContext state = BankInviteFlowState.get(viewer.uniqueId());

    if (player == null || state == null) {
      setInfo(ctx, Component.text("Invite players"), List.of("No invite context is available."));
      setBackButton(ctx, null);
      return;
    }

    setInfo(ctx, state);
    setBackButton(ctx, state);
    BankExtraItemSupport.populate(ctx, servicesRef, EXTRA_ITEMS, "bank-invite-player");
  }

  private static void setInfo(MenuPopulateContext ctx, BankInviteFlowState.InviteContext state) {
    BankDefinition definition = resolveDefinition(state);
    String bankName = definition == null ? state.bankId() : definition.nameMiniMessage();
    String ownerName = nameOrUuid(state.ownerUuid());
    int onlineCount = Bukkit.getOnlinePlayers().size();

    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("bank-name", bankName),
        Placeholder.parsed("owner-name", ownerName),
        Placeholder.parsed("online-count", String.valueOf(onlineCount))
    ));

    ItemStack stack = renderConfiguredItem(infoTemplate, "items.info", "Invite players", resolver);
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
      BankInviteFlowState.clear(clickCtx.viewer().uniqueId());
      if (servicesRef != null) {
        if (state != null) {
          BankDetailMenu.open(servicesRef, clickCtx.viewer(), state.bankId(), state.ownerUuid(), state.ownerBank());
        } else {
          BankOverviewMenu.open(servicesRef, clickCtx.viewer());
        }
      }
    });
  }

  private static PagedAreaDefinition<PlayerEntry> buildPagedArea() {
    PageSource<PlayerEntry> source = (menuKey, viewer) -> {
      List<PlayerEntry> entries = new ArrayList<>();
      if (Bukkit.getOnlinePlayers().isEmpty()) {
        return entries;
      }

      UUID viewerUuid = viewer == null ? null : viewer.uniqueId();
      if (viewerUuid == null) {
        return entries;
      }

      BankInviteFlowState.InviteContext state = BankInviteFlowState.get(viewerUuid);
      List<UUID> memberUuids = loadMemberUuids(state);
      UUID ownerUuid = state == null ? null : state.ownerUuid();

      List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
      onlinePlayers.sort(Comparator.comparing(BankInvitePlayerMenu::playerSortKey, String.CASE_INSENSITIVE_ORDER));

      for (Player online : onlinePlayers) {
        if (online == null) {
          continue;
        }
        if (viewerUuid.equals(online.getUniqueId())) {
          continue;
        }
        if (ownerUuid != null && ownerUuid.equals(online.getUniqueId())) {
          continue;
        }
        if (memberUuids.contains(online.getUniqueId())) {
          continue;
        }
        entries.add(new PlayerEntry(online.getUniqueId(), online.getName().isBlank() ? online.getUniqueId().toString() : online.getName()));
      }

      return entries;
    };

    PageBounds bounds = readBounds(config);
    PageNavigation navigation = buildNavigation(config, previousTemplate, nextTemplate);

    return new PagedAreaDefinition<>(
        AREA_ID,
        bounds,
        source,
        (entry, index) -> PlannedMenuItemSupplier.withHead(
            MenuItem.of(renderPlayerItem(entry)),
            headService.loadHead(entry.playerUuid())
        ),
        navigation,
        Optional.of((entry, index, clickCtx) -> {
          clickCtx.cancel();
          triggerGeneralClick(clickCtx.viewer().uniqueId());
          BankInviteFlowState.selectTarget(clickCtx.viewer().uniqueId(), entry.playerUuid(), entry.playerName());
          BankInviteFlowState.markTransition(clickCtx.viewer().uniqueId());
          if (servicesRef != null) {
            BankInviteRoleMenu.open(servicesRef, clickCtx.viewer());
          }
        })
    );
  }

  private static ItemStack renderPlayerItem(PlayerEntry entry) {
    TagResolver resolver = TagResolver.resolver(List.of(
        Placeholder.parsed("player-name", entry.playerName()),
        Placeholder.parsed("player-uuid", entry.playerUuid().toString())
    ));

    return renderConfiguredItem(playerEntryTemplate, "items.player-entry", entry.playerName(), resolver);
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

  private static List<UUID> loadMemberUuids(BankInviteFlowState.InviteContext state) {
    if (state == null || bankProvider == null) {
      return List.of();
    }

    try {
      BankResponse<List<BankMemberEntity>> response = bankProvider.members(state.bankId(), state.ownerUuid()).join();
      List<BankMemberEntity> members = response == null ? null : response.payload();
      if (members == null || members.isEmpty()) {
        return List.of();
      }

      List<UUID> uuids = new ArrayList<>();
      for (BankMemberEntity member : members) {
        if (member != null && member.getMemberUuid() != null) {
          uuids.add(member.getMemberUuid());
        }
      }

      return List.copyOf(uuids);
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static String playerSortKey(Player player) {
    if (player == null || player.getName().isBlank()) {
      return "";
    }
    return player.getName();
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
    ConfigurationSection bounds = cfg.getSection("layout.players.bounds");
    int x = bounds != null ? bounds.getInt("x", 1) : 1;
    int y = bounds != null ? bounds.getInt("y", 2) : 2;
    int width = bounds != null ? bounds.getInt("width", 7) : 7;
    int height = bounds != null ? bounds.getInt("height", 3) : 3;
    String alignmentRaw = bounds != null ? bounds.getString("alignment", "LEFT") : "LEFT";
    return new PageBounds(x, y, width, height, parseAlignment(alignmentRaw));
  }

  private static PageNavigation buildNavigation(FileConfiguration cfg, ItemStack previous, ItemStack next) {
    ConfigurationSection navigation = cfg.getSection("layout.players.navigation");
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
    String value = raw == null || raw.isBlank() ? "<yellow>Invite Player</yellow>" : raw;
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

  private static String nameOrUuid(UUID uuid) {
    if (uuid == null) {
      return "Unknown";
    }

    Player player = Bukkit.getPlayer(uuid);
    if (player != null && !player.getName().isBlank()) {
      return player.getName();
    }

    return uuid.toString();
  }

  private record PlayerEntry(UUID playerUuid, String playerName) {}
}





