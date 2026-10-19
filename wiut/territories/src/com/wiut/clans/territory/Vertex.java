package com.wiut.clans.territory;

/** A horizontal block coordinate. Height never affects a territory. */
public record Vertex(int x, int z) {
    public Vertex {
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000) {
            throw new IllegalArgumentException("Vertex coordinates exceed Minecraft's limits.");
        }
    }
}
