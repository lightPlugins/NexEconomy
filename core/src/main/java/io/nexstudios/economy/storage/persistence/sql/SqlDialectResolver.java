package io.nexstudios.economy.storage.persistence.sql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

/**
 * Resolves SQL dialect by inspecting JDBC metadata.
 */
public final class SqlDialectResolver {

    private SqlDialectResolver() { }

    public static EcoSqlDialect resolve(Connection connection) {
        try {
            DatabaseMetaData md = connection.getMetaData();
            String product = safeLower(md.getDatabaseProductName());
            String url = safeLower(md.getURL());

            // Detect SQLite by product or URL
            if (product.contains("sqlite") || url.startsWith("jdbc:sqlite")) {
                return EcoSqlDialect.SQLITE;
            }
            // Default to MYSQL for MySQL/MariaDB (product names vary, but both are treated the same here)
            return EcoSqlDialect.MYSQL;
        } catch (Exception e) {
            // Fallback: default to MYSQL
            return EcoSqlDialect.MYSQL;
        }
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}