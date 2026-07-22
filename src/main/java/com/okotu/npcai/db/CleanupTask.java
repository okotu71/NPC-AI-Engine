package com.okotu.npcai.db;

import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Girato periodicamente (async) per allineare MySQL alla finestra scorrevole:
 * la cache Caffeine gia' limita cosa viene tenuto in RAM, ma su MySQL i vecchi
 * messaggi restano finche' questo job non li rimuove. Farlo qui invece che ad
 * ogni singolo insert evita un DELETE con window function su ogni messaggio.
 */
public class CleanupTask implements Runnable {

    private final Plugin plugin;
    private final ConversationDao conversationDao;
    private final int historySize;

    public CleanupTask(Plugin plugin, ConversationDao conversationDao, int historySize) {
        this.plugin = plugin;
        this.conversationDao = conversationDao;
        this.historySize = historySize;
    }

    @Override
    public void run() {
        try {
            int deleted = conversationDao.trimAllHistories(historySize);
            if (deleted > 0) {
                plugin.getLogger().fine("Cleanup conversazioni: rimosse " + deleted + " righe oltre la finestra.");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Errore durante il cleanup periodico delle conversazioni", e);
        }
    }
}
