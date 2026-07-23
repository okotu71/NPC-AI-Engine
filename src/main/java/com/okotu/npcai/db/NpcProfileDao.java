package com.okotu.npcai.db;

import com.okotu.npcai.model.NpcProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Access to npc_profiles ("character sheet"). All methods are blocking:
 * always call them from an async thread (never from the server main thread).
 *
 * <p>No {@code model} column since 1.04 - the Ollama model is chosen purely
 * via {@code ollama.default-model} in config.yml, the same for every NPC.
 */
public class NpcProfileDao {

    private final Database database;
    private final String table;

    public NpcProfileDao(Database database) {
        this.database = database;
        this.table = database.table("npc_profiles");
    }

    public Optional<NpcProfile> find(int npcId) throws SQLException {
        String sql = "SELECT npc_id, name, role, personality, background, village, profession, "
                + "speech_style, knowledge, system_prompt FROM " + table + " WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(fromRow(rs));
            }
        }
    }

    /** Returns the existing profile, or creates it with default values and returns it. */
    public NpcProfile findOrCreate(int npcId, String fallbackName) throws SQLException {
        return findOrCreate(npcId, () -> NpcProfile.defaultFor(npcId, fallbackName));
    }

    /**
     * Returns the existing profile, or creates it using whatever
     * {@code defaultSupplier} produces (e.g. RandomProfileGenerator on first
     * conversation, or NpcProfile.defaultFor for a plain fallback) and
     * returns it.
     */
    public NpcProfile findOrCreate(int npcId, java.util.function.Supplier<NpcProfile> defaultSupplier) throws SQLException {
        Optional<NpcProfile> existing = find(npcId);
        if (existing.isPresent()) {
            return existing.get();
        }
        NpcProfile fresh = defaultSupplier.get();
        insert(fresh);
        return fresh;
    }

    private void insert(NpcProfile p) throws SQLException {
        String sql = "INSERT INTO " + table
                + " (npc_id, name, role, personality, background, village, profession, speech_style, "
                + "knowledge, system_prompt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name)";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, p.npcId());
            ps.setString(2, p.name());
            ps.setString(3, nvl(p.role()));
            ps.setString(4, nvl(p.personality()));
            ps.setString(5, nvl(p.background()));
            ps.setString(6, p.village());
            ps.setString(7, p.profession());
            ps.setString(8, p.speechStyle());
            ps.setString(9, p.knowledge());
            ps.setString(10, p.systemPrompt());
            ps.executeUpdate();
        }
    }

    /** Updates a single text/varchar field by column name. Used by the admin "profile" command. */
    public void updateField(int npcId, String column, String value) throws SQLException {
        if (!ALLOWED_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("Unknown or non-editable profile column: " + column);
        }
        String sql = "UPDATE " + table + " SET " + column + " = ? WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setInt(2, npcId);
            ps.executeUpdate();
        }
    }

    private static final java.util.Set<String> ALLOWED_COLUMNS = java.util.Set.of(
            "name", "role", "personality", "background", "village",
            "profession", "speech_style", "knowledge", "system_prompt");

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private NpcProfile fromRow(ResultSet rs) throws SQLException {
        return new NpcProfile(
                rs.getInt("npc_id"),
                rs.getString("name"),
                rs.getString("role"),
                rs.getString("personality"),
                rs.getString("background"),
                rs.getString("village"),
                rs.getString("profession"),
                rs.getString("speech_style"),
                rs.getString("knowledge"),
                rs.getString("system_prompt")
        );
    }
}
