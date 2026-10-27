package com.westshield.protection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Active kick cycles plus preserved pardon receipts, atomically replaced on disk. */
public final class StrikeLedger {
    private final Path path;
    private volatile Map<UUID, Integer> counts = Map.of();
    private final YamlConfiguration data;

    public StrikeLedger(Path path) throws Exception {
        this.path = path;
        data = new YamlConfiguration();
        if (Files.exists(path)) {
            data.load(path.toFile());
            if (data.getInt("version", 0) != 1) throw new IllegalArgumentException("Unsupported strike ledger version");
        } else data.set("version", 1);
        Map<UUID, Integer> loaded = new HashMap<>();
        var accounts = data.getConfigurationSection("accounts");
        if (accounts != null) for (String key : accounts.getKeys(false)) {
            UUID id = UUID.fromString(key);int count = accounts.getInt(key + ".kicks", -1);
            if (count < 1 || count > 3) throw new IllegalArgumentException("Invalid confirmed-kick count");
            loaded.put(id, count);
        }
        counts = Map.copyOf(loaded);
    }

    public int count(UUID id) { return counts.getOrDefault(id, 0); }
    public int bannedAccounts() { return (int) counts.values().stream().filter(n -> n >= 3).count(); }

    public synchronized Set<UUID> accountsNamed(String name) {
        Set<UUID> result = new HashSet<>();
        for (UUID id : counts.keySet()) if (name.equalsIgnoreCase(data.getString("accounts." + id + ".name"))) result.add(id);
        return Set.copyOf(result);
    }

    public synchronized String name(UUID id) { return data.getString("accounts." + id + ".name"); }

    /** Persist the audit/reset before publishing the new counter to login threads. */
    public synchronized UUID pardon(UUID id, String name, String actor, String reason) throws Exception {
        UUID action = UUID.randomUUID();
        String base = "accounts." + id;
        YamlConfiguration candidate = new YamlConfiguration();candidate.loadFromString(data.saveToString());
        var previous = candidate.getConfigurationSection(base);
        Map<String, Object> archived = previous == null ? Map.of() : snapshot(previous);
        var receipts = candidate.getConfigurationSection(base + ".receipts");
        if (receipts != null) for (String receipt : receipts.getKeys(false)) candidate.set("seen-receipts." + id + "." + receipt, true);
        candidate.set("pardons." + action, Map.of("account", id.toString(), "name", name,
                "actor", actor, "reason", reason, "at", System.currentTimeMillis(),
                "previous-kicks", count(id), "previous", archived,
                "operation", "counter-reset-before-native-pardon"));
        candidate.set(base, null);
        persist(candidate.saveToString());
        data.loadFromString(candidate.saveToString());
        Map<UUID, Integer> updated = new HashMap<>(counts);updated.remove(id);counts = Map.copyOf(updated);
        return action;
    }

    private static Map<String, Object> snapshot(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            result.put(key, value instanceof ConfigurationSection nested ? snapshot(nested) : value);
        }
        return result;
    }

    public synchronized int record(UUID id, String name, UUID receipt, String provider, String check) throws Exception {
        if(count(id)>=3)return 3;
        String base = "accounts." + id;
        if (data.contains(base + ".receipts." + receipt) || data.contains("seen-receipts." + id + "." + receipt)) return count(id);
        int next = Math.min(3, count(id) + 1);
        YamlConfiguration candidate = new YamlConfiguration();candidate.loadFromString(data.saveToString());
        candidate.set(base + ".name", name);candidate.set(base + ".kicks", next);
        candidate.set(base + ".receipts." + receipt, Map.of("provider", provider, "check", check, "confirmed-at", System.currentTimeMillis()));
        persist(candidate.saveToString());
        data.loadFromString(candidate.saveToString());
        Map<UUID,Integer> updated = new HashMap<>(counts);updated.put(id, next);counts = Map.copyOf(updated);
        return next;
    }

    private void persist(String text) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "strikes-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
