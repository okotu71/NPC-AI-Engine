package com.okotu.npcai.npc;

import com.okotu.npcai.OkotuNpcAiPlugin;
import com.okotu.npcai.util.RateLimiter;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Optional;
import java.util.logging.Level;

/**
 * Bridge between Citizens and the conversation engine. Two ways in:
 * 1) right-click on an NPC (if {@code interaction.right-click.enabled}) -
 *    opens a conversation session via {@link ConversationSessionManager};
 * 2) proximity - see {@link ProximityGreetingTask}, which opens sessions the
 *    same way when an NPC notices a player getting close and greets first.
 *
 * Either way, once a session is open for a player, their next chat message
 * is intercepted here (not broadcast) and sent to the AI engine, and the
 * reply reaches them as a message from the NPC.
 *
 * Note: uses the classic AsyncPlayerChatEvent for broader Spigot/Paper compatibility.
 * On recent Paper you can migrate to io.papermc.paper.event.player.AsyncChatEvent
 * (based on Adventure Component) if you want rich message formatting.
 */
public class NpcBridgeListener implements Listener {

    private final OkotuNpcAiPlugin plugin;
    private final RateLimiter rateLimiter;

    public NpcBridgeListener(OkotuNpcAiPlugin plugin, RateLimiter rateLimiter) {
        this.plugin = plugin;
        this.rateLimiter = rateLimiter;
    }

    @EventHandler
    public void onNpcRightClick(NPCRightClickEvent event) {
        if (!plugin.getPluginConfig().rightClickTriggerEnabled) {
            return;
        }

        NPC npc = event.getNPC();
        Player player = event.getClicker();

        plugin.getConversationSessionManager().start(player.getUniqueId(), npc.getId(), npc.getName());

        player.sendMessage(ChatColor.GRAY + "[" + npc.getName() + ChatColor.GRAY
                + "] Type in chat to talk to them. The conversation expires after a bit of inactivity.");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Optional<ConversationSessionManager.ActiveConversation> active =
                plugin.getConversationSessionManager().get(player.getUniqueId());
        if (active.isEmpty()) {
            return;
        }

        // Intercept the message: it must not end up in the server's public chat
        event.setCancelled(true);
        plugin.getConversationSessionManager().clear(player.getUniqueId());

        if (!rateLimiter.tryAcquire(player.getUniqueId())) {
            long remaining = rateLimiter.remainingCooldownMs(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                    ChatColor.RED + "Wait " + (remaining / 1000.0) + "s before talking to an NPC again."));
            return;
        }

        String message = event.getMessage();
        int npcId = active.get().npcId();
        String npcName = active.get().npcName();

        plugin.getConversationService().handlePlayerMessage(npcId, npcName, player.getName(), player.getUniqueId(), message)
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
}
