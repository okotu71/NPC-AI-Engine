-- =========================================================
-- okotu-npc-ai-engine - schema MySQL
-- =========================================================
-- Applicato automaticamente da Database.java all'avvio (CREATE TABLE IF NOT EXISTS),
-- riportato qui anche come riferimento/documentazione e per eventuale uso manuale.

CREATE TABLE IF NOT EXISTS npc_character (
    npc_id       INT UNSIGNED NOT NULL,
    nome         VARCHAR(64)  NOT NULL,
    backstory    TEXT         NOT NULL DEFAULT '',
    personalita  TEXT         NOT NULL DEFAULT '',
    tratti_json  JSON         NULL,
    model        VARCHAR(64)  NULL,          -- override del modello Ollama per questo NPC, NULL = usa default
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
    CONSTRAINT fk_conv_npc FOREIGN KEY (npc_id) REFERENCES npc_character (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Query di rotazione: cancella tutto tranne le ultime N righe per coppia (npc_id, player_uuid).
-- MySQL 8+: usa ROW_NUMBER() via CTE. Eseguita periodicamente da CleanupTask (vedi ConversationDao.trimHistory).
--
-- DELETE c FROM npc_conversation_log c
-- JOIN (
--     SELECT id,
--            ROW_NUMBER() OVER (PARTITION BY npc_id, player_uuid ORDER BY ts DESC, id DESC) AS rn
--     FROM npc_conversation_log
-- ) ranked ON ranked.id = c.id
-- WHERE ranked.rn > 20;
