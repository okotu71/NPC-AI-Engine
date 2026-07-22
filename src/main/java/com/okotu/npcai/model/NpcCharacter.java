package com.okotu.npcai.model;

/**
 * Backstory e configurazione persistente di un NPC.
 * {@code model} puo' essere null: in tal caso si usa il modello di default globale.
 */
public record NpcCharacter(
        int npcId,
        String nome,
        String backstory,
        String personalita,
        String traitsJson,
        String model
) {

    public static NpcCharacter defaultFor(int npcId, String nome) {
        return new NpcCharacter(
                npcId,
                nome,
                "Un abitante di questo mondo, di cui non si sa ancora nulla.",
                "Neutrale, curioso verso i nuovi arrivati.",
                "{}",
                null
        );
    }
}
