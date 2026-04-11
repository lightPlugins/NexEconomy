package io.nexstudios.nexeconomy.service.bank.menu.extra;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.service.bank.effects.BankClickEffectService;
import io.nexstudios.nexlogic.bukkit.services.effects.context.BukkitContextKeys;
import io.nexstudios.nexlogic.common.effects.config.ConfigSection;
import io.nexstudios.nexlogic.common.effects.config.MapConfigSection;
import io.nexstudios.nexlogic.common.effects.model.LogicContext;
import io.nexstudios.nexlogic.common.services.engine.LogicEngineService;
import io.nexstudios.nexlogic.bukkit.services.items.config.ConfigItemService;
import io.nexstudios.menuservice.common.api.MenuPopulateContext;
import io.nexstudios.menuservice.common.api.MenuSlot;
import io.nexstudios.menuservice.common.api.item.MenuItem;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class BankExtraItemSupport {

  private static final String EXTRA_ITEMS_PATH = "extra-items";
  private static final String SLOT_PATH_PREFIX = "layout.slots.";
  private static final String LOGIC_IDENTIFIER = "nexeconomy-bank-extra-item";

  private BankExtraItemSupport() {}

  public static List<ExtraItemBinding> loadBindings(FileConfiguration config, ConfigItemService configItemService) {
    if (config == null || configItemService == null) {
      return List.of();
    }

    MapConfigSection root = MapConfigSection.of(config.getValues(false));
    ConfigSection extraItemsSection = root.getSection(EXTRA_ITEMS_PATH);
    if (extraItemsSection == null) {
      return List.of();
    }

    List<ExtraItemBinding> bindings = new ArrayList<>();
    for (String id : extraItemsSection.getKeys(false)) {
      if (id == null || id.isBlank()) {
        continue;
      }

      int slot = config.getInt(SLOT_PATH_PREFIX + id, -1);
      if (slot < 0) {
        continue;
      }

      ConfigurationSection itemSection = config.getSection(EXTRA_ITEMS_PATH + "." + id);
      if (itemSection == null) {
        continue;
      }

      ItemStack item = configItemService.convertSectionToItem(itemSection)
          .orElseGet(() -> new ItemStack(Material.BARRIER));

      ConfigSection effectSection = extraItemsSection.getSection(id);
      List<ConfigSection> effects = effectSection == null ? List.of() : effectSection.getSectionList("effects");

      bindings.add(new ExtraItemBinding(id, slot, item, effects == null ? List.of() : List.copyOf(effects)));
    }

    bindings.sort(Comparator.comparingInt(ExtraItemBinding::slot).thenComparing(binding -> binding.id().toLowerCase(Locale.ROOT)));
    return List.copyOf(bindings);
  }

  public static void populate(MenuPopulateContext ctx,
                       ServiceAccessor services,
                       List<ExtraItemBinding> bindings,
                       String menuContextId) {
    if (ctx == null || services == null || bindings == null || bindings.isEmpty()) {
      return;
    }

    for (ExtraItemBinding binding : bindings) {
      if (binding == null || binding.item() == null) {
        continue;
      }

      MenuSlot menuSlot = ctx.slot(binding.slot());
      menuSlot.setPlannedItem(() -> MenuItem.of(binding.item().clone()));
      menuSlot.onClick(clickCtx -> {
        clickCtx.cancel();
        Player player = Bukkit.getPlayer(clickCtx.viewer().uniqueId());
        if (player == null) {
          return;
        }

        triggerGeneralClick(services, player);
        executeEffects(services, player, menuContextId, binding.id(), binding.effects());
      });
    }
  }

  private static void triggerGeneralClick(ServiceAccessor services, Player player) {
    if (services == null || player == null) {
      return;
    }

    BankClickEffectService effects = services.getService(BankClickEffectService.class);
    if (effects != null) {
      effects.executeGeneralClick(player);
    }
  }

  private static void executeEffects(ServiceAccessor services,
                                     Player player,
                                     String menuContextId,
                                     String itemId,
                                     List<ConfigSection> effects) {
    if (services == null || player == null || effects == null || effects.isEmpty()) {
      return;
    }

    LogicEngineService logicEngineService = NexEconomyPlugin.getNexLogicService().getService(LogicEngineService.class);
    if (logicEngineService == null) {
      return;
    }

    LogicContext logicContext = new LogicContext(LOGIC_IDENTIFIER + ":" + (menuContextId == null ? "menu" : menuContextId) + ":" + itemId);
    logicContext.put(BukkitContextKeys.PLAYER, player);
    logicContext.put(BukkitContextKeys.WORLD, player.getWorld());
    logicContext.put(BukkitContextKeys.LOCATION, player.getLocation());

    logicEngineService.executeEffects(effects, logicContext);
  }

  public record ExtraItemBinding(String id, int slot, ItemStack item, List<ConfigSection> effects) {}
}


