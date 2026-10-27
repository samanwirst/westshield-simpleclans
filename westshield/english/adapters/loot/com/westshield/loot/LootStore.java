package com.westshield.loot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Single-worker SQLite authority. No Bukkit state or Java object deserialization. */
public final class LootStore implements AutoCloseable {
    public static final int MAX_BLOB = 1048576;
    public record Cell(int x, int y, int z, String data, String table, long seed) { }
    public record Container(UUID id, UUID world, String kind, UUID entity, List<Cell> cells,
                            String table, long seed, boolean retired) {
        public Container { cells = List.copyOf(cells); }
        public Container retiredCopy() { return new Container(id, world, kind, entity, cells, table, seed, true); }
    }
    public record Roll(long seed, byte[] remaining) {
        public Roll { remaining = remaining == null ? null : remaining.clone(); }
        @Override public byte[] remaining() { return remaining == null ? null : remaining.clone(); }
    }
    public record Transfer(UUID id, UUID container, UUID player, UUID world, byte[] playerBefore,
                           byte[] playerAfter, byte[] lootBefore, byte[] lootAfter) {
        public Transfer {
            playerBefore = playerBefore.clone(); playerAfter = playerAfter.clone();
            lootBefore = lootBefore.clone(); lootAfter = lootAfter.clone();
        }
        @Override public byte[] playerBefore() { return playerBefore.clone(); }
        @Override public byte[] playerAfter() { return playerAfter.clone(); }
        @Override public byte[] lootBefore() { return lootBefore.clone(); }
        @Override public byte[] lootAfter() { return lootAfter.clone(); }
    }
    public enum Recovery { NONE, DELIVERED, RETURNED, REVIEW_REQUIRED }
    private final Connection connection;

    public LootStore(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=DELETE");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA foreign_keys=ON");
            int version;
            try (ResultSet r = s.executeQuery("PRAGMA user_version")) { r.next(); version = r.getInt(1); }
            if (version != 0 && version != 1) throw new SQLException("Unsupported loot database version");
            if (version == 0) {
                connection.setAutoCommit(false);
                try {
                    s.execute("CREATE TABLE containers (id TEXT PRIMARY KEY, world TEXT NOT NULL, kind TEXT NOT NULL, entity TEXT UNIQUE, table_key TEXT NOT NULL, seed INTEGER NOT NULL, retired INTEGER NOT NULL DEFAULT 0 CHECK(retired IN (0,1)))");
                    s.execute("CREATE TABLE cells (container TEXT NOT NULL REFERENCES containers(id), world TEXT NOT NULL, ordinal INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, block_data TEXT NOT NULL, table_key TEXT, seed INTEGER NOT NULL, PRIMARY KEY(container,ordinal), UNIQUE(world,x,y,z))");
                    s.execute("CREATE TABLE rolls (container TEXT NOT NULL REFERENCES containers(id), player TEXT NOT NULL, seed INTEGER NOT NULL, remaining BLOB, PRIMARY KEY(container,player))");
                    s.execute("CREATE TABLE transfers (id TEXT PRIMARY KEY, container TEXT NOT NULL, player TEXT NOT NULL UNIQUE, world TEXT NOT NULL, player_before BLOB NOT NULL, player_after BLOB NOT NULL, loot_before BLOB NOT NULL, loot_after BLOB NOT NULL, FOREIGN KEY(container,player) REFERENCES rolls(container,player))");
                    s.execute("CREATE TABLE audit (sequence INTEGER PRIMARY KEY AUTOINCREMENT, container TEXT NOT NULL, action TEXT NOT NULL, actor TEXT NOT NULL, at INTEGER NOT NULL)");
                    s.execute("PRAGMA user_version=1");
                    connection.commit();
                } catch (Exception error) { connection.rollback(); throw error; }
                finally { connection.setAutoCommit(true); }
            }
            try (ResultSet r = s.executeQuery("PRAGMA quick_check")) {
                if (!r.next() || !r.getString(1).equals("ok")) throw new SQLException("Loot database integrity check failed");
            }
        }
    }

    public List<Container> containers() throws SQLException {
        List<Container> result = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM containers ORDER BY id")) {
            while (r.next()) {
                UUID id = UUID.fromString(r.getString("id")); List<Cell> cells = new ArrayList<>();
                try (PreparedStatement q = connection.prepareStatement("SELECT * FROM cells WHERE container=? ORDER BY ordinal")) {
                    q.setString(1, id.toString()); try (ResultSet c = q.executeQuery()) {
                        while (c.next()) cells.add(new Cell(c.getInt("x"), c.getInt("y"), c.getInt("z"), c.getString("block_data"), c.getString("table_key"), c.getLong("seed")));
                    }
                }
                String entity = r.getString("entity");
                result.add(new Container(id, UUID.fromString(r.getString("world")), r.getString("kind"),
                    entity == null ? null : UUID.fromString(entity), cells, r.getString("table_key"), r.getLong("seed"), r.getInt("retired") != 0));
            }
        }
        return result;
    }

    public void register(Container c, int limit) throws SQLException {
        if (c.cells().isEmpty() || c.cells().size() > 2) throw new IllegalArgumentException("Invalid container footprint");
        transaction(() -> {
            try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM containers")) {
                r.next(); if (r.getLong(1) >= limit) throw new SQLException("Container registry ceiling reached");
            }
            try (PreparedStatement q = connection.prepareStatement("INSERT INTO containers(id,world,kind,entity,table_key,seed) VALUES(?,?,?,?,?,?)")) {
                q.setString(1, c.id().toString()); q.setString(2, c.world().toString()); q.setString(3, c.kind());
                q.setString(4, c.entity() == null ? null : c.entity().toString()); q.setString(5, c.table()); q.setLong(6, c.seed()); q.executeUpdate();
            }
            // Minecarts use entity identity; a coordinate is only their original restore location.
            if (c.entity() == null) for (int i = 0; i < c.cells().size(); i++) {
                Cell cell = c.cells().get(i);
                try (PreparedStatement q = connection.prepareStatement("INSERT INTO cells VALUES(?,?,?,?,?,?,?,?,?)")) {
                    q.setString(1, c.id().toString()); q.setString(2, c.world().toString()); q.setInt(3, i);
                    q.setInt(4, cell.x()); q.setInt(5, cell.y()); q.setInt(6, cell.z()); q.setString(7, cell.data()); q.setString(8, cell.table()); q.setLong(9, cell.seed()); q.executeUpdate();
                }
            }
            if (c.entity() != null) {
                // Entity restore coordinates have no block reservation.
                try (PreparedStatement q = connection.prepareStatement("INSERT INTO cells VALUES(?,?,?,?,?,?,?,?,?)")) {
                    Cell cell = c.cells().getFirst();
                    q.setString(1, c.id().toString()); q.setString(2, "entity:" + c.id()); q.setInt(3, 0);
                    q.setInt(4, cell.x()); q.setInt(5, cell.y()); q.setInt(6, cell.z()); q.setString(7, cell.data()); q.setString(8, cell.table()); q.setLong(9, cell.seed()); q.executeUpdate();
                }
            }
            audit(c.id(), "register", "world"); return null;
        });
    }

    public Roll reserve(UUID container, UUID player, long seed) throws SQLException {
        return transaction(() -> {
            requireActive(container);
            try (PreparedStatement q = connection.prepareStatement("INSERT OR IGNORE INTO rolls(container,player,seed) VALUES(?,?,?)")) {
                q.setString(1, container.toString()); q.setString(2, player.toString()); q.setLong(3, seed); q.executeUpdate();
            }
            return roll(container, player);
        });
    }
    public Roll roll(UUID container, UUID player) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT seed,remaining FROM rolls WHERE container=? AND player=?")) {
            q.setString(1, container.toString()); q.setString(2, player.toString());
            try (ResultSet r = q.executeQuery()) { return r.next() ? new Roll(r.getLong(1), r.getBytes(2)) : null; }
        }
    }
    public Roll generated(UUID container, UUID player, byte[] remaining) throws SQLException {
        blob(remaining);
        return transaction(() -> {
            requireActive(container);
            try (PreparedStatement q = connection.prepareStatement("UPDATE rolls SET remaining=? WHERE container=? AND player=? AND remaining IS NULL")) {
                q.setBytes(1, remaining); q.setString(2, container.toString()); q.setString(3, player.toString()); q.executeUpdate();
            }
            return Objects.requireNonNull(roll(container, player));
        });
    }
    public void prepare(Transfer transfer) throws SQLException {
        blob(transfer.playerBefore()); blob(transfer.playerAfter()); blob(transfer.lootBefore()); blob(transfer.lootAfter());
        transaction(() -> {
            requireActive(transfer.container());
            try (PreparedStatement q = connection.prepareStatement("UPDATE rolls SET remaining=? WHERE container=? AND player=? AND remaining=?")) {
                q.setBytes(1, transfer.lootAfter()); q.setString(2, transfer.container().toString());
                q.setString(3, transfer.player().toString()); q.setBytes(4, transfer.lootBefore());
                if (q.executeUpdate() != 1) throw new SQLException("Loot changed before transfer admission");
            }
            try (PreparedStatement q = connection.prepareStatement("INSERT INTO transfers VALUES(?,?,?,?,?,?,?,?)")) {
                q.setString(1, transfer.id().toString()); q.setString(2, transfer.container().toString());
                q.setString(3, transfer.player().toString()); q.setString(4, transfer.world().toString());
                q.setBytes(5, transfer.playerBefore()); q.setBytes(6, transfer.playerAfter());
                q.setBytes(7, transfer.lootBefore()); q.setBytes(8, transfer.lootAfter()); q.executeUpdate();
            }
            return null;
        });
    }
    public Transfer pending(UUID player) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT * FROM transfers WHERE player=?")) {
            q.setString(1, player.toString()); try (ResultSet r = q.executeQuery()) {
                if (!r.next()) return null;
                return new Transfer(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("container")),
                    player, UUID.fromString(r.getString("world")), r.getBytes("player_before"), r.getBytes("player_after"), r.getBytes("loot_before"), r.getBytes("loot_after"));
            }
        }
    }
    public Recovery recover(UUID player, UUID world, byte[] actual) throws SQLException {
        return transaction(() -> {
            Transfer t = pending(player); if (t == null) return Recovery.NONE;
            if (!t.world().equals(world)) return Recovery.REVIEW_REQUIRED;
            if (Arrays.equals(actual, t.playerAfter())) { removeTransfer(t); audit(t.container(), "recover-delivered", player.toString()); return Recovery.DELIVERED; }
            if (Arrays.equals(actual, t.playerBefore())) { rollbackInternal(t); audit(t.container(), "recover-returned", player.toString()); return Recovery.RETURNED; }
            return Recovery.REVIEW_REQUIRED;
        });
    }
    public void committed(Transfer t) throws SQLException { transaction(() -> { removeTransfer(t); audit(t.container(), "take", t.player().toString()); return null; }); }
    public void rollback(Transfer t) throws SQLException { transaction(() -> { rollbackInternal(t); return null; }); }
    private void rollbackInternal(Transfer t) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("UPDATE rolls SET remaining=? WHERE container=? AND player=? AND remaining=?")) {
            q.setBytes(1, t.lootBefore()); q.setString(2, t.container().toString()); q.setString(3, t.player().toString()); q.setBytes(4, t.lootAfter());
            if (q.executeUpdate() != 1) throw new SQLException("Transfer rollback pairing mismatch");
        }
        removeTransfer(t);
    }
    private void removeTransfer(Transfer t) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("DELETE FROM transfers WHERE id=? AND player=? AND container=?")) {
            q.setString(1, t.id().toString()); q.setString(2, t.player().toString()); q.setString(3, t.container().toString());
            if (q.executeUpdate() != 1) throw new SQLException("Transfer receipt no longer pending");
        }
    }
    public void destroyed(UUID id, String reason) throws SQLException {
        transaction(() -> {
            try (PreparedStatement q = connection.prepareStatement("UPDATE containers SET retired=1 WHERE id=? AND retired=0")) {
                q.setString(1, id.toString()); if (q.executeUpdate() == 1) audit(id, "destroy", reason);
            }
            return null;
        });
    }
    public long count(String table) throws SQLException {
        if (!Set.of("containers", "rolls", "transfers").contains(table)) throw new IllegalArgumentException("Unknown metric");
        try (Statement q = connection.createStatement(); ResultSet r = q.executeQuery("SELECT count(*) FROM " + table)) { r.next(); return r.getLong(1); }
    }
    private void audit(UUID id, String action, String actor) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("INSERT INTO audit(container,action,actor,at) VALUES(?,?,?,?)")) {
            q.setString(1, id.toString()); q.setString(2, action); q.setString(3, actor); q.setLong(4, System.currentTimeMillis()); q.executeUpdate();
        }
    }
    private void requireActive(UUID id) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT retired FROM containers WHERE id=?")) {
            q.setString(1, id.toString()); try (ResultSet r = q.executeQuery()) {
                if (!r.next() || r.getInt(1) != 0) throw new SQLException("Container is unavailable or retired");
            }
        }
    }
    private static void blob(byte[] b) { if (b == null || b.length == 0 || b.length > MAX_BLOB) throw new IllegalArgumentException("Invalid loot record size"); }
    @FunctionalInterface private interface Work<T> { T run() throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try { T result = work.run(); connection.commit(); return result; }
        catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        finally { connection.setAutoCommit(true); }
    }
    @Override public void close() throws SQLException { connection.close(); }
}
