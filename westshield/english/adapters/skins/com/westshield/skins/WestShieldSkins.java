package com.westshield.skins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.xephi.authme.api.v3.AuthMeApi;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.property.SkinIdentifier;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.property.SkinType;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Resolves explicit source identities; SkinsRestorer owns profile application and durable selections. */
public final class WestShieldSkins extends JavaPlugin implements Listener {
    private enum Source { MINECRAFT, ELY, UPDATE }
    private record Skin(UUID id,String name,SkinProperty property) { }
    private final Map<UUID,Long> nextRequest=new HashMap<>();
    private final Set<UUID> pending=new HashSet<>();
    private SkinsRestorer skins;
    private ThreadPoolExecutor workers;
    private HttpClient http;
    private long submitted,failed;

    @Override public void onEnable(){
        skins=SkinsRestorerProvider.get();
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"WestShield-skin-lookup");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        Bukkit.getPluginManager().registerEvents(this,this);
        getLogger().info("Skin sources ready: /skin=Minecraft; /elyskin=Ely.by; automatic=Ely-first; authentication=AuthMe");
    }
    @Override public void onDisable(){if(workers!=null)workers.shutdownNow();if(http!=null)http.close();}
    private boolean authenticated(Player p){return isEnabled() && p.isOnline() && Bukkit.getPlayer(p.getUniqueId())==p && AuthMeApi.getInstance().isAuthenticated(p);}
    private void message(Player p,String text){p.sendMessage(Component.text("[",NamedTextColor.DARK_GRAY).append(Component.text("West",NamedTextColor.AQUA)).append(Component.text("Shield",NamedTextColor.GOLD)).append(Component.text("] ",NamedTextColor.DARK_GRAY)).append(Component.text(text,NamedTextColor.GRAY)));}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void skinCommand(PlayerCommandPreprocessEvent event){
        String[] words=event.getMessage().trim().split("\\s+");
        String root=words[0].toLowerCase(Locale.ROOT);if(!root.equals("/skin") && !root.equals("/skinsrestorer:skin"))return;
        if(!authenticated(event.getPlayer()))return; // AuthMe owns its sign-in response.
        String name=null;Source source=Source.MINECRAFT;
        if(words.length==2 && words[1].equalsIgnoreCase("update"))source=Source.UPDATE;
        else if(words.length==2 && !Set.of("clear","reset","help","undo","revert","random","search","edit","favourite","favorite","url","notification","set","select").contains(words[1].toLowerCase(Locale.ROOT)))name=words[1];
        else if(words.length==3 && Set.of("set","select","mojang","minecraft","ely").contains(words[1].toLowerCase(Locale.ROOT))){name=words[2];if(words[1].equalsIgnoreCase("ely"))source=Source.ELY;}
        else return;
        event.setCancelled(true);request(event.getPlayer(),source,name);
    }
    @EventHandler public void quit(PlayerQuitEvent event){nextRequest.remove(event.getPlayer().getUniqueId());}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(command.getName().equals("westshieldskins")){
            if(sender instanceof Player p && !authenticated(p))return true;
            sender.sendMessage("WestShield skins: ready=true; default-command=Minecraft; automatic=Ely-first; auth=AuthMe; pending="+pending.size()+"; submitted="+submitted+"; failed="+failed);return true;
        }
        if(!(sender instanceof Player p)) {sender.sendMessage("Use /elyskin in game.");return true;}
        if(!authenticated(p)){message(p,"Sign in with /login first.");return true;}
        if(args.length>1){message(p,"Use /elyskin [nickname].");return true;}
        request(p,Source.ELY,args.length==0?p.getName():args[0]);return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String label,String[] args){return List.of();}

    private void request(Player player,Source source,String name){
        String permission=source==Source.UPDATE?"skinsrestorer.command.update":"skinsrestorer.command.set";
        if(!authenticated(player) || !player.hasPermission(permission) || (source==Source.ELY && !player.hasPermission("westshieldskins.ely"))){message(player,"You cannot change this skin.");return;}
        if(name!=null && !name.matches("[A-Za-z0-9_]{1,16}")){message(player,"Use a Java nickname with 1–16 letters, numbers or underscores.");return;}
        UUID account=player.getUniqueId();long now=System.currentTimeMillis();
        if(pending.contains(account)){message(player,"Your skin is still loading.");return;}
        long remaining=nextRequest.getOrDefault(account,0L)-now;
        if(remaining>0 && !player.hasPermission("skinsrestorer.bypasscooldown")){message(player,"Wait "+((remaining+999)/1000)+" seconds before changing your skin again.");return;}
        SkinIdentifier selected=skins.getPlayerStorage().getSkinIdOfPlayer(account).orElse(null);
        String ownName=player.getName();nextRequest.put(account,now+30000);pending.add(account);message(player,"Loading skin…");
        try{workers.execute(()->{
            try{
                Skin skin;
                if(source==Source.UPDATE){
                    if(selected==null){skin=elyByName(ownName);if(skin==null)skin=minecraftByName(ownName);}
                    else if(selected.getSkinType()==SkinType.PLAYER){UUID id=selected.getPlayerUniqueId();skin=minecraftProfile(id,null);if(skin==null)skin=elyProfile(id);}
                    else throw new IOException("Use the command for that custom skin source");
                }else skin=source==Source.ELY?elyByName(name):minecraftByName(name);
                if(skin==null)throw new IOException("Skin not found");
                // Cache only public texture data under its actual source UUID, never change the server account UUID.
                skins.getSkinStorage().setPlayerSkinData(skin.id(),skin.name(),skin.property(),System.currentTimeMillis());
                Skin resolved=skin;
                if(isEnabled())Bukkit.getScheduler().runTask(this,()->{
                    pending.remove(account);
                    if(!authenticated(player) || !player.hasPermission(permission))return;
                    // Upstream retains its permissions, cooldown, history, persistence and native Paper refresh.
                    player.performCommand("skinsrestorer:skin "+resolved.id());submitted++;
                });
            }catch(Exception problem){
                if(problem instanceof InterruptedException)Thread.currentThread().interrupt();
                if(isEnabled())Bukkit.getScheduler().runTask(this,()->{pending.remove(account);failed++;nextRequest.put(account,System.currentTimeMillis()+5000);if(authenticated(player))message(player,"Skin unavailable. Check the nickname and try again shortly; your current skin is unchanged.");});
            }
        });}catch(RejectedExecutionException busy){pending.remove(account);nextRequest.remove(account);message(player,"The skin service is busy. Try again shortly.");}
    }

    private Skin minecraftByName(String name)throws Exception{
        JsonObject profile=get("https://api.mojang.com/users/profiles/minecraft/"+name);if(profile==null)return null;
        return minecraftProfile(parseId(profile.get("id").getAsString()),profile.get("name").getAsString());
    }
    private Skin minecraftProfile(UUID id,String name)throws Exception{
        JsonObject profile=get("https://sessionserver.mojang.com/session/minecraft/profile/"+id.toString().replace("-","")+"?unsigned=false");
        if(profile==null)return null;return signed(profile,id,name);
    }
    private Skin elyByName(String name)throws Exception{
        JsonObject profile=get("https://account.ely.by/api/mojang/profiles/"+name);if(profile==null)return null;
        UUID id=parseId(profile.get("id").getAsString());
        return signed(get("https://skinsystem.ely.by/textures/signed/"+name),id,name);
    }
    private Skin elyProfile(UUID id)throws Exception{
        JsonObject profile=get("https://account.ely.by/api/minecraft/session/profile/"+id.toString().replace("-",""));
        if(profile==null)return null;String name=profile.get("name").getAsString();
        return signed(get("https://skinsystem.ely.by/textures/signed/"+name),id,name);
    }
    private Skin signed(JsonObject profile,UUID id,String knownName)throws IOException{
        if(profile==null || !profile.has("properties"))return null;
        for(var entry:profile.getAsJsonArray("properties")){
            var property=entry.getAsJsonObject();if(!property.get("name").getAsString().equals("textures"))continue;
            String value=property.get("value").getAsString(),signature=property.get("signature").getAsString();
            if(value.isEmpty() || signature.isEmpty())throw new IOException("Unsigned texture");
            JsonObject texture=JsonParser.parseString(new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8)).getAsJsonObject();
            URI skin=URI.create(texture.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString());
            if(!"textures.minecraft.net".equals(skin.getHost()) || !(skin.getScheme().equals("https") || skin.getScheme().equals("http")))throw new IOException("Texture must be vanilla-compatible");
            String name=knownName!=null?knownName:profile.get("name").getAsString();
            return new Skin(id,name,SkinProperty.of(value,signature));
        }
        return null;
    }
    private static UUID parseId(String value){
        if(!value.matches("[0-9a-fA-F]{32}"))throw new IllegalArgumentException("Invalid source UUID");
        return UUID.fromString(value.substring(0,8)+"-"+value.substring(8,12)+"-"+value.substring(12,16)+"-"+value.substring(16,20)+"-"+value.substring(20));
    }
    private JsonObject get(String url)throws Exception{
        HttpRequest request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).header("User-Agent","WestShieldSkins/1.0.0").GET().build();
        HttpResponse<byte[]> response=http.send(request,info->HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofByteArray(),65536));
        if(response.statusCode()==204 || response.statusCode()==404)return null;
        if(response.statusCode()!=200)throw new IOException("Skin source unavailable");
        return JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
