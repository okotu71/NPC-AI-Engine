package com.okotu.npcai.db;

import com.okotu.npcai.model.ConversationEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Accesso a npc_conversation_log. Tutti i metodi sono bloccanti: vanno sempre
 * chiamati da un thread asincrono (mai dal main thread del server).
 */
public class ConversationDao {

    private final Database database;

    public ConversationDao(Database database) {
        this.database = database;
    }

    public void insert(int npcId, UUID playerUuid, ConversationEntry entry) throws SQLException {
        String sql = "INSERT INTO npc_conversation_log (npc_id, player_uuid, ruolo, messaggio, ts) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, playerUuid.toString());
            ps.setString(3, entry.role().dbValue());
            ps.setString(4, entry.message());
            ps.setTimestamp(5, Timestamp.from(entry.timestamp()));
            ps.executeUpdate();
        }
    }

    /** Ultime {@code limit} battute per la coppia (npc, player), in ordine cronologico crescente. */
    public List<ConversationEntry> fetchLast(int npcId, UUID playerUuid, int limit) throws SQLException {
        String sql = "SELECT ruolo, messaggio, ts FROM npc_conversation_log "
                + "WHERE npc_id = ? AND player_uuid = ? "
                + "ORDER BY ts DESC, id DESC LIMIT ?";
        List<ConversationEntry> reversed = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, playerUuid.toString());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    reversed.add(new ConversationEntry(
                            ConversationEntry.Role.fromDb(rs.getString("ruolo")),
                            rs.getString("messaggio"),
                            rs.getTimestamp("ts").toInstant()
                    ));
                }
            }
        }
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * Rotazione globale: mantiene solo le ultime {@code keep} righe per ogni coppia
     * (npc_id, player_uuid) su tutta la tabella. Pensata per girare periodicamente
     * (vedi CleanupTask), non ad ogni messaggio, per non appesantire MySQL.
     */
    public int trimAllHistories(int keep) throws SQLException {
        String sql = "DELETE c FROM npc_conversation_log c "
                + "JOIN ( "
                + "    SELECT id, "
                + "           ROW_NUMBER() OVER (PARTITION BY npc_id, player_uuid ORDER BY ts DESC, id DESC) AS rn "
                + "    FROM npc_conversation_log "
                + ") ranked ON ranked.id = c.id "
                + "WHERE ranked.rn > ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, keep);
            return ps.executeUpdate();
        }
    }
}
