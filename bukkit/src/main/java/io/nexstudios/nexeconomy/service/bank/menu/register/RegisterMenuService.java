package io.nexstudios.nexeconomy.service.bank.menu.register;

import io.nexstudios.nexeconomy.service.bank.menu.bank.BankDetailMenu;
import io.nexstudios.nexeconomy.service.bank.menu.bank.BankInvitePlayerMenu;
import io.nexstudios.nexeconomy.service.bank.menu.bank.BankInviteRoleMenu;
import io.nexstudios.nexeconomy.service.bank.menu.bank.BankMemberMenu;
import io.nexstudios.nexeconomy.service.bank.menu.bank.BankMemberRoleMenu;
import io.nexstudios.nexeconomy.service.bank.menu.bank.BankLevelMenu;
import io.nexstudios.nexeconomy.service.bank.menu.bank.BankOverviewMenu;
import io.nexstudios.nexeconomy.service.bank.menu.bank.BankTransactionMenu;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

public class RegisterMenuService implements Service {

  private final ServiceAccessor accessor;

  public RegisterMenuService(ServiceAccessor accessor) {
    this.accessor = accessor;
  }

  public void loadMenus() {
    BankOverviewMenu.register(accessor);
    BankDetailMenu.register(accessor);
    BankInvitePlayerMenu.register(accessor);
    BankInviteRoleMenu.register(accessor);
    BankMemberMenu.register(accessor);
    BankMemberRoleMenu.register(accessor);
    BankLevelMenu.register(accessor);
    BankTransactionMenu.register(accessor);
  }
}
