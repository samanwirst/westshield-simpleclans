package com.wiut.clans.test;

import com.google.gson.Gson;
import fr.xephi.authme.api.v3.AuthMeApi;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import com.wiut.clans.territory.ClanColors;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.markers.HtmlMarker;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated fixture controls and observations; must never be installed on production. */
public final class TerritoryProbe extends JavaPlugin {
    private final List<Map<String,Object>> results=new ArrayList<>();
    private void record(String label,Object value) {
        results.add(Map.of("label",label,"value",value));
        try { Files.writeString(getDataFolder().toPath().resolve("results.json"),new Gson().toJson(results)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override public void onEnable() { getDataFolder().mkdirs();record("enabled",true); }
    private Map<String,Object> inspect(Player player) {
        var manager=SimpleClans.getInstance().getClanManager();
        var clan=manager.getClanByPlayerUniqueId(player.getUniqueId());
        return Map.ofEntries(Map.entry("clan",clan==null?"":clan.getTag()),Map.entry("clans",manager.getClans().size()),
                Map.entry("admin",player.hasPermission("wiutclans.admin")),Map.entry("op",player.isOp()),
                Map.entry("authenticated",AuthMeApi.getInstance().isAuthenticated(player)),
                Map.entry("world",player.getWorld().getName()),Map.entry("mode",player.getGameMode().name()),
                Map.entry("group-poly",player.hasPermission("group.poly")),Map.entry("group-other",player.hasPermission("group.other")),
                Map.entry("open",player.getOpenInventory().getTopInventory().getType().name()),
                Map.entry("held",player.getInventory().getItemInMainHand().getType().name()),
                Map.entry("color-tag",clan==null?"":clan.getColorTag()),
                Map.entry("display-name",player.getDisplayName()),
                Map.entry("claims-enabled",GriefPrevention.instance.claimsEnabledForWorld(player.getWorld())));
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        try {
            if (args[0].equals("block")) {
                record(args[5],Bukkit.getWorld(args[1]).getBlockAt(Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4])).getType().name());return true;
            }
            if (args[0].equals("fixture")) {
                Bukkit.getWorld(args[1]).getBlockAt(Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4])).setType(Material.valueOf(args[5]));
                record(args[6],true);return true;
            }
            Player player=Bukkit.getPlayerExact(args[1]);
            if (player==null) throw new IllegalArgumentException("Player not online");
            switch (args[0]) {
                case "inspect" -> record(args[2],inspect(player));
                case "position" -> {
                    var world=Bukkit.getWorld(args[2]);int x=Integer.parseInt(args[3]),y=Integer.parseInt(args[4]),z=Integer.parseInt(args[5]);
                    for(int dx=-1;dx<=3;dx++) for(int dz=-1;dz<=1;dz++) {
                        world.getBlockAt(x+dx,y-1,z+dz).setType(Material.STONE);
                        for(int dy=0;dy<4;dy++) world.getBlockAt(x+dx,y+dy,z+dz).setType(Material.AIR);
                    }
                    player.closeInventory();
                    player.teleport(new Location(world,x+.5,y,z+.5,-90,30));
                    player.setGameMode(GameMode.CREATIVE);
                    player.getInventory().setHeldItemSlot(0);
                    player.getInventory().setItem(0,ItemStack.of(Material.STONE,64));
                    record(args[6],inspect(player));
                }
                case "equip" -> {
                    player.getInventory().setHeldItemSlot(0);
                    player.getInventory().setItem(0,ItemStack.of(Material.valueOf(args[2]),64));
                    record(args[3],inspect(player));
                }
                case "copywand" -> {
                    Player target=Bukkit.getPlayerExact(args[2]);
                    ItemStack wand=java.util.Arrays.stream(player.getInventory().getContents()).filter(item->item!=null&&item.getType()==Material.BLAZE_ROD&&item.hasItemMeta()).findFirst().orElseThrow();
                    target.getInventory().setHeldItemSlot(0);target.getInventory().setItem(0,wand.clone());record(args[3],true);
                }
                case "select" -> {
                    var block=player.getWorld().getBlockAt(Integer.parseInt(args[3]),63,Integer.parseInt(args[4]));
                    var event=new PlayerInteractEvent(player,Action.valueOf(args[2]),player.getInventory().getItemInMainHand(),block,BlockFace.UP,EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(event);
                    record(args[5],Map.of("use-block",event.useInteractedBlock().name(),"held",player.getInventory().getItemInMainHand().getType().name()));
                }
                case "rename" -> {
                    var clan=SimpleClans.getInstance().getClanManager().getClan(args[2]);
                    clan.setName(args[3]);SimpleClans.getInstance().getStorageManager().updateClan(clan);record(args[4],true);
                }
                case "claim" -> {
                    var gp=GriefPrevention.instance;var location=player.getLocation();var claim=gp.dataStore.getClaimAt(location,true,null);
                    record(args[2],Map.of("claimed",claim!=null,"build",gp.allowBuild(player,location,Material.STONE)==null));
                }
                case "map" -> {
                    var api=BlueMapAPI.getInstance().orElse(null);
                    if(api==null) { record(args[3],Map.of("ready",false));break; }
                    var map=api.getMap(args[2]).orElse(null);
                    if(map==null) { record(args[3],Map.of("ready",false));break; }
                    var layer=map.getMarkerSets().get("wiut-clans");
                    if(layer==null) { record(args[3],Map.of("ready",false));break; }
                    ArrayList<Map<String,Object>> markers=new ArrayList<>();
                    layer.getMarkers().forEach((id,marker)-> {
                        if(marker instanceof ShapeMarker shape) markers.add(Map.of("id",id,"kind","shape", "points",shape.getShape().getPointCount(),
                                "vertices",java.util.Arrays.stream(shape.getShape().getPoints()).map(point->List.of(point.getX(),point.getY())).toList(),
                                "rgb",ClanColors.hex(shape.getFillColor().getRed()<<16|shape.getFillColor().getGreen()<<8|shape.getFillColor().getBlue()),
                                "alpha",shape.getFillColor().getAlpha()));
                        if(marker instanceof HtmlMarker html) markers.add(Map.of("id",id,"kind","html","html",html.getHtml()));
                    });
                    record(args[3],Map.of("ready",true,"toggleable",layer.isToggleable(),"label",layer.getLabel(),"markers",markers));
                }
                default -> throw new IllegalArgumentException("Unknown fixture command");
            }
        } catch(Throwable error) {
            getLogger().log(java.util.logging.Level.SEVERE,"Local territory probe failed",error);
            record("error",error.getClass().getSimpleName());
        }
        return true;
    }
}
