package com.wiut.clans.territory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

/** Validates the whole file before activation and persists edits before publishing them. */
public final class TerritoryStore {
    private final Path path;
    private Map<String, Territory> territories = new LinkedHashMap<>();
    private Map<UUID, STRtree> indexes = new LinkedHashMap<>();
    private long revision;

    public TerritoryStore(Path path) throws IOException, InvalidConfigurationException {
        this.path = path;
        if (!Files.exists(path)) return;
        YamlConfiguration config = new YamlConfiguration();
        config.load(path.toFile());
        if (!(config.get("version") instanceof Integer version) || version != 1
                || !(config.get("territories") instanceof List<?> rows)) {
            throw new IllegalArgumentException("Unsupported territory file schema");
        }
        Map<String, Territory> loaded = new LinkedHashMap<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> data) || !(data.get("vertices") instanceof List<?> vertices)) {
                throw new IllegalArgumentException("Invalid territory entry");
            }
            ArrayList<Vertex> points = new ArrayList<>();
            for (Object raw : vertices) {
                if (!(raw instanceof Map<?, ?> point)) throw new IllegalArgumentException("Invalid vertex");
                points.add(new Vertex(integer(point.get("x")), integer(point.get("z"))));
            }
            Territory territory = new Territory(text(data,"id"), UUID.fromString(text(data,"world")),
                    text(data,"world-name"), text(data,"clan-tag"), positiveLong(data.get("clan-founded")), points);
            if (loaded.putIfAbsent(territory.id(), territory) != null) throw new IllegalArgumentException("Duplicate territory ID");
        }
        validateOverlap(loaded.values());
        activate(loaded);
    }

    private static String text(Map<?, ?> data, String key) {
        if (data.get(key) instanceof String value && !value.isBlank()) return value;
        throw new IllegalArgumentException("Invalid territory field: " + key);
    }
    private static int integer(Object raw) {
        if (!(raw instanceof Integer value)) throw new IllegalArgumentException("Vertex must be an integer");
        return value;
    }
    private static long positiveLong(Object raw) {
        if (!(raw instanceof Long || raw instanceof Integer) || ((Number) raw).longValue() <= 0) {
            throw new IllegalArgumentException("Invalid clan founding timestamp");
        }
        return ((Number) raw).longValue();
    }

    private static void validateOverlap(Collection<Territory> values) {
        List<Territory> all = List.copyOf(values);
        for (int first = 0; first < all.size(); first++) {
            for (int second = first + 1; second < all.size(); second++) {
                if (all.get(first).overlapsInterior(all.get(second))) {
                    throw new IllegalArgumentException("Territory interiors overlap: "
                            + all.get(first).id() + " / " + all.get(second).id());
                }
            }
        }
    }

    private void activate(Map<String, Territory> next) {
        Map<UUID, STRtree> nextIndexes = new LinkedHashMap<>();
        for (Territory territory : next.values()) {
            nextIndexes.computeIfAbsent(territory.world(), ignored -> new STRtree())
                    .insert(territory.envelope(), territory);
        }
        nextIndexes.values().forEach(STRtree::build);
        territories = new LinkedHashMap<>(next);
        indexes = nextIndexes;
        revision++;
    }

    public List<Territory> all() { return List.copyOf(territories.values()); }
    public Territory get(String id) { return territories.get(id); }
    public long revision() { return revision; }

    @SuppressWarnings("unchecked")
    public List<Territory> at(UUID world, int x, int z) {
        STRtree tree = indexes.get(world);
        if (tree == null) return List.of();
        List<Territory> candidates = tree.query(new Envelope(x,x,z,z));
        return candidates.stream().filter(territory -> territory.contains(x,z))
                .sorted(Comparator.comparing(Territory::id)).toList();
    }

    public void put(Territory territory, boolean replace) throws IOException {
        if (territories.containsKey(territory.id()) != replace) {
            throw new IllegalArgumentException(replace ? "Territory not found." : "This ID is already in use; use reshape.");
        }
        LinkedHashMap<String,Territory> next = new LinkedHashMap<>(territories);
        next.put(territory.id(), territory);
        validateOverlap(next.values());
        persist(next);
        activate(next);
    }

    public void remove(String id) throws IOException {
        if (!territories.containsKey(id)) throw new IllegalArgumentException("Territory not found.");
        LinkedHashMap<String,Territory> next = new LinkedHashMap<>(territories);
        next.remove(id);
        persist(next);
        activate(next);
    }

    private void persist(Map<String,Territory> next) throws IOException {
        ArrayList<Map<String,Object>> rows = new ArrayList<>();
        for (Territory territory : next.values()) {
            LinkedHashMap<String,Object> row = new LinkedHashMap<>();
            row.put("id",territory.id());row.put("world",territory.world().toString());
            row.put("world-name",territory.worldName());row.put("clan-tag",territory.clanTag());
            row.put("clan-founded",territory.clanFounded());
            row.put("vertices",territory.vertices().stream()
                    .map(point -> Map.of("x",point.x(),"z",point.z())).toList());
            rows.add(row);
        }
        YamlConfiguration config = new YamlConfiguration();
        config.set("version",1);config.set("territories",rows);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(),"territories-",".tmp");
        try {
            Files.writeString(temporary,config.saveToString(),StandardCharsets.UTF_8);
            try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
