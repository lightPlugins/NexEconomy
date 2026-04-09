package io.nexstudios.nexeconomy.service.bank.effects;

import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexlogic.bukkit.services.effects.context.BukkitContextKeys;
import io.nexstudios.nexlogic.common.effects.config.ConfigSection;
import io.nexstudios.nexlogic.common.effects.config.MapConfigSection;
import io.nexstudios.nexlogic.common.effects.model.LogicContext;
import io.nexstudios.nexlogic.common.services.engine.LogicEngineService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.List;

@Dependencies({
    FileReaderService.class
})
public class DefaultBankClickEffectService implements BankClickEffectService {

  private static final String DEFAULT_PATH = "bank.inventory-effects";
  private static final String DEFAULT_IDENTIFIER = "nexeconomy-click-effects";

  private final FileReaderService fileReaderService;
  private final LogicEngineService logicEngineService;
  private FileConfiguration settings;

  public DefaultBankClickEffectService(ServiceAccessor accessor) {
    this.fileReaderService = accessor.getService(FileReaderService.class);
    this.logicEngineService = NexEconomyPlugin.getNexLogicService().getService(LogicEngineService.class);
    reload();
  }

  public void reload() {
    this.settings = fileReaderService.load(Path.of("settings.yml"), "settings.yml", true);
  }

  @Override
  public void executeGeneralClick(Player player) {
    executeEffect(player, "general-click");
  }

  @Override
  public void executeBankUpgradeSuccess(Player player) {
    executeEffect(player, "bank-upgrade-success");
  }

  @Override
  public void executeBankUpgradeFailed(Player player) {
    executeEffect(player, "bank-upgrade-fail");
  }

  @Override
  public void executeDepositSuccess(Player player) {
    executeEffect(player, "deposit-success");
  }

  @Override
  public void executeDepositFailed(Player player) {
    executeEffect(player, "deposit-fail");
  }

  @Override
  public void executeWithdrawSuccess(Player player) {
    executeEffect(player, "withdraw-success");
  }

  @Override
  public void executeWithdrawFailed(Player player) {
    executeEffect(player, "withdraw-fail");
  }

  private LogicContext generateLogicContext(Player player, String context) {
    LogicContext logitContext = new LogicContext(context);
    logitContext.put(BukkitContextKeys.PLAYER, player);
    logitContext.put(BukkitContextKeys.WORLD, player.getWorld());
    logitContext.put(BukkitContextKeys.LOCATION, player.getLocation());
    return logitContext;
  }

  private List<ConfigSection> getEffects(String section) {
    MapConfigSection cfg = MapConfigSection.of(settings.getValues(false));
    return cfg.getSection(DEFAULT_PATH).getSectionList(section);
  }

  private void executeEffect(Player player, String section) {
    logicEngineService.executeEffects(
        getEffects(section),
        generateLogicContext(player, DEFAULT_IDENTIFIER));
  }
}
