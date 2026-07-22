package com.okotu.npcai.npc;

import com.okotu.npcai.service.ConversationService;
import com.okotu.npcai.util.RateLimiter;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Bridge tra Citizens e il motore di conversazione:
 * 1) click destro su un NPC "abilitato" -> il player entra in modalita' "in conversazione con NPC X";
 * 2) il suo prossimo messaggio in chat viene intercettato (non broadcastato) e inviato al motore AI;
 * 3) la risposta arriva al player come messaggio dell'NPC.
 *
 * Nota: usa la classica AsyncPlayerChatEvent per compatibilita' Spigot/Paper piu' ampia.
 * Su Paper recenti puoi migrare a io.papermc.paper.event.player.AsyncChatEvent (basata su
 * Adventure Component) se vuoi supportare formattazione ricca nei messaggi.
 */
public class NpcBridgeListener implements Listener {

    /** Quanto resta "attiva" la conversazione dopo il click, se il player non scrive nulla. */
    private static final long CONVERSATION_TIMEOUT_MS = 30_000;

    private final Plugin plugin;
    private final ConversationService conversationService;
    private final RateLimiter rateLimiter;

    // playerUuid -> stato conversazione attiva
    private final Map<UUID, ActiveConversation> activeConversations = new ConcurrentHashMap<>();

    public NpcBridgeListener(Plugin plugin, ConversationService conversationService, RateLimiter rateLimiter) {
        this.plugin = plugin;
        this.conversationService = conversationService;
        this.rateLimiter = rateLimiter;
    }

    @EventHandler
    public void onNpcRightClick(NPCRightClickEvent event) {
        NPC npc = event.getNPC();
        Player player = event.getClicker();

        activeConversations.put(player.getUniqueId(), new ActiveConversation(
                npc.getId(), npc.getName(), System.currentTimeMillis()));

        player.sendMessage(ChatColor.GRAY + "[" + npc.getName() + ChatColor.GRAY
                + "] Scrivi in chat per parlargli. La conversazione scade dopo 30s di inattivita'.");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ActiveConversation active = activeConversations.get(player.getUniqueId());
        if (active == null) {
            return;
        }

        if (System.currentTimeMillis() - active.startedAt() > CONVERSATION_TIMEOUT_MS) {
            activeConversations.remove(player.getUniqueId());
            return;
        }

        // Intercetta il messaggio: non deve finire nella chat pubblica del server
        event.setCancelled(true);
        activeConversations.remove(player.getUniqueId());

        if (!rateLimiter.tryAcquire(player.getUniqueId())) {
            long remaining = rateLimiter.remainingCooldownMs(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                    ChatColor.RED + "Aspetta ancora " + (remaining / 1000.0) + "s prima di riparlare con un NPC."));
            return;
        }

        String message = event.getMessage();
        int npcId = active.npcId();
        String npcName = active.npcName();

        conversationService.handlePlayerMessage(npcId, npcName, player.getUniqueId(), message)
                .whenComplete((reply, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Errore imprevisto nel flusso di conversazione NPC " + npcId, throwable);
                        player.sendMessage(ChatColor.RED + "L'NPC non riesce a risponderti ora.");
                        return;
                    }
                    player.sendMessage(ChatColor.GOLD + "[" + npcName + ChatColor.GOLD + "] "
                            + ChatColor.WHITE + reply);
                }));
    }

    private record ActiveConversation(int npcId, String npcName, long startedAt) {
    }
}
