import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.*;
import org.bukkit.Material;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.crypto.FloodgateCipher;
import org.geysermc.floodgate.util.BedrockData;
import fr.xephi.authme.api.v3.AuthMeApi;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class AutoProbe extends JavaPlugin {
 @Override public void onEnable(){getCommand("autoprobe").setExecutor(this);}
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
  if(!(sender instanceof ConsoleCommandSender))return true;
  try {
   var out=new LinkedHashMap<String,Object>();
   if(args[0].equals("hostname")){
    var plugin=Bukkit.getPluginManager().getPlugin("floodgate");var field=plugin.getClass().getDeclaredField("injector");field.setAccessible(true);Object injector=field.get(plugin);
    var method=injector.getClass().getMethod("getInstance",Class.class);method.setAccessible(true);
    var cipher=(FloodgateCipher)method.invoke(injector,FloodgateCipher.class);
    var data=BedrockData.of("26.30",args[1],args[2],1,"en_US",0,1,"127.0.0.1",0,"fixture");
    out.put("hostname","127.0.0.1\0"+new String(cipher.encryptFromString(data.toString()),StandardCharsets.UTF_8));
   }else{
    var p=Objects.requireNonNull(Bukkit.getPlayerExact(args[1]),"Fixture player missing");
    out.put("name",p.getName());out.put("uuid",p.getUniqueId().toString());out.put("authenticated",AuthMeApi.getInstance().isAuthenticated(p));
    out.put("bedrock",FloodgateApi.getInstance().isFloodgatePlayer(p.getUniqueId()));
    out.put("diamonds",Arrays.stream(p.getInventory().getContents()).filter(Objects::nonNull).filter(i->i.getType()==Material.DIAMOND).mapToInt(i->i.getAmount()).sum());
    out.put("level",p.getLevel());out.put("world",p.getWorld().getName());out.put("location",List.of(p.getLocation().getBlockX(),p.getLocation().getBlockY(),p.getLocation().getBlockZ()));
   }
   getDataFolder().mkdirs();Path file=getDataFolder().toPath().resolve(args[args.length-1]+".json");Files.writeString(file,new com.google.gson.Gson().toJson(out));
  }catch(Exception e){getLogger().severe("Fixture probe failed: "+e);}
  return true;
 }
}
