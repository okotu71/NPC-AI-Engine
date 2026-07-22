package com.okotu.npcai.db;

import com.okotu.npcai.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Datasource MySQL condiviso (pool HikariCP) + applicazione dello schema all'avvio.
 */
public class Database {

    private final Plugin plugin;
    private final HikariDataSource dataSource;

    public Database(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;

        String jdbcUrl = "jdbc:mysql://" + config.mysqlHost + ":" + config.mysqlPort
                + "/" + config.mysqlDatabase
                + (config.mysqlExtraParams.isBlank() ? "" : "?" + config.mysqlExtraParams);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.mysqlUsername);
        hikariConfig.setPassword(config.mysqlPassword);
        hikariConfig.setMaximumPoolSize(config.mysqlPoolSize);
        hikariConfig.setPoolName("okotu-npc-ai-pool");
        // Consigliato da HikariCP per il driver MySQL, riduce overhead su query ripetute
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Crea le tabelle se non esistono, leggendo schema.sql impacchettato nel jar. */
    public void applySchema() {
        List<String> statements = readSchemaStatements();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
            plugin.getLogger().info("Schema MySQL verificato/applicato (" + statements.size() + " statement).");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossibile applicare lo schema MySQL. "
                    + "Il plugin potrebbe non funzionare correttamente.", e);
        }
    }

    private List<String> readSchemaStatements() {
        List<String> statements = new ArrayList<>();
        try (InputStream in = plugin.getResource("schema.sql")) {
            if (in == null) {
                plugin.getLogger().warning("schema.sql non trovato nel jar.");
                return statements;
            }
            StringBuilder buffer = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                        continue;
                    }
                    buffer.append(line).append('\n');
                    if (trimmed.endsWith(";")) {
                        statements.add(buffer.toString());
                        buffer.setLength(0);
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Errore leggendo schema.sql", e);
        }
        return statements;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
