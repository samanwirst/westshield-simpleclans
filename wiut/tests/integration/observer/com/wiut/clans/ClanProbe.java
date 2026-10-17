package com.wiut.clans;

import com.google.gson.Gson;
import fr.xephi.authme.api.v3.AuthMeApi;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Local-only observations and controlled fixtures; never deploy this plugin. */
public final class ClanProbe extends JavaPlugin implements Listener {
    private final List<Map<String, Object>> evidence = new ArrayList<>();

    private void record(String action, Object value) {
        evidence.add(Map.of("action", action, "value", value));
        try { Files.writeString(getDataFolder().toPath().resolve("results.json"), new Gson().toJson(evidence)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        Bukkit.getPluginManager().registerEvents(this, this);
        record("enabled", true);
    }

    private List<String> items(ItemStack[] inventory) {
        List<String> result = new ArrayList<>();
        for (ItemStack item : inventory) result.add(item == null ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        return result;
    }

    private Map<String, Object> state(Player player) {
        Map<String, Object> value = new LinkedHashMap<>();
        var clan = SimpleClans.getInstance().getClanManager().getClanByPlayerUniqueId(player.getUniqueId());
        value.put("name", player.getName());
        value.put("clan", clan == null ? "" : clan.getTag());
        value.put("clan-name", clan == null ? "" : clan.getName());
        value.put("members", clan == null ? 0 : clan.getMembers().size());
        value.put("clans", SimpleClans.getInstance().getClanManager().getClans().size());
        value.put("group-wiut", player.hasPermission("group.wiut"));
        value.put("group-other", player.hasPermission("group.other"));
        value.put("op", player.isOp());
        value.put("authenticated", AuthMeApi.getInstance().isAuthenticated(player));
        value.put("world", player.getWorld().getName());
        value.put("mode", player.getGameMode().name());
        value.put("inventory", items(player.getInventory().getContents()));
        value.put("ender", items(player.getEnderChest().getContents()));
        value.put("location", List.of(player.getX(), player.getY(), player.getZ()));
        value.put("open-inventory", player.getOpenInventory().getTopInventory().getType().name());
        for (String permission : List.of("simpleclans.member.coords", "simpleclans.member.vitals",
                "simpleclans.member.home", "simpleclans.leader.home-set", "simpleclans.leader.regroup.me",
                "simpleclans.leader.war", "simpleclans.leader.create", "simpleclans.member.bank")) {
            value.put(permission, player.hasPermission(permission));
        }
        var gp = GriefPrevention.instance;
        var location = new Location(Bukkit.getWorld("wiut_creative"), 600.5, 64, 600.5);
        var claim = gp.dataStore.getClaimAt(location, true, null);
        value.put("claimed", claim != null);
        value.put("raw-build", gp.allowBuild(player, location, Material.STONE) == null);
        value.put("raw-build-low", gp.allowBuild(player, new Location(location.getWorld(), 600.5, -64, 600.5), Material.STONE) == null);
        value.put("raw-build-high", gp.allowBuild(player, new Location(location.getWorld(), 600.5, 319, 600.5), Material.STONE) == null);
        value.put("wilderness-build", gp.allowBuild(player, new Location(location.getWorld(), 1500.5, 64, 1500.5), Material.STONE) == null);
        value.put("claims-enabled-here", gp.claimsEnabledForWorld(player.getWorld()));
        value.put("raw-container", claim != null && claim.checkPermission(player, ClaimPermission.Container, null) == null);
        return value;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        try {
            if (args[0].equals("block")) {
                record(args[4], Bukkit.getWorld("wiut_creative").getBlockAt(Integer.parseInt(args[1]),
                        Integer.parseInt(args[2]), Integer.parseInt(args[3])).getType().name());
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) throw new IllegalArgumentException("Player not online");
            switch (args[0]) {
                case "inspect" -> record(args[2], state(player));
                case "position" -> {
                    player.closeInventory();
                    int x = args.length > 2 ? Integer.parseInt(args[2]) : 600;
                    int z = args.length > 3 ? Integer.parseInt(args[3]) : 600;
                    var world = Bukkit.getWorld("wiut_creative");
                    for (int dx = 0; dx <= 3; dx++) {
                        world.getBlockAt(x + dx, 63, z).setType(Material.GRASS_BLOCK);
                        for (int y = 64; y <= 67; y++) world.getBlockAt(x + dx, y, z).setType(Material.AIR);
                    }
                    player.teleport(new Location(world, x + .5, 64, z + .5, -90, 30));
                    player.getInventory().setHeldItemSlot(0);
                    player.getInventory().setItem(0, new ItemStack(Material.STONE, 64));
                    record("position-" + player.getName(), state(player));
                }
                case "fixture" -> {
                    var world = Bukkit.getWorld("wiut_creative");
                    world.getBlockAt(602, 64, 600).setType(Material.valueOf(args[2]));
                    record("fixture", args[2]);
                }
                default -> throw new IllegalArgumentException("Unknown test command");
            }
        } catch (Throwable failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "CLAN_PROBE_FAIL " + String.join(" ", args), failure);
            record("probe-error", failure.getClass().getName());
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getPlayer().getName().startsWith("ClanProbe")) record("native-place", Map.of(
                "player", event.getPlayer().getName(), "cancelled", event.isCancelled(), "can-build", event.canBuild()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        if (event.getPlayer().getName().startsWith("ClanProbe")) record("native-break", Map.of(
                "player", event.getPlayer().getName(), "cancelled", event.isCancelled()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer().getName().startsWith("ClanProbe")) record("native-open", Map.of(
                "player", event.getPlayer().getName(), "cancelled", event.isCancelled(), "type", event.getInventory().getType().name()));
    }
}
