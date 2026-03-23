package io.nexstudios.nexeconomy.service.migration;

import java.util.concurrent.CompletableFuture;

public interface MigrationImporter {

  /**
   * Unique identifier used by the command layer, e.g. "vault".
   */
  String id();

  /**
   * Returns true if this importer can run in the current server environment.
   * Example: Vault plugin installed + an Economy provider is registered.
   */
  boolean isAvailable();

  /**
   * Executes the migration.
   */
  CompletableFuture<MigrationResult> migrate(MigrationRequest request);
}