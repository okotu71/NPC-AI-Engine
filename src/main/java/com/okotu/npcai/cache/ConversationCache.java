package com.okotu.npcai.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.okotu.npcai.config.PluginConfig;
import com.okotu.npcai.db.ConversationDao;
import com.okotu.npcai.model.ConversationEntry;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tiene in RAM le ultime N battute per coppia (npc, player), evitando una query
 * MySQL ad ogni singolo messaggio. MySQL resta la fonte di verita' persistente:
 * su cache-miss (es. dopo un riavvio) si ricarica da li'.
 *
 * Chiave cache: "npcId:playerUuid".
 */
public class ConversationCache {

    private final ConversationDao dao;
    private final int historySize;
    private final Cache<String, Deque<ConversationEntry>> cache;
    // Un lock per chiave eviterebbe contese inutili; per semplicita' usiamo
    // synchronized sulla singola Deque, che e' comunque per-coppia-npc/player.
    private final ReentrantLock loadLock = new ReentrantLock();

    public ConversationCache(ConversationDao dao, PluginConfig config) {
        this.dao = dao;
        this.historySize = config.historySize;
        this.cache = Caffeine.newBuilder()
                .maximumSize(config.cacheMaxEntries)
                .expireAfterAccess(config.cacheExpireAfterMinutes, TimeUnit.MINUTES)
                .build();
    }

    private static String key(int npcId, UUID playerUuid) {
        return npcId + ":" + playerUuid;
    }

    /**
     * Ritorna la cronologia corrente (letta da cache, o caricata da MySQL se assente).
     * BLOCCANTE se serve un cache-load da DB: chiamare sempre da thread asincrono.
     */
    public List<ConversationEntry> getHistory(int npcId, UUID playerUuid) throws SQLException {
        String k = key(npcId, playerUuid);
        Deque<ConversationEntry> deque = cache.getIfPresent(k);
        if (deque == null) {
            loadLock.lock();
            try {
                deque = cache.getIfPresent(k);
                if (deque == null) {
                    List<ConversationEntry> fromDb = dao.fetchLast(npcId, playerUuid, historySize);
                    deque = new ArrayDeque<>(fromDb);
                    cache.put(k, deque);
                }
            } finally {
                loadLock.unlock();
            }
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    /**
     * Aggiunge una battuta sia alla cache in RAM (troncando alla finestra configurata)
     * sia a MySQL (persistenza). La scrittura su MySQL e' bloccante: chiamare da async.
     */
    public void append(int npcId, UUID playerUuid, ConversationEntry entry) throws SQLException {
        String k = key(npcId, playerUuid);
        Deque<ConversationEntry> deque = cache.getIfPresent(k);
        if (deque == null) {
            // Popola la cache leggendo lo storico esistente prima di aggiungere la nuova voce
            getHistory(npcId, playerUuid);
            deque = cache.getIfPresent(k);
        }
        synchronized (deque) {
            deque.addLast(entry);
            while (deque.size() > historySize) {
                deque.removeFirst();
            }
        }
        dao.insert(npcId, playerUuid, entry);
    }

    public void invalidate(int npcId, UUID playerUuid) {
        cache.invalidate(key(npcId, playerUuid));
    }
}
