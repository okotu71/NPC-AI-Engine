package com.okotu.npcai.db;

import com.okotu.npcai.model.ConversationEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Access to the npc_conversation_log table. All methods are blocking: always
 * call them from an async thread (never from the server main thread).
 */
public class ConversationDao {

    private final Database database;
    private final String table;

    public ConversationDao(Database database) {
        this.database = database;
        this.table = database.table("npc_conversation_log");
    }

    public void insert(int npcId, UUID playerUuid, ConversationEntry entry) throws SQLException {
        String sql = "INSERT INTO " + table + " (npc_id, player_uuid, ruolo, messaggio, ts) "
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

    /** Last {@code limit} turns for the (npc, player) pair, in ascending chronological order. */
    public List<ConversationEntry> fetchLast(int npcId, UUID playerUuid, int limit) throws SQLException {
        String sql = "SELECT ruolo, messaggio, ts FROM " + table + " "
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
     * Global rotation: keeps only the last {@code keep} rows for every
     * (npc_id, player_uuid) pair across the whole table. Meant to run
     * periodically (see CleanupTask), not on every message, to avoid
     * overloading MySQL.
     */
    public int trimAllHistories(int keep) throws SQLException {
        String sql = "DELETE c FROM " + table + " c "
                + "JOIN ( "
                + "    SELECT id, "
                + "           ROW_NUMBER() OVER (PARTITION BY npc_id, player_uuid ORDER BY ts DESC, id DESC) AS rn "
                + "    FROM " + table + " "
                + ") ranked ON ranked.id = c.id "
                + "WHERE ranked.rn > ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, keep);
            return ps.executeUpdate();
        }
    }
}
