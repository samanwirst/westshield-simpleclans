package com.westshield.portal;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.*;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.LogoutEvent;
import fr.xephi.authme.events.RegisterEvent;
import io.github.theodoremeyer.simplevoicegeyser.core.SvgCore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.PermissionAttachment;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps both web and mod voice packets gated by the current AuthMe session. */
public final class VoiceAuthGuard implements VoicechatPlugin, Listener {
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PermissionAttachment> pendingLogin = new HashMap<>();
    private final JavaPlugin plugin;

    VoiceAuthGuard(JavaPlugin plugin) { this.plugin = plugin; }

    void register() {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) throw new IllegalStateException("Simple Voice Chat service is unavailable");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (var player : plugin.getServer().getOnlinePlayers()) {
            if (AuthMeApi.getInstance().isAuthenticated(player)) allowWebVoice(player);
            else denyWebVoice(player);
        }
        service.registerPlugin(this);
    }

    @Override public String getPluginId() { return "westshield-authme-voice-guard"; }

    @Override public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            if (!isAuthenticated(event.getSenderConnection())) event.cancel();
        });
        registration.registerEvent(EntitySoundPacketEvent.class, this::guardSound);
        registration.registerEvent(StaticSoundPacketEvent.class, this::guardSound);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::guardSound);
    }

    private void guardSound(SoundPacketEvent<?> event) {
        if (!isAuthenticated(event.getReceiverConnection()) ||
                (event.getSenderConnection() != null && !isAuthenticated(event.getSenderConnection()))) event.cancel();
    }

    private boolean isAuthenticated(VoicechatConnection connection) {
        return connection != null && authenticated.contains(connection.getPlayer().getUuid());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(LoginEvent event) { allowWebVoice(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegister(RegisterEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline() && AuthMeApi.getInstance().isAuthenticated(event.getPlayer())) allowWebVoice(event.getPlayer());
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        authenticated.remove(event.getPlayer().getUniqueId());
        if (AuthMeApi.getInstance().isAuthenticated(event.getPlayer())) allowWebVoice(event.getPlayer());
        else denyWebVoice(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogout(LogoutEvent event) {
        authenticated.remove(event.getPlayer().getUniqueId());
        denyWebVoice(event.getPlayer());
        disconnectWebVoice(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        authenticated.remove(event.getPlayer().getUniqueId());
        releaseAttachment(event.getPlayer());
        disconnectWebVoice(event.getPlayer());
    }

    private void allowWebVoice(Player player) {
        authenticated.add(player.getUniqueId());
        releaseAttachment(player);
    }

    private void denyWebVoice(Player player) {
        pendingLogin.computeIfAbsent(player.getUniqueId(), ignored ->
                player.addAttachment(plugin, "svg.vc.join", false));
    }

    private void releaseAttachment(Player player) {
        PermissionAttachment attachment = pendingLogin.remove(player.getUniqueId());
        if (attachment != null) player.removeAttachment(attachment);
    }

    private void disconnectWebVoice(Player player) {
        // SVG audio listeners run before SVC packet events, so close their connection explicitly.
        var manager = SvgCore.getConnectionManager();
        var connection = manager.get(player.getUniqueId());
        String reason = "Log in with AuthMe before using voice chat.";
        if (connection != null) connection.sendError(reason, true);
        manager.disconnect(player.getUniqueId(), 1008, reason);
    }

    void close() {
        authenticated.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            releaseAttachment(player);
            disconnectWebVoice(player);
        }
        pendingLogin.clear();
    }
}
