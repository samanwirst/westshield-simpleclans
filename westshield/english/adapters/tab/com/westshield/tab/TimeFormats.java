package com.westshield.tab;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Calendar ages and elapsed online duration intentionally use different clocks. */
public final class TimeFormats {
    private TimeFormats() { }
    public static long days(LocalDate start, Instant now, ZoneId zone) {
        return Math.max(0, ChronoUnit.DAYS.between(start, now.atZone(zone).toLocalDate()));
    }
    public static String age(long epochMillis, Instant now, ZoneId zone) {
        return epochMillis > 0 && epochMillis <= now.toEpochMilli()
                ? days(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate(), now, zone) + "d" : "Unavailable";
    }
    public static String online(long milliseconds) {
        long hours = Math.max(0, milliseconds) / 3_600_000L;
        return hours / 24 + "d " + hours % 24 + "h";
    }
    public static String area(double squareBlocks) {
        return String.format(Locale.US, "%,.1f", squareBlocks).replaceFirst("\\.0$", "");
    }
}
