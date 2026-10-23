package com.westshield.portal;

import com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import java.util.List;
import java.util.UUID;

/** Resolves a pending gamertag invite from Floodgate's authenticated login identity. */
public final class BedrockInvites implements Listener {
    private final JavaPlugin plugin;
    private volatile List<String> invited;

    BedrockInvites(JavaPlugin plugin) {
        this.plugin = plugin;
        invited = List.copyOf(plugin.getConfig().getStringList("pending-bedrock-invites"));
    }

    private String matchingInvite(UUID profileId) {
        return matchingInvite(profileId, FloodgateApi.getInstance(), invited);
    }

    static String matchingInvite(UUID profileId, FloodgateApi api, List<String> invited) {
        if (profileId == null) return null;
        var player = api.getPlayer(profileId);
        if (player == null || player.isLinked() || !profileId.equals(player.getCorrectUniqueId())) return null;
        try {
            if (!profileId.equals(api.createJavaPlayerId(Long.parseUnsignedLong(player.getXuid())))) return null;
        } catch (NumberFormatException invalidIdentity) {
            return null;
        }
        for (String name : invited) {
            if (name.equalsIgnoreCase(player.getUsername())) return name;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onWhitelistCheck(ProfileWhitelistVerifyEvent event) {
        if (event.isWhitelistEnabled() && matchingInvite(event.getPlayerProfile().getId()) != null) {
            event.setWhitelisted(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        String invite = matchingInvite(event.getPlayer().getUniqueId());
        if (invite == null) return;
        // Save the verified UUID first; consume the invite only after the player really joined.
        event.getPlayer().setWhitelisted(true);
        invited = invited.stream().filter(name -> !name.equalsIgnoreCase(invite)).toList();
        plugin.getConfig().set("pending-bedrock-invites", invited);
        plugin.saveConfig();
        plugin.getLogger().info("Resolved Bedrock whitelist invite for " + invite + " (" + event.getPlayer().getUniqueId() + ")");
    }
}
