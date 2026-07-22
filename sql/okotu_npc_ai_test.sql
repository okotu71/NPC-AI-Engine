-- =========================================================
-- okotu-npc-ai-engine - TEST database (okotu_npc_ai_test)
-- =========================================================
-- Identical structure to okotu_npc_ai.sql, pointed at the test database used
-- when active-profile: "test" (or -Dokotu.profile=test) is selected.

CREATE DATABASE IF NOT EXISTS okotu_npc_ai_test
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE okotu_npc_ai_test;

CREATE TABLE IF NOT EXISTS npc_character (
    npc_id       INT UNSIGNED NOT NULL,
    nome         VARCHAR(64)  NOT NULL,
    backstory    TEXT         NOT NULL DEFAULT '',
    personalita  TEXT         NOT NULL DEFAULT '',
    tratti_json  JSON         NULL,
    model        VARCHAR(64)  NULL,
    creato_il    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    aggiornato_il DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (npc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_conversation_log (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    npc_id       INT UNSIGNED NOT NULL,
    player_uuid  CHAR(36)     NOT NULL,
    ruolo        ENUM('player', 'npc') NOT NULL,
    messaggio    TEXT         NOT NULL,
    ts           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_npc_player_ts (npc_id, player_uuid, ts),
    CONSTRAINT fk_conv_npc FOREIGN KEY (npc_id)
        REFERENCES npc_character (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Example dedicated user (adjust host/password, then match config.yml "test" block):
-- CREATE USER 'usr'@'%' IDENTIFIED BY 'psw';
-- GRANT ALL PRIVILEGES ON okotu_npc_ai_test.* TO 'usr'@'%';
-- FLUSH PRIVILEGES;
