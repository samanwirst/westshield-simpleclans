package com.westshield.protection;

import java.util.UUID;

/** Routing only; authentication, visibility and event audiences remain host-owned. */
public final class ChatScope {
    private ChatScope() { }
    public static boolean global(String message) { return message.startsWith("!"); }
    public static String content(String message) { return global(message) ? message.substring(1).stripLeading() : message; }
    public static boolean receives(boolean global, UUID sourceWorld, double x, double y, double z,
                                   UUID targetWorld, double tx, double ty, double tz, double squaredRadius) {
        if (global) return true;
        if (!sourceWorld.equals(targetWorld)) return false;
        double dx=x-tx,dy=y-ty,dz=z-tz;
        return dx*dx+dy*dy+dz*dz <= squaredRadius;
    }
}
