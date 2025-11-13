package io.nexstudios.economy.storage.persistence.sql;

/**
 * Table/column constants and DDL builders.
 */
final class EcoTables {

    private EcoTables() { }

    static final String TABLE = "eco_accounts";
    static final String COL_PLAYER = "player_uuid";
    static final String COL_CURRENCY = "currency_key";
    static final String COL_BALANCE = "balance";
    static final String COL_VERSION = "version";
    static final String COL_UPDATED_AT = "updated_at_millis";

    static String ddlMySql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + COL_PLAYER + " VARCHAR(36) NOT NULL,"
                + COL_CURRENCY + " VARCHAR(64) NOT NULL,"
                + COL_BALANCE + " DECIMAL(38, 9) NOT NULL DEFAULT 0,"
                + COL_VERSION + " BIGINT NOT NULL DEFAULT 0,"
                + COL_UPDATED_AT + " BIGINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (" + COL_PLAYER + ", " + COL_CURRENCY + ")"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
    }

    static String ddlSqlite() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + COL_PLAYER + " TEXT NOT NULL,"
                + COL_CURRENCY + " TEXT NOT NULL,"
                + COL_BALANCE + " NUMERIC NOT NULL DEFAULT 0,"
                + COL_VERSION + " INTEGER NOT NULL DEFAULT 0,"
                + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (" + COL_PLAYER + ", " + COL_CURRENCY + ")"
                + ");";
    }

    static String selectAllForPlayer() {
        return "SELECT " + COL_CURRENCY + ", " + COL_BALANCE + ", " + COL_VERSION + ", " + COL_UPDATED_AT
                + " FROM " + TABLE + " WHERE " + COL_PLAYER + " = ?";
    }

    static String selectVersionsForPlayer() {
        return "SELECT " + COL_CURRENCY + ", " + COL_VERSION + ", " + COL_UPDATED_AT
                + " FROM " + TABLE + " WHERE " + COL_PLAYER + " = ?";
    }

    public static String selectAllRows() {
        return "SELECT " + COL_PLAYER + ", " + COL_CURRENCY + ", " + COL_BALANCE + ", " + COL_VERSION + ", " + COL_UPDATED_AT
                + " FROM " + TABLE;
    }

    static String selectDistinctPlayersByCurrency() {
        return "SELECT DISTINCT " + COL_PLAYER + " FROM " + TABLE + " WHERE " + COL_CURRENCY + " = ?";
    }


    static String upsertMySql() {
        return "INSERT INTO " + TABLE + " ("
                + COL_PLAYER + ", " + COL_CURRENCY + ", " + COL_BALANCE + ", " + COL_VERSION + ", " + COL_UPDATED_AT + ") "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + COL_BALANCE + " = VALUES(" + COL_BALANCE + "), "
                + COL_VERSION + " = GREATEST(" + COL_VERSION + ", VALUES(" + COL_VERSION + ")), "
                + COL_UPDATED_AT + " = VALUES(" + COL_UPDATED_AT + ")";
    }

    static String upsertSqlite() {
        return "INSERT INTO " + TABLE + " ("
                + COL_PLAYER + ", " + COL_CURRENCY + ", " + COL_BALANCE + ", " + COL_VERSION + ", " + COL_UPDATED_AT + ") "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT(" + COL_PLAYER + ", " + COL_CURRENCY + ") DO UPDATE SET "
                + COL_BALANCE + " = excluded." + COL_BALANCE + ", "
                + COL_VERSION + " = MAX(" + COL_VERSION + ", excluded." + COL_VERSION + "), "
                + COL_UPDATED_AT + " = excluded." + COL_UPDATED_AT;
    }
}