package com.wiut.rules;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.session.GeyserSession;

/** Applies dimension rules without altering portal frames or generated terrain. */
public final class WiutWorldRules extends JavaPlugin implements Listener {
    private boolean locatorDisabled;
    private boolean endLocked;
    private int overworldRadius;
    private int netherRadius;
    private List<String> survivalOverworlds;
    private List<String> survivalNethers;
    private Component endMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfiguration();
        getServer().getPluginManager().registerEvents(this, this);
        applyAll();
        getLogger().info("World borders and locator policy applied; End locked=" + endLocked);
    }

    private void readConfiguration() {
        int normal = getConfig().getInt("overworld-border-radius", 2000);
        int nether = getConfig().getInt("nether-border-radius", 1000);
        if (normal < 1 || nether < 1 || normal > 29999984 || nether > 29999984) {
            throw new IllegalArgumentException("World border radii must be between 1 and 29999984");
        }
        survivalOverworlds = List.copyOf(getConfig().getStringList("survival-overworlds"));
        survivalNethers = List.copyOf(getConfig().getStringList("survival-nethers"));
        if (survivalOverworlds.isEmpty() || survivalNethers.isEmpty()) {
            throw new IllegalArgumentException("Explicit survival world lists are required");
        }
        overworldRadius = normal;
        netherRadius = nether;
        locatorDisabled = getConfig().getBoolean("locator-disabled", true);
        endLocked = getConfig().getBoolean("end-locked", true);
        endMessage = Component.text(getConfig().getString("end-locked-message", "The End is currently closed."));
    }

    private void applyAll() {
        for (World world : getServer().getWorlds()) applyWorld(world);
        for (Player player : getServer().getOnlinePlayers()) applyPlayer(player);
    }

    private void applyWorld(World world) {
        world.setGameRule(GameRule.LOCATOR_BAR, !locatorDisabled);
        int radius = switch (world.getEnvironment()) {
            case NORMAL -> survivalOverworlds.contains(world.getName()) ? overworldRadius : 0;
            case NETHER -> survivalNethers.contains(world.getName()) ? netherRadius : 0;
            default -> 0;
        };
        if (radius != 0) {
            world.getWorldBorder().setCenter(0.0, 0.0);
            world.getWorldBorder().setSize(radius * 2.0);
        }
    }

    private void applyPlayer(Player player) {
        for (Attribute attribute : List.of(Attribute.WAYPOINT_TRANSMIT_RANGE, Attribute.WAYPOINT_RECEIVE_RANGE)) {
            var instance = player.getAttribute(attribute);
            if (instance != null) instance.setBaseValue(locatorDisabled ? 0.0 : instance.getDefaultValue());
        }
        suppressBedrockLocator(player);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) suppressBedrockLocator(player);
        }, 20L);
    }

    private void suppressBedrockLocator(Player player) {
        // Pinned Geyser 2.11.3 initially sends locatorBar=true. Override it on its event loop.
        var connection = GeyserApi.api().connectionByUuid(player.getUniqueId());
        if (connection instanceof GeyserSession session) {
            boolean enabled = !locatorDisabled;
            session.executeInEventLoop(() -> {
                if (!session.isClosed()) session.sendGameRule("locatorBar", enabled);
            });
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) { applyWorld(event.getWorld()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { applyPlayer(event.getPlayer()); }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) { applyPlayer(event.getPlayer()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        getServer().getScheduler().runTask(this, () -> applyPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrameInteraction(PlayerInteractEvent event) {
        if (!endLocked || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.END_PORTAL_FRAME
                || event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.getPlayer().sendActionBar(endMessage);
    }

    private boolean isLockedDestination(Location destination) {
        return endLocked && destination != null && destination.getWorld() != null
                && destination.getWorld().getEnvironment() == World.Environment.THE_END;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (isLockedDestination(event.getTo())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(endMessage);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (isLockedDestination(event.getTo())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(endMessage);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (isLockedDestination(event.getTo())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (endLocked && event.getBlocks().stream().anyMatch(block -> block.getType() == Material.END_PORTAL)) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("wiutworldrules.admin")) {
            sender.sendMessage("You do not have permission to do that.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("end")) {
            String state = args[1].toLowerCase(Locale.ROOT);
            if (!state.equals("open") && !state.equals("close")) return false;
            endLocked = state.equals("close");
            getConfig().set("end-locked", endLocked);
            saveConfig();
            sender.sendMessage("End access: " + (endLocked ? "closed" : "open") + ". Portal blocks were not changed.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            try {
                readConfiguration();
                applyAll();
                sender.sendMessage("WIUT world rules reloaded.");
            } catch (IllegalArgumentException failure) {
                sender.sendMessage("Configuration rejected: " + failure.getMessage());
            }
            return true;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("status"))) {
            sender.sendMessage("WIUT rules: locator-disabled=" + locatorDisabled + "; end-locked=" + endLocked);
            for (World world : getServer().getWorlds()) {
                var border = world.getWorldBorder();
                sender.sendMessage("World " + world.getName() + ": " + world.getEnvironment()
                        + "; locator=" + world.getGameRuleValue(GameRule.LOCATOR_BAR)
                        + "; border=" + border.getSize() + "; center="
                        + border.getCenter().getX() + "," + border.getCenter().getZ());
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("wiutworldrules.admin")) return List.of();
        if (args.length == 1) return List.of("status", "reload", "end");
        if (args.length == 2 && args[0].equalsIgnoreCase("end")) return List.of("open", "close");
        return List.of();
    }
}
