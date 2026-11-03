package com.cheonsa.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import org.bukkit.Location;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TicTacToePlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Game> games = new HashMap<>();
    private final Map<UUID, UUID> invites = new HashMap<>();     // invited player -> challenger
    private final Map<UUID, Integer> lastClick = new HashMap<>();
    private int tick;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tick++;
            for (Game g : new ArrayList<>(new HashSet<>(games.values()))) g.tick();
        }, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (Game g : new ArrayList<>(new HashSet<>(games.values()))) g.end("The game was stopped (server reload).");
    }

    void remove(Game g) {
        games.remove(g.x.getUniqueId());
        games.remove(g.o.getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) return false;
        switch (args[0].toLowerCase()) {
            case "challenge" -> {
                if (args.length < 2) return false;
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null || target.equals(p)) {
                    p.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                } else if (games.containsKey(p.getUniqueId()) || games.containsKey(target.getUniqueId())) {
                    p.sendMessage(Component.text("One of you is already in a game.", NamedTextColor.RED));
                } else {
                    invites.put(target.getUniqueId(), p.getUniqueId());
                    p.sendMessage(Component.text("Challenge sent to " + target.getName() + ".", NamedTextColor.GREEN));
                    target.sendMessage(Component.text(p.getName() + " challenged you to Tic-Tac-Toe! ", NamedTextColor.GOLD)
                            .append(Component.text("[ACCEPT]", NamedTextColor.GREEN, TextDecoration.BOLD)
                                    .clickEvent(ClickEvent.runCommand("/ttt accept"))));
                }
            }
            case "accept" -> {
                UUID from = invites.remove(p.getUniqueId());
                Player challenger = from == null ? null : Bukkit.getPlayer(from);
                if (challenger == null) {
                    p.sendMessage(Component.text("You have no pending challenge.", NamedTextColor.RED));
                } else if (!challenger.getWorld().equals(p.getWorld())
                        || challenger.getLocation().distance(p.getLocation()) > 12) {
                    p.sendMessage(Component.text("Stand within 12 blocks of " + challenger.getName() + " to play.", NamedTextColor.RED));
                } else if (games.containsKey(p.getUniqueId()) || games.containsKey(challenger.getUniqueId())) {
                    p.sendMessage(Component.text("One of you is already in a game.", NamedTextColor.RED));
                } else {
                    Game g = new Game(this, challenger, p);
                    games.put(challenger.getUniqueId(), g);
                    games.put(p.getUniqueId(), g);
                    Component msg = Component.text("[Tic-Tac-Toe] ", NamedTextColor.GOLD)
                            .append(Component.text(challenger.getName() + " is X, " + p.getName()
                                    + " is O. Look at the board and left-click to play.", NamedTextColor.WHITE));
                    challenger.sendMessage(msg);
                    p.sendMessage(msg);
                }
            }
            case "leave" -> {
                Game g = games.get(p.getUniqueId());
                if (g == null) p.sendMessage(Component.text("You're not in a game.", NamedTextColor.RED));
                else g.end(p.getName() + " left the game.");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("challenge", "accept", "leave");
        if (args.length == 2 && args[0].equalsIgnoreCase("challenge")) return null; // player names
        return List.of();
    }

    // ------------------------------------------------------------------ input

    /** Players are spectators in-game: a left-click on the board's hitbox is a spectate attempt. */
    @EventHandler
    public void onSpectate(PlayerStartSpectatingEntityEvent e) {
        Player p = e.getPlayer();
        Game g = games.get(p.getUniqueId());
        if (g == null) return;
        e.setCancelled(true); // never let the camera leave the player
        if (g.view(p).hit(p) == null) return;
        int last = lastClick.getOrDefault(p.getUniqueId(), -100);
        if (tick - last < 4) return;
        lastClick.put(p.getUniqueId(), tick);
        g.click(p);
    }

    /** Blocks the spectator hotbar's "teleport to player" while in a game. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE && games.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /** Backstop for the zero fly speed: position stays locked, looking around is free. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!games.containsKey(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        e.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
    }

    /** Undoes the spectator freeze if the server stopped mid-game without restoring it. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Game.restore(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        invites.remove(e.getPlayer().getUniqueId());
        lastClick.remove(e.getPlayer().getUniqueId());
        Game g = games.get(e.getPlayer().getUniqueId());
        if (g != null) g.end(e.getPlayer().getName() + " left the server.");
    }
}
