package io.nexstudios.nexeconomy.service.migration;

import io.nexstudios.nexeconomy.service.migration.impl.VaultEconomyMigrationImporter;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Dependencies({
    VaultEconomyMigrationImporter.class
})
public final class MigrationService implements Service {

  private final List<MigrationImporter> importers;

  public MigrationService(ServiceAccessor accessor) {
    // Register new importers here later (EssentialsX, CMI, ...)
    this.importers = List.of(
        accessor.getService(VaultEconomyMigrationImporter.class)
    );
  }

  public List<String> availableImporterIds() {
    List<String> out = new ArrayList<>();
    for (MigrationImporter importer : importers) {
      if (importer != null && importer.isAvailable()) {
        out.add(importer.id());
      }
    }
    return out;
  }

  public CompletableFuture<MigrationResult> migrate(MigrationRequest request) {
    Objects.requireNonNull(request, "request");

    MigrationImporter importer = findAvailableImporter(request.importerId());
    if (importer == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("Importer not available: " + request.importerId()));
    }
    return importer.migrate(request);
  }

  private MigrationImporter findAvailableImporter(String id) {
    if (id == null || id.isBlank()) return null;

    for (MigrationImporter importer : importers) {
      if (importer == null) continue;
      if (!id.equalsIgnoreCase(importer.id())) continue;
      if (!importer.isAvailable()) return null;
      return importer;
    }
    return null;
  }
}