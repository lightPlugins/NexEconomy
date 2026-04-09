package io.nexstudios.nexeconomy.service.bank.effects;

import io.nexstudios.serviceregistry.di.Service;
import org.bukkit.entity.Player;

public interface BankClickEffectService extends Service {

  void executeGeneralClick(Player player);

  void executeBankUpgradeSuccess(Player player);
  void executeBankUpgradeFailed(Player player);

  void executeDepositSuccess(Player player);
  void executeDepositFailed(Player player);

  void executeWithdrawSuccess(Player player);
  void executeWithdrawFailed(Player player);

}
