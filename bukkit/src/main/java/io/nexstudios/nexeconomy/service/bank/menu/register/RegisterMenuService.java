package io.nexstudios.nexeconomy.service.bank.menu.register;

import io.nexstudios.nexeconomy.service.bank.menu.bank.BankDetailMenu;
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
    BankTransactionMenu.register(accessor);
  }
}
