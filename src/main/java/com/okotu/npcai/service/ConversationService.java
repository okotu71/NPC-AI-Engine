package com.okotu.npcai.service;

import com.okotu.npcai.ai.OllamaClient;
import com.okotu.npcai.ai.PromptBuilder;
import com.okotu.npcai.cache.RecentMessageCache;
import com.okotu.npcai.config.PluginConfig;
import com.okotu.npcai.db.KnowledgeDao;
import com.okotu.npcai.db.NpcProfileDao;
import com.okotu.npcai.db.NpcStateDao;
import com.okotu.npcai.db.PlayerMemoryDao;
import com.okotu.npcai.db.VillageEventDao;
import com.okotu.npcai.model.ConversationEntry;
import com.okotu.npcai.model.KnowledgeEntry;
import com.okotu.npcai.model.NpcProfile;
import com.okotu.npcai.model.NpcState;
import com.okotu.npcai.model.PlayerMemory;
import com.okotu.npcai.model.VillageEvent;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single entry point for "player X said Y to NPC Z": loads the character
 * sheet, per-player memory, knowledge, village context and emotional state,
 * builds the prompt, calls Ollama, persists the turn, updates memory
 * bookkeeping (last_seen, messages-since-summary) and kicks off memory
 * compression when due - all without ever touching the server main thread.
 */
public class ConversationService {

    private final PluginConfig config;
    private final NpcProfileDao npcProfileDao;
    private final PlayerMemoryDao playerMemoryDao;
    private final KnowledgeDao knowledgeDao;
    private final VillageEventDao villageEventDao;
    private final NpcStateDao npcStateDao;
    private final RecentMessageCache recentMessageCache;
    private final OllamaClient ollamaClient;
    private final PromptBuilder promptBuilder;
    private final SummaryService summaryService;
    private final RandomProfileGenerator randomProfileGenerator;
    private final Executor asyncExecutor;
    private final Logger logger;
    private final Random random = new Random();

    public ConversationService(PluginConfig config, NpcProfileDao npcProfileDao, PlayerMemoryDao playerMemoryDao,
                                KnowledgeDao knowledgeDao, VillageEventDao villageEventDao, NpcStateDao npcStateDao,
                                RecentMessageCache recentMessageCache, OllamaClient ollamaClient,
                                PromptBuilder promptBuilder, SummaryService summaryService,
                                RandomProfileGenerator randomProfileGenerator,
                                Executor asyncExecutor, Logger logger) {
        this.config = config;
        this.npcProfileDao = npcProfileDao;
        this.playerMemoryDao = playerMemoryDao;
        this.knowledgeDao = knowledgeDao;
        this.villageEventDao = villageEventDao;
        this.npcStateDao = npcStateDao;
        this.recentMessageCache = recentMessageCache;
        this.ollamaClient = ollamaClient;
        this.promptBuilder = promptBuilder;
        this.summaryService = summaryService;
        this.randomProfileGenerator = randomProfileGenerator;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    /**
     * Processes one dialogue turn, fully async. The returned future completes
     * on the async executor: callers must hop back to the main thread
     * (Bukkit scheduler) before touching player/NPC APIs with the result.
     */
    public CompletableFuture<String> handlePlayerMessage(int npcId, String npcName, String knownPlayerName,
                                                           UUID playerUuid, String playerMessage) {
        return CompletableFuture.supplyAsync(() -> loadContext(npcId, npcName, playerUuid), asyncExecutor)
                .thenCompose(ctx -> {
                    String model = ctx.profile.model() != null ? ctx.profile.model() : config.ollamaDefaultModel;
                    String systemPrompt = promptBuilder.buildSystemPrompt(
                            ctx.profile, ctx.memory, ctx.knowledge, ctx.villageEvents, ctx.state);
                    List<OllamaClient.ChatMessage> history = promptBuilder.buildHistory(ctx.recent);

                    return ollamaClient.chat(model, systemPrompt, history, playerMessage)
                            .thenApply(reply -> {
                                persistTurn(npcId, playerUuid, knownPlayerName, playerMessage, reply);
                                return reply;
                            })
                            .exceptionally(throwable -> {
                                logger.log(Level.WARNING, "Ollama did not answer in time for npc="
                                        + npcId + ": " + throwable.getMessage());
                                persistPlayerMessageOnly(npcId, playerUuid, knownPlayerName, playerMessage);
                                return fallbackMessage();
                            });
                });
    }

    private Context loadContext(int npcId, String fallbackName, UUID playerUuid) {
        try {
            NpcProfile profile = npcProfileDao.findOrCreate(npcId, () -> randomProfileGenerator.generate(npcId, fallbackName));
            PlayerMemory memory = playerMemoryDao.findOrCreate(npcId, playerUuid);
            List<KnowledgeEntry> knowledge = knowledgeDao.findForNpc(npcId, config.knowledgeLimit);
            List<VillageEvent> villageEvents = profile.village() != null
                    ? villageEventDao.findActive(profile.village(), config.villageEventsLimit)
                    : List.of();
            NpcState state = npcStateDao.findOrCreate(npcId);
            List<ConversationEntry> recent = recentMessageCache.getRecent(npcId, playerUuid);
            return new Context(profile, memory, knowledge, villageEvents, state, recent);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error loading conversation context for npc=" + npcId, e);
            NpcProfile fallback = NpcProfile.defaultFor(npcId, fallbackName);
            PlayerMemory fallbackMemory = PlayerMemory.defaultFor(npcId, playerUuid, config.relationshipDefault);
            return new Context(fallback, fallbackMemory, List.of(), List.of(), NpcState.defaultFor(npcId), List.of());
        }
    }

    private void persistTurn(int npcId, UUID playerUuid, String knownPlayerName, String playerMessage, String npcReply) {
        try {
            recentMessageCache.append(npcId, playerUuid, ConversationEntry.player(playerMessage));
            recentMessageCache.append(npcId, playerUuid, ConversationEntry.npc(npcReply));
            playerMemoryDao.touch(npcId, playerUuid, knownPlayerName);
            playerMemoryDao.incrementMessagesSinceSummary(npcId, playerUuid, 2);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error persisting conversation turn for npc=" + npcId, e);
        }
        // Fire-and-forget: checks the counter and compresses in the background if due.
        summaryService.maybeCompressAsync(npcId, playerUuid);
    }

    private void persistPlayerMessageOnly(int npcId, UUID playerUuid, String knownPlayerName, String playerMessage) {
        try {
            recentMessageCache.append(npcId, playerUuid, ConversationEntry.player(playerMessage));
            playerMemoryDao.touch(npcId, playerUuid, knownPlayerName);
            playerMemoryDao.incrementMessagesSinceSummary(npcId, playerUuid, 1);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error persisting player message for npc=" + npcId, e);
        }
    }

    private String fallbackMessage() {
        List<String> messages = config.fallbackMessages;
        if (!config.fallbackEnabled || messages.isEmpty()) {
            return "*The NPC doesn't answer.*";
        }
        return messages.get(random.nextInt(messages.size()));
    }

    private record Context(NpcProfile profile, PlayerMemory memory, List<KnowledgeEntry> knowledge,
                            List<VillageEvent> villageEvents, NpcState state, List<ConversationEntry> recent) {
    }
}
