package com.westshield.welcome;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.LogoutEvent;
import fr.xephi.authme.events.RegisterEvent;
import fr.xephi.authme.events.RestoreSessionEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.floodgate.api.FloodgateApi;

/** Authenticated presentation only: no account, permission, world or inventory writes. */
public final class WestShieldWelcome extends JavaPlugin implements Listener {
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Set<UUID> welcomed = new HashSet<>();
    private final Map<UUID, BukkitTask> pending = new HashMap<>();
    private long automaticWelcomes;

    @Override public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Welcome presentation ready; authentication is owned by AuthMe.");
    }

    private boolean authenticated(Player player) {
        return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player
                && AuthMeApi.getInstance().isAuthenticated(player);
    }

    private void queue(Player player) {
        // AuthMe can publish completion events from its asynchronous worker.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player) return;
            UUID id = player.getUniqueId();
            if (welcomed.contains(id)) return;
            BukkitTask previous = pending.remove(id);
            if (previous != null) previous.cancel();
            pending.put(id, Bukkit.getScheduler().runTaskLater(this, () -> {
                pending.remove(id);
                if (!authenticated(player) || !welcomed.add(id)) return;
                showWelcome(player, true);
                automaticWelcomes++;
            }, 15L));
        });
    }

    private void reset(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            welcomed.remove(player.getUniqueId());
            BukkitTask task = pending.remove(player.getUniqueId());
            if (task != null) task.cancel();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) { queue(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void login(LoginEvent event) { queue(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void register(RegisterEvent event) { queue(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void restore(RestoreSessionEvent event) { queue(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void logout(LogoutEvent event) { reset(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { reset(event.getPlayer()); }

    private Component render(String template, String name) {
        return mini.deserialize(template, Placeholder.unparsed("player", name));
    }

    private boolean bedrock(Player player) {
        return Bukkit.getPluginManager().isPluginEnabled("floodgate")
                && FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    public List<Component> welcome(String name, boolean bedrock) {
        return getConfig().getStringList(bedrock ? "welcome.bedrock" : "welcome.java").stream()
                .map(line -> render(line, name)).toList();
    }

    private void showWelcome(Player player, boolean automatic) {
        welcome(player.getName(), bedrock(player)).forEach(player::sendMessage);
        if (automatic && getConfig().getBoolean("title.enabled", true)) {
            player.showTitle(Title.title(render(getConfig().getString("title.main", "WestShield"), player.getName()),
                    render(getConfig().getString("title.subtitle", "Welcome, <player>!"), player.getName()),
                    Title.Times.times(Duration.ofMillis(350), Duration.ofMillis(1800), Duration.ofMillis(500))));
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("westshieldwelcome") && args.length == 1 && args[0].equalsIgnoreCase("status")) {
            if (sender.hasPermission("westshieldwelcome.admin")) sender.sendMessage("WestShield welcome: ready=true; auth=AuthMe; automatic-welcomes="
                    + automaticWelcomes + "; pending=" + pending.size());
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Use /westshieldwelcome status from console.");return true; }
        if (!authenticated(player)) { player.sendMessage(render(getConfig().getString("sign-in", "<gray>Please sign in first.</gray>"), player.getName()));return true; }
        if (args.length != 0) return false;
        switch (command.getName()) {
            case "map" -> getConfig().getStringList("map").forEach(line -> player.sendMessage(render(line, player.getName())));
            case "voice" -> getConfig().getStringList("voice").forEach(line -> player.sendMessage(render(line, player.getName())));
            default -> showWelcome(player, false);
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return command.getName().equals("westshieldwelcome") && args.length == 1 && sender.hasPermission("westshieldwelcome.admin")
                && "status".startsWith(args[0].toLowerCase(java.util.Locale.ROOT)) ? List.of("status") : List.of();
    }
}
