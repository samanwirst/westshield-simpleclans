package com.westshield.creative;

import dev.geco.gsit.api.GSitAPI;
import dev.geco.gsit.model.StopReason;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Optional GSit integration, loaded only when GSit is enabled. */
final class GsitTravelSupport {
    private GsitTravelSupport() { }

    static boolean prepare(Player player) {
        Location standing = null;
        var pose = GSitAPI.getPoseByPlayer(player);
        if (pose != null) {
            Location original = pose.getSeat().getReturnLocation();
            if (original == null || !player.getWorld().equals(original.getWorld())) return false;
            standing = original.clone();
            if (!GSitAPI.removePose(pose, StopReason.TELEPORT, false)) return false;
        }
        var seat = GSitAPI.getSeatByEntity(player);
        if (seat != null) {
            Location original = seat.getReturnLocation();
            if (original == null || !player.getWorld().equals(original.getWorld())) return false;
            standing = original.clone();
            if (!GSitAPI.removeSeat(seat, StopReason.TELEPORT, false)) return false;
        }
        var crawl = GSitAPI.getCrawlByPlayer(player);
        if (crawl != null && !GSitAPI.stopCrawl(crawl, StopReason.TELEPORT)) return false;
        GSitAPI.stopPlayerSit(player, StopReason.TELEPORT);
        if (player.isInsideVehicle() && !player.leaveVehicle()) return false;
        if (standing != null) {
            standing.setYaw(player.getYaw());
            standing.setPitch(player.getPitch());
            // GSit's normal teleport listener runs after the event's origin was captured.
            // Restore the standing origin in the same world before Multiverse saves LAST_LOCATION.
            return player.teleport(standing, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        return true;
    }
}
