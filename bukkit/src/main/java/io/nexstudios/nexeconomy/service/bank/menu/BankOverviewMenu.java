package io.nexstudios.nexeconomy.service.bank.menu;

import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.menuservice.common.api.MenuKey;
import io.nexstudios.menuservice.common.api.MenuService;
import io.nexstudios.menuservice.common.api.ViewerRef;
import io.nexstudios.menuservice.common.api.builder.MenuDefinitionBuilder;
import io.nexstudios.menuservice.common.api.interaction.InteractionPolicies;
import io.nexstudios.menuservice.common.api.item.MenuItem;
import io.nexstudios.menuservice.common.api.page.*;
import io.nexstudios.menuservice.common.api.page.control.PageControlButton;
import io.nexstudios.menuservice.common.api.page.control.PageSortControl;
import io.nexstudios.menuservice.common.api.registry.DuplicateStrategy;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;

@Dependencies({
    ItemService.class,
    MenuService.class,
    ComponentService.class,
    BankService.class,
    LoggerService.class
})
public class BankOverviewMenu {

  public static final MenuKey KEY = MenuKey.of("nexeconomy", "bank-overview");
  private static final String SORT_ID = "sort";
  private static final int SLOT_SORT_BUTTON = 8;
  private static final String AREA_ID = "entries";

  private static LoggerService logger;

  public static void register(@NotNull ServiceAccessor services) {
    MenuService menuService = services.getService(MenuService.class);
    ItemService items = services.getService(ItemService.class);
    BankService bankService = services.getService(BankService.class);
    ComponentService components = services.getService(ComponentService.class);
    logger = services.getService(LoggerService.class);

    var def = MenuDefinitionBuilder.create()
        .key(KEY)
        .title("§eBank Overview")
        .rows(6)
        .refreshInterval(Duration.ofSeconds(1))
        .interactionPolicy(InteractionPolicies.locked())
        .populator(ctx -> {
          ctx.slot(SLOT_SORT_BUTTON).setPlannedItem(() -> MenuItem.of(buildSortButton(items)));
        })
        .addSortControl(AREA_ID, buildSortControl())
        .addControlButton(buildSortControlButton(items))
        .addPagedArea(buildPagedArea(items, bankService, components))
        .build();

    menuService.registry().register(def, DuplicateStrategy.REPLACE);
  }

  private static PagedAreaDefinition<BankEntry> buildPagedArea(ItemService items, BankService bankService, ComponentService components) {
    PageSource<BankEntry> source = (menuKey, viewer) -> {
      try {
        UUID viewerUuid = getViewerUuid(viewer);
        org.bukkit.Bukkit.getLogger().info("[BankOverviewMenu] Loading banks for viewer: " + viewerUuid);
        
        List<BankEntry> entries = new ArrayList<>();
        
        try {
          // Load ALL banks (owner + member)
          List<BankRepositoryService.BankAccountRef> allBanks = bankService.allBanks(viewerUuid).get();
          if (allBanks != null) {
            for (BankRepositoryService.BankAccountRef ref : allBanks) {
              if (ref == null) continue;
              boolean isOwner = ref.ownerUuid().equals(viewerUuid);
              entries.add(new BankEntry(
                  ref.bankIdLower(),
                  ref.ownerUuid(),
                  isOwner ? Category.OWN_BANK : Category.MEMBER_BANK
              ));
            }
          }
          org.bukkit.Bukkit.getLogger().info("[BankOverviewMenu] Loaded " + (allBanks != null ? allBanks.size() : 0) + " banks");
        } catch (Exception e) {
          org.bukkit.Bukkit.getLogger().warning("[BankOverviewMenu] Error loading banks: " + e.getMessage());
          e.printStackTrace();
        }
        
        org.bukkit.Bukkit.getLogger().info("[BankOverviewMenu] Total entries: " + entries.size() + " banks");
        return entries;
      } catch (Exception e) {
        org.bukkit.Bukkit.getLogger().warning("[BankOverviewMenu] Failed to load banks: " + e.getMessage());
        e.printStackTrace();
        return new ArrayList<>();
      }
    };

    // Bounds: 7x4 = 28 Items pro Seite
    PageBounds bounds = new PageBounds(1, 1, 7, 4, PageAlignment.LEFT);

    // Navigation unten: prev (45), refresh (49), next (53)
    PageNavigation nav = new PageNavigation(
        OptionalInt.of(45),
        OptionalInt.of(53),
        OptionalInt.of(49)
    );

    return new PagedAreaDefinition<>(
        AREA_ID,
        bounds,
        source,
        (entry, index) -> () -> MenuItem.of(renderBank(items, entry, index)),
        nav,
        Optional.empty()
    );
  }

  private static ItemStack renderBank(ItemService items, BankEntry bank, int index) {
    OfflinePlayer owner = Bukkit.getOfflinePlayer(bank.ownerUuid());
    String ownerName = owner.getName() != null ? owner.getName() : bank.ownerUuid().toString();

    NamedTextColor typeColor = bank.category() == Category.OWN_BANK ? NamedTextColor.GREEN : NamedTextColor.BLUE;
    String typeLabel = bank.category() == Category.OWN_BANK ? "Own Bank" : "Member Bank";

    return items.builder(Material.PAPER)
        .amount(1)
        .name(Component.text(bank.bankName(), NamedTextColor.GOLD))
        .lore(l -> l
            .line("&7Type: " + typeColor + typeLabel)
            .line("&7Owner: &f" + ownerName)
            .line("&eClick to manage")
        )
        .build();
  }

  private static PageControlButton buildSortControlButton(ItemService items) {
    return new PageControlButton() {
      @Override public String areaId() { return AREA_ID; }
      @Override public String controlId() { return SORT_ID; }
      @Override public int slot() { return SLOT_SORT_BUTTON; }

      @Override
      public MenuItem render(RenderContext ctx) {
        String mode = ctx.activeModeId().orElse(ctx.control().defaultModeId());
        String label = ctx.control().labelForMode(mode);

        return MenuItem.of(items.builder(Material.COMPARATOR)
            .amount(1)
            .name(Component.text("Sort: " + label, NamedTextColor.GOLD))
            .lore(l -> l
                .line("&7Klick: Modus wechseln")
                .line("&8Aktiv: &f" + mode)
            )
            .build());
      }

      @Override
      public void onClick(ClickContext ctx) {
        ctx.stateStore().cycleToNextMode(ctx.viewer(), ctx.menuKey(), ctx.areaId(), ctx.control());
        ctx.requestAreaRefresh();
      }
    };
  }

  private static PageSortControl<BankEntry> buildSortControl() {
    return new PageSortControl<>() {
      @Override public String controlId() { return SORT_ID; }

      @Override public List<String> modeIds() { return List.of("all", "owner", "member"); }

      @Override public String defaultModeId() { return "all"; }

      @Override
      public String labelForMode(String modeId) {
        return switch (modeId) {
          case "owner" -> "Owner Banks";
          case "member" -> "Member Banks";
          default -> "All Banks";
        };
      }

      @Override
      public Comparator<BankEntry> comparatorFor(String modeId, MenuKey menuKey, ViewerRef viewer) {
        // Filter by category, then sort by name
        return Comparator.comparing((BankEntry b) -> {
          return switch (modeId) {
            case "owner" -> b.category() == Category.OWN_BANK ? 0 : 1;
            case "member" -> b.category() == Category.MEMBER_BANK ? 0 : 1;
            default -> 2; // All - no filtering
          };
        }).thenComparing(BankEntry::bankName, String.CASE_INSENSITIVE_ORDER);
      }
    };
  }

  public static void open(@NotNull ServiceAccessor services, @NotNull ViewerRef viewer) {
    MenuService menuService = services.getService(MenuService.class);
    menuService.open(viewer, KEY);
  }

  private static UUID getViewerUuid(ViewerRef viewer) {
    return viewer.uniqueId();
  }

  private static ItemStack buildSortButton(ItemService items) {
    return items.builder(Material.COMPARATOR)
        .amount(1)
        .name(Component.text("Sort", NamedTextColor.GOLD))
        .lore(l -> l.line("&7Wird vom Control-Button gerendert"))
        .build();
  }

  private enum Category {
    OWN_BANK("Own Banks"),
    MEMBER_BANK("Member Banks");

    final String id;
    Category(String id) { this.id = id; }
  }

  private record BankEntry(String bankName, UUID ownerUuid, Category category) {}

}
