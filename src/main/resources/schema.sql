-- =========================================================
-- okotu-npc-ai-engine - MySQL schema (template)
-- =========================================================
-- This file is applied automatically by Database.java on startup
-- (CREATE TABLE IF NOT EXISTS), against whichever database the active
-- profile (prod/test) points to.
--
-- {{PREFIX}} is replaced at runtime with the "mysql-table-prefix" value
-- configured for the active profile (empty string by default).
--
-- Ready-to-run reference copies for manual execution (with an empty prefix,
-- matching the default config) are provided separately in sql/okotu_npc_ai.sql
-- (prod) and sql/okotu_npc_ai_test.sql (test).

CREATE TABLE IF NOT EXISTS {{PREFIX}}npc_character (
    npc_id       INT UNSIGNED NOT NULL,
    nome         VARCHAR(64)  NOT NULL,
    backstory    TEXT         NOT NULL DEFAULT '',
    personalita  TEXT         NOT NULL DEFAULT '',
    tratti_json  JSON         NULL,
    model        VARCHAR(64)  NULL,          -- per-NPC override of the Ollama model, NULL = use default
    creato_il    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    aggiornato_il DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (npc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS {{PREFIX}}npc_conversation_log (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    npc_id       INT UNSIGNED NOT NULL,
    player_uuid  CHAR(36)     NOT NULL,
    ruolo        ENUM('player', 'npc') NOT NULL,
    messaggio    TEXT         NOT NULL,
    ts           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_npc_player_ts (npc_id, player_uuid, ts),
    CONSTRAINT {{PREFIX}}fk_conv_npc FOREIGN KEY (npc_id)
        REFERENCES {{PREFIX}}npc_character (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Rotation query: deletes everything except the last N rows per (npc_id, player_uuid) pair.
-- MySQL 8+: ROW_NUMBER() via a join subquery. Run periodically by CleanupTask
-- (see ConversationDao.trimAllHistories), not on every single insert.
--
-- DELETE c FROM {{PREFIX}}npc_conversation_log c
-- JOIN (
--     SELECT id,
--            ROW_NUMBER() OVER (PARTITION BY npc_id, player_uuid ORDER BY ts DESC, id DESC) AS rn
--     FROM {{PREFIX}}npc_conversation_log
-- ) ranked ON ranked.id = c.id
-- WHERE ranked.rn > 20;
