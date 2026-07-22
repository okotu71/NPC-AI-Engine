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
 * Bridge between Citizens and the conversation engine:
 * 1) right-click on an NPC -> the player enters "talking to NPC X" mode;
 * 2) their next chat message is intercepted (not broadcast) and sent to the AI engine;
 * 3) the reply reaches the player as a message from the NPC.
 *
 * Note: uses the classic AsyncPlayerChatEvent for broader Spigot/Paper compatibility.
 * On recent Paper you can migrate to io.papermc.paper.event.player.AsyncChatEvent
 * (based on Adventure Component) if you want rich message formatting.
 */
public class NpcBridgeListener implements Listener {

    /** How long the conversation stays "active" after the click if the player writes nothing. */
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
                + "] Type in chat to talk to them. The conversation expires after 30s of inactivity.");
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
                    ChatColor.RED + "Wait " + (remaining / 1000.0) + "s before talking to an NPC again."));
            return;
        }

        String message = event.getMessage();
        int npcId = active.npcId();
        String npcName = active.npcName();

        conversationService.handlePlayerMessage(npcId, npcName, player.getName(), player.getUniqueId(), message)
                .whenComplete((reply, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Unexpected error in the conversation flow for NPC " + npcId, throwable);
                        player.sendMessage(ChatColor.RED + "The NPC can't answer you right now.");
                        return;
                    }
                    player.sendMessage(ChatColor.GOLD + "[" + npcName + ChatColor.GOLD + "] "
                            + ChatColor.WHITE + reply);
                }));
    }

    private record ActiveConversation(int npcId, String npcName, long startedAt) {
    }
}
