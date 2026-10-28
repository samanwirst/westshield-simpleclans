package com.westshield.protection;

import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.events.CommandExecuteEvent;
import ac.grim.grimac.api.event.events.FlagEvent;
import fr.xephi.authme.api.v3.AuthMeApi;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import me.vekster.lightanticheat.api.event.LACPunishmentEvent;
import me.vekster.lightanticheat.api.event.LACViolationEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;

/** Censor-only chat policy and one shared counter for distinct, observed anti-cheat disconnects. */
public final class WestShieldProtection extends JavaPlugin implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingKick> pending = new HashMap<>();
    private final AtomicLong censored = new AtomicLong();
    private StrikeLedger ledger;
    private WordFilter filter;
    private SteamDictionary steam;
    private GrimAbstractAPI grim;
    private long graceMillis;
    private long confirmedKicks;
    private long persistenceFailures;
    private double localChatDistanceSquared;

    private static final class Session {
        final Player player;
        volatile boolean authenticated;
        volatile long graceUntil;
        boolean issued;
        Session(Player player) { this.player = player; }
    }
    private static final class PendingKick {
        final Session session;
        final UUID receipt = UUID.randomUUID();
        final String provider;
        final String check;
        final Component reason;
        boolean accepted;
        PendingKick(Session session, String provider, String check, int next) {
            this.session = session;this.provider = provider;this.check = check;
            reason = Component.text("WestShield: unfair gameplay detected. Anti-cheat kick " + next + "/3."
                    + (next >= 3 ? " Permanent ban after this kick." : ""));
        }
    }

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (getConfig().getInt("permanent-ban-after-kicks",3) != 3) throw new IllegalArgumentException("This server policy requires three confirmed kicks");
            graceMillis = Math.max(0, getConfig().getLong("teleport-grace-seconds",5)) * 1000;
            double chatDistance = getConfig().getDouble("local-chat-radius",100);
            if (!Double.isFinite(chatDistance) || chatDistance < 1 || chatDistance > 1000) throw new IllegalArgumentException("Invalid local chat radius");
            localChatDistanceSquared = chatDistance * chatDistance;
            ledger = new StrikeLedger(getDataFolder().toPath().resolve("strikes.yml"));
            ArrayList<String> words = new ArrayList<>();
            try (var in = new BufferedReader(new InputStreamReader(java.util.Objects.requireNonNull(getResource("words.txt")), StandardCharsets.UTF_8))) {
                in.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).forEach(words::add);
            }
            words.addAll(getConfig().getStringList("extra-words"));
            steam=getConfig().getBoolean("external-steam-dictionaries",false)?SteamDictionary.load(getDataFolder().toPath().resolve("steam")):SteamDictionary.empty();
            words.addAll(steam.words());ArrayList<String> allow=new ArrayList<>(steam.allowWords());allow.addAll(getConfig().getStringList("allow-words"));
            filter = new WordFilter(words,allow,getConfig().getString("replacement","****"));
            grim = java.util.Objects.requireNonNull(Bukkit.getServicesManager().load(GrimAbstractAPI.class),"Grim API unavailable");
            grim.getEventBus().subscribe(this,FlagEvent.class,this::grimFlag);
            grim.getEventBus().subscribe(this,CommandExecuteEvent.class,this::grimPunishment);
            Bukkit.getPluginManager().registerEvents(this,this);
            for (Player player : Bukkit.getOnlinePlayers()) add(player);
            Bukkit.getScheduler().runTaskTimer(this,() -> sessions.values().forEach(s -> s.authenticated = authenticated(s.player)),1,20);
            getLogger().info("Protection ready: base-dictionaries=28; words=" + filter.size() + "; steam-languages="+steam.languageCount()+"; steam-patterns="+steam.patternCount()+"; Java=GrimAC; Bedrock=LightAntiCheat-Plus; ban-after-confirmed-kicks=3; chat-sanctions=false");
        } catch (Exception failure) {
            getLogger().log(java.util.logging.Level.SEVERE,"Protection could not start; existing ledger retained",failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() { if(grim!=null)grim.getEventBus().unregisterAllListeners(this); }

    private boolean authenticated(Player p) { return p.isOnline() && Bukkit.getPlayer(p.getUniqueId()) == p && AuthMeApi.getInstance().isAuthenticated(p); }
    private void add(Player p) { Session s = new Session(p);s.graceUntil=System.currentTimeMillis()+graceMillis;s.authenticated=authenticated(p);sessions.put(p.getUniqueId(),s); }
    @EventHandler public void join(PlayerJoinEvent event) { add(event.getPlayer()); }
    private void authChanged(Player p) {
        Bukkit.getScheduler().runTask(this,() -> {
            Session s=sessions.get(p.getUniqueId());
            if (s != null && s.player == p) { s.authenticated=authenticated(p);s.graceUntil=System.currentTimeMillis()+graceMillis; }
        });
    }
    @EventHandler public void login(fr.xephi.authme.events.LoginEvent e) { authChanged(e.getPlayer()); }
    @EventHandler public void logout(fr.xephi.authme.events.LogoutEvent e) { authChanged(e.getPlayer()); }
    @EventHandler public void register(fr.xephi.authme.events.RegisterEvent e) { authChanged(e.getPlayer()); }
    @EventHandler(ignoreCancelled=true) public void restore(fr.xephi.authme.events.RestoreSessionEvent e) { authChanged(e.getPlayer()); }
    private void grace(Player p) { Session s=sessions.get(p.getUniqueId());if(s!=null) s.graceUntil=System.currentTimeMillis()+graceMillis; }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e) { grace(e.getPlayer()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void mode(PlayerGameModeChangeEvent e) { grace(e.getPlayer()); }
    @EventHandler(priority=EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e) { grace(e.getPlayer()); }
    private boolean admitted(Session s) { return s != null && s.authenticated && System.currentTimeMillis() >= s.graceUntil; }

    public void grimFlag(FlagEvent event) {
        if (!admitted(sessions.get(event.getUser().getUniqueId()))) event.setCancelled(true);
    }

    public void grimPunishment(CommandExecuteEvent event) {
        if (event.isCancelled() || !event.getCommand().equals("[westshield]")) return;
        event.setCancelled(true);
        var user = event.getUser();
        Session captured = sessions.get(user.getUniqueId());
        if (!admitted(captured) || event.getCheck().isExperimental() || grim.getGrimUser(user.getUniqueId()) != user) return;
        String check = event.getCheck().getCheckName();
        Bukkit.getScheduler().runTask(this,() -> {
            if (sessions.get(user.getUniqueId()) == captured && grim.getGrimUser(user.getUniqueId()) == user) punish(captured,"GrimAC",check);
        });
    }

    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void lacFlag(LACViolationEvent event) {
        if (!admitted(sessions.get(event.getPlayer().getUniqueId()))) event.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void lacPunishment(LACPunishmentEvent event) {
        event.setCancelled(true); // The shared ledger owns sanctions; stock console commands stay disabled.
        if(!event.getCheckSettings().enabled || !event.getCheckSettings().punishable || !event.getCheckSettings().detectBedrock)return;
        Player player = event.getPlayer();Session captured = sessions.get(player.getUniqueId());
        if (captured == null || captured.player != player || !admitted(captured)) return;
        Bukkit.getScheduler().runTask(this,() -> {
            if (sessions.get(player.getUniqueId()) == captured && FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId()))
                punish(captured,"LightAntiCheat-Plus",event.getCheckName());
        });
    }

    private void punish(Session s, String provider, String check) {
        if (!admitted(s) || s.issued || !authenticated(s.player)) return;
        if (Bukkit.getTPS()[0] < 15) return;
        UUID id=s.player.getUniqueId();s.issued=true;
        PendingKick kick=new PendingKick(s,provider,check,Math.min(3,ledger.count(id)+1));pending.put(id,kick);
        s.player.kick(kick.reason,PlayerKickEvent.Cause.PLUGIN);
        // Paper may queue disconnection. Keep the exact admission until the observed quit.
        Bukkit.getScheduler().runTaskLater(this,() -> {
            if(pending.remove(id,kick))s.issued=false;
        },100L);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void kickObserved(PlayerKickEvent event) {
        PendingKick kick=pending.get(event.getPlayer().getUniqueId());
        if (kick != null && kick.session.player == event.getPlayer() && PLAIN.serialize(kick.reason).equals(PLAIN.serialize(event.reason()))) {
            kick.accepted=!event.isCancelled();
            if(event.isCancelled()) {pending.remove(event.getPlayer().getUniqueId(),kick);kick.session.issued=false;}
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        UUID id=event.getPlayer().getUniqueId();PendingKick kick=pending.remove(id);
        if (kick != null && kick.session.player == event.getPlayer() && kick.accepted && event.getReason()==PlayerQuitEvent.QuitReason.KICKED) {
            try {
                int count=ledger.record(id,event.getPlayer().getName(),kick.receipt,kick.provider,kick.check);confirmedKicks++;
                if(count>=3) ((org.bukkit.ban.ProfileBanList)Bukkit.getBanList(BanList.Type.PROFILE)).addBan(event.getPlayer().getPlayerProfile(),"WestShield: three confirmed anti-cheat kicks.",(java.util.Date)null,"WestShieldProtection");
                getLogger().info("Confirmed anti-cheat kick: account=" + id + "; provider=" + kick.provider + "; check=" + kick.check + "; kicks=" + count + "; permanent-ban=" + (count>=3));
            } catch(Exception failure) { persistenceFailures++;getLogger().log(java.util.logging.Level.SEVERE,"Could not persist confirmed anti-cheat kick",failure); }
        }
        sessions.computeIfPresent(id,(key,s) -> s.player==event.getPlayer()?null:s);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if(ledger.count(event.getUniqueId())>=3) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,Component.text("WestShield: permanently banned after three confirmed anti-cheat kicks."));
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void chat(AsyncChatEvent event) {
        Session captured=sessions.get(event.getPlayer().getUniqueId());
        event.setCancelled(true);
        if(captured==null) return;
        String original=PLAIN.serialize(event.message());
        boolean global = ChatScope.global(original);
        String content = ChatScope.content(original), cleaned=censor(content);
        if (cleaned.isBlank()) return;
        if(!content.equals(cleaned)) censored.incrementAndGet();
        // Main-thread delivery uses live positions and the existing renderer for both editions.
        var viewers=List.copyOf(event.viewers());var renderer=event.renderer();
        Bukkit.getScheduler().runTask(this,() -> {
            if(sessions.get(event.getPlayer().getUniqueId())!=captured || !authenticated(captured.player)) return;
            var origin=captured.player.getLocation();
            for(var viewer:viewers) {
                if(viewer instanceof Player target) {
                    if(!authenticated(target) || !target.canSee(captured.player)) continue;
                    var destination=target.getLocation();
                    if(!ChatScope.receives(global,origin.getWorld().getUID(),origin.getX(),origin.getY(),origin.getZ(),
                            destination.getWorld().getUID(),destination.getX(),destination.getY(),destination.getZ(),localChatDistanceSquared)) continue;
                }
                Component rendered=renderer.render(captured.player,captured.player.displayName(),Component.text(cleaned),viewer);
                if(global) rendered=Component.text("[Global] ",net.kyori.adventure.text.format.NamedTextColor.GOLD).append(rendered);
                viewer.sendMessage(rendered);
            }
        });
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void clanChat(net.sacredlabyrinth.phaed.simpleclans.events.ChatEvent event) {
        Player sender=event.getSender().toPlayer();
        if(sender==null || !authenticated(sender)) {event.setCancelled(true);return;}
        String original=event.getMessage(),cleaned=censor(original);
        if(!original.equals(cleaned)) {event.setMessage(cleaned);censored.incrementAndGet();}
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void textCommand(PlayerCommandPreprocessEvent event) {
        if(!authenticated(event.getPlayer())) return;
        String input=event.getMessage();int first=input.indexOf(' ');if(first<0)return;
        String root=input.substring(1,first).toLowerCase(java.util.Locale.ROOT);
        int colon=root.indexOf(':');if(colon>=0)root=root.substring(colon+1);
        int start=first+1;
        if(List.of("msg","tell","w").contains(root)) {
            if(start<input.length() && input.charAt(start)=='"') { int end=input.indexOf('"',start+1);if(end<0)return;start=end+1; }
            else { int end=input.indexOf(' ',start);if(end<0)return;start=end; }
            while(start<input.length() && input.charAt(start)==' ')start++;
        } else if(!List.of("me","say").contains(root)) return;
        String text=input.substring(start),cleaned=censor(text);
        if(!text.equals(cleaned)) { event.setCancelled(true);event.getPlayer().performCommand(input.substring(1,start)+cleaned);censored.incrementAndGet(); }
    }

    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        if (!sender.hasPermission("westshieldprotection.admin")) return true;
        if(sender instanceof Player p && !authenticated(p))return true;
        boolean shortPardon = cmd.getName().equalsIgnoreCase("wsunban");
        if (shortPardon || args.length > 0 && args[0].equalsIgnoreCase("pardon")) {
            int offset = shortPardon ? 0 : 1;
            if (args.length <= offset) { sender.sendMessage("/wsunban <name|uuid> [reason]");return true; }
            String reason = args.length > offset + 1 ? String.join(" ", java.util.Arrays.copyOfRange(args,offset+1,args.length)) : "Administrator pardon";
            if (reason.length() > 200) { sender.sendMessage("Pardon reason must be at most 200 characters.");return true; }
            pardon(sender,args[offset],reason);return true;
        }
        if(args.length==1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("WestShield protection: ready=true; words="+filter.size()+"; base-dictionaries=28; steam-languages="+steam.languageCount()+"; steam-patterns="+steam.patternCount()+"; Java=GrimAC; Bedrock=LightAntiCheat-Plus; confirmed-kicks="+confirmedKicks+"; banned-accounts="+ledger.bannedAccounts()+"; persistence-failures="+persistenceFailures+"; censored-messages="+censored.get()+"; chat-sanctions=false; local-chat-radius="+Math.sqrt(localChatDistanceSquared)+"; global-prefix=!");return true;
        }
        if(args.length==2 && args[0].equalsIgnoreCase("history")) {
            try { UUID id=UUID.fromString(args[1]);sender.sendMessage("Account "+id+": confirmed anti-cheat kicks="+ledger.count(id)+"/3"); }
            catch(IllegalArgumentException invalid) { sender.sendMessage("Use an account UUID."); }return true;
        }
        sender.sendMessage("/westshieldprotection status | history <uuid> | pardon <name|uuid> [reason]");return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String label,String[] args) { return List.of(); }
    private String censor(String text){return steam.censor(filter.censor(text));}

    private void pardon(CommandSender sender, String target, String reason) {
        var bans = (org.bukkit.ban.ProfileBanList) Bukkit.getBanList(BanList.Type.PROFILE);
        java.util.Set<UUID> candidates = new java.util.HashSet<>();
        UUID explicit = null;
        try { explicit = UUID.fromString(target);candidates.add(explicit); }
        catch (IllegalArgumentException ignored) { candidates.addAll(ledger.accountsNamed(target)); }
        for (var entry : bans.getBanEntries()) {
            if (!(entry.getBanTarget() instanceof org.bukkit.profile.PlayerProfile profile)) continue;
            if (profile.getUniqueId() != null && (profile.getUniqueId().equals(explicit)
                    || explicit == null && target.equalsIgnoreCase(profile.getName()))) candidates.add(profile.getUniqueId());
        }
        if (candidates.isEmpty()) { sender.sendMessage("No active anti-cheat record or profile ban found for " + target + ".");return; }
        if (candidates.size() != 1) { sender.sendMessage("Ambiguous account name. Use /wsunban <account UUID>.");return; }
        UUID id = candidates.iterator().next();
        String name = ledger.name(id);
        if (name == null) for (var entry : bans.getBanEntries()) if (entry.getBanTarget() instanceof org.bukkit.profile.PlayerProfile p && id.equals(p.getUniqueId())) { name = p.getName();break; }
        var profile = name == null ? Bukkit.createProfile(id) : Bukkit.createProfile(id, name);
        if (name == null) name = id.toString();
        if (ledger.count(id) == 0 && !bans.isBanned(profile)) { sender.sendMessage("No active ban or anti-cheat strikes for " + name + ".");return; }
        String actor = sender instanceof Player player ? "player:" + player.getUniqueId() : "console";
        UUID action;
        try { action = ledger.pardon(id, name, actor, reason); }
        catch (Exception failure) {
            persistenceFailures++;getLogger().log(java.util.logging.Level.SEVERE,"Pardon refused: counter reset could not be saved for " + id,failure);
            sender.sendMessage("Pardon failed: the counter could not be saved. The native ban was not changed.");return;
        }
        PendingKick removed = pending.remove(id);
        if (removed != null) removed.session.issued = false;
        Session session = sessions.get(id);
        if (session != null) { session.issued = false;session.graceUntil = System.currentTimeMillis() + graceMillis; }
        try {
            bans.pardon(profile);
            if (bans.isBanned(profile)) throw new IllegalStateException("Native profile ban remains active");
            getLogger().info("Administrator pardon completed: action=" + action + "; account=" + id + "; actor=" + actor + "; active-kicks=0/3");
            sender.sendMessage("Pardoned " + name + "; anti-cheat kicks=0/3. The player can reconnect. No restart required.");
        } catch (Exception failure) {
            persistenceFailures++;getLogger().log(java.util.logging.Level.SEVERE,"Counter reset saved, but native pardon failed: action=" + action + "; account=" + id,failure);
            sender.sendMessage("Counter reset saved, but the native pardon failed. Retry /wsunban " + id + ".");
        }
    }
}
