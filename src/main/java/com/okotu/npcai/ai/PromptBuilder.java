package com.okotu.npcai.ai;

import com.okotu.npcai.model.ConversationEntry;
import com.okotu.npcai.model.NpcCharacter;

import java.util.ArrayList;
import java.util.List;

/**
 * Trasforma backstory + cronologia in cio' che serve a OllamaClient:
 * un system prompt testuale e una lista di ChatMessage (role/content).
 *
 * Nota sulla context window: con modelli piccoli (es. qwen2.5:1.5b) e history-size
 * di default (20), il prompt resta tipicamente entro poche migliaia di token.
 * Se in futuro alzi history-size o usi backstory molto lunghe, valuta un riassunto
 * periodico della cronologia piu' vecchia invece di includerla per intero.
 */
public class PromptBuilder {

    public String buildSystemPrompt(String template, NpcCharacter character) {
        return template
                .replace("{name}", character.nome())
                .replace("{backstory}", character.backstory())
                .replace("{personality}", character.personalita());
    }

    public List<OllamaClient.ChatMessage> buildHistory(List<ConversationEntry> entries) {
        List<OllamaClient.ChatMessage> messages = new ArrayList<>(entries.size());
        for (ConversationEntry entry : entries) {
            OllamaClient.ChatMessage message = entry.role() == ConversationEntry.Role.PLAYER
                    ? OllamaClient.ChatMessage.user(entry.message())
                    : OllamaClient.ChatMessage.assistant(entry.message());
            messages.add(message);
        }
        return messages;
    }
}
