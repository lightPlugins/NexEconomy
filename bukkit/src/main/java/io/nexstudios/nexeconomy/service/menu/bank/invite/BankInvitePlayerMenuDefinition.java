package io.nexstudios.nexeconomy.service.menu.bank.invite;

import io.nexstudios.menuservice.api.MenuContext;
import io.nexstudios.menuservice.api.MenuDefinition;
import io.nexstudios.menuservice.api.MenuKey;
import io.nexstudios.menuservice.api.MenuView;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

/**
 * Definition for the bank-invite-player menu.
 * This menu is always opened directly with bank-specific runtime parameters;
 * it must not be opened through the registry.
 */
public class BankInvitePlayerMenuDefinition implements MenuDefinition {

  public static final MenuKey KEY = MenuKey.of("bank-invite-player");
  private final ServiceAccessor accessor;

  public BankInvitePlayerMenuDefinition(ServiceAccessor accessor) {
    this.accessor = accessor;
  }

  @Override
  public MenuKey key() {
    return KEY;
  }

  @Override
  public MenuView create(MenuContext menuContext) {
    throw new UnsupportedOperationException(
        "BankInvitePlayerMenuView must be opened directly with bankId, ownerUuid and viewerUuid.");
  }
}

