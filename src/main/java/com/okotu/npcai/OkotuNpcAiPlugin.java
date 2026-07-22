package com.okotu.npcai;

import com.okotu.npcai.ai.OllamaClient;
import com.okotu.npcai.cache.ConversationCache;
import com.okotu.npcai.command.OkotuCommand;
import com.okotu.npcai.config.PluginConfig;
import com.okotu.npcai.db.CharacterDao;
import com.okotu.npcai.db.CleanupTask;
import com.okotu.npcai.db.ConversationDao;
import com.okotu.npcai.db.Database;
import com.okotu.npcai.npc.NpcBridgeListener;
import com.okotu.npcai.service.ConversationService;
import com.okotu.npcai.util.RateLimiter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OkotuNpcAiPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private Database database;
    private CharacterDao characterDao;
    private ConversationDao conversationDao;
    private ConversationCache conversationCache;
    private OllamaClient ollamaClient;
    private ConversationService conversationService;
    private ExecutorService asyncExecutor;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Citizens non trovato: okotu-npc-ai-engine richiede il plugin Citizens. Disabilito.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        // Executor dedicato per DB + HTTP: mai usare il thread principale del server per queste operazioni
        this.asyncExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "okotu-npc-ai-worker");
            t.setDaemon(true);
            return t;
        });

        initializeComponents();

        getServer().getPluginManager().registerEvents(
                new NpcBridgeListener(this, conversationService, new RateLimiter(pluginConfig.perPlayerCooldownMs)),
                this);

        var command = getCommand("okotunpc");
        if (command != null) {
            command.setExecutor(new OkotuCommand(this));
        }

        long intervalTicks = pluginConfig.cleanupIntervalMinutes * 60L * 20L; // minuti -> tick (20 tick/s)
        Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                new CleanupTask(this, conversationDao, pluginConfig.historySize),
                intervalTicks, intervalTicks);

        getLogger().info("okotu-npc-ai-engine avviato. Docking Ollama: " + pluginConfig.ollamaBaseUrl
                + " | modello default: " + pluginConfig.ollamaDefaultModel);
    }

    private void initializeComponents() {
        this.pluginConfig = new PluginConfig(this);
        this.database = new Database(this, pluginConfig);
        this.database.applySchema();

        this.characterDao = new CharacterDao(database);
        this.conversationDao = new ConversationDao(database);
        this.conversationCache = new ConversationCache(conversationDao, pluginConfig);
        this.ollamaClient = new OllamaClient(pluginConfig, getLogger(), asyncExecutor);

        this.conversationService = new ConversationService(
                pluginConfig, characterDao, conversationCache, ollamaClient, asyncExecutor, getLogger());
    }

    /** Richiamato da /okotunpc reload. Ricrea config e componenti dipendenti; NON cambia il pool MySQL a caldo. */
    public void reloadPlugin() {
        reloadConfig();
        this.pluginConfig = new PluginConfig(this);
        this.ollamaClient = new OllamaClient(pluginConfig, getLogger(), asyncExecutor);
        this.conversationService = new ConversationService(
                pluginConfig, characterDao, conversationCache, ollamaClient, asyncExecutor, getLogger());
        getLogger().info("Configurazione ricaricata.");
    }

    @Override
    public void onDisable() {
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("okotu-npc-ai-engine arrestato.");
    }

    public CharacterDao getCharacterDao() {
        return characterDao;
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }
}
