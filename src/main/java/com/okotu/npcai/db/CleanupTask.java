package com.okotu.npcai.db;

import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Run periodically (async) to align MySQL with the configured sliding window:
 * the Caffeine cache already limits what's kept in RAM, but old messages
 * remain in MySQL until this job removes them. Doing it here instead of on
 * every single insert avoids a window-function DELETE on every message.
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
                plugin.getLogger().fine("Conversation cleanup: removed " + deleted + " rows beyond the window.");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error during periodic conversation cleanup", e);
        }
    }
}
