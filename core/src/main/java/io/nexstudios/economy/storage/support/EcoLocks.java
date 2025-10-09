package io.nexstudios.economy.storage.support;

import io.nexstudios.economy.storage.model.AccountKey;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Striped locks for per-account synchronization without excessive lock objects.
 */
public final class EcoLocks {
    private static final int STRIPES = 4096; // power of two recommended
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public EcoLocks() {
        for (int i = 0; i < STRIPES; i++) locks[i] = new ReentrantLock();
    }

    public Lock lockFor(AccountKey key) {
        int idx = (key.hashCode() ^ (key.hashCode() >>> 16)) & (STRIPES - 1);
        return locks[idx];
    }
}