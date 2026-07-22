package com.okotu.npcai.model;

/**
 * The "character sheet" of an NPC (npc_profiles). Changes rarely - this is
 * the part of an NPC that stays stable across every conversation, as opposed
 * to per-player memory or moment-to-moment emotional state.
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
                "Neutrale, curioso verso i nuovi arrivati.",
                "Un abitante di questo mondo, di cui non si sa ancora nulla.",
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
