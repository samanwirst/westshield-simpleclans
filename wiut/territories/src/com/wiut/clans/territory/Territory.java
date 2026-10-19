package com.wiut.clans.territory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.valid.IsValidOp;

/** An immutable simple polygon, bound to one specific world and clan incarnation. */
public final class Territory {
    private static final GeometryFactory FACTORY = new GeometryFactory();
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private final String id;
    private final UUID world;
    private final String worldName;
    private final String clanTag;
    private final long clanFounded;
    private final List<Vertex> vertices;
    private final Polygon polygon;
    private final PreparedGeometry prepared;

    public Territory(String id, UUID world, String worldName, String clanTag, long clanFounded,
                     List<Vertex> input) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("ID: 1-64 lowercase letters a-z, digits, hyphens or underscores.");
        }
        if (world == null || worldName == null || worldName.isBlank() || clanTag == null || clanTag.isBlank()
                || clanFounded <= 0) throw new IllegalArgumentException("Invalid territory world or clan.");
        ArrayList<Vertex> points = new ArrayList<>(input);
        if (points.size() > 1 && points.getFirst().equals(points.getLast())) points.removeLast();
        if (points.size() < 3) throw new IllegalArgumentException("Select at least three vertices.");
        if (new HashSet<>(points).size() != points.size()) {
            throw new IllegalArgumentException("Vertices must not repeat.");
        }
        Coordinate[] ring = new Coordinate[points.size() + 1];
        for (int index = 0; index < points.size(); index++) {
            ring[index] = new Coordinate(points.get(index).x(), points.get(index).z());
        }
        ring[points.size()] = new Coordinate(ring[0]);
        polygon = FACTORY.createPolygon(ring);
        if (!new IsValidOp(polygon).isValid() || polygon.getArea() <= 0) {
            throw new IllegalArgumentException("The boundary intersects itself or has no area. Select vertices in boundary order.");
        }
        this.id = id;
        this.world = world;
        this.worldName = worldName;
        this.clanTag = clanTag;
        this.clanFounded = clanFounded;
        this.vertices = List.copyOf(points);
        prepared = PreparedGeometryFactory.prepare(polygon);
    }

    public String id() { return id; }
    public UUID world() { return world; }
    public String worldName() { return worldName; }
    public String clanTag() { return clanTag; }
    public long clanFounded() { return clanFounded; }
    public List<Vertex> vertices() { return vertices; }
    public double area() { return polygon.getArea(); }
    public double[] labelPoint() {
        var interior = polygon.getInteriorPoint();
        return new double[]{interior.getX(),interior.getY()};
    }
    org.locationtech.jts.geom.Envelope envelope() { return polygon.getEnvelopeInternal(); }

    public boolean contains(int x, int z) {
        return polygon.getEnvelopeInternal().covers(x, z)
                && prepared.covers(FACTORY.createPoint(new Coordinate(x, z)));
    }

    public boolean overlapsInterior(Territory other) {
        return world.equals(other.world) && polygon.getEnvelopeInternal().intersects(other.envelope())
                && polygon.intersection(other.polygon).getArea() > 0;
    }
}
