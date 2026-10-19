package com.wiut.creative;

import fr.xephi.authme.api.v3.AuthMeApi;
import java.util.List;
import java.util.Set;
import me.ryanhamshire.GriefPrevention.ClaimsMode;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.inventories.MultiverseInventoriesApi;

/** Keeps the creative sandbox separate while Multiverse owns player profiles. */
public final class WiutCreative extends JavaPlugin implements Listener {
    private String creativeWorld;
    private String survivalWorld;
    private Set<String> survivalWorlds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        creativeWorld = getConfig().getString("creative-world", "wiut_creative");
        survivalWorld = getConfig().getString("survival-world", "wiut");
        survivalWorlds = Set.copyOf(getConfig().getStringList("survival-worlds"));
        if (survivalWorlds.isEmpty() || survivalWorlds.contains(creativeWorld)
                || !survivalWorlds.contains(survivalWorld)) {
            throw new IllegalArgumentException("Creative and survival worlds must have distinct configured groups");
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (World world : getServer().getWorlds()) configureWorld(world);
        getLogger().info("Creative isolation ready for " + creativeWorld);
    }

    private boolean isCreative(World world) {
        return world != null && world.getName().equals(creativeWorld);
    }

    private boolean crossesBoundary(Location from, Location to) {
        return to != null && isCreative(from.getWorld()) != isCreative(to.getWorld());
    }

    private void configureWorld(World world) {
        if (!isCreative(world)) return;
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setPVP(false);
    }

    private boolean ready() {
        World world = getServer().getWorld(creativeWorld);
        var groups = MultiverseInventoriesApi.get().getWorldGroupManager();
        var creative = groups.getGroup("creative");
        var survival = groups.getGroup("survival");
        return getConfig().getBoolean("enabled", true) && world != null
                && GriefPrevention.instance.config_claims_worldModes.get(world) == ClaimsMode.SurvivalRequiringClaims
                && creative != null && creative.containsWorld(creativeWorld)
                && survival != null && survivalWorlds.stream().allMatch(survival::containsWorld)
                && !survival.containsWorld(creativeWorld);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("wiutcreative")) {
            if (!sender.hasPermission("wiutcreative.admin")) return true;
            World world = getServer().getWorld(creativeWorld);
            sender.sendMessage("WIUT creative: world=" + creativeWorld + "; loaded=" + (world != null) + "; ready=" + ready());
            if (world != null) sender.sendMessage("Creative border=" + world.getWorldBorder().getSize()
                    + "; natural-spawning=" + world.getGameRuleValue(GameRule.DO_MOB_SPAWNING)
                    + "; pvp=" + world.getPVP() + "; min-y=" + world.getMinHeight()
                    + "; spawn=" + world.getSpawnLocation());
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return true;
        }
        if (args.length != 0) return false;
        if (!player.hasPermission("wiutcreative.travel")) return true;
        if (!AuthMeApi.getInstance().isAuthenticated(player)) {
            player.sendMessage("Please log in first: /login or /register.");
            return true;
        }
        if (!ready()) {
            player.sendMessage("The creative world is unavailable. Please contact an administrator.");
            return true;
        }
        boolean entering = command.getName().equalsIgnoreCase("creative");
        if (entering == isCreative(player.getWorld())) {
            player.sendMessage(entering ? "You are already in the creative world." : "You are already in survival.");
            return true;
        }
        if (!entering && !isCreative(player.getWorld())) return true;
        if (entering && !survivalWorlds.contains(player.getWorld().getName())) {
            player.sendMessage("You can travel from a survival world.");
            return true;
        }
        player.closeInventory();
        if ((getServer().getPluginManager().isPluginEnabled("GSit") && !GsitTravelSupport.prepare(player))
                || (player.isInsideVehicle() && !player.leaveVehicle())) {
            player.sendMessage("Stand up from your seat or pose first, then try again.");
            return true;
        }
        String target = entering ? creativeWorld : survivalWorld;
        var parsed = MultiverseCoreApi.get().getDestinationsProvider().parseDestination("ll:" + target);
        var destination = parsed.getOrNull();
        Location location = destination == null ? null : destination.getLocation(player).getOrNull();
        if (location == null || (entering ? !isCreative(location.getWorld())
                : !survivalWorlds.contains(location.getWorld().getName()))) {
            player.sendMessage("This world is unavailable. Please contact an administrator.");
            return true;
        }
        // Close containers/crafting before Multiverse captures the outgoing inventory.
        player.closeInventory();
        if (player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            player.setGameMode(entering ? GameMode.CREATIVE : GameMode.SURVIVAL);
            player.sendMessage(entering
                    ? "Creative world. Select a claim with a golden shovel; /trust <player> adds a builder. Return: /survival."
                    : "You have returned to survival. Your inventory and state have been restored.");
        } else {
            player.sendMessage("Travel cancelled. You are still in your previous world.");
        }
        return true;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) { configureWorld(event.getWorld()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!crossesBoundary(event.getFrom(), event.getTo())) return;
        if (!AuthMeApi.getInstance().isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        } else {
            event.getPlayer().closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (isCreative(event.getFrom().getWorld())
                || (event.getTo() != null && isCreative(event.getTo().getWorld()))) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("Use /creative and /survival to travel between game modes."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (isCreative(event.getFrom().getWorld())
                || (event.getTo() != null && isCreative(event.getTo().getWorld()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (isCreative(event.getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (isCreative(event.getLocation().getWorld())) {
            event.blockList().clear();
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (isCreative(event.getBlock().getWorld())) {
            event.blockList().clear();
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!isCreative(event.getLocation().getWorld())) return;
        switch (event.getSpawnReason()) {
            case NATURAL, CHUNK_GEN, PATROL, RAID, VILLAGE_INVASION, VILLAGE_DEFENSE,
                    NETHER_PORTAL, REINFORCEMENTS -> event.setCancelled(true);
            default -> { /* Explicit eggs, breeding and constructed mobs remain available. */ }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
