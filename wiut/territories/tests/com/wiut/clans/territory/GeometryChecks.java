package com.wiut.clans.territory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Standalone geometry and persistence acceptance, without a Minecraft server. */
public final class GeometryChecks {
    private static int checks;
    private static void check(boolean condition,String label) {
        if (!condition) throw new AssertionError(label);
        checks++;System.out.println("PASS " + label);
    }
    private static void rejected(Runnable action,String label) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { check(true,label);return; }
        throw new AssertionError(label);
    }
    private static List<Vertex> points(int... coordinates) {
        ArrayList<Vertex> result = new ArrayList<>();
        for (int index=0;index<coordinates.length;index+=2) result.add(new Vertex(coordinates[index],coordinates[index+1]));
        return result;
    }
    public static void main(String[] args) throws Exception {
        UUID world = UUID.randomUUID();
        List<Vertex> outline = points(0,0,12,0,12,4,4,4,4,12,0,12);
        Territory concave = new Territory("alpha",world,"survival","poly",1000,outline);
        check(concave.contains(2,10) && concave.contains(10,2),"Both arms of a concave polygon are included");
        check(!concave.contains(10,10),"The concave notch is excluded despite being inside the bounding box");
        double[] label=concave.labelPoint();
        check(label[0]>=0 && label[1]>=0 && label[0]<=12 && label[1]<=12
                && (label[0]<=4 || label[1]<=4),"A concave polygon's permanent label anchor is inside its actual area");
        check(concave.contains(0,0) && concave.contains(4,4) && concave.contains(4,10),"Vertices and shared edges are inclusive");
        check(!concave.contains(-1,0) && !concave.contains(13,2),"Exterior points remain outside");
        ArrayList<Vertex> reversed = new ArrayList<>(outline);Collections.reverse(reversed);
        Territory clockwise = new Territory("clockwise",world,"survival","poly",1000,reversed);
        check(clockwise.contains(2,10) && !clockwise.contains(10,10),"Either winding order has the same meaning");
        rejected(() -> new Territory("bad",world,"s","p",1,points(0,0,8,8,0,8,8,0)),"Self-intersections are rejected");
        rejected(() -> new Territory("bad",world,"s","p",1,points(0,0,1,1,2,2)),"Zero-area outlines are rejected");
        rejected(() -> new Territory("bad",world,"s","p",1,points(0,0,3,0,3,3,3,0,0,3)),"Repeated internal vertices are rejected");
        rejected(() -> new Territory("bad",world,"s","p",1,points(0,0,1,0)),"Two points cannot become a territory");
        Territory neighbor = new Territory("neighbor",world,"survival","other",2000,points(12,0,20,0,20,4,12,4));
        check(!concave.overlapsInterior(neighbor),"Neighboring polygons may share a complete edge");
        Territory contained = new Territory("contained",world,"survival","other",2000,points(1,1,3,1,3,3,1,3));
        check(concave.overlapsInterior(contained),"Complete containment is recognized as an overlap");
        Territory crossing = new Territory("crossing",world,"survival","other",2000,points(10,1,15,1,15,3,10,3));
        check(concave.overlapsInterior(crossing),"Crossing polygon interiors are recognized");
        Path directory = Files.createTempDirectory("wiut-territory-check-");
        Path file = directory.resolve("territories.yml");
        TerritoryStore store = new TerritoryStore(file);
        store.put(concave,false);store.put(neighbor,false);
        check(store.at(world,12,2).getFirst().id().equals("alpha"),"A shared boundary has deterministic ID ordering");
        byte[] saved = Files.readAllBytes(file);
        try { store.put(crossing,false);throw new AssertionError("Overlap was accepted"); }
        catch (IllegalArgumentException expected) {
            check(java.util.Arrays.equals(saved,Files.readAllBytes(file)) && store.get("crossing")==null,"Rejected overlaps change neither the file nor live state");
        }
        UUID otherWorld = UUID.randomUUID();
        store.put(new Territory("different-world",otherWorld,"creative","poly",1000,outline),false);
        check(store.at(otherWorld,2,10).size()==1 && store.at(UUID.randomUUID(),2,10).isEmpty(),"Equal coordinates are isolated by world UUID");
        ArrayList<Vertex> many = new ArrayList<>();
        for (int index=0;index<10000;index++) {
            double angle=2*Math.PI*index/10000;
            many.add(new Vertex((int)Math.round(1_000_000*Math.cos(angle)),(int)Math.round(1_000_000*Math.sin(angle))));
        }
        UUID detailedWorld = UUID.randomUUID();
        store.put(new Territory("detailed",detailedWorld,"detail","poly",1000,many),false);
        TerritoryStore loaded = new TerritoryStore(file);
        check(loaded.get("detailed").vertices().size()==10000 && loaded.get("detailed").contains(0,0),"A 10000-vertex polygon survives save and reload without a vertex cap");
        check(loaded.get("alpha").vertices().equals(outline) && loaded.get("alpha").clanFounded()==1000,"Reload preserves exact vertices and clan incarnation");
        loaded.remove("neighbor");
        check(new TerritoryStore(file).get("neighbor")==null,"Deletion remains effective after restart");
        Path invalid = directory.resolve("invalid.yml");
        Files.writeString(invalid,"version: 1\nterritories: wrong\n");
        byte[] invalidBytes=Files.readAllBytes(invalid);
        rejected(() -> { try { new TerritoryStore(invalid); } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException error) { throw new RuntimeException(error); } },"Malformed storage fails closed");
        check(java.util.Arrays.equals(invalidBytes,Files.readAllBytes(invalid)),"A malformed file is retained unchanged");
        Path blocked=directory.resolve("blocked.yml");
        TerritoryStore failing = new TerritoryStore(blocked);
        Files.createDirectory(blocked);Files.writeString(blocked.resolve("sentinel"),"retain");
        try { failing.put(concave,false);throw new AssertionError("Expected I/O failure"); }
        catch (java.io.IOException expected) { check(failing.all().isEmpty(),"Failed disk persistence never activates a territory"); }
        System.out.println("GEOMETRY_CHECKS_PASSED="+checks);
    }
}
