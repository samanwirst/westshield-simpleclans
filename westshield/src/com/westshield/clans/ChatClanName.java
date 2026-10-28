package com.westshield.clans;

import java.util.regex.Pattern;
import org.bukkit.ChatColor;

/** Display the clan name with its stored tag styling, retaining the immutable tag. */
public final class ChatClanName {
    private static final Pattern FORMATTING = Pattern.compile("(?i)\u00a7[0-9a-fk-orx]");

    private ChatClanName() {}

    public static String render(String coloredTag, String clanName) {
        StringBuilder styling = new StringBuilder();
        FORMATTING.matcher(coloredTag).results().forEach(match -> styling.append(match.group()));
        // Names are literal text; only the administrator-controlled clan color applies.
        return styling + ChatColor.stripColor(clanName).replace('\n', ' ').replace('\r', ' ');
    }
}
