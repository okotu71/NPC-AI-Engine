package com.okotu.npcai.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.logging.Logger;

/**
 * Typed reading of config.yml. Re-created on every /okotunpc reload.
 *
 * <p>MySQL credentials are split into two named profiles, "prod" and "test",
 * selected via {@code active-profile} in config.yml. This can be overridden
 * without editing the file by starting the server with:
 * {@code -Dokotu.profile=test} (or "prod"). The system property always wins
 * over the config.yml value.
 */
public class PluginConfig {

    public static final String PROFILE_SYSTEM_PROPERTY = "okotu.profile";

    // --- active profile ---
    public final String activeProfile;

    // --- mysql (resolved from the active profile) ---
    public final String mysqlHost;
    public final int mysqlPort;
    public final String mysqlDatabase;
    public final String mysqlUsername;
    public final String mysqlPassword;
    public final String mysqlTablePrefix;

    // --- mysql (shared across profiles) ---
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
        Logger logger = plugin.getLogger();

        String configuredProfile = cfg.getString("active-profile", "prod");
        String resolvedProfile = System.getProperty(PROFILE_SYSTEM_PROPERTY, configuredProfile);
        if (!"prod".equalsIgnoreCase(resolvedProfile) && !"test".equalsIgnoreCase(resolvedProfile)) {
            logger.warning("Unknown profile '" + resolvedProfile + "' (expected 'prod' or 'test'), "
                    + "falling back to 'prod'.");
            resolvedProfile = "prod";
        }
        this.activeProfile = resolvedProfile.toLowerCase();

        ConfigurationSection profileSection = cfg.getConfigurationSection(activeProfile);
        if (profileSection == null) {
            logger.severe("Profile '" + activeProfile + "' is missing from config.yml! "
                    + "Falling back to built-in defaults, please check your configuration.");
        }

        this.mysqlHost = getFromProfile(profileSection, "mysql-host", "localhost");
        this.mysqlPort = profileSection != null ? profileSection.getInt("mysql-port", 3306) : 3306;
        this.mysqlDatabase = getFromProfile(profileSection, "mysql-database",
                "prod".equals(activeProfile) ? "okotu_npc_ai" : "okotu_npc_ai_test");
        this.mysqlUsername = getFromProfile(profileSection, "mysql-username", "okotu");
        this.mysqlPassword = getFromProfile(profileSection, "mysql-password", "");
        this.mysqlTablePrefix = getFromProfile(profileSection, "mysql-table-prefix", "");

        this.mysqlPoolSize = cfg.getInt("mysql-pool-size", 10);
        this.mysqlExtraParams = cfg.getString("mysql-extra-params", "");

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

    private static String getFromProfile(ConfigurationSection section, String key, String fallback) {
        return section != null ? section.getString(key, fallback) : fallback;
    }

    private static String defaultPromptFallback() {
        return "You are {name}. Backstory: {backstory}. Personality: {personality}. "
                + "Always stay in character, answer briefly.";
    }

    /** Model configured for a specific NPC, or null if not present in config.yml (npcs.<id>.model). */
    public String modelOverrideFor(int npcId) {
        if (npcsSection == null) return null;
        ConfigurationSection section = npcsSection.getConfigurationSection(String.valueOf(npcId));
        return section != null ? section.getString("model", null) : null;
    }

    /** System prompt template for a specific NPC, or the global default if not present. */
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
