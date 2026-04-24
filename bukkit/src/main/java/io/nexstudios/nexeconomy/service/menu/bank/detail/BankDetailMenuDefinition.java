package io.nexstudios.nexeconomy.service.menu.bank.detail;

import io.nexstudios.menuservice.api.MenuContext;
import io.nexstudios.menuservice.api.MenuDefinition;
import io.nexstudios.menuservice.api.MenuKey;
import io.nexstudios.menuservice.api.MenuView;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

/**
 * Definition for the bank-detail menu.
 * This menu is always opened directly with bank-specific runtime parameters;
 * it must not be opened through the registry.
 */
public class BankDetailMenuDefinition implements MenuDefinition {

  public static final MenuKey KEY = MenuKey.of("bank-detail");
  private final ServiceAccessor accessor;

  public BankDetailMenuDefinition(ServiceAccessor accessor) {
    this.accessor = accessor;
  }

  @Override
  public MenuKey key() {
    return KEY;
  }

  @Override
  public MenuView create(MenuContext menuContext) {
    throw new UnsupportedOperationException(
        "BankDetailMenuView must be opened directly with bankId and ownerUuid.");
  }
}
