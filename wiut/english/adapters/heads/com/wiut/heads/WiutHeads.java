package com.wiut.heads;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Uses native skull components so trophies survive placement, loot and restarts. */
public final class WiutHeads extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Guaranteed PvP heads enabled; native names and static skin profiles.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null && event.getDamageSource().getCausingEntity() instanceof Player attacker) {
            killer = attacker;
        }
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;

        ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
        var profile = victim.getPlayerProfile().clone();
        // Supplying both identity fields creates a static profile even for default skins.
        // Keep Floodgate's uploaded texture property intact; never resolve by victim name.
        var frozen = ResolvableProfile.resolvableProfile()
                .uuid(victim.getUniqueId())
                .name(victim.getName())
                .addProperties(profile.getProperties())
                .build();
        if (frozen.dynamic()) throw new IllegalStateException("Trophy profiles must be static");
        head.setData(DataComponentTypes.PROFILE, frozen);
        head.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Head of " + victim.getName(), NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        event.getDrops().add(head);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInspect(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Material type = event.getClickedBlock().getType();
        if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) return;
        if (event.getClickedBlock().getState() instanceof Skull skull && skull.customName() != null) {
            event.getPlayer().sendActionBar(skull.customName());
        }
    }
}

