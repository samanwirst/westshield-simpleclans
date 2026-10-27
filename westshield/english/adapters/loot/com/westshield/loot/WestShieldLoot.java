package com.westshield.loot;

import fr.xephi.authme.api.v3.AuthMeApi;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.*;
import org.bukkit.loot.*;

/** Account-bound loot with durable rolls and fenced, journalled take-only delivery. */
public final class WestShieldLoot extends org.bukkit.plugin.java.JavaPlugin implements Listener {
    private final Map<UUID, View> views = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Set<UUID> registering = new HashSet<>();
    private final Set<UUID> breaking = new HashSet<>();
    private final Set<NaturalRegistry.Position> unregisteredBreaks = new HashSet<>();
    private final Map<UUID, Integer> openers = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private NaturalRegistry registry;
    private LootStore store;
    private ExecutorService worker;
    private Set<GameMode> modes = Set.of();
    private boolean ready;
    private boolean closing;
    private int generating;
    private int maxRequests;
    private int maxContainers;
    private double distanceSquared;
    private long failures;
    private long deliveries;
    private long destroyed;

    private static final class View implements InventoryHolder {
        final Player player;
        final LootStore.Container source;
        final Inventory inventory;
        byte[] remaining;
        boolean opened;
        View(Player player, LootStore.Container source, byte[] remaining, ItemStack[] items, String title) {
            this.player = player; this.source = source; this.remaining = remaining.clone();
            inventory = Bukkit.createInventory(this, items.length, Component.text(title)); inventory.setContents(items);
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    @Override public void onEnable() {
        saveDefaultConfig(); registry = new NaturalRegistry(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        try {
            if (!getConfig().getBoolean("enabled") || !Bukkit.getMinecraftVersion().equals("26.2")) throw new IllegalStateException("Unverified loot runtime or disabled configuration");
            Set<GameMode> selected = new HashSet<>();
            for (String mode : getConfig().getStringList("allowed-game-modes")) selected.add(GameMode.valueOf(mode));
            if (selected.isEmpty() || selected.contains(GameMode.CREATIVE) || selected.contains(GameMode.SPECTATOR)) throw new IllegalStateException("Loot requires survival/adventure isolation");
            modes = Set.copyOf(selected); maxRequests = getConfig().getInt("max-pending-requests", 200);
            maxContainers = getConfig().getInt("max-containers", 100000);
            double distance = getConfig().getDouble("max-open-distance", 8);
            if (maxRequests < 1 || maxRequests > 200 || maxContainers < 1 || maxContainers > 100000 || distance < 1 || distance > 16) throw new IllegalStateException("Invalid loot bounds");
            distanceSquared = distance * distance;
            store = new LootStore(getDataFolder().toPath().resolve("loot.sqlite"));
            for (var container : store.containers()) registry.put(container);
            worker = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "WestShieldLoot-SQLite"); thread.setDaemon(true); return thread; });
            ready = true;
            getLogger().info("Personal loot ready: AuthMe; canonical account UUID; one permanent roll; natural chests/barrels/minecarts; destructible=true; vaults=vanilla");
        } catch (Exception error) { failed(error); }
    }
    @Override public void onDisable() {
        closing = true; ready = false;
        for (View view : List.copyOf(views.values())) view.player.closeInventory();
        views.clear();
        if (worker != null) {
            worker.shutdown();
            try {
                if (!worker.awaitTermination(8, TimeUnit.SECONDS)) { getLogger().severe("Loot worker has not drained; pending transfers retained for recovery"); return; }
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
        }
        if (store != null) try { store.close(); } catch (Exception error) { getLogger().log(Level.SEVERE, "Loot database close failed", error); }
    }
    private void failed(Throwable error) {
        ready = false; failures++;
        busy.clear();
        for (View view : List.copyOf(views.values())) view.player.closeInventory();
        getLogger().log(Level.SEVERE, "Personal loot paused; durable records retained; no automatic reward retry", error);
    }
    private void message(Player player, String text) { player.sendMessage(Component.text("WestShield: " + text, NamedTextColor.GOLD)); }
    private boolean authenticated(Player p) {
        return p.isOnline() && !p.isDead() && Bukkit.getPlayer(p.getUniqueId()) == p && AuthMeApi.getInstance().isAuthenticated(p);
    }
    private boolean admitted(Player p) { return ready && authenticated(p) && registry.allowed(p.getWorld()) && modes.contains(p.getGameMode()); }
    private boolean sourceValid(LootStore.Container c) {
        var current = registry.byId(c.id());
        return current != null && !current.retired() && !breaking.contains(c.id()) && !registering.contains(c.id()) && registry.valid(current);
    }
    private boolean nearby(Player p, LootStore.Container c) {
        Location location = registry.location(c);
        return location != null && p.getWorld().equals(location.getWorld()) && p.getLocation().distanceSquared(location) <= distanceSquared;
    }
    @FunctionalInterface private interface Sql<T> { T run() throws Exception; }
    private <T> void sql(Sql<T> work, Consumer<T> done) {
        worker.execute(() -> {
            T value;
            try { value = work.run(); }
            catch (Exception error) { main(() -> failed(error)); return; }
            main(() -> done.accept(value));
        });
    }
    private void main(Runnable task) {
        if (closing || !isEnabled()) return;
        try { Bukkit.getScheduler().runTask(this, task); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Shutdown retains any pending journal. */ }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock(); var known = registry.at(block);
        if (known != null && !known.retired() && block.getState() instanceof TileState tile && !registry.unplaced(tile)) {
            destroy(known, "player-replacement"); return;
        }
        if ((known == null || known.retired()) && !registry.marked(block) && !registry.natural(block)) return;
        // Never remove another plugin's denial, including AuthMe and claim protection.
        if (event.useInteractedBlock() == Event.Result.DENY) return;
        event.setUseInteractedBlock(Event.Result.DENY); event.setUseItemInHand(Event.Result.DENY);
        if (known != null && known.retired()) return;
        if (!admitted(event.getPlayer())) { message(event.getPlayer(), "Sign in and use Survival to open personal loot."); return; }
        if (known == null) discover(registry.candidate(block), event.getPlayer());
        else openKnown(known, event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interactCart(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof StorageMinecart cart)) return;
        var known = registry.at(cart);
        if ((known == null || known.retired()) && !registry.marked(cart) && !registry.natural(cart)) return;
        event.setCancelled(true);
        if (known != null && known.retired()) return;
        if (!admitted(event.getPlayer())) { message(event.getPlayer(), "Sign in and use Survival to open personal loot."); return; }
        if (known == null) discover(registry.candidate(cart), event.getPlayer()); else openKnown(known, event.getPlayer());
    }
    private void discover(LootStore.Container c, Player player) {
        if (c == null) { message(player, "This loot container is unavailable. Please contact staff."); return; }
        if (registering.size() >= maxRequests) { message(player, "Loot storage is busy. Please try again."); return; }
        registry.put(c); registering.add(c.id());
        sql(() -> { store.register(c, maxContainers); return c; }, registered -> {
            registering.remove(c.id());
            if (registry.byId(c.id()).retired() || breaking.contains(c.id())) return;
            if (!registry.bind(c)) { destroy(c, "registration-source-changed"); return; }
            if (admitted(player)) openKnown(c, player);
        });
    }
    private void openKnown(LootStore.Container source, Player player) {
        if (!ready || registering.contains(source.id()) || breaking.contains(source.id())) { message(player, "This container is being updated. Please try again."); return; }
        if (source.retired()) return;
        if (!registry.valid(source) && !registry.bind(source)) { message(player, "This container is no longer available."); return; }
        if (!nearby(player, source) || busy.contains(player.getUniqueId())) return;
        if (busy.size() >= maxRequests) { message(player, "Loot storage is busy. Please try again."); return; }
        if (!empty(player.getItemOnCursor())) { message(player, "Put away the item on your cursor first."); return; }
        UUID account = player.getUniqueId(); busy.add(account);
        sql(() -> store.pending(account), pending -> {
            if (!admitted(player) || !sourceValid(source) || !nearby(player, source)) { busy.remove(account); return; }
            if (pending == null) reserve(source, player);
            else {
                byte[] actual = encode(player.getInventory().getStorageContents()); UUID world = player.getWorld().getUID();
                sql(() -> store.recover(account, world, actual), recovery -> {
                    if (recovery == LootStore.Recovery.REVIEW_REQUIRED) {
                        busy.remove(account); message(player, "A previous loot transfer needs staff review. No items have been issued again.");
                    } else if (admitted(player) && sourceValid(source) && nearby(player, source)) reserve(source, player);
                    else busy.remove(account);
                });
            }
        });
    }
    private void reserve(LootStore.Container source, Player player) {
        UUID account = player.getUniqueId(); long seed = random.nextLong();
        sql(() -> store.reserve(source.id(), account, seed), roll -> {
            if (!admitted(player) || !sourceValid(source) || !nearby(player, source)) { busy.remove(account); return; }
            if (roll.remaining() != null) show(source, player, roll.remaining());
            else {
                byte[] generated;
                try { generated = encode(generate(source, player, roll.seed())); }
                catch (Exception error) { busy.remove(account); failed(error); return; }
                sql(() -> store.generated(source.id(), account, generated), committed -> {
                    if (admitted(player) && sourceValid(source) && nearby(player, source)) show(source, player, committed.remaining());
                    else busy.remove(account);
                });
            }
        });
    }
    private ItemStack[] generate(LootStore.Container source, Player player, long seed) {
        List<ItemStack> items = new ArrayList<>(); int ordinal = 0;
        for (var cell : source.cells()) {
            Inventory part = Bukkit.createInventory(null, 27);
            if (cell.table() != null) {
                LootTable table = Objects.requireNonNull(Bukkit.getLootTable(Objects.requireNonNull(NamespacedKey.fromString(cell.table()))), "Original loot table is unavailable");
                LootContext context = new LootContext.Builder(Objects.requireNonNull(registry.location(source))).luck(0).lootedEntity(player).killer(player).build();
                generating++;
                try { table.fillInventory(part, new Random(seed ^ (0x9e3779b97f4a7c15L * ++ordinal)), context); }
                finally { generating--; }
            }
            items.addAll(Arrays.asList(part.getContents()));
        }
        return items.toArray(ItemStack[]::new);
    }
    private void show(LootStore.Container source, Player player, byte[] remaining) {
        busy.remove(player.getUniqueId());
        if (!admitted(player) || !sourceValid(source) || !nearby(player, source)) return;
        ItemStack[] items = decode(remaining); int expected = source.cells().size() * 27;
        if (items.length != expected) { failed(new IllegalStateException("Stored loot size does not match its original footprint")); return; }
        View view = new View(player, source, remaining, items, getConfig().getString("inventory-title", "Personal Loot"));
        views.put(player.getUniqueId(), view);
        InventoryView opened = player.openInventory(view.inventory);
        if (opened == null || opened.getTopInventory() != view.inventory) views.remove(player.getUniqueId(), view);
        else {
            view.opened = true;
            int count = openers.merge(source.id(), 1, Integer::sum);
            if (count == 1) registry.lid(source, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        boolean alreadyDenied = event.isCancelled(); event.setCancelled(true);
        if (alreadyDenied || !(event.getWhoClicked() instanceof Player player) || player != view.player) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= view.inventory.getSize()) return;
        if (!Set.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT).contains(event.getClick())) return;
        if (!admitted(player) || views.get(player.getUniqueId()) != view || busy.contains(player.getUniqueId()) || !empty(player.getItemOnCursor())) return;
        if (!sourceValid(view.source) || !nearby(player, view.source)) { player.closeInventory(); return; }
        int slot = event.getRawSlot(); boolean one = event.getClick() == ClickType.RIGHT;
        busy.add(player.getUniqueId());
        // Bukkit requires inventory mutations outside the click event's own transaction.
        Bukkit.getScheduler().runTask(this, () -> take(view, slot, one));
    }
    private void take(View view, int slot, boolean one) {
        Player player = view.player; UUID account = player.getUniqueId();
        if (!admitted(player) || views.get(account) != view || player.getOpenInventory().getTopInventory() != view.inventory
            || !sourceValid(view.source) || !nearby(player, view.source) || !empty(player.getItemOnCursor())) { busy.remove(account); return; }
        ItemStack[] loot = decode(view.remaining); ItemStack stack = loot[slot];
        if (empty(stack)) { busy.remove(account); return; }
        ItemStack[] before = cloneItems(player.getInventory().getStorageContents()); ItemStack[] after = cloneItems(before);
        int requested = one ? 1 : stack.getAmount(); int moved = add(after, stack, requested);
        if (moved == 0) { busy.remove(account); message(player, "Your inventory is full."); return; }
        ItemStack[] remaining = cloneItems(loot); remaining[slot] = stack.clone(); remaining[slot].setAmount(stack.getAmount() - moved);
        if (remaining[slot].getAmount() == 0) remaining[slot] = null;
        LootStore.Transfer transfer = new LootStore.Transfer(UUID.randomUUID(), view.source.id(), account, player.getWorld().getUID(), encode(before), encode(after), view.remaining, encode(remaining));
        sql(() -> { store.prepare(transfer); return transfer; }, prepared -> {
            boolean valid = admitted(player) && views.get(account) == view && sourceValid(view.source) && nearby(player, view.source)
                && player.getWorld().getUID().equals(prepared.world()) && empty(player.getItemOnCursor())
                && Arrays.equals(encode(player.getInventory().getStorageContents()), prepared.playerBefore());
            if (!valid) {
                sql(() -> { store.rollback(prepared); return true; }, ignored -> { busy.remove(account); if (player.isOnline()) player.updateInventory(); });
                return;
            }
            try {
                player.getInventory().setStorageContents(decode(prepared.playerAfter()));
                // The journal spans SQLite and Minecraft's independent player save. Ambiguity is never retried blindly.
                player.saveData();
            } catch (Exception error) { busy.remove(account); player.closeInventory(); failed(error); return; }
            sql(() -> { store.committed(prepared); return true; }, ignored -> {
                busy.remove(account); deliveries++;
                if (views.get(account) == view) { view.remaining = prepared.lootAfter(); view.inventory.setContents(decode(view.remaining)); }
                if (player.isOnline()) player.updateInventory();
            });
        });
    }
    static int add(ItemStack[] storage, ItemStack stack, int requested) {
        int left = requested; int maximum = Math.min(64, stack.getMaxStackSize());
        for (ItemStack item : storage) if (!empty(item) && item.isSimilar(stack) && item.getAmount() < maximum) {
            int move = Math.min(left, maximum - item.getAmount()); item.setAmount(item.getAmount() + move); left -= move; if (left == 0) return requested;
        }
        for (int i = 0; i < storage.length && left > 0; i++) if (empty(storage[i])) {
            int move = Math.min(left, maximum); storage[i] = stack.clone(); storage[i].setAmount(move); left -= move;
        }
        return requested - left;
    }
    static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() == 0; }
    static ItemStack[] cloneItems(ItemStack[] source) { return Arrays.stream(source).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new); }
    static byte[] encode(ItemStack[] items) { return ItemStack.serializeItemsAsBytes(items); }
    static ItemStack[] decode(byte[] bytes) { return ItemStack.deserializeItemsFromBytes(bytes); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) { if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true); }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof View view) {
            if (views.get(event.getPlayer().getUniqueId()) == view) views.remove(event.getPlayer().getUniqueId());
            if (view.opened) {
                view.opened = false;
                int count = openers.getOrDefault(view.source.id(), 1) - 1;
                if (count <= 0) { openers.remove(view.source.id()); registry.lid(view.source, false); }
                else openers.put(view.source.id(), count);
            }
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { views.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void logout(fr.xephi.authme.events.LogoutEvent event) { main(() -> event.getPlayer().closeInventory()); }
    @EventHandler public void changedWorld(PlayerChangedWorldEvent event) {
        if (views.containsKey(event.getPlayer().getUniqueId())) event.getPlayer().closeInventory();
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void changedMode(PlayerGameModeChangeEvent event) {
        if (!modes.contains(event.getNewGameMode()) && views.containsKey(event.getPlayer().getUniqueId())) event.getPlayer().closeInventory();
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) { if (busy.contains(event.getEntity().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) { if (busy.contains(event.getPlayer().getUniqueId()) || views.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) { if (busy.contains(event.getPlayer().getUniqueId()) || views.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }

    private void destroy(LootStore.Container c, String cause) {
        var current = registry.byId(c.id()); if (current == null || current.retired()) return;
        registry.put(current.retiredCopy()); breaking.remove(c.id());
        for (View view : List.copyOf(views.values())) if (view.source.id().equals(c.id())) view.player.closeInventory();
        registry.clearMarkers(current);
        if (worker != null) sql(() -> { store.destroyed(c.id(), cause); return true; }, ignored -> destroyed++);
    }
    private void checkDestroyed(LootStore.Container c, String cause) {
        if (c == null || c.retired() || !breaking.add(c.id())) return;
        for (View view : List.copyOf(views.values())) if (view.source.id().equals(c.id())) view.player.closeInventory();
        Bukkit.getScheduler().runTask(this, () -> {
            breaking.remove(c.id());
            if (!registry.valid(c)) destroy(c, cause);
        });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        var c = registry.at(event.getBlock());
        if (c != null && !c.retired()) checkDestroyed(c, "block-break");
        else if (registry.natural(event.getBlock())) {
            var position = NaturalRegistry.position(event.getBlock()); unregisteredBreaks.add(position);
            Bukkit.getScheduler().runTask(this, () -> unregisteredBreaks.remove(position));
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void exploded(EntityExplodeEvent event) { for (Block block : event.blockList()) checkDestroyed(registry.at(block), "entity-explosion"); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void exploded(BlockExplodeEvent event) { for (Block block : event.blockList()) checkDestroyed(registry.at(block), "block-explosion"); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void cartDestroyed(VehicleDestroyEvent event) { checkDestroyed(registry.at(event.getVehicle()), "vehicle-destroy"); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placed(BlockPlaceEvent event) {
        LootStore.Container c = registry.at(event.getBlock()); registry.placed(event.getBlock());
        if (c != null && !c.retired()) destroy(c, "block-replaced");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventMerge(BlockPlaceEvent event) {
        if (!(event.getBlock().getState() instanceof Chest chest) || !(chest.getInventory().getHolder() instanceof DoubleChest pair)) return;
        for (InventoryHolder half : List.of(pair.getLeftSide(), pair.getRightSide())) if (half instanceof Chest part) {
            var c = registry.at(part.getBlock());
            if (c != null && !c.retired() && registry.at(event.getBlock()) == null) {
                event.setCancelled(true); message(event.getPlayer(), "Break the natural loot chest first before replacing or extending it."); return;
            }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityPlaced(EntityPlaceEvent event) { registry.placed(event.getEntity()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(InventoryMoveItemEvent event) {
        if (managed(event.getSource().getHolder()) || managed(event.getDestination().getHolder())) event.setCancelled(true);
    }
    private boolean managed(InventoryHolder holder) {
        if (holder instanceof org.bukkit.block.Container block) return registry.protectedFromAutomation(block.getBlock());
        if (holder instanceof DoubleChest pair) return managed(pair.getLeftSide()) || managed(pair.getRightSide());
        return holder instanceof StorageMinecart cart && registry.protectedFromAutomation(cart);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void physicalLoot(LootGenerateEvent event) {
        if (generating > 0 || event.isPlugin()) return;
        InventoryHolder holder = event.getInventoryHolder();
        if (holder instanceof DoubleChest pair) { capturePhysical(pair.getLeftSide(), event); capturePhysical(pair.getRightSide(), event); }
        else capturePhysical(holder, event);
    }
    private void capturePhysical(InventoryHolder holder, LootGenerateEvent event) {
        if (holder instanceof org.bukkit.block.Container container && (container instanceof Chest || container instanceof Barrel)
            && registry.allowed(container.getWorld()) && registry.permittedTable(event.getLootTable()) && registry.unplaced(container)) {
            event.setCancelled(true);
            var c = registry.at(container.getBlock());
            if (unregisteredBreaks.contains(NaturalRegistry.position(container.getBlock())) || c != null && breaking.contains(c.id())) return;
            registry.capture(container.getBlock(), event.getLootTable());
        } else if (holder instanceof StorageMinecart cart && registry.allowed(cart.getWorld()) && registry.permittedTable(event.getLootTable()) && registry.unplaced(cart)) {
            event.setCancelled(true); registry.capture(cart, event.getLootTable());
        }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("westshieldloot.admin")) return true;
        if (sender instanceof Player p && !authenticated(p)) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("WestShield loot: ready=" + ready + "; cached-containers=" + registry.all().size() + "; pending-players=" + busy.size() + "; deliveries=" + deliveries + "; destroyed=" + destroyed + "; failures=" + failures + "; refills=false; vaults=vanilla");
            if (ready) sql(() -> List.of(store.count("containers"), store.count("rolls"), store.count("transfers")), counts -> sender.sendMessage("Durable loot: containers=" + counts.get(0) + "; account-rolls=" + counts.get(1) + "; unresolved-transfers=" + counts.get(2)));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("inspect") && sender instanceof Player p) {
            Block target = p.getTargetBlockExact(6); LootStore.Container c = target == null ? null : registry.at(target);
            sender.sendMessage(c == null ? "No registered loot container targeted." : "Loot container " + c.id() + "; retired=" + c.retired() + "; table=" + c.table()); return true;
        }
        sender.sendMessage("/westshieldloot status | inspect"); return true;
    }
}
