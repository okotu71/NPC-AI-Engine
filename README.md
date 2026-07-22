# okotu-npc-ai-engine

Plugin Paper/Spigot che collega NPC gestiti da Citizens a un docking Ollama (vedi l'egg
Pterodactyl fornito a parte) e persiste backstory + ultime N iterazioni per coppia
(NPC, player) su MySQL, con cache in RAM (Caffeine) per non martellare il DB ad ogni messaggio.

## Stato del progetto

Questo è uno **scheletro funzionale completo** (compila concettualmente, segue le API
correnti di Paper/Citizens/Ollama), ma **non è stato buildato con Maven in questo
ambiente** (nessun accesso di rete nel sandbox in cui è stato generato). Prima di
metterlo in produzione:

1. `mvn clean package` in locale e correggi eventuali refusi di compilazione.
2. Verifica le versioni in `pom.xml` (`paper.version`, `citizens.version`,
   `mysql.version` ecc.) contro quelle attualmente disponibili nei rispettivi repository.
3. Testa su un server di sviluppo prima di andare live.

## Struttura

```
src/main/java/com/okotu/npcai/
├── OkotuNpcAiPlugin.java        # bootstrap: collega tutti i moduli
├── config/PluginConfig.java     # lettura tipizzata di config.yml
├── db/
│   ├── Database.java            # pool HikariCP + applicazione schema.sql
│   ├── CharacterDao.java        # CRUD npc_character
│   ├── ConversationDao.java     # insert / fetch / rotazione npc_conversation_log
│   └── CleanupTask.java         # job periodico di rotazione MySQL
├── cache/ConversationCache.java # finestra scorrevole in RAM (Caffeine)
├── model/                       # ConversationEntry, NpcCharacter
├── ai/
│   ├── OllamaClient.java        # client HTTP async verso /api/chat, con retry/timeout
│   └── PromptBuilder.java       # backstory+cronologia -> prompt per Ollama
├── service/ConversationService.java  # orchestrazione di un turno di dialogo, con fallback
├── npc/NpcBridgeListener.java   # click su NPC Citizens -> cattura chat -> risposta
├── command/OkotuCommand.java    # /okotunpc reload|setmodel|setbackstory|info
└── util/RateLimiter.java        # cooldown per-player
```

## Setup

### 1. Database

Crea un database e un utente dedicati:

```sql
CREATE DATABASE okotu_npc CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'okotu'@'%' IDENTIFIED BY 'una-password-forte';
GRANT ALL PRIVILEGES ON okotu_npc.* TO 'okotu'@'%';
FLUSH PRIVILEGES;
```

Lo schema (`schema.sql`) viene applicato automaticamente all'avvio del plugin
(`CREATE TABLE IF NOT EXISTS`), non serve eseguirlo a mano — è incluso anche come
riferimento/documentazione.

### 2. Docking Ollama

Punta `ollama.base-url` in `config.yml` all'indirizzo:porta del server Pterodactyl
creato con l'egg `egg-ollama-okotu-npc-ai-engine.json` (es. `http://IP_NODO:PORTA_ASSEGNATA`).

### 3. Build

```bash
mvn clean package
```

Il jar finale (`target/okotu-npc-ai-engine-1.0.0.jar`) include già HikariCP, Caffeine,
Gson e il driver MySQL (shadati sotto `com.okotu.npcai.libs.*`), quindi non servono
librerie esterne aggiuntive nel server — solo Citizens come dipendenza runtime.

### 4. Config

Modifica `plugins/OkotuNpcAiEngine/config.yml` (generato al primo avvio) con le tue
credenziali MySQL e l'URL del docking Ollama, poi `/okotunpc reload`.

## Uso in gioco

- Click destro su un NPC Citizens → il plugin "ascolta" il prossimo messaggio in chat
  del player (max 30s, poi la conversazione scade).
- Il messaggio viene inviato al modello configurato (default o override per NPC),
  insieme a backstory + ultime `conversation.history-size` battute per quella coppia
  NPC/player.
- La risposta arriva come messaggio in chat, prefissata dal nome dell'NPC.
- Se Ollama non risponde entro `ollama.timeout-ms` (dopo gli eventuali retry), il
  player riceve un messaggio di fallback casuale da `fallback.messages`, e la
  conversazione non si "rompe": il messaggio del player viene comunque salvato.

### Comandi (permesso `okotu.npcai.admin`, default op)

- `/okotunpc reload` — ricarica config.yml
- `/okotunpc setmodel <npcId> <modello>` — override modello per un NPC specifico
- `/okotunpc setbackstory <npcId> <testo backstory>|<personalità>` — imposta backstory
  (separatore `|` tra backstory e personalità)
- `/okotunpc info <npcId>` — mostra il personaggio salvato

## Limiti noti / prossimi passi consigliati

- **Cattura chat**: usa la classica `AsyncPlayerChatEvent` (deprecata ma ancora
  funzionante) per compatibilità Spigot/Paper più ampia. Su Paper recente puoi
  migrare a `io.papermc.paper.event.player.AsyncChatEvent` se vuoi supportare
  Adventure Component nei messaggi.
- **Context window**: con backstory molto lunghe o `history-size` alzato molto oltre
  20, valuta un riassunto periodico della cronologia più vecchia invece di includerla
  per intero nel prompt.
- **Multi-conversazione**: attualmente un player può avere una sola "conversazione
  attiva" alla volta (l'ultimo NPC cliccato). Se vuoi conversazioni parallele con più
  NPC contemporaneamente, la struttura dati in `NpcBridgeListener` va estesa da
  `Map<UUID, ActiveConversation>` a qualcosa che tenga più NPC per player.
- **Failover multi-nodo**: se in futuro avrai più docking Ollama (es. per bilanciare
  il carico tra più egg Pterodactyl), `OllamaClient` andrà esteso con selezione del
  nodo (round robin / health check) invece del singolo `base-url`.
