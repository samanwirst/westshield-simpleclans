package com.westshield.accounts;

import com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.RegisterEvent;
import fr.xephi.authme.events.RestoreSessionEvent;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.InstanceHolder;
import org.geysermc.floodgate.api.handshake.HandshakeData;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.util.BedrockData;
import org.geysermc.floodgate.util.LinkedPlayer;

/** Resolves identity before profile loading; AuthMe remains the sole authentication authority. */
@SuppressWarnings("deprecation")
public final class WestShieldAccounts extends JavaPlugin implements Listener {
    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private Map<String, UUID> overrides = Map.of();
    private volatile Map<String, String> registered = Map.of();
    private volatile Set<UUID> approved = Set.of();
    private final AtomicLong matched = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private int handlerId = -1;
    private volatile boolean ready;

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        try {
            if (!getConfig().getBoolean("enabled")) throw new IllegalStateException("Shared profiles disabled");
            var floodgate = Objects.requireNonNull(getServer().getPluginManager().getPlugin("floodgate"));
            if (!floodgate.getPluginMeta().getVersion().equals(getConfig().getString("floodgate-version"))) {
                throw new IllegalStateException("The installed Floodgate build has not been verified");
            }
            if (Bukkit.getOnlineMode()) throw new IllegalStateException("This profile resolver requires the configured AuthMe offline server");
            Map<String, UUID> configured = new HashMap<>();
            var section = getConfig().getConfigurationSection("profile-overrides");
            if (section != null) for (String name : section.getKeys(false)) {
                if (!validName(name)) throw new IllegalStateException("Invalid profile override name");
                if (configured.put(key(name), UUID.fromString(Objects.requireNonNull(section.getString(name)))) != null) {
                    throw new IllegalStateException("Duplicate profile override");
                }
            }
            if (new HashSet<>(configured.values()).size() != configured.size()) throw new IllegalStateException("Profile overrides must be one-to-one");
            overrides = Map.copyOf(configured);
            refreshRegistered();
            refreshWhitelist();
            handlerId = InstanceHolder.getHandshakeHandlers().addHandshakeHandler(this::handshake);
            if (handlerId < 0) throw new IllegalStateException("Floodgate handshake resolver unavailable");
            ready = true;
            getCommand("bedrocklogin").setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player player) showBedrockLogin(player);
                return true;
            });
            getServer().getScheduler().runTaskTimer(this, this::refreshWhitelist, 100, 100);
            getServer().getScheduler().runTaskTimerAsynchronously(this, this::refreshRegisteredSafely, 200, 200);
            getLogger().info("Shared accounts ready; same Java-compatible name, one UUID, authentication=AuthMe; overrides=" + overrides.size());
        } catch (Exception error) {
            failures.incrementAndGet();
            getLogger().severe("Shared accounts are unavailable: " + error.getMessage());
            // Keep the admission listener installed so a failed adapter cannot silently split identities.
        }
        getCommand("westshieldaccounts").setExecutor((sender, command, label, args) -> {
            sender.sendMessage("WestShield accounts: ready=" + ready + "; auth=AuthMe; automatic=true; overrides="
                + overrides.size() + "; matches=" + matched.get() + "; failures=" + failures.get());
            return true;
        });
    }

    public static boolean validName(String name) { return name != null && JAVA_NAME.matcher(name).matches(); }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    public static UUID offlineId(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
    private UUID profileId(String name) { return overrides.getOrDefault(key(name), offlineId(name)); }

    private void refreshRegistered() {
        Map<String, String> names = new HashMap<>();
        for (String name : AuthMeApi.getInstance().getRegisteredRealNames()) if (validName(name)) {
            String old = names.putIfAbsent(key(name), name);
            if (old != null && !old.equals(name)) throw new IllegalStateException("Ambiguous registered account casing");
        }
        registered = Map.copyOf(names);
    }
    private void refreshRegisteredSafely() {
        try { refreshRegistered(); } catch (Exception error) { failures.incrementAndGet(); }
    }
    private void refreshWhitelist() {
        Set<UUID> ids = new HashSet<>();
        for (var player : Bukkit.getWhitelistedPlayers()) ids.add(player.getUniqueId());
        approved = Set.copyOf(ids);
    }
    private String canonical(String name) {
        String existing = registered.get(key(name));
        if (existing != null) return existing;
        // A new registration may precede the periodic refresh. Never treat an existing account as fresh.
        if (AuthMeApi.getInstance().isRegistered(name)) {
            refreshRegistered();
            return registered.get(key(name));
        }
        return name;
    }

    private void handshake(HandshakeData data) {
        if (!data.isFloodgatePlayer() || data.shouldDisconnect() || data.getBedrockData() == null) return;
        if (!ready) { data.setDisconnectReason("WestShield: shared accounts are temporarily unavailable."); return; }
        // Existing explicit links for different gamertags retain their approved identity.
        if (data.getLinkedPlayer() != null) return;
        BedrockData bedrock = data.getBedrockData();
        if (bedrock.getDataLength() != BedrockData.EXPECTED_LENGTH || !validName(bedrock.getUsername())) return;
        try {
            UUID original = FloodgateApi.getInstance().createJavaPlayerId(Long.parseUnsignedLong(bedrock.getXuid()));
            if (!original.equals(data.getJavaUniqueId())) throw new IllegalStateException("Bedrock identity mismatch");
            String name = canonical(bedrock.getUsername());
            if (name == null) throw new IllegalStateException("Registered name could not be resolved");
            UUID target = profileId(name);
            // Resolve only invited identities. The ordinary whitelist/ban/authentication checks still run.
            if (!approved.contains(original) && !approved.contains(target)) return;
            data.setLinkedPlayer(LinkedPlayer.of(name, target, original));
            matched.incrementAndGet();
        } catch (Exception error) {
            failures.incrementAndGet();
            data.setDisconnectReason("WestShield: account identity could not be verified. Contact an administrator.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (!ready) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("WestShield: shared accounts are temporarily unavailable."));
            return;
        }
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !validName(event.getName())) return;
        // Floodgate already supplies its correct UUID and canonical name from the verified handshake.
        if (FloodgateApi.getInstance().isFloodgatePlayer(event.getUniqueId())) return;
        String name = registered.get(key(event.getName()));
        if (name == null) name = event.getName();
        UUID target = profileId(name);
        if (!event.getUniqueId().equals(target) || !event.getName().equals(name)) {
            var profile = event.getPlayerProfile();
            profile.setId(target); profile.setName(name); event.setPlayerProfile(profile);
        }
    }

    private FloodgatePlayer automatic(Player player) {
        var bridge = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
        if (bridge == null || !validName(bridge.getUsername()) || !bridge.getUsername().equalsIgnoreCase(player.getName())) return null;
        if (!player.getUniqueId().equals(profileId(player.getName())) || !player.getUniqueId().equals(bridge.getCorrectUniqueId())) return null;
        return bridge;
    }
    @EventHandler(priority = EventPriority.NORMAL)
    public void whitelist(ProfileWhitelistVerifyEvent event) {
        var profile = event.getPlayerProfile();
        var bridge = FloodgateApi.getInstance().getPlayer(profile.getId());
        if (!ready || bridge == null || !validName(bridge.getUsername()) || !bridge.getUsername().equalsIgnoreCase(profile.getName())) return;
        if (profileId(profile.getName()).equals(profile.getId()) && approved.contains(bridge.getJavaUniqueId())) event.setWhitelisted(true);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event) {
        if (automatic(event.getPlayer()) != null) event.getPlayer().sendMessage(Component.text("WestShield: Java and Bedrock use this same profile. Sign in with your server password."));
        getServer().getScheduler().runTaskLater(this, () -> showBedrockLogin(event.getPlayer()), 30L);
    }
    private void showBedrockLogin(Player player) {
        if (!ready || !player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player
                || !FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())
                || AuthMeApi.getInstance().isAuthenticated(player)) return;
        boolean registeredAccount = AuthMeApi.getInstance().isRegistered(player.getName());
        var builder = org.geysermc.cumulus.form.CustomForm.builder()
                .title(registeredAccount ? "WestShield | Sign in" : "WestShield | Register")
                .input("Server password", registeredAccount ? "Your existing WestShield password" : "Choose a password (8+ characters)");
        if (!registeredAccount) builder.input("Confirm password", "Enter the same password again");
        builder.validResultHandler(response -> {
            String password = response.asInput(0);
            String confirmation = registeredAccount ? password : response.asInput(1);
            if (!isEnabled()) return;
            getServer().getScheduler().runTask(this, () -> {
                if (!ready || !player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player
                        || AuthMeApi.getInstance().isAuthenticated(player)) return;
                if (AuthMeApi.getInstance().isRegistered(player.getName()) != registeredAccount) { showBedrockLogin(player);return; }
                if (password == null || password.isEmpty() || password.length() > 128
                        || password.codePoints().anyMatch(Character::isWhitespace)
                        || !password.equals(confirmation)) {
                    player.sendMessage(Component.text("Passwords must match and contain no spaces. Reopen the form with /bedrocklogin."));return;
                }
                // AuthMe validates the secret; no force-login or credential storage occurs here.
                player.performCommand(registeredAccount ? "login " + password : "register " + password + " " + confirmation);
            });
        });
        if (!FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder))
            player.sendMessage(Component.text("Open sign-in again with /bedrocklogin, or use /login <password>."));
    }
    private void authenticated(Player player) {
        getServer().getScheduler().runTask(this, () -> {
            if (!ready || !player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player || !AuthMeApi.getInstance().isAuthenticated(player)) return;
            var bridge = automatic(player);
            if (bridge != null && approved.contains(bridge.getJavaUniqueId())) {
                player.setWhitelisted(true); // Future Java logins use the same approved UUID.
                refreshWhitelist();
            }
            refreshRegisteredSafely();
        });
    }
    @EventHandler(priority = EventPriority.MONITOR) public void login(LoginEvent event) { authenticated(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void register(RegisterEvent event) { authenticated(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void restored(RestoreSessionEvent event) { authenticated(event.getPlayer()); }
    @Override public void onDisable() {
        ready = false;
        if (handlerId >= 0) InstanceHolder.getHandshakeHandlers().removeHandshakeHandler(handlerId);
    }
}
