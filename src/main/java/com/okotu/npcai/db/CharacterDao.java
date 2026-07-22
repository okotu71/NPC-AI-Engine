package com.okotu.npcai.db;

import com.okotu.npcai.model.NpcCharacter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Access to the npc_character table. All methods are blocking: always call
 * them from an async thread (never from the server main thread).
 */
public class CharacterDao {

    private final Database database;
    private final String table;

    public CharacterDao(Database database) {
        this.database = database;
        this.table = database.table("npc_character");
    }

    public Optional<NpcCharacter> find(int npcId) throws SQLException {
        String sql = "SELECT npc_id, nome, backstory, personalita, tratti_json, model "
                + "FROM " + table + " WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new NpcCharacter(
                        rs.getInt("npc_id"),
                        rs.getString("nome"),
                        rs.getString("backstory"),
                        rs.getString("personalita"),
                        rs.getString("tratti_json"),
                        rs.getString("model")
                ));
            }
        }
    }

    /** Returns the existing character, or creates it with default values and returns it. */
    public NpcCharacter findOrCreate(int npcId, String fallbackNome) throws SQLException {
        Optional<NpcCharacter> existing = find(npcId);
        if (existing.isPresent()) {
            return existing.get();
        }
        NpcCharacter fresh = NpcCharacter.defaultFor(npcId, fallbackNome);
        insert(fresh);
        return fresh;
    }

    private void insert(NpcCharacter character) throws SQLException {
        String sql = "INSERT INTO " + table + " (npc_id, nome, backstory, personalita, tratti_json, model) "
                + "VALUES (?, ?, ?, ?, CAST(? AS JSON), ?) "
                + "ON DUPLICATE KEY UPDATE nome = VALUES(nome)";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, character.npcId());
            ps.setString(2, character.nome());
            ps.setString(3, character.backstory());
            ps.setString(4, character.personalita());
            ps.setString(5, character.traitsJson());
            ps.setString(6, character.model());
            ps.executeUpdate();
        }
    }

    public void updateBackstory(int npcId, String backstory, String personalita) throws SQLException {
        String sql = "UPDATE " + table + " SET backstory = ?, personalita = ? WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, backstory);
            ps.setString(2, personalita);
            ps.setInt(3, npcId);
            ps.executeUpdate();
        }
    }

    public void updateModel(int npcId, String model) throws SQLException {
        String sql = "UPDATE " + table + " SET model = ? WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, model);
            ps.setInt(2, npcId);
            ps.executeUpdate();
        }
    }
}
