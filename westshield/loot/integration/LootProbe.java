import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.inventory.*;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import io.netty.buffer.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
public final class LootProbe extends JavaPlugin implements Listener {
 private boolean deny;
 private int interactions; private String lastInteraction="none";
 @EventHandler(priority=EventPriority.MONITOR) public void observed(org.bukkit.event.player.PlayerInteractEvent e){interactions++;lastInteraction=e.getAction()+"/"+e.useInteractedBlock()+"/"+(e.getClickedBlock()==null?"none":e.getClickedBlock().getType());}
 @Override public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);}
 @EventHandler(priority=EventPriority.NORMAL) public void denial(org.bukkit.event.player.PlayerInteractEvent e){if(deny)e.setUseInteractedBlock(Event.Result.DENY);}
 @SuppressWarnings({"unchecked","rawtypes"}) private int packetId(net.minecraft.network.ProtocolInfo protocol,net.minecraft.network.protocol.Packet packet){ByteBuf b=Unpooled.buffer();try{protocol.codec().encode(b,packet);return new FriendlyByteBuf(b).readVarInt();}finally{b.release();}}
 private Block block(int x){return Bukkit.getWorlds().getFirst().getBlockAt(x,65,30);}
 private void chest(int x,Material material,LootTables table){Block b=block(x);b.setType(material,false);var state=b.getState();if(state instanceof org.bukkit.loot.Lootable l){l.setLootTable(table.getLootTable());l.setSeed(4488);state.update();}}
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
  if(!(sender instanceof ConsoleCommandSender))return true;
  try{
   var out=new LinkedHashMap<String,Object>();Player p=args.length>1?Bukkit.getPlayerExact(args[1]):null;
   switch(args[0]){
    case "setup" -> {for(int x=25;x<=80;x++)for(int z=25;z<=35;z++)for(int y=65;y<=73;y++)Bukkit.getWorlds().getFirst().getBlockAt(x,y,z).setType(Material.AIR,false);for(int x=25;x<=80;x++)for(int z=25;z<=35;z++)Bukkit.getWorlds().getFirst().getBlockAt(x,64,z).setType(Material.STONE,false);
     chest(30,Material.CHEST,LootTables.SIMPLE_DUNGEON);chest(34,Material.BARREL,LootTables.SHIPWRECK_SUPPLY);chest(40,Material.CHEST,LootTables.BASTION_TREASURE);chest(60,Material.CHEST,LootTables.SIMPLE_DUNGEON);chest(70,Material.CHEST,LootTables.SHIPWRECK_TREASURE);
     chest(45,Material.CHEST,LootTables.SIMPLE_DUNGEON);chest(46,Material.CHEST,LootTables.SIMPLE_DUNGEON);block(45).setBlockData(Bukkit.createBlockData("minecraft:chest[facing=north,type=left]"),false);block(46).setBlockData(Bukkit.createBlockData("minecraft:chest[facing=north,type=right]"),false);
     block(50).setType(Material.CHEST,false);((org.bukkit.block.Container)block(50).getState()).getInventory().addItem(ItemStack.of(Material.DIAMOND,5));
     block(54).setType(Material.VAULT,false);
     var cart=Bukkit.getWorlds().getFirst().spawn(new Location(Bukkit.getWorlds().getFirst(),30.5,65,34.5),StorageMinecart.class);cart.setLootTable(LootTables.ABANDONED_MINESHAFT.getLootTable());out.put("cart",cart.getUniqueId().toString());
    }
    case "deny" -> deny=Boolean.parseBoolean(args[1]);
    case "teleport" -> {p.teleport(new Location(Bukkit.getWorlds().getFirst(),Double.parseDouble(args[2])+.5,65,28.5,0,17.22f));p.setGameMode(GameMode.SURVIVAL);}
    case "break" -> out.put("broken",p.breakBlock(block(Integer.parseInt(args[2]))));
    case "replace" -> block(Integer.parseInt(args[2])).setType(Material.CHEST,false);
    case "explode" -> {int x=Integer.parseInt(args[2]);p.teleport(new Location(p.getWorld(),1,100,1));out.put("exploded",p.getWorld().createExplosion(x+.5,65.5,30.5,4,false,true));}
    case "clear" -> {p.getInventory().clear();p.setItemOnCursor(null);}
    case "full" -> {for(int i=0;i<36;i++)p.getInventory().setItem(i,ItemStack.of(Material.COBBLESTONE,64));}
    case "close" -> p.closeInventory();
    case "cart" -> {var cart=(StorageMinecart)Bukkit.getEntity(UUID.fromString(args[2]));p.teleport(cart.getLocation().clone().add(0,0,-2));p.setGameMode(GameMode.SURVIVAL);var e=new org.bukkit.event.player.PlayerInteractEntityEvent(p,cart,EquipmentSlot.HAND);Bukkit.getPluginManager().callEvent(e);out.put("cancelled",e.isCancelled());}
    case "cartmove" -> {var cart=(StorageMinecart)Bukkit.getEntity(UUID.fromString(args[2]));cart.teleport(cart.getLocation().clone().add(2,0,0));}
    case "cartbreak" -> {var cart=(StorageMinecart)Bukkit.getEntity(UUID.fromString(args[2]));var e=new org.bukkit.event.vehicle.VehicleDestroyEvent(cart,org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).withCausingEntity(p).withDirectEntity(p).build(),p);Bukkit.getPluginManager().callEvent(e);if(!e.isCancelled())cart.remove();}
    case "hopper" -> {var w=Bukkit.getWorlds().getFirst();w.getBlockAt(70,64,30).setType(Material.HOPPER,false);var input=w.getBlockAt(71,65,30);input.setType(Material.HOPPER,false);var d=(org.bukkit.block.data.type.Hopper)input.getBlockData();d.setFacing(BlockFace.WEST);input.setBlockData(d,false);((org.bukkit.block.Hopper)input.getState()).getInventory().addItem(ItemStack.of(Material.DIAMOND,3));}
    case "hopperinspect" -> {var w=Bukkit.getWorlds().getFirst();out.put("below",Arrays.stream(((org.bukkit.block.Hopper)w.getBlockAt(70,64,30).getState()).getInventory().getContents()).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum());out.put("input",Arrays.stream(((org.bukkit.block.Hopper)w.getBlockAt(71,65,30).getState()).getInventory().getContents()).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum());}
    case "placednatural" -> {int x=80;block(x).setType(Material.AIR,false);var prior=block(x).getState();chest(x,Material.CHEST,LootTables.SIMPLE_DUNGEON);var e=new BlockPlaceEvent(block(x),prior,block(x).getRelative(BlockFace.DOWN),ItemStack.of(Material.CHEST),p,true,EquipmentSlot.HAND);Bukkit.getPluginManager().callEvent(e);}
    case "creative" -> {var w=Bukkit.getWorlds().stream().filter(v->v!=Bukkit.getWorlds().getFirst()&&v.getEnvironment()==World.Environment.NORMAL).findFirst().orElseThrow();var l=w.getSpawnLocation();var b=w.getBlockAt(l.getBlockX()+1,l.getBlockY(),l.getBlockZ());b.setType(Material.CHEST,false);var state=(org.bukkit.block.Chest)b.getState();state.setLootTable(LootTables.SIMPLE_DUNGEON.getLootTable());state.update();p.teleport(l);p.setGameMode(GameMode.CREATIVE);var e=new org.bukkit.event.player.PlayerInteractEvent(p,Action.RIGHT_CLICK_BLOCK,null,b,BlockFace.UP,EquipmentSlot.HAND);Bukkit.getPluginManager().callEvent(e);out.put("world",w.getUID().toString());}
    case "eventopen" -> {var e=new org.bukkit.event.player.PlayerInteractEvent(p,org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,null,block(Integer.parseInt(args[2])),BlockFace.UP,EquipmentSlot.HAND);Bukkit.getPluginManager().callEvent(e);out.put("denied",e.useInteractedBlock().name());}
    case "inspect" -> {
     out.put("name",p.getName());out.put("uuid",p.getUniqueId().toString());out.put("authenticated",fr.xephi.authme.api.v3.AuthMeApi.getInstance().isAuthenticated(p));out.put("bedrock",org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgatePlayer(p.getUniqueId()));out.put("mode",p.getGameMode().name());out.put("grim_exempt",p.hasPermission("grim.exempt"));out.put("operator",p.isOp());out.put("location",p.getLocation().toString());out.put("interactions",interactions);out.put("last_interaction",lastInteraction);var nativePlayer=((CraftPlayer)p).getHandle();var awaiting=nativePlayer.connection.getClass().getDeclaredField("awaitingPositionFromClient");awaiting.setAccessible(true);out.put("awaiting_teleport",String.valueOf(awaiting.get(nativePlayer.connection)));out.put("client_loaded",nativePlayer.connection.hasClientLoaded());var b=block(30).getState();out.put("block30",b.getType().name());if(b instanceof org.bukkit.loot.Lootable ll){out.put("table30",String.valueOf(ll.getLootTable()));if(ll instanceof com.destroystokyo.paper.loottable.LootableInventory pp)out.put("filled30",pp.hasBeenFilled());}out.put("world_key",p.getWorld().getKey().toString());
     Inventory top=p.getOpenInventory().getTopInventory();out.put("size",top.getSize());out.put("holder",top.getHolder()==null?"none":top.getHolder().getClass().getName());out.put("loot",Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(top.getContents())));out.put("occupied",java.util.stream.IntStream.range(0,top.getSize()).filter(i->top.getItem(i)!=null&&!top.getItem(i).getType().isAir()).boxed().toList());out.put("inventory",Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(p.getInventory().getStorageContents())));out.put("items",Arrays.stream(p.getInventory().getStorageContents()).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum());
    }
    case "protocol" -> {
     var access=((org.bukkit.craftbukkit.CraftWorld)Bukkit.getWorlds().getFirst()).getHandle().registryAccess();
     var incoming=GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access),()->false);
     var outgoing=GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access));
     out.put("teleport",packetId(outgoing,new ClientboundPlayerPositionPacket(1,new net.minecraft.world.entity.PositionMoveRotation(Vec3.ZERO,Vec3.ZERO,0,0),Set.of())));
     out.put("teleport_ack",packetId(incoming,new ServerboundAcceptTeleportationPacket(1)));
     out.put("keepalive",packetId(outgoing,new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(1)));
     out.put("keepalive_ack",packetId(incoming,new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(1)));
     out.put("ping",packetId(outgoing,new net.minecraft.network.protocol.common.ClientboundPingPacket(1)));
     out.put("pong",packetId(incoming,new net.minecraft.network.protocol.common.ServerboundPongPacket(1)));
     out.put("loaded",packetId(incoming,new ServerboundPlayerLoadedPacket()));
     out.put("disconnect",packetId(outgoing,new net.minecraft.network.protocol.common.ClientboundDisconnectPacket(net.minecraft.network.chat.Component.literal("fixture"))));
    }
    case "packet" -> {
     var handle=((CraftPlayer)p).getHandle();var codec=GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(handle.level().registryAccess()),()->false).codec();ByteBuf buffer=Unpooled.buffer();
     try{
      if(args[2].equals("move")){var l=p.getLocation();codec.encode(buffer,new ServerboundMovePlayerPacket.PosRot(l.getX(),l.getY(),l.getZ(),l.getYaw(),l.getPitch(),true,false));}
      else if(args[2].equals("open")){int x=Integer.parseInt(args[3]);BlockPos position=new BlockPos(x,65,30);codec.encode(buffer,new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,new BlockHitResult(new Vec3(x+.5,66.0,30.5),Direction.UP,position,false),0));}
      else {var menu=handle.containerMenu;codec.encode(buffer,new ServerboundContainerClickPacket(menu.containerId,menu.getStateId(),Short.parseShort(args[3]),(byte)0,ContainerInput.PICKUP,new Int2ObjectOpenHashMap<HashedStack>(),HashedStack.EMPTY));}
      out.put("packet",Base64.getEncoder().encodeToString(ByteBufUtil.getBytes(buffer)));
     }finally{buffer.release();}
    }
    default -> throw new IllegalArgumentException("Unknown fixture operation");
   }
   getDataFolder().mkdirs();Files.writeString(getDataFolder().toPath().resolve(args[args.length-1]+".json"),new com.google.gson.Gson().toJson(out));
  }catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"Fixture probe failed",e);}
  return true;
 }
}
