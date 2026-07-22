package com.okotu.npcai.model;

/**
 * The "character sheet" of an NPC (npc_profiles). Changes rarely - this is
 * the part of an NPC that stays stable across every conversation, as opposed
 * to per-player memory or moment-to-moment emotional state.
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
        String systemPrompt,
        String model
) {

    public static NpcProfile defaultFor(int npcId, String name) {
        return new NpcProfile(
                npcId,
                name,
                "",
                "Neutral, curious about newcomers.",
                "An inhabitant of this world, about whom nothing is known yet.",
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
