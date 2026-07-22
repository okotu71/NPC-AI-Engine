# okotu-npc-ai-engine

Paper/Spigot plugin that connects Citizens-managed NPCs to an Ollama docking
service (see the Pterodactyl egg provided separately) and persists backstory +
the last N turns per (NPC, player) pair to MySQL, with an in-RAM cache
(Caffeine) so the database isn't hit on every single message.

## Status

This is a **functionally complete skeleton** (follows current Paper/Citizens/
Ollama APIs), but it has **not been built with Maven in the environment that
generated it** (no network access in that sandbox). Before going to
production:

1. `mvn clean package` locally and fix any compile nits.
2. Double-check the versions in `pom.xml` (`paper.version`, `citizens.version`,
   `mysql.version`, etc.) against what's currently published in their
   respective repositories.
3. Test on a development server before going live.

## Versioning

The jar filename always embeds the Maven version (`<finalName>` in `pom.xml`
uses `${project.artifactId}-${project.version}`), e.g.
`okotu-npc-ai-engine-1.01.jar`. `plugin.yml`'s `version:` field is filled in
automatically at build time from the same value (Maven resource filtering),
so **the only place you need to bump the version for a new release is
`pom.xml`**.

## Structure

```
src/main/java/com/okotu/npcai/
├── OkotuNpcAiPlugin.java        # bootstrap: wires all modules together
├── config/PluginConfig.java     # typed reading of config.yml, incl. prod/test profile
├── db/
│   ├── Database.java             # HikariCP pool + schema.sql application + table-prefix resolution
│   ├── CharacterDao.java         # CRUD on npc_character
│   ├── ConversationDao.java      # insert / fetch / rotation on npc_conversation_log
│   └── CleanupTask.java          # periodic MySQL rotation job
├── cache/ConversationCache.java  # in-RAM sliding window (Caffeine)
├── model/                        # ConversationEntry, NpcCharacter
├── ai/
│   ├── OllamaClient.java         # async HTTP client for /api/chat, with retry/timeout
│   └── PromptBuilder.java        # backstory+history -> prompt for Ollama
├── service/ConversationService.java  # orchestrates one dialogue turn, incl. fallback
├── npc/NpcBridgeListener.java    # NPC click -> chat capture -> reply
├── command/OkotuCommand.java     # /okotunpc reload|setmodel|setbackstory|info
└── util/RateLimiter.java         # per-player cooldown

src/main/resources/
├── plugin.yml     # Bukkit manifest (build-time, packaged into the jar)
├── config.yml     # default configuration, copied to plugins/OkotuNpcAiEngine/ on first run
└── schema.sql      # schema template ({{PREFIX}} placeholder), applied automatically on startup

sql/
├── okotu_npc_ai.sql       # ready-to-run reference copy for the PROD database
└── okotu_npc_ai_test.sql  # ready-to-run reference copy for the TEST database
```

## Setup

### 1. Database(s)

Two separate databases are expected: `okotu_npc_ai` (prod) and
`okotu_npc_ai_test` (test). You can provision them ahead of time with the
scripts in `sql/`, or just let the plugin create the tables automatically on
first startup (`CREATE TABLE IF NOT EXISTS`, from `schema.sql`) — either way
the *databases themselves* must already exist and the configured user needs
privileges on them:

```sql
CREATE DATABASE IF NOT EXISTS okotu_npc_ai      CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS okotu_npc_ai_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'usr'@'%' IDENTIFIED BY 'psw';
GRANT ALL PRIVILEGES ON okotu_npc_ai.*      TO 'usr'@'%';
GRANT ALL PRIVILEGES ON okotu_npc_ai_test.* TO 'usr'@'%';
FLUSH PRIVILEGES;
```

### 2. Choosing prod vs test

`config.yml` has two independent MySQL blocks, `prod:` and `test:`, each with
its own host/port/database/username/password/table-prefix. Which one is used
is controlled by `active-profile` in `config.yml`:

```yaml
active-profile: "prod"   # or "test"
```

You can override this **without editing the file**, e.g. from your
Pterodactyl startup parameters or systemd unit, with a JVM system property:

```
-Dokotu.profile=test
```

The system property always wins over `active-profile` in `config.yml`. This
is resolved once at startup (and again on `/okotunpc reload`), so switching
profiles requires restarting the server or reloading the plugin — it does not
hot-swap an already-open MySQL connection pool mid-session.

### 3. Table prefix

Each profile also has `mysql-table-prefix` (empty by default). If set (e.g.
`"srv1_"`), the plugin will create/use `srv1_npc_character` and
`srv1_npc_conversation_log` instead of the plain names. Useful if you want
several deployments to share one physical database.

### 4. Ollama docking

Point `ollama.base-url` in `config.yml` to your Pterodactyl-hosted Ollama
server (see the egg `egg-ollama-okotu-npc-ai-engine.json` provided
separately), e.g. `http://NODE_IP:ASSIGNED_PORT`.

### 5. Build

```bash
mvn clean package
```

The final jar (`target/okotu-npc-ai-engine-1.01.jar`) already bundles
HikariCP, Caffeine, Gson and the MySQL driver (shaded under
`com.okotu.npcai.libs.*`), so no extra libraries are needed on the server —
only Citizens as a runtime dependency.

### 6. First run

On first start, if `plugins/OkotuNpcAiEngine/config.yml` doesn't exist yet,
the plugin creates it from the bundled default. Edit it with your real MySQL
credentials and Ollama URL, then `/okotunpc reload`.

> **About `plugin.yml`**: unlike `config.yml`, `plugin.yml` is a **build-time**
> manifest packaged inside the jar — it's what tells Bukkit/Paper the plugin
> exists in the first place, so it can't be "created at runtime" the plugin
> itself. If a built jar is missing it (plugin fails to load / shows up as
> unrecognized), that's a packaging issue, not a runtime one — see
> Troubleshooting below.

## In-game usage

- Right-click a Citizens NPC → the plugin "listens" for the player's next chat
  message (30s timeout, then the conversation expires).
- The message is sent to the configured model (default or per-NPC override),
  together with the backstory and the last `conversation.history-size` turns
  for that NPC/player pair.
- The reply appears in chat, prefixed with the NPC's name.
- If Ollama doesn't respond within `ollama.timeout-ms` (after any configured
  retries), the player gets a random fallback message from
  `fallback.messages`, and the conversation doesn't break: the player's
  message is still saved.

### Commands (permission `okotu.npcai.admin`, default op)

- `/okotunpc reload` — reload config.yml
- `/okotunpc setmodel <npcId> <model>` — per-NPC model override
- `/okotunpc setbackstory <npcId> <backstory text>|<personality>` — set
  backstory (`|` separates backstory from personality)
- `/okotunpc info <npcId>` — show the stored character

## Troubleshooting

- **Plugin doesn't load / `plugin.yml` seems missing from the jar**: run
  `unzip -l target/okotu-npc-ai-engine-1.01.jar | grep plugin.yml` after
  building. It should be at the jar root. If it's missing, check that
  `src/main/resources/plugin.yml` exists and that no custom `<resources>`
  block in `pom.xml` was changed to exclude it (the shipped `pom.xml` filters
  `src/main/resources` as a whole, which includes it). A stale `target/`
  from a partial/failed previous build can also cause this — try
  `mvn clean package` again from scratch.
- **MySQL connection errors on startup**: check `active-profile` actually
  matches a section present in `config.yml`, and that the account has
  privileges on the configured database.

## Known limitations / suggested next steps

- **Chat capture** uses the classic `AsyncPlayerChatEvent` (deprecated but
  still functional) for broader Spigot/Paper compatibility. On recent Paper
  you can migrate to `io.papermc.paper.event.player.AsyncChatEvent` if you
  want Adventure Component support in messages.
- **Context window**: with very long backstories or `history-size` raised
  well above 20, consider periodically summarizing older history instead of
  including it in full in the prompt.
- **One active conversation per player**: a player can only have one "active
  conversation" at a time (the last NPC they clicked). For parallel
  conversations with multiple NPCs at once, `NpcBridgeListener`'s
  `Map<UUID, ActiveConversation>` needs to become something that tracks more
  than one NPC per player.
- **Multi-node failover**: if you later run multiple Ollama dockings (e.g. to
  load-balance across several Pterodactyl eggs), `OllamaClient` will need
  node selection (round robin / health check) instead of a single
  `base-url`.

## Migrating from 1.0.0 to 1.01

**No column-level `ALTER TABLE` is needed** — `npc_character` and
`npc_conversation_log` have the exact same columns as in 1.0.0. What changed
between the two versions is:

1. The **database name**: 1.0.0 used a single generic database (whatever you
   named it, e.g. `okotu_npc`, driven by `mysql.database` in the old
   `config.yml`). 1.01 expects two explicitly named databases, `okotu_npc_ai`
   (prod) and `okotu_npc_ai_test` (test).
2. Config layout: MySQL settings moved from a single flat `mysql:` block to
   two profiles (`prod:` / `test:`), selected by `active-profile` (or
   `-Dokotu.profile=...`).
3. An optional **table prefix** was added (`mysql-table-prefix`, empty by
   default — with an empty prefix, table names are unchanged from 1.0.0).

### If you already have 1.0.0 data you want to keep

With an empty table prefix (the default), the table structure is identical,
so migration is a database-level rename, not a schema change. Two options:

**Option A — same MySQL server, rename in place** (MySQL doesn't support
`RENAME DATABASE` directly, so recreate + move tables):

```sql
CREATE DATABASE IF NOT EXISTS okotu_npc_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

RENAME TABLE old_database_name.npc_character        TO okotu_npc_ai.npc_character;
RENAME TABLE old_database_name.npc_conversation_log  TO okotu_npc_ai.npc_conversation_log;

-- once you've verified everything moved correctly:
DROP DATABASE old_database_name;
```

Repeat against `okotu_npc_ai_test` if you also want to seed the test database
(e.g. from a copy) — `RENAME TABLE` moves the table, so for a copy instead use
`mysqldump` (Option B) or `CREATE TABLE ... LIKE` + `INSERT ... SELECT`.

**Option B — dump and restore** (works across servers too):

```bash
mysqldump -u usr -p old_database_name npc_character npc_conversation_log > okotu_dump.sql
mysql -u usr -p okotu_npc_ai < okotu_dump.sql
```

### If you want to use a non-empty table prefix going forward

Only needed if you're consolidating multiple deployments into one physical
database. Rename the tables to include the prefix you set in
`mysql-table-prefix`, and rename the foreign key constraint to match (the
constraint name in 1.01's `schema.sql` is `{{PREFIX}}fk_conv_npc`):

```sql
RENAME TABLE npc_character       TO yourprefix_npc_character;
RENAME TABLE npc_conversation_log TO yourprefix_npc_conversation_log;

ALTER TABLE yourprefix_npc_conversation_log
    DROP FOREIGN KEY fk_conv_npc,
    ADD CONSTRAINT yourprefix_fk_conv_npc FOREIGN KEY (npc_id)
        REFERENCES yourprefix_npc_character (npc_id) ON DELETE CASCADE;
```

(This is the one case where an actual `ALTER TABLE` is involved — dropping
and re-adding the foreign key constraint under its new prefixed name. Column
definitions themselves are untouched.)
