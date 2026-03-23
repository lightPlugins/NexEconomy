package io.nexstudios.nexeconomy.service.economy;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class EconomyLocks {

  private static final int STRIPES = 1024;
  private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

  static {
    for (int i = 0; i < STRIPES; i++) {
      LOCKS[i] = new ReentrantLock();
    }
  }

  private EconomyLocks() {}

  public static ReentrantLock lockFor(UUID uuid) {
    int h = uuid == null ? 0 : uuid.hashCode();
    int idx = (h & 0x7fffffff) % STRIPES;
    return LOCKS[idx];
  }
}