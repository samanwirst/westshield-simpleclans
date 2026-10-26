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
    private GrimAbstractAPI grim;
    private long graceMillis;
    private long confirmedKicks;
    private long persistenceFailures;

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
            ledger = new StrikeLedger(getDataFolder().toPath().resolve("strikes.yml"));
            ArrayList<String> words = new ArrayList<>();
            try (var in = new BufferedReader(new InputStreamReader(java.util.Objects.requireNonNull(getResource("words.txt")), StandardCharsets.UTF_8))) {
                in.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).forEach(words::add);
            }
            words.addAll(getConfig().getStringList("extra-words"));
            filter = new WordFilter(words,getConfig().getStringList("allow-words"),getConfig().getString("replacement","****"));
            grim = java.util.Objects.requireNonNull(Bukkit.getServicesManager().load(GrimAbstractAPI.class),"Grim API unavailable");
            grim.getEventBus().subscribe(this,FlagEvent.class,this::grimFlag);
            grim.getEventBus().subscribe(this,CommandExecuteEvent.class,this::grimPunishment);
            Bukkit.getPluginManager().registerEvents(this,this);
            for (Player player : Bukkit.getOnlinePlayers()) add(player);
            Bukkit.getScheduler().runTaskTimer(this,() -> sessions.values().forEach(s -> s.authenticated = authenticated(s.player)),1,20);
            getLogger().info("Protection ready: dictionaries=28; words=" + filter.size() + "; Java=GrimAC; Bedrock=LightAntiCheat-Plus; ban-after-confirmed-kicks=3; chat-sanctions=false");
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
        if(captured==null) return; // Delivery rechecks live AuthMe state; a stale snapshot cannot leak an uncensored message.
        String original=PLAIN.serialize(event.message()),cleaned=filter.censor(original);
        if(original.equals(cleaned)) return;
        // Deliver a fresh system component so signed-original hover text cannot reveal censored words.
        event.setCancelled(true);censored.incrementAndGet();
        var viewers=List.copyOf(event.viewers());var renderer=event.renderer();
        Bukkit.getScheduler().runTask(this,() -> {
            if(sessions.get(event.getPlayer().getUniqueId())!=captured || !authenticated(captured.player)) return;
            for(var viewer:viewers) {
                if(viewer instanceof Player target && (!target.isOnline() || !target.canSee(captured.player))) continue;
                viewer.sendMessage(renderer.render(captured.player,captured.player.displayName(),Component.text(cleaned),viewer));
            }
        });
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void clanChat(net.sacredlabyrinth.phaed.simpleclans.events.ChatEvent event) {
        Player sender=event.getSender().toPlayer();
        if(sender==null || !authenticated(sender)) {event.setCancelled(true);return;}
        String original=event.getMessage(),cleaned=filter.censor(original);
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
        String text=input.substring(start),cleaned=filter.censor(text);
        if(!text.equals(cleaned)) { event.setCancelled(true);event.getPlayer().performCommand(input.substring(1,start)+cleaned);censored.incrementAndGet(); }
    }

    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        if(sender instanceof Player p && !authenticated(p))return true;
        if(args.length==1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("WestShield protection: ready=true; words="+filter.size()+"; dictionaries=28; Java=GrimAC; Bedrock=LightAntiCheat-Plus; confirmed-kicks="+confirmedKicks+"; banned-accounts="+ledger.bannedAccounts()+"; persistence-failures="+persistenceFailures+"; censored-messages="+censored.get()+"; chat-sanctions=false");return true;
        }
        if(args.length==2 && args[0].equalsIgnoreCase("history")) {
            try { UUID id=UUID.fromString(args[1]);sender.sendMessage("Account "+id+": confirmed anti-cheat kicks="+ledger.count(id)+"/3"); }
            catch(IllegalArgumentException invalid) { sender.sendMessage("Use an account UUID."); }return true;
        }
        sender.sendMessage("/westshieldprotection status | history <uuid>");return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String label,String[] args) { return List.of(); }
}
