package com.okotu.npcai.service;

import com.okotu.npcai.ai.OllamaClient;
import com.okotu.npcai.ai.PromptBuilder;
import com.okotu.npcai.cache.ConversationCache;
import com.okotu.npcai.config.PluginConfig;
import com.okotu.npcai.db.CharacterDao;
import com.okotu.npcai.model.ConversationEntry;
import com.okotu.npcai.model.NpcCharacter;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Punto d'ingresso unico per "il player X ha detto Y all'NPC Z": si occupa di
 * caricare backstory + cronologia, chiamare Ollama in modo asincrono, salvare
 * la risposta, e applicare il fallback in caso di errore/timeout.
 *
 * Tutto il lavoro bloccante (MySQL, HTTP) gira sull'executor asincrono passato
 * al costruttore: il chiamante (listener Bukkit) resta libero di girare la
 * callback di ritorno sul main thread quando serve toccare l'API Bukkit/Citizens.
 */
public class ConversationService {

    private final PluginConfig config;
    private final CharacterDao characterDao;
    private final ConversationCache conversationCache;
    private final OllamaClient ollamaClient;
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final Executor asyncExecutor;
    private final Logger logger;
    private final Random random = new Random();

    public ConversationService(PluginConfig config,
                                CharacterDao characterDao,
                                ConversationCache conversationCache,
                                OllamaClient ollamaClient,
                                Executor asyncExecutor,
                                Logger logger) {
        this.config = config;
        this.characterDao = characterDao;
        this.conversationCache = conversationCache;
        this.ollamaClient = ollamaClient;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    /**
     * Elabora un turno di dialogo in modo completamente asincrono.
     * Il CompletableFuture restituito si completa sull'executor asincrono:
     * il chiamante deve rientrare sul main thread (Bukkit scheduler) prima
     * di usare il risultato per interagire con player/NPC.
     */
    public CompletableFuture<String> handlePlayerMessage(int npcId, String npcName,
                                                           UUID playerUuid, String playerMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return characterDao.findOrCreate(npcId, npcName);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Errore leggendo il personaggio NPC " + npcId, e);
                return NpcCharacter.defaultFor(npcId, npcName);
            }
        }, asyncExecutor).thenCompose(character -> {
            List<ConversationEntry> history;
            try {
                history = conversationCache.getHistory(npcId, playerUuid);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Errore leggendo la cronologia per npc=" + npcId, e);
                history = List.of();
            }

            String model = character.model() != null ? character.model() : config.ollamaDefaultModel;
            String systemPrompt = promptBuilder.buildSystemPrompt(
                    config.systemPromptTemplateFor(npcId), character);

            List<OllamaClient.ChatMessage> chatHistory = promptBuilder.buildHistory(history);

            return ollamaClient.chat(model, systemPrompt, chatHistory, playerMessage)
                    .thenApply(reply -> {
                        persist(npcId, playerUuid, playerMessage, reply);
                        return reply;
                    })
                    .exceptionally(throwable -> {
                        logger.log(Level.WARNING, "Ollama non ha risposto in tempo per npc="
                                + npcId + ": " + throwable.getMessage());
                        String fallback = fallbackMessage();
                        // Salviamo comunque il messaggio del player, cosi' la prossima
                        // richiesta ha comunque il contesto; NON salviamo la risposta
                        // di fallback come se fosse una vera battuta dell'NPC.
                        persistPlayerOnly(npcId, playerUuid, playerMessage);
                        return fallback;
                    });
        });
    }

    private void persist(int npcId, UUID playerUuid, String playerMessage, String npcReply) {
        try {
            conversationCache.append(npcId, playerUuid, ConversationEntry.player(playerMessage));
            conversationCache.append(npcId, playerUuid, ConversationEntry.npc(npcReply));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Errore salvando la conversazione per npc=" + npcId, e);
        }
    }

    private void persistPlayerOnly(int npcId, UUID playerUuid, String playerMessage) {
        try {
            conversationCache.append(npcId, playerUuid, ConversationEntry.player(playerMessage));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Errore salvando il messaggio player per npc=" + npcId, e);
        }
    }

    private String fallbackMessage() {
        List<String> messages = config.fallbackMessages;
        if (!config.fallbackEnabled || messages.isEmpty()) {
            return "*L'NPC non risponde.*";
        }
        return messages.get(random.nextInt(messages.size()));
    }
}
