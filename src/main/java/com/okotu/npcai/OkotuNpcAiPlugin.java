package com.okotu.npcai;

import com.okotu.npcai.ai.OllamaClient;
import com.okotu.npcai.ai.PromptBuilder;
import com.okotu.npcai.api.OkotuNpcApi;
import com.okotu.npcai.api.OkotuNpcApiImpl;
import com.okotu.npcai.cache.RecentMessageCache;
import com.okotu.npcai.command.OkotuCommand;
import com.okotu.npcai.config.PluginConfig;
import com.okotu.npcai.db.CleanupTask;
import com.okotu.npcai.db.Database;
import com.okotu.npcai.db.DialogHistoryDao;
import com.okotu.npcai.db.KnowledgeDao;
import com.okotu.npcai.db.NpcProfileDao;
import com.okotu.npcai.db.NpcStateDao;
import com.okotu.npcai.db.PlayerMemoryDao;
import com.okotu.npcai.db.VillageEventDao;
import com.okotu.npcai.npc.NpcBridgeListener;
import com.okotu.npcai.service.ConversationService;
import com.okotu.npcai.service.RelationshipService;
import com.okotu.npcai.service.SummaryService;
import com.okotu.npcai.util.RateLimiter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OkotuNpcAiPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private Database database;

    private NpcProfileDao npcProfileDao;
    private PlayerMemoryDao playerMemoryDao;
    private DialogHistoryDao dialogHistoryDao;
    private VillageEventDao villageEventDao;
    private KnowledgeDao knowledgeDao;
    private NpcStateDao npcStateDao;

    private RecentMessageCache recentMessageCache;
    private OllamaClient ollamaClient;
    private RelationshipService relationshipService;
    private SummaryService summaryService;
    private ConversationService conversationService;

    private ExecutorService asyncExecutor;
    private OkotuNpcApiImpl apiImpl;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Citizens not found: okotu-npc-ai-engine requires the Citizens plugin. Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        ensureDefaultConfigExists();

        this.asyncExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "okotu-npc-ai-worker");
            t.setDaemon(true);
            return t;
        });

        initializeComponents();
        registerApi();

        getServer().getPluginManager().registerEvents(
                new NpcBridgeListener(this, conversationService, new RateLimiter(pluginConfig.perPlayerCooldownMs)),
                this);

        var command = getCommand("okotunpc");
        if (command != null) {
            command.setExecutor(new OkotuCommand(this));
        }

        long intervalTicks = pluginConfig.cleanupIntervalMinutes * 60L * 20L; // minutes -> ticks (20 ticks/s)
        Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                new CleanupTask(this, dialogHistoryDao, villageEventDao, pluginConfig.maxRawMessagesSafety),
                intervalTicks, intervalTicks);

        getLogger().info("okotu-npc-ai-engine v" + getDescription().getVersion() + " started."
                + " Profile: " + pluginConfig.activeProfile
                + " | Database: " + database.databaseName()
                + " | Table prefix: '" + pluginConfig.mysqlTablePrefix + "'"
                + " | Ollama docking: " + pluginConfig.ollamaBaseUrl
                + " | Default model: " + pluginConfig.ollamaDefaultModel);
    }

    /**
     * Makes sure plugins/OkotuNpcAiEngine/config.yml exists, creating it from
     * the bundled default on first run. See README "About plugin.yml" for
     * why plugin.yml itself can't be created this way.
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

        this.npcProfileDao = new NpcProfileDao(database);
        this.playerMemoryDao = new PlayerMemoryDao(database, pluginConfig.relationshipDefault);
        this.dialogHistoryDao = new DialogHistoryDao(database);
        this.villageEventDao = new VillageEventDao(database);
        this.knowledgeDao = new KnowledgeDao(database);
        this.npcStateDao = new NpcStateDao(database);

        this.recentMessageCache = new RecentMessageCache(dialogHistoryDao, pluginConfig);
        this.ollamaClient = new OllamaClient(pluginConfig, getLogger(), asyncExecutor);
        this.relationshipService = new RelationshipService(playerMemoryDao, pluginConfig);
        this.summaryService = new SummaryService(pluginConfig, playerMemoryDao, dialogHistoryDao,
                recentMessageCache, ollamaClient, asyncExecutor, getLogger());

        PromptBuilder promptBuilder = new PromptBuilder(relationshipService);
        this.conversationService = new ConversationService(pluginConfig, npcProfileDao, playerMemoryDao,
                knowledgeDao, villageEventDao, npcStateDao, recentMessageCache, ollamaClient, promptBuilder,
                summaryService, asyncExecutor, getLogger());
    }

    private void registerApi() {
        this.apiImpl = new OkotuNpcApiImpl(relationshipService, villageEventDao, knowledgeDao, npcStateDao, asyncExecutor);
        getServer().getServicesManager().register(OkotuNpcApi.class, apiImpl, this, ServicePriority.Normal);
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
        this.relationshipService = new RelationshipService(playerMemoryDao, pluginConfig);
        this.summaryService = new SummaryService(pluginConfig, playerMemoryDao, dialogHistoryDao,
                recentMessageCache, ollamaClient, asyncExecutor, getLogger());
        PromptBuilder promptBuilder = new PromptBuilder(relationshipService);
        this.conversationService = new ConversationService(pluginConfig, npcProfileDao, playerMemoryDao,
                knowledgeDao, villageEventDao, npcStateDao, recentMessageCache, ollamaClient, promptBuilder,
                summaryService, asyncExecutor, getLogger());
        getLogger().info("Configuration reloaded (profile: " + pluginConfig.activeProfile + ").");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);

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

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public NpcProfileDao getNpcProfileDao() {
        return npcProfileDao;
    }

    public PlayerMemoryDao getPlayerMemoryDao() {
        return playerMemoryDao;
    }

    public DialogHistoryDao getDialogHistoryDao() {
        return dialogHistoryDao;
    }

    public VillageEventDao getVillageEventDao() {
        return villageEventDao;
    }

    public KnowledgeDao getKnowledgeDao() {
        return knowledgeDao;
    }

    public NpcStateDao getNpcStateDao() {
        return npcStateDao;
    }
}
