package io.nexstudios.nexeconomy.service.bank.sync;

import io.nexstudios.serviceregistry.di.ServiceAccessor;

/**
 * @deprecated Renamed to {@link DefaultBankRedisSyncService}. This class exists only for
 *             backward compatibility and will be removed in a future version.
 *             Use {@link DefaultBankRedisSyncService} instead.
 */
@Deprecated(forRemoval = true)
public final class DefaultBankRedisSyncServiceService extends DefaultBankRedisSyncService {

  public DefaultBankRedisSyncServiceService(ServiceAccessor accessor) {
    super(accessor);
  }
}