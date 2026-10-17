package net.sacredlabyrinth.phaed.simpleclans.acf;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Preserve ACF locale updates through the supported Bukkit/Paper API. */
public class WiutPaperCommandManager extends PaperCommandManager {
    public WiutPaperCommandManager(Plugin plugin) {
        super(plugin);
    }

    @Override
    void readPlayerLocale(Player player) {
        if (!player.isOnline()) return;
        String code = player.locale().toLanguageTag();
        if (!code.equals(issuersLocaleString.put(player.getUniqueId(), code))) {
            setPlayerLocale(player, player.locale());
        }
    }
}
