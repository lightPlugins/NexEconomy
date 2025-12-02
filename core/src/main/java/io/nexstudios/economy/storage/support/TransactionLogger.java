package io.nexstudios.economy.storage.support;

import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Lightweight append-only transaction log writer.
 * Logs only local server transactions to eco_logs.txt in the plugin folder.
 */
public final class TransactionLogger {

    private final File logFile;
    private final DateTimeFormatter tsFmt = DateTimeFormatter.ISO_INSTANT;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

    public TransactionLogger(Plugin plugin) {
        // Ensure plugin data folder exists
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }
        this.logFile = new File(plugin.getDataFolder(), "eco_logs.txt");

        // Hintergrund-Writer-Thread starten
        Thread writerThread = new Thread(this::runWriter, "NexEconomy-TxLogger");
        writerThread.setDaemon(true);
        writerThread.start();
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
        // Nicht blockieren, falls die Queue voll wäre – im Worst Case Logzeile verwerfen.
        // Normalerweise ist LinkedBlockingQueue unbounded, daher praktisch immer erfolgreich.
        queue.offer(line);
    }

    private void runWriter() {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, true))) {
            while (true) {
                try {
                    String line = queue.take();
                    bw.write(line);
                    bw.newLine();
                    bw.flush();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (IOException ignored) {
            // Logger soll niemals Exceptions nach außen werfen.
        }
    }
}