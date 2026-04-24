package io.nexstudios.nexeconomy.service.menu.bank.overview;

import io.nexstudios.menuservice.api.MenuContext;
import io.nexstudios.menuservice.api.MenuDefinition;
import io.nexstudios.menuservice.api.MenuKey;
import io.nexstudios.menuservice.api.MenuView;
import io.nexstudios.nexeconomy.service.menu.bank.overview.view.BankOverviewMenuView;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

public class BankOverviewMenuDefinition implements MenuDefinition {

  public static final MenuKey KEY = MenuKey.of("bank-overview");
  private final ServiceAccessor accessor;

  public BankOverviewMenuDefinition(ServiceAccessor accessor) {
    this.accessor = accessor;
  }

  @Override
  public MenuKey key() {
    return KEY;
  }

  @Override
  public MenuView create(MenuContext menuContext) {
    return new BankOverviewMenuView(accessor);
  }
}
