package com.westshield.clans.territory;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sacredlabyrinth.phaed.simpleclans.Clan;

/** One source of truth: the color already persisted in SimpleClans' colored tag. */
public final class ClanColors {
    private static final Pattern HEX = Pattern.compile("(?i)^§x((?:§[0-9a-f]){6})");
    private static final int[] LEGACY = {0x000000,0x0000aa,0x00aa00,0x00aaaa,0xaa0000,0xaa00aa,0xffaa00,0xaaaaaa,
            0x555555,0x5555ff,0x55ff55,0x55ffff,0xff5555,0xff55ff,0xffff55,0xffffff};
    private ClanColors() { }
    public static int rgb(Clan clan,String defaultColor) {
        String code = clan.getColorTag();
        Matcher hex = HEX.matcher(code);
        if (hex.find()) return Integer.parseInt(hex.group(1).replace("§",""),16);
        if (code.length() >= 2 && code.charAt(0)=='§') {
            int index = "0123456789abcdef".indexOf(Character.toLowerCase(code.charAt(1)));
            if (index>=0) return LEGACY[index];
        }
        Matcher fallbackHex = HEX.matcher(defaultColor);
        if (fallbackHex.find()) return Integer.parseInt(fallbackHex.group(1).replace("§",""),16);
        for (int offset=defaultColor.length()-2;offset>=0;offset--) {
            if (defaultColor.charAt(offset)=='§') {
                int index="0123456789abcdef".indexOf(Character.toLowerCase(defaultColor.charAt(offset+1)));
                if (index>=0) return LEGACY[index];
            }
        }
        return 0xffffff;
    }
    public static String hex(int rgb) { return String.format(Locale.ROOT,"#%06X",rgb&0xffffff); }
}
