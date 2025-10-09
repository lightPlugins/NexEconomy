package io.nexstudios.economy.storage.support;

import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Lightweight append-only transaction log writer.
 * Logs only local server transactions to eco_logs.txt in the plugin folder.
 */
public final class TransactionLogger {

    private final File logFile;
    private final DateTimeFormatter tsFmt = DateTimeFormatter.ISO_INSTANT;

    public TransactionLogger(Plugin plugin) {
        // Ensure plugin data folder exists
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }
        this.logFile = new File(plugin.getDataFolder(), "eco_logs.txt");
    }

    public void log(UUID playerId, String currencyKey, String action, String details) {
        // action: DEPOSIT/WITHDRAW/CREATE/FLUSH/ERROR
        // details: "amount=..., balance=..., result=SUCCESS|... , msg=..."
        String ts = tsFmt.format(Instant.now().atOffset(ZoneOffset.UTC));
        String line = ts + " | " + action + " | player=" + playerId + " | currency=" + currencyKey + " | " + details;
        writeLine(line);
    }

    public void logRaw(String action, String details) {
        String ts = tsFmt.format(Instant.now().atOffset(ZoneOffset.UTC));
        String line = ts + " | " + action + " | " + details;
        writeLine(line);
    }

    private void writeLine(String line) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, true))) {
            bw.write(line);
            bw.newLine();
        } catch (Exception ignored) {
            // Do not rethrow from logger; keep it fail-safe.
        }
    }
}