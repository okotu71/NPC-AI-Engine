package com.okotu.npcai.model;

/**
 * The "character sheet" of an NPC (npc_profiles). Changes rarely - this is
 * the part of an NPC that stays stable across every conversation, as opposed
 * to per-player memory or moment-to-moment emotional state.
 *
 * <p>As of 1.04 there is no per-NPC model override anymore: every NPC uses
 * whatever {@code ollama.default-model} is set to in config.yml (change it
 * there to switch every NPC at once, e.g. to "gemma3:1b"). Earlier versions
 * had a {@code model} column here; see MIGRATION_1.03_TO_1.04.sql if
 * upgrading from a database that still has it.
 *
 * <p>{@link #defaultFor(int, String)} is only used as a last-resort fallback
 * (e.g. if the database is unreachable when a conversation starts). The
 * profile actually created the first time a player talks to a new NPC is
 * instead generated randomly by {@code RandomProfileGenerator} from the
 * pools configured under {@code npc-defaults} in config.yml - see
 * ConversationService/RandomProfileGenerator, not this class.
 */
public record NpcProfile(
        int npcId,
        String name,
        String role,
        String personality,
        String background,
        String village,
        String profession,
        String speechStyle,
        String knowledge,
        String systemPrompt
) {

    public static NpcProfile defaultFor(int npcId, String name) {
        return new NpcProfile(
                npcId,
                name,
                "",
                "Neutral and curious about newcomers.",
                "A resident of this world; not much is known about them yet.",
                null,
                null,
                null,
                null,
                null
        );
    }
}
