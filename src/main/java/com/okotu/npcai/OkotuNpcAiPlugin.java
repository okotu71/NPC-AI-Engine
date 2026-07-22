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

import java.io.File;
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
            getLogger().severe("Citizens not found: okotu-npc-ai-engine requires the Citizens plugin. Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        ensureDefaultConfigExists();

        // Dedicated executor for DB + HTTP work: never use the server main thread for these
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

        long intervalTicks = pluginConfig.cleanupIntervalMinutes * 60L * 20L; // minutes -> ticks (20 ticks/s)
        Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                new CleanupTask(this, conversationDao, pluginConfig.historySize),
                intervalTicks, intervalTicks);

        getLogger().info("okotu-npc-ai-engine v" + getDescription().getVersion() + " started."
                + " Profile: " + pluginConfig.activeProfile
                + " | Database: " + database.databaseName()
                + " | Table prefix: '" + pluginConfig.mysqlTablePrefix + "'"
                + " | Ollama docking: " + pluginConfig.ollamaBaseUrl
                + " | Default model: " + pluginConfig.ollamaDefaultModel);
    }

    /**
     * Makes sure plugins/OkotuNpcAiEngine/config.yml exists, creating it from the
     * bundled default (src/main/resources/config.yml) on first run. plugin.yml
     * itself is a build-time manifest packaged inside the jar (Bukkit needs it
     * to even load the plugin), so it cannot be "created" at runtime the same
     * way config.yml can - if it's ever missing from a built jar, that's a
     * packaging/build issue (see README "Troubleshooting"), not something this
     * method can fix from inside the plugin.
     */
    private void ensureDefaultConfigExists() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            getLogger().info("config.yml not found, creating default configuration...");
            saveDefaultConfig();
        } else {
            reloadConfig();
        }
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

    /**
     * Called by /okotunpc reload. Re-reads config.yml and re-creates the
     * dependent components. Does NOT hot-swap the MySQL pool (host/db/profile
     * changes still require a full server restart).
     */
    public void reloadPlugin() {
        reloadConfig();
        this.pluginConfig = new PluginConfig(this);
        this.ollamaClient = new OllamaClient(pluginConfig, getLogger(), asyncExecutor);
        this.conversationService = new ConversationService(
                pluginConfig, characterDao, conversationCache, ollamaClient, asyncExecutor, getLogger());
        getLogger().info("Configuration reloaded (profile: " + pluginConfig.activeProfile + ").");
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
        getLogger().info("okotu-npc-ai-engine stopped.");
    }

    public CharacterDao getCharacterDao() {
        return characterDao;
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }
}
