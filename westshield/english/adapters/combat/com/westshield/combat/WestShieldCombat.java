package com.westshield.combat;

import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Combat policy for the pinned Purpur 26.2 native equipment/cooldown contract. */
public final class WestShieldCombat extends JavaPlugin implements Listener {
    private boolean blockMacePvp;
    private boolean preventSwapping;
    private long blockedMaceHits;
    private long swapResets;
    private final Map<UUID, ItemStack> observedWeapons = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        blockMacePvp = getConfig().getBoolean("block-mace-pvp", true);
        preventSwapping = getConfig().getBoolean("prevent-attribute-swapping", true);
        if (preventSwapping) enforceNativeProtection();
        for (Player player : getServer().getOnlinePlayers()) rememberWeapon(player);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Combat policy enabled: mace-pvp-blocked=" + blockMacePvp
                + "; attribute-swap-protection=" + preventSwapping);
    }

    private void enforceNativeProtection() {
        GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions = true;
    }

    private void synchronizeWeapon(Player player) {
        if (!preventSwapping) return;
        enforceNativeProtection();
        ItemStack current = player.getInventory().getItemInMainHand();
        ItemStack previous = observedWeapons.put(player.getUniqueId(), current.clone());
        if (previous != null) resetWeaponUse(player, previous, current);
        ((CraftPlayer) player).getHandle().detectEquipmentUpdates();
    }

    private void rememberWeapon(Player player) {
        observedWeapons.put(player.getUniqueId(), player.getInventory().getItemInMainHand().clone());
    }

    private boolean restrictedWeapon(ItemStack item) {
        return item != null && (item.getType() == Material.MACE || item.getType().name().endsWith("_SPEAR"));
    }

    private boolean weaponChanged(ItemStack before, ItemStack after) {
        if (!restrictedWeapon(before) && !restrictedWeapon(after)) return false;
        // Stack count and ordinary durability wear do not start a new weapon use.
        ItemStack left = before == null ? ItemStack.empty() : before.clone();
        ItemStack right = after == null ? ItemStack.empty() : after.clone();
        if (!left.isEmpty()) { left.setAmount(1); left.unsetData(DataComponentTypes.DAMAGE); }
        if (!right.isEmpty()) { right.setAmount(1); right.unsetData(DataComponentTypes.DAMAGE); }
        return !left.isSimilar(right);
    }

    private void resetWeaponUse(Player player, ItemStack before, ItemStack after) {
        if (!preventSwapping || !weaponChanged(before, after)) return;
        enforceNativeProtection();
        // Do this in the originating event, before another packet in the same tick.
        var handle = ((CraftPlayer) player).getHandle();
        if (handle.isUsingItem()
                && handle.getUseItem().has(net.minecraft.core.component.DataComponents.KINETIC_WEAPON)) {
            handle.stopUsingItem();
        }
        player.resetCooldown();
        swapResets++;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        synchronizeWeapon(event.getPlayer());
        if (blockMacePvp && event.getAttacked() instanceof Player
                && event.getPlayer().getInventory().getItemInMainHand().getType() == Material.MACE) {
            event.setCancelled(true);
            blockedMaceHits++;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!blockMacePvp || !(event.getEntity() instanceof Player)) return;
        boolean smash = event.getDamageSource().getCausingEntity() instanceof Player
                && event.getDamageSource().getDamageType().equals(DamageType.MACE_SMASH);
        boolean melee = event.getDamageSource().getDirectEntity() instanceof Player attacker
                && attacker.getInventory().getItemInMainHand().getType() == Material.MACE;
        if (smash || melee) {
            event.setCancelled(true);
            blockedMaceHits++;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        // Includes air clicks, whose native interact event can already be cancelled.
        synchronizeWeapon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHotbar(PlayerItemHeldEvent event) {
        var inventory = event.getPlayer().getInventory();
        resetWeaponUse(event.getPlayer(), inventory.getItem(event.getPreviousSlot()), inventory.getItem(event.getNewSlot()));
        ItemStack next = inventory.getItem(event.getNewSlot());
        observedWeapons.put(event.getPlayer().getUniqueId(), next == null ? ItemStack.empty() : next.clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHandSwap(PlayerSwapHandItemsEvent event) {
        resetWeaponUse(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand(), event.getMainHandItem());
        observedWeapons.put(event.getPlayer().getUniqueId(), event.getMainHandItem().clone());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryChange(PlayerInventorySlotChangeEvent event) {
        int slot = event.getSlot();
        if (slot == event.getPlayer().getInventory().getHeldItemSlot()) {
            synchronizeWeapon(event.getPlayer());
        } else if (slot == 40) {
            resetWeaponUse(event.getPlayer(), event.getOldItemStack(), event.getNewItemStack());
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { rememberWeapon(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { observedWeapons.remove(event.getPlayer().getUniqueId()); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("westshieldcombat.admin")) return true;
        sender.sendMessage("WestShield combat: mace-pvp-blocked=" + blockMacePvp
                + "; attribute-swap-protection=" + preventSwapping
                + "; native-equipment-protection="
                + GlobalConfiguration.get().unsupportedSettings.updateEquipmentOnPlayerActions
                + "; blocked-mace-hits=" + blockedMaceHits + "; weapon-swap-resets=" + swapResets);
        return true;
    }
}

