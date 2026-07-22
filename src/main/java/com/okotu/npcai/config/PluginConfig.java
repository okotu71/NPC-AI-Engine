package com.okotu.npcai.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Lettura tipizzata di config.yml. Ricreata ad ogni /okotunpc reload.
 */
public class PluginConfig {

    // --- mysql ---
    public final String mysqlHost;
    public final int mysqlPort;
    public final String mysqlDatabase;
    public final String mysqlUsername;
    public final String mysqlPassword;
    public final int mysqlPoolSize;
    public final String mysqlExtraParams;

    // --- ollama ---
    public final String ollamaBaseUrl;
    public final String ollamaDefaultModel;
    public final long ollamaTimeoutMs;
    public final int ollamaMaxRetries;
    public final long ollamaRetryDelayMs;

    // --- conversation ---
    public final int historySize;
    public final long cacheMaxEntries;
    public final long cacheExpireAfterMinutes;
    public final long cleanupIntervalMinutes;

    // --- rate limit ---
    public final long perPlayerCooldownMs;

    // --- fallback ---
    public final boolean fallbackEnabled;
    public final List<String> fallbackMessages;

    // --- npcs ---
    private final ConfigurationSection npcsSection;
    public final String defaultSystemPromptTemplate;

    public PluginConfig(Plugin plugin) {
        FileConfiguration cfg = plugin.getConfig();

        this.mysqlHost = cfg.getString("mysql.host", "localhost");
        this.mysqlPort = cfg.getInt("mysql.port", 3306);
        this.mysqlDatabase = cfg.getString("mysql.database", "okotu_npc");
        this.mysqlUsername = cfg.getString("mysql.username", "okotu");
        this.mysqlPassword = cfg.getString("mysql.password", "");
        this.mysqlPoolSize = cfg.getInt("mysql.pool-size", 10);
        this.mysqlExtraParams = cfg.getString("mysql.extra-params", "");

        this.ollamaBaseUrl = trimTrailingSlash(cfg.getString("ollama.base-url", "http://127.0.0.1:11434"));
        this.ollamaDefaultModel = cfg.getString("ollama.default-model", "qwen2.5:1.5b");
        this.ollamaTimeoutMs = cfg.getLong("ollama.timeout-ms", 8000);
        this.ollamaMaxRetries = cfg.getInt("ollama.max-retries", 1);
        this.ollamaRetryDelayMs = cfg.getLong("ollama.retry-delay-ms", 500);

        this.historySize = cfg.getInt("conversation.history-size", 20);
        this.cacheMaxEntries = cfg.getLong("conversation.cache-max-entries", 5000);
        this.cacheExpireAfterMinutes = cfg.getLong("conversation.cache-expire-after-minutes", 30);
        this.cleanupIntervalMinutes = cfg.getLong("conversation.cleanup-interval-minutes", 10);

        this.perPlayerCooldownMs = cfg.getLong("rate-limit.per-player-cooldown-ms", 3000);

        this.fallbackEnabled = cfg.getBoolean("fallback.enabled", true);
        this.fallbackMessages = cfg.getStringList("fallback.messages");

        this.npcsSection = cfg.getConfigurationSection("npcs");
        this.defaultSystemPromptTemplate = npcsSection != null
                ? npcsSection.getString("default.system-prompt", defaultPromptFallback())
                : defaultPromptFallback();
    }

    private static String defaultPromptFallback() {
        return "Sei {name}. Backstory: {backstory}. Personalita': {personality}. "
                + "Rispondi restando nel personaggio, in italiano, in modo breve.";
    }

    /** Modello configurato per un NPC specifico, o null se non presente in config.yml (npcs.<id>.model). */
    public String modelOverrideFor(int npcId) {
        if (npcsSection == null) return null;
        ConfigurationSection section = npcsSection.getConfigurationSection(String.valueOf(npcId));
        return section != null ? section.getString("model", null) : null;
    }

    /** Template di system prompt per un NPC specifico, o il default globale se non presente. */
    public String systemPromptTemplateFor(int npcId) {
        if (npcsSection != null) {
            ConfigurationSection section = npcsSection.getConfigurationSection(String.valueOf(npcId));
            if (section != null && section.isString("system-prompt")) {
                return section.getString("system-prompt");
            }
        }
        return defaultSystemPromptTemplate;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
