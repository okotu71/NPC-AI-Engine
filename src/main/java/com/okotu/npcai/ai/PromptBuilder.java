package com.okotu.npcai.ai;

import com.okotu.npcai.model.ConversationEntry;
import com.okotu.npcai.model.KnowledgeEntry;
import com.okotu.npcai.model.NpcProfile;
import com.okotu.npcai.model.NpcState;
import com.okotu.npcai.model.PlayerMemory;
import com.okotu.npcai.model.VillageEvent;
import com.okotu.npcai.service.RelationshipService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Assembles the system prompt sent to Ollama out of everything the plugin
 * knows: the NPC's character sheet, what it remembers about this specific
 * player, what it knows about the world, current village events + emotional
 * state, and the last few raw messages. Aims to stay in the 300-500 token
 * range described in the design doc, small enough for a 1B-1.5B model.
 *
 * Sections mirror the design spec: SYSTEM / MEMORIA / CONOSCENZA / CONTESTO.
 * The final "ULTIMI MESSAGGI" section is NOT included in the system prompt -
 * it's passed separately to OllamaClient as chat history, since that maps
 * more naturally onto Ollama's /api/chat message list than being flattened
 * into the system message text.
 */
public class PromptBuilder {

    private static final String SEPARATOR =
            "-------------------------------------------------";

    private final RelationshipService relationshipService;

    public PromptBuilder(RelationshipService relationshipService) {
        this.relationshipService = relationshipService;
    }

    public String buildSystemPrompt(NpcProfile profile, PlayerMemory memory,
                                     List<KnowledgeEntry> knowledge, List<VillageEvent> villageEvents,
                                     NpcState state) {
        StringBuilder sb = new StringBuilder();

        sb.append("SYSTEM\n").append(buildCharacterSection(profile)).append('\n');

        String memoriaSection = buildMemoriaSection(memory);
        if (!memoriaSection.isBlank()) {
            sb.append(SEPARATOR).append("\nMEMORIA\n").append(memoriaSection).append('\n');
        }

        String conoscenzaSection = buildConoscenzaSection(knowledge);
        if (!conoscenzaSection.isBlank()) {
            sb.append(SEPARATOR).append("\nCONOSCENZA\n").append(conoscenzaSection).append('\n');
            sb.append("Parla solo di cio' che conosci. Se ti chiedono di un argomento che non hai "
                    + "in questa lista, ammetti di non saperne nulla invece di inventare dettagli.\n");
        }

        String contestoSection = buildContestoSection(villageEvents, state);
        if (!contestoSection.isBlank()) {
            sb.append(SEPARATOR).append("\nCONTESTO\n").append(contestoSection);
        }

        return sb.toString().stripTrailing();
    }

    public List<OllamaClient.ChatMessage> buildHistory(List<ConversationEntry> entries) {
        return entries.stream()
                .map(e -> e.role() == ConversationEntry.Role.PLAYER
                        ? OllamaClient.ChatMessage.user(e.message())
                        : OllamaClient.ChatMessage.assistant(e.message()))
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------
    // SYSTEM: the character sheet
    // ---------------------------------------------------------------
    private String buildCharacterSection(NpcProfile profile) {
        // If an explicit system_prompt was authored for this NPC, it fully
        // overrides the auto-built version below.
        if (profile.systemPrompt() != null && !profile.systemPrompt().isBlank()) {
            return profile.systemPrompt().strip();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Sei ").append(profile.name()).append(".\n");
        if (notBlank(profile.profession())) {
            sb.append(profile.profession()).append(".\n");
        } else if (notBlank(profile.role())) {
            sb.append(profile.role()).append(".\n");
        }
        if (notBlank(profile.village())) {
            sb.append("Abiti nel villaggio ").append(profile.village()).append(".\n");
        }
        if (notBlank(profile.personality())) {
            sb.append("Carattere: ").append(profile.personality()).append("\n");
        }
        if (notBlank(profile.background())) {
            sb.append("Background: ").append(profile.background()).append("\n");
        }
        if (notBlank(profile.speechStyle())) {
            sb.append(profile.speechStyle()).append("\n");
        }
        if (notBlank(profile.knowledge())) {
            sb.append(profile.knowledge()).append("\n");
        }
        sb.append("Resta sempre nel personaggio. Rispondi in italiano, in modo breve "
                + "(massimo 2-3 frasi), adatto a una chat di gioco.");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // MEMORIA: what this NPC remembers about this specific player
    // ---------------------------------------------------------------
    private String buildMemoriaSection(PlayerMemory memory) {
        if (memory == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String playerLabel = notBlank(memory.knownName()) ? memory.knownName() : "questo giocatore";
        sb.append("Conosci ").append(playerLabel).append(".\n");
        sb.append("Nei suoi confronti: ").append(relationshipService.describe(memory.relationshipScore()))
                .append(" (punteggio ").append(memory.relationshipScore()).append("/100).\n");
        if (notBlank(memory.summary())) {
            sb.append(memory.summary().strip()).append("\n");
        }
        if (notBlank(memory.notes())) {
            sb.append("Note: ").append(memory.notes().strip()).append("\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // CONOSCENZA: what this NPC knows (and, implicitly, doesn't know)
    // ---------------------------------------------------------------
    private String buildConoscenzaSection(List<KnowledgeEntry> knowledge) {
        if (knowledge == null || knowledge.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeEntry entry : knowledge) {
            sb.append("- ").append(entry.topic()).append(": ").append(entry.text()).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // CONTESTO: shared village events + current emotional state
    // ---------------------------------------------------------------
    private String buildContestoSection(List<VillageEvent> villageEvents, NpcState state) {
        StringBuilder sb = new StringBuilder();
        if (villageEvents != null) {
            for (VillageEvent event : villageEvents) {
                sb.append("- ").append(event.summary()).append('\n');
            }
        }
        String mood = describeMood(state);
        if (!mood.isBlank()) {
            sb.append(mood);
        }
        return sb.toString();
    }

    private String describeMood(NpcState state) {
        if (state == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (state.fatigue() >= 60) sb.append("Oggi sei stanco.\n");
        if (state.hunger() >= 60) sb.append("Hai fame.\n");
        if (state.fear() >= 60) sb.append("Sei spaventato.\n");
        if (state.anger() >= 60) sb.append("Sei arrabbiato.\n");
        if (state.happiness() >= 80) sb.append("Sei di ottimo umore.\n");
        else if (state.happiness() <= 20) sb.append("Sei di pessimo umore.\n");
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
