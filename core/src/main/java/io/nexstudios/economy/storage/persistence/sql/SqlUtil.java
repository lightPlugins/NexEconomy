package io.nexstudios.economy.storage.persistence.sql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Small JDBC helpers to avoid duplication.
 */
final class SqlUtil {

    private SqlUtil() { }

    static void setUuid(PreparedStatement ps, int idx, UUID id) throws SQLException {
        ps.setString(idx, id.toString());
    }

    static String getString(ResultSet rs, String col) throws SQLException {
        return rs.getString(col);
    }
}