package com.westshield.loot;

import com.destroystokyo.paper.loottable.LootableInventory;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.loot.Lootable;
import org.bukkit.loot.LootTables;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread metadata index only. Player inventories stay on disk. */
final class NaturalRegistry {
    record Position(UUID world, int x, int y, int z) { }
    private final Map<UUID, LootStore.Container> ids = new HashMap<>();
    private final Map<Position, UUID> positions = new HashMap<>();
    private final Map<UUID, UUID> entities = new HashMap<>();
    final NamespacedKey marker;
    final NamespacedKey placed;
    private final NamespacedKey capturedTable;
    private final NamespacedKey capturedSeed;
    private final Set<String> tables = new HashSet<>();
    private final Set<String> worlds;

    NaturalRegistry(JavaPlugin plugin) {
        marker = new NamespacedKey(plugin, "container"); placed = new NamespacedKey(plugin, "player_placed");
        capturedTable = new NamespacedKey(plugin, "captured_table"); capturedSeed = new NamespacedKey(plugin, "captured_seed");
        worlds = Set.copyOf(plugin.getConfig().getStringList("allowed-world-keys"));
        for (LootTables table : LootTables.values()) {
            String key = table.getKey().toString();
            if (key.startsWith("minecraft:chests/") && !key.endsWith("spawn_bonus_chest")) tables.add(key);
        }
    }
    boolean allowed(World world) { return worlds.contains(world.getKey().toString()); }
    static Position position(Block block) { return new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    LootStore.Container byId(UUID id) { return ids.get(id); }
    LootStore.Container at(Block block) { return ids.get(positions.get(position(block))); }
    LootStore.Container at(Entity entity) { return ids.get(entities.get(entity.getUniqueId())); }
    Collection<LootStore.Container> all() { return List.copyOf(ids.values()); }
    void put(LootStore.Container c) {
        ids.put(c.id(), c);
        if (c.entity() != null) entities.put(c.entity(), c.id());
        else for (var cell : c.cells()) positions.put(new Position(c.world(), cell.x(), cell.y(), cell.z()), c.id());
    }
    boolean marked(Block block) {
        return block.getState() instanceof TileState tile && tile.getPersistentDataContainer().has(marker, PersistentDataType.STRING);
    }
    boolean marked(Entity entity) { return entity.getPersistentDataContainer().has(marker, PersistentDataType.STRING); }
    boolean natural(Block block) { return candidate(block) != null; }
    boolean natural(Entity entity) { return entity instanceof StorageMinecart cart && candidate(cart) != null; }
    private String table(Lootable source) {
        if (source instanceof PersistentDataHolder holder) {
            String captured = holder.getPersistentDataContainer().get(capturedTable, PersistentDataType.STRING);
            if (captured != null) return captured;
        }
        return source.getLootTable() == null ? null : source.getLootTable().getKey().toString();
    }
    private long seed(Lootable source) {
        if (source instanceof PersistentDataHolder holder) {
            Long captured = holder.getPersistentDataContainer().get(capturedSeed, PersistentDataType.LONG);
            if (captured != null) return captured;
        }
        return source.getSeed();
    }
    boolean unplaced(PersistentDataHolder source) {
        return !source.getPersistentDataContainer().has(placed);
    }
    private boolean unopened(Lootable source) {
        if (source instanceof PersistentDataHolder holder && holder.getPersistentDataContainer().has(capturedTable)) return true;
        return !(source instanceof LootableInventory paper) || !paper.hasBeenFilled();
    }
    LootStore.Container candidate(Block block) {
        if (!allowed(block.getWorld()) || at(block) != null) return null;
        BlockState state = block.getState();
        if (!(state instanceof org.bukkit.block.Container container) || !(state instanceof Lootable source)
            || !(state instanceof Chest || state instanceof Barrel) || !unplaced(container) || !unopened(source)) return null;
        String key = table(source);
        if (key == null || !tables.contains(key)) return null;
        List<org.bukkit.block.Container> halves = new ArrayList<>();
        if (state instanceof Chest chest && chest.getInventory().getHolder() instanceof DoubleChest pair) {
            if (!(pair.getLeftSide() instanceof Chest left) || !(pair.getRightSide() instanceof Chest right)) return null;
            halves.add(left); halves.add(right);
        } else halves.add(container);
        halves.sort(java.util.Comparator.comparingInt((org.bukkit.block.Container c) -> c.getX()).thenComparingInt(c -> c.getY()).thenComparingInt(c -> c.getZ()));
        List<LootStore.Cell> cells = new ArrayList<>();
        for (var half : halves) {
            if (!unplaced(half) || !(half instanceof Lootable lootable) || !unopened(lootable) || at(half.getBlock()) != null) return null;
            String halfKey = table(lootable);
            if (halfKey != null && !tables.contains(halfKey)) return null;
            // An empty unseeded half may be part of a natural double chest; never take stored goods.
            if (halfKey == null && !localInventory(half).isEmpty()) return null;
            cells.add(new LootStore.Cell(half.getX(), half.getY(), half.getZ(), half.getBlockData().getAsString(), halfKey, seed(lootable)));
        }
        return new LootStore.Container(UUID.randomUUID(), block.getWorld().getUID(), "BLOCK", null, cells, key, seed(source), false);
    }
    LootStore.Container candidate(StorageMinecart cart) {
        if (!allowed(cart.getWorld()) || at(cart) != null || !unplaced(cart) || !unopened(cart)) return null;
        String key = table(cart); if (key == null || !tables.contains(key)) return null;
        Location l = cart.getLocation();
        return new LootStore.Container(UUID.randomUUID(), cart.getWorld().getUID(), "MINECART", cart.getUniqueId(),
            List.of(new LootStore.Cell(l.getBlockX(), l.getBlockY(), l.getBlockZ(), "minecraft:chest", key, seed(cart))), key, seed(cart), false);
    }
    private Inventory localInventory(org.bukkit.block.Container c) { return c instanceof Chest chest ? chest.getBlockInventory() : c.getInventory(); }
    private boolean own(PersistentDataHolder holder, UUID id) {
        return unplaced(holder) && id.toString().equals(holder.getPersistentDataContainer().get(marker, PersistentDataType.STRING));
    }
    Location location(LootStore.Container c) {
        if (c.entity() != null) { Entity e = Bukkit.getEntity(c.entity()); return e == null ? null : e.getLocation(); }
        World world = Bukkit.getWorld(c.world()); var cell = c.cells().getFirst();
        return world == null ? null : new Location(world, cell.x() + .5, cell.y() + .5, cell.z() + .5);
    }
    boolean valid(LootStore.Container c) {
        if (c.retired()) return false;
        if (c.entity() != null) {
            Entity entity = Bukkit.getEntity(c.entity());
            return entity instanceof StorageMinecart && !entity.isDead() && allowed(entity.getWorld()) && own(entity, c.id());
        }
        World world = Bukkit.getWorld(c.world()); if (world == null) return false;
        for (var cell : c.cells()) {
            if (!world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4)) return false;
            BlockState state = world.getBlockAt(cell.x(), cell.y(), cell.z()).getState();
            if (!(state instanceof org.bukkit.block.Container container) || !own(container, c.id())
                || !state.getType().equals(Bukkit.createBlockData(cell.data()).getMaterial())) return false;
        }
        return true;
    }
    /** Called only after the registration transaction committed. Never makes a new roll. */
    boolean bind(LootStore.Container c) {
        if (c.retired()) return false;
        if (c.entity() != null) {
            Entity entity = Bukkit.getEntity(c.entity());
            if (!(entity instanceof StorageMinecart cart) || !unplaced(cart)) return false;
            if (!own(cart, c.id()) && !Objects.equals(table(cart), c.table())) return false;
            cart.getPersistentDataContainer().set(marker, PersistentDataType.STRING, c.id().toString());
            cart.setLootTable(null); cart.getInventory().clear(); return true;
        }
        World world = Bukkit.getWorld(c.world()); if (world == null) return false;
        List<org.bukkit.block.Container> originals = new ArrayList<>();
        for (var cell : c.cells()) {
            if (!world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4)) return false;
            BlockState state = world.getBlockAt(cell.x(), cell.y(), cell.z()).getState();
            if (!(state instanceof org.bukkit.block.Container container) || !(state instanceof Lootable lootable)
                || !unplaced(container) || state.getType() != Bukkit.createBlockData(cell.data()).getMaterial()) return false;
            if (!own(container, c.id()) && (!Objects.equals(table(lootable), cell.table()) || cell.table() == null && !localInventory(container).isEmpty())) return false;
            originals.add(container);
        }
        for (var container : originals) {
            if (own(container, c.id())) continue;
            ((Lootable) container).setLootTable(null);
            container.getPersistentDataContainer().set(marker, PersistentDataType.STRING, c.id().toString());
            container.getSnapshotInventory().clear();
            if (!container.update(false, false)) return false;
        }
        return valid(c);
    }
    void placed(Block block) {
        if (!(block.getState() instanceof TileState state)) return;
        state.getPersistentDataContainer().remove(marker);
        state.getPersistentDataContainer().remove(capturedTable); state.getPersistentDataContainer().remove(capturedSeed);
        state.getPersistentDataContainer().set(placed, PersistentDataType.BYTE, (byte) 1);
        state.update(false, false);
    }
    void placed(Entity entity) {
        var data = entity.getPersistentDataContainer(); data.remove(marker); data.remove(capturedTable); data.remove(capturedSeed);
        data.set(placed, PersistentDataType.BYTE, (byte) 1);
    }
    boolean permittedTable(org.bukkit.loot.LootTable table) { return table != null && tables.contains(table.getKey().toString()); }
    boolean protectedFromAutomation(Block block) {
        var known = at(block); return known != null && !known.retired() || marked(block) || natural(block)
            || block.getState() instanceof TileState tile && tile.getPersistentDataContainer().has(capturedTable);
    }
    boolean protectedFromAutomation(StorageMinecart cart) {
        var known = at(cart); return known != null && !known.retired() || marked(cart) || natural(cart)
            || cart.getPersistentDataContainer().has(capturedTable);
    }
    void capture(Block block, org.bukkit.loot.LootTable table) {
        if (!(block.getState() instanceof org.bukkit.block.Container state) || !(state instanceof Lootable lootable)) return;
        if (state.getPersistentDataContainer().has(marker)) return;
        state.getPersistentDataContainer().set(capturedTable, PersistentDataType.STRING, table.getKey().toString());
        state.getPersistentDataContainer().set(capturedSeed, PersistentDataType.LONG, lootable.getSeed());
        lootable.setLootTable(null); state.getSnapshotInventory().clear(); state.update(false, false);
    }
    void capture(StorageMinecart cart, org.bukkit.loot.LootTable table) {
        if (cart.getPersistentDataContainer().has(marker)) return;
        cart.getPersistentDataContainer().set(capturedTable, PersistentDataType.STRING, table.getKey().toString());
        cart.getPersistentDataContainer().set(capturedSeed, PersistentDataType.LONG, cart.getSeed()); cart.setLootTable(null);
    }
    void clearMarkers(LootStore.Container c) {
        if (c.entity() != null) {
            Entity entity = Bukkit.getEntity(c.entity());
            if (entity != null && own(entity, c.id())) { var d = entity.getPersistentDataContainer(); d.remove(marker); d.remove(capturedTable); d.remove(capturedSeed); }
            return;
        }
        World world = Bukkit.getWorld(c.world()); if (world == null) return;
        for (var cell : c.cells()) if (world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4)) {
            if (world.getBlockAt(cell.x(), cell.y(), cell.z()).getState() instanceof TileState tile && own(tile, c.id())) {
                var d = tile.getPersistentDataContainer(); d.remove(marker); d.remove(capturedTable); d.remove(capturedSeed); tile.update(false, false);
            }
        }
    }
    void lid(LootStore.Container c, boolean open) {
        if (c.entity() != null) return;
        World world = Bukkit.getWorld(c.world()); if (world == null) return;
        for (var cell : c.cells()) if (world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4)) {
            BlockState state = world.getBlockAt(cell.x(), cell.y(), cell.z()).getState();
            if (state instanceof Lidded lidded && state instanceof TileState tile && own(tile, c.id())) {
                if (open) lidded.open(); else lidded.close();
            }
        }
    }
}
