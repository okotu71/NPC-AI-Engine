package com.okotu.npcai.command;

import com.okotu.npcai.OkotuNpcAiPlugin;
import com.okotu.npcai.db.CharacterDao;
import com.okotu.npcai.model.NpcCharacter;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Level;

public class OkotuCommand implements CommandExecutor {

    private final OkotuNpcAiPlugin plugin;

    public OkotuCommand(OkotuNpcAiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /okotunpc <reload|setmodel <npcId> <modello>|setbackstory <npcId> <testo>|<personalita>|info <npcId>>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadPlugin();
                sender.sendMessage(ChatColor.GREEN + "okotu-npc-ai-engine: configurazione ricaricata.");
            }
            case "setmodel" -> handleSetModel(sender, args);
            case "setbackstory" -> handleSetBackstory(sender, args);
            case "info" -> handleInfo(sender, args);
            default -> sender.sendMessage(ChatColor.RED + "Sottocomando sconosciuto.");
        }
        return true;
    }

    private void handleSetBackstory(CommandSender sender, String[] args) {
        // /okotunpc setbackstory <npcId> <backstory>|<personalita>
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED
                    + "Uso: /okotunpc setbackstory <npcId> <backstory>|<personalita>");
            return;
        }
        int npcId;
        try {
            npcId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "npcId non valido.");
            return;
        }
        String rest = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        String[] parts = rest.split("\\|", 2);
        String backstory = parts[0].trim();
        String personalita = parts.length > 1 ? parts[1].trim() : "";

        CharacterDao dao = plugin.getCharacterDao();
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    dao.findOrCreate(npcId, "NPC-" + npcId);
                    dao.updateBackstory(npcId, backstory, personalita);
                    sender.sendMessage(ChatColor.GREEN + "Backstory/personalita' aggiornate per NPC " + npcId + ".");
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Errore impostando backstory per NPC " + npcId, e);
                    sender.sendMessage(ChatColor.RED + "Errore aggiornando la backstory (vedi console).");
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void handleSetModel(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Uso: /okotunpc setmodel <npcId> <modello>");
            return;
        }
        int npcId;
        try {
            npcId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "npcId non valido.");
            return;
        }
        String model = args[2];
        CharacterDao dao = plugin.getCharacterDao();

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    dao.updateModel(npcId, model);
                    sender.sendMessage(ChatColor.GREEN + "Modello per NPC " + npcId + " impostato a " + model + ".");
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Errore impostando il modello per NPC " + npcId, e);
                    sender.sendMessage(ChatColor.RED + "Errore aggiornando il modello (vedi console).");
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Uso: /okotunpc info <npcId>");
            return;
        }
        int npcId;
        try {
            npcId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "npcId non valido.");
            return;
        }
        CharacterDao dao = plugin.getCharacterDao();

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    Optional<NpcCharacter> character = dao.find(npcId);
                    if (character.isEmpty()) {
                        sender.sendMessage(ChatColor.YELLOW + "Nessun personaggio salvato per NPC " + npcId
                                + " (verra' creato al primo dialogo).");
                        return;
                    }
                    NpcCharacter c = character.get();
                    sender.sendMessage(ChatColor.GOLD + "NPC " + c.npcId() + " - " + c.nome());
                    sender.sendMessage(ChatColor.GRAY + "Modello: " + (c.model() != null ? c.model() : "(default)"));
                    sender.sendMessage(ChatColor.GRAY + "Backstory: " + c.backstory());
                    sender.sendMessage(ChatColor.GRAY + "Personalita': " + c.personalita());
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Errore leggendo NPC " + npcId, e);
                    sender.sendMessage(ChatColor.RED + "Errore leggendo il personaggio (vedi console).");
                }
            }
        }.runTaskAsynchronously(plugin);
    }
}
