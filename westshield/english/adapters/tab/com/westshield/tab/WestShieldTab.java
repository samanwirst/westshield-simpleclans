package com.westshield.tab;

import com.westshield.clans.territory.ClanColors;
import com.westshield.clans.territory.WestShieldClanTerritories;
import fr.xephi.authme.api.v3.AuthMeApi;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

/** Presentation only. World, authentication, permissions and Plan history remain owned by their plugins. */
public final class WestShieldTab extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final String BRAND = "§b§lWest§6§lShield§r";
    private static final String RULE = "§x§4§2§6§4§9§5━━━━━━━━━━━━━━━━━━━━━━━━━━━━";
    private final Map<UUID, Player> connections = new HashMap<>();
    private final Map<UUID, PlanHistory.History> histories = new HashMap<>();
    private final Set<UUID> unavailable = new HashSet<>();
    private final Map<UUID, Object> admissions = new HashMap<>();
    private final AtomicBoolean reading = new AtomicBoolean();
    private volatile Map<UUID, Panel> panels = Map.of();
    private volatile String publicHeader = BRAND;
    private final PlanHistory history = new PlanHistory();
    private final EventHandler<TabLoadEvent> tabReload = event -> registerPlaceholders();
    private ZoneId zone;
    private LocalDate launch;
    private String domain;
    private SimpleClans clans;
    private WestShieldClanTerritories territories;
    private int tick;
    private int refreshTicks;
    private long successfulReads;
    private long failedReads;

    public record Panel(String header, String footer) { }

    @Override public void onEnable() {
        saveDefaultConfig();
        zone = ZoneId.of(getConfig().getString("timezone", "Asia/Tashkent"));
        launch = LocalDate.parse(getConfig().getString("server-launch-date"));
        if (launch.isAfter(LocalDate.now(zone))) throw new IllegalArgumentException("Server launch cannot be in the future");
        domain = getConfig().getString("domain", "westshield.minecraft.surf");
        refreshTicks = Math.max(5, getConfig().getInt("history-refresh-seconds", 30));
        clans = (SimpleClans) Bukkit.getPluginManager().getPlugin("SimpleClans");
        territories = (WestShieldClanTerritories) Bukkit.getPluginManager().getPlugin("WestShieldClanTerritories");
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            connections.put(player.getUniqueId(), player);
            admissions.put(player.getUniqueId(), new Object());
        }
        TabAPI.getInstance().getEventBus().register(TabLoadEvent.class, tabReload);
        registerPlaceholders();
        Bukkit.getScheduler().runTaskTimer(this, this::refreshPanels, 1L, 20L);
        getLogger().info("TAB presentation ready; timezone=" + zone + "; launch=" + launch + "; sidebar=false");
    }

    private void registerPlaceholders() {
        var manager = TabAPI.getInstance().getPlaceholderManager();
        manager.registerPlayerPlaceholder("%westshield_header%", 1000, player -> publicHeader);
        manager.registerPlayerPlaceholder("%westshield_footer%", 1000, player -> {
            Panel panel = panels.get(player.getUniqueId());
            return panel == null ? "§7Sign in to view your journey and clan." : panel.footer();
        });
    }

    @Override public void onDisable() {
        if (TabAPI.getInstance() != null) {
            TabAPI.getInstance().getEventBus().unregister(tabReload);
            var manager = TabAPI.getInstance().getPlaceholderManager();
            manager.unregisterPlaceholder("%westshield_header%");
            manager.unregisterPlaceholder("%westshield_footer%");
        }
    }

    private boolean authenticated(Player player) {
        return player.isOnline() && connections.get(player.getUniqueId()) == player
                && AuthMeApi.getInstance().isAuthenticated(player);
    }

    @org.bukkit.event.EventHandler public void joined(PlayerJoinEvent event) {
        connections.put(event.getPlayer().getUniqueId(), event.getPlayer());
        admissions.put(event.getPlayer().getUniqueId(), new Object());
        tick = 0;
    }

    @org.bukkit.event.EventHandler public void quit(PlayerQuitEvent event) {
        connections.remove(event.getPlayer().getUniqueId(), event.getPlayer());
        histories.remove(event.getPlayer().getUniqueId());
        admissions.remove(event.getPlayer().getUniqueId());
        unavailable.remove(event.getPlayer().getUniqueId());
        refreshPanels();
    }

    private void authenticationChanged(Player player) {
        // AuthMe completion events can arrive off the server thread.
        Bukkit.getScheduler().runTask(this, () -> {
            if (connections.get(player.getUniqueId()) != player) return;
            admissions.put(player.getUniqueId(), new Object());
            histories.remove(player.getUniqueId());
            unavailable.remove(player.getUniqueId());
            tick = 0;
            refreshPanels();
        });
    }

    @org.bukkit.event.EventHandler public void login(fr.xephi.authme.events.LoginEvent event) { authenticationChanged(event.getPlayer()); }
    @org.bukkit.event.EventHandler public void logout(fr.xephi.authme.events.LogoutEvent event) { authenticationChanged(event.getPlayer()); }
    @org.bukkit.event.EventHandler public void register(fr.xephi.authme.events.RegisterEvent event) { authenticationChanged(event.getPlayer()); }
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void restore(fr.xephi.authme.events.RestoreSessionEvent event) { authenticationChanged(event.getPlayer()); }

    private void refreshPanels() {
        Instant now = Instant.now();
        double tps = Math.min(20, Bukkit.getTPS()[0]);
        String health = tps >= 18 ? "§a" : tps >= 15 ? "§e" : "§c";
        publicHeader = BRAND + "\n§7by §bMinecraft Club §8× §6WestDevs\n" + RULE
                + "\n§7Online §f" + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers()
                + " §8• §7TPS " + health + String.format(Locale.ROOT, "%.1f", tps)
                + "\n§7Server age §f" + TimeFormats.days(launch, now, zone) + "d §8• §7Tashkent";
        Map<UUID, Panel> next = new HashMap<>();
        for (Player player : List.copyOf(connections.values())) {
            if (!authenticated(player)) continue;
            var saved = histories.get(player.getUniqueId());
            long first = player.getFirstPlayed();
            if (saved != null && saved.firstSeen() > 0) first = first > 0 ? Math.min(first, saved.firstSeen()) : saved.firstSeen();
            String online = saved == null ? (unavailable.contains(player.getUniqueId()) ? "Unavailable" : "Loading…") : TimeFormats.online(saved.onlineMillis()
                    + Math.max(0, now.toEpochMilli() - saved.sampledAt()));
            String footer = "\n§b§lYOUR JOURNEY§r\n§7Member for §f" + TimeFormats.age(first, now, zone)
                    + " §8• §7Online §f" + online + "\n" + clanLines(player, now)
                    + "\n" + RULE + "\n§b/survival §8• §b/creative §8• §b/clan help"
                    + "\n§6/map §8• §6/voice §8• §6/westshield\n§7" + domain;
            next.put(player.getUniqueId(), new Panel(publicHeader, footer));
        }
        panels = Map.copyOf(next);
        if (tick++ % refreshTicks == 0) readHistory();
    }

    private String clanLines(Player player, Instant now) {
        var clan = clans.getClanManager().getClanByPlayerUniqueId(player.getUniqueId());
        if (clan == null) return "§7Clan §8• §fNone";
        var land = territories.landSummary(clan);
        long online = clan.getMembers().stream().filter(member -> {
            Player connected = connections.get(member.getUniqueId());
            return connected != null && authenticated(connected);
        }).count();
        int rgb = ClanColors.rgb(clan, clans.getSettingsManager().getColored(ConfigField.TAG_DEFAULT_COLOR));
        // Serialize a real component to preserve the shared clan RGB without accepting formatting in names.
        String name = LEGACY.serialize(Component.text(clean(clan.getName()), net.kyori.adventure.text.format.TextColor.color(rgb)));
        return "§6§lCLAN§r §8• " + name + " §8[§7" + clean(clan.getTag()) + "§8]"
                + "\n§7Age §f" + TimeFormats.age(clan.getFounded(), now, zone) + " §8• §7Members §f" + clan.getSize() + " §8(§a" + online + " online§8)"
                + "\n§7Land §f" + TimeFormats.area(land.squareBlocks()) + " blocks² §8• §7Regions §f" + land.regions();
    }

    private static String clean(String value) {
        return ChatColor.stripColor(value).replace('&', '＆').replace('<', '‹').replace('>', '›')
                .replace('\n', ' ').replace('\r', ' ').replace('%', '％');
    }

    private void readHistory() {
        if (!reading.compareAndSet(false, true)) return;
        Map<UUID, Player> admitted = new HashMap<>();
        connections.forEach((id, player) -> { if (authenticated(player)) admitted.put(id, player); });
        Map<UUID, Object> capturedAdmissions = new HashMap<>(admissions);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Map<UUID, PlanHistory.History> results = new HashMap<>();
            int failures = 0;
            for (UUID id : admitted.keySet()) {
                try { results.put(id, history.read(id)); }
                catch (RuntimeException failure) { failures++; }
            }
            int count = failures;
            if (!isEnabled()) { reading.set(false); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    results.forEach((id, value) -> {
                        if (connections.get(id) == admitted.get(id) && admissions.get(id) == capturedAdmissions.get(id)
                                && authenticated(admitted.get(id))) {
                            histories.put(id, value);
                            unavailable.remove(id);
                        }
                    });
                    admitted.forEach((id, player) -> {
                        if (!results.containsKey(id) && connections.get(id) == player && admissions.get(id) == capturedAdmissions.get(id)) unavailable.add(id);
                    });
                    successfulReads += results.size();
                    failedReads += count;
                    if (count > 0) getLogger().warning("Plan history read unavailable for " + count + " connected player(s); no history was changed");
                } finally { reading.set(false); }
            });
        });
    }

    public Panel panel(Player player) { return authenticated(player) ? panels.get(player.getUniqueId()) : null; }

    /** Build-only accessor also lets the isolated protocol fixture inspect the actual Bedrock payload. */
    public SimpleForm informationForm(Player player) {
        Panel panel = panel(player);
        if (panel == null) throw new IllegalStateException("Sign in before opening server information");
        return SimpleForm.builder().title("§bWest§6Shield")
                .content(panel.header() + "\n" + panel.footer()).button("Close").build();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("status") && sender.hasPermission("westshieldtab.admin")) {
            sender.sendMessage("WestShield TAB: ready=true; timezone=" + zone + "; launch=" + launch
                    + "; panels=" + panels.size() + "; history-reads=" + successfulReads + "; history-failures=" + failedReads);
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Use /westshield status from console."); return true; }
        Panel panel = panel(player);
        if (panel == null) { player.sendMessage(LEGACY.deserialize("§8[§bWest§6Shield§8] §7Please sign in first.")); return true; }
        if (Bukkit.getPluginManager().isPluginEnabled("floodgate") && FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) {
            FloodgateApi.getInstance().sendForm(player.getUniqueId(), informationForm(player));
        } else {
            player.sendMessage(LEGACY.deserialize(panel.header() + "\n" + panel.footer()));
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return sender.hasPermission("westshieldtab.admin") && args.length == 1 && "status".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("status") : List.of();
    }
}
