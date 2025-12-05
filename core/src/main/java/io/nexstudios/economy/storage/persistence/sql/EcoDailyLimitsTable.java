package io.nexstudios.economy.storage.persistence.sql;

/**
 * Table/column constants und DDL-Builder für die täglichen Limits.
 */
public final class EcoDailyLimitsTable {

    private EcoDailyLimitsTable() { }

    public static final String TABLE = "eco_daily_limits";
    public static final String COL_PLAYER = "player_uuid";
    public static final String COL_CURRENCY = "currency_key";
    public static final String COL_DAY = "day_utc";
    public static final String COL_SENT = "sent";
    public static final String COL_RECEIVED = "received";
    public static final String COL_UPDATED_AT = "updated_at_millis";

    public static String ddlMySql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + COL_PLAYER + " VARCHAR(36) NOT NULL,"
                + COL_CURRENCY + " VARCHAR(64) NOT NULL,"
                + COL_DAY + " INT NOT NULL,"
                + COL_SENT + " DECIMAL(38, 9) NOT NULL DEFAULT 0,"
                + COL_RECEIVED + " DECIMAL(38, 9) NOT NULL DEFAULT 0,"
                + COL_UPDATED_AT + " BIGINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (" + COL_PLAYER + ", " + COL_CURRENCY + ", " + COL_DAY + ")"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
    }

    public static String ddlSqlite() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + COL_PLAYER + " TEXT NOT NULL,"
                + COL_CURRENCY + " TEXT NOT NULL,"
                + COL_DAY + " INTEGER NOT NULL,"
                + COL_SENT + " NUMERIC NOT NULL DEFAULT 0,"
                + COL_RECEIVED + " NUMERIC NOT NULL DEFAULT 0,"
                + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (" + COL_PLAYER + ", " + COL_CURRENCY + ", " + COL_DAY + ")"
                + ");";
    }
}