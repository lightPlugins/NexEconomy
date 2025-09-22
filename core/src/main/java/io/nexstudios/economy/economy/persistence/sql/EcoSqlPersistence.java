package io.nexstudios.economy.economy.persistence.sql;

import io.nexstudios.economy.economy.persistence.EcoPersistencePort;
import io.nexstudios.economy.economy.persistence.model.DbAccountSnapshot;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * JDBC-based implementation of EcoPersistencePort.
 * - Creates the schema if missing.
 * - Performs single-query bulk loads per player or full scan.
 * - Performs transactional batch upserts across multiple players.
 */
public record EcoSqlPersistence(DataSource dataSource, EcoSqlDialect dialect) implements EcoPersistencePort {

    public EcoSqlPersistence(DataSource dataSource, EcoSqlDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        ensureSchema();
    }

    @Override
    public CompletableFuture<Map<String, DbAccountSnapshot>> loadAllAccountsForPlayer(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = EcoTables.selectAllForPlayer();
            Map<String, DbAccountSnapshot> result = new HashMap<>();
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                SqlUtil.setUuid(ps, 1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = SqlUtil.getString(rs, EcoTables.COL_CURRENCY);
                        BigDecimal bal = rs.getBigDecimal(EcoTables.COL_BALANCE);
                        long ver = rs.getLong(EcoTables.COL_VERSION);
                        long ts = rs.getLong(EcoTables.COL_UPDATED_AT);
                        if (key != null) {
                            String k = key.toLowerCase(Locale.ROOT);
                            result.put(k, new DbAccountSnapshot(k, bal, ver, ts));
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("loadAllAccountsForPlayer failed: " + e.getMessage(), e);
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<Map<String, DbAccountSnapshot>> loadVersionsForPlayer(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = EcoTables.selectVersionsForPlayer();
            Map<String, DbAccountSnapshot> result = new HashMap<>();
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                SqlUtil.setUuid(ps, 1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = SqlUtil.getString(rs, EcoTables.COL_CURRENCY);
                        long ver = rs.getLong(EcoTables.COL_VERSION);
                        long ts = rs.getLong(EcoTables.COL_UPDATED_AT);
                        if (key != null) {
                            String k = key.toLowerCase(Locale.ROOT);
                            result.put(k, new DbAccountSnapshot(k, BigDecimal.ZERO, ver, ts));
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("loadVersionsForPlayer failed: " + e.getMessage(), e);
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<Map<UUID, Map<String, DbAccountSnapshot>>> loadAllAccountsAllPlayers() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = EcoTables.selectAllRows();
            Map<UUID, Map<String, DbAccountSnapshot>> out = new HashMap<>();
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String pidStr = SqlUtil.getString(rs, EcoTables.COL_PLAYER);
                    if (pidStr == null) continue;
                    UUID pid = UUID.fromString(pidStr);

                    String key = SqlUtil.getString(rs, EcoTables.COL_CURRENCY);
                    BigDecimal bal = rs.getBigDecimal(EcoTables.COL_BALANCE);
                    long ver = rs.getLong(EcoTables.COL_VERSION);
                    long ts = rs.getLong(EcoTables.COL_UPDATED_AT);
                    if (key == null) continue;
                    String k = key.toLowerCase(Locale.ROOT);

                    out.computeIfAbsent(pid, x -> new HashMap<>())
                            .put(k, new DbAccountSnapshot(k, bal, ver, ts));
                }
            } catch (SQLException e) {
                throw new RuntimeException("loadAllAccountsAllPlayers failed: " + e.getMessage(), e);
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Void> upsertBatch(Map<UUID, List<DbAccountSnapshot>> batchByPlayer) {
        if (batchByPlayer == null || batchByPlayer.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            String sql = (dialect == EcoSqlDialect.MYSQL) ? EcoTables.upsertMySql() : EcoTables.upsertSqlite();
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                c.setAutoCommit(false);
                int batchSize = 0;

                for (Map.Entry<UUID, List<DbAccountSnapshot>> entry : batchByPlayer.entrySet()) {
                    UUID playerId = entry.getKey();
                    for (DbAccountSnapshot snap : entry.getValue()) {
                        SqlUtil.setUuid(ps, 1, playerId);
                        ps.setString(2, snap.currencyKey());
                        ps.setBigDecimal(3, snap.balance());
                        ps.setLong(4, snap.version());
                        ps.setLong(5, snap.updatedAtMillis());
                        ps.addBatch();
                        batchSize++;

                        if (batchSize % 1000 == 0) {
                            ps.executeBatch();
                        }
                    }
                }

                ps.executeBatch();
                c.commit();
                c.setAutoCommit(true);
            } catch (SQLException e) {
                throw new RuntimeException("upsertBatch failed: " + e.getMessage(), e);
            }
        });
    }

    private void ensureSchema() {
        String ddl = (dialect == EcoSqlDialect.SQLITE) ? EcoTables.ddlSqlite() : EcoTables.ddlMySql();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new RuntimeException("ensureSchema failed: " + e.getMessage(), e);
        }
    }
}