package com.westshield.clans.territory;

import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.HtmlMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.sacredlabyrinth.phaed.simpleclans.Clan;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

/** Publishes the same polygon vertices and native clan colors as one toggleable layer. */
public final class BlueMapBridge implements AutoCloseable {
    public static final String LAYER="westshield-clans";
    private final JavaPlugin plugin;
    private final TerritoryStore store;
    private final Function<Territory,Clan> resolve;
    private final Function<Clan,Integer> color;
    private final Consumer<BlueMapAPI> enableListener;
    private final Consumer<BlueMapAPI> disableListener;
    private BlueMapAPI api;
    private List<String> previous=List.of();

    public BlueMapBridge(JavaPlugin plugin,TerritoryStore store,Function<Territory,Clan> resolve,
                         Function<Clan,Integer> color) {
        this.plugin=plugin;this.store=store;this.resolve=resolve;this.color=color;
        enableListener=active -> Bukkit.getScheduler().runTask(plugin,() -> {
            api=active;previous=List.of();refresh();
        });
        disableListener=ignored -> Bukkit.getScheduler().runTask(plugin,() -> { api=null;previous=List.of(); });
        BlueMapAPI.onEnable(enableListener);
        BlueMapAPI.onDisable(disableListener);
    }

    public void refresh() {
        if (api==null) return;
        ArrayList<String> signature=new ArrayList<>();
        signature.add(Long.toString(store.revision()));
        for (BlueMapMap map:api.getMaps()) signature.add("map:"+map.getId());
        for (Territory territory:store.all()) {
            Clan clan=resolve.apply(territory);
            signature.add(territory.id()+":"+(clan==null?"inactive":clan.getTag()+":"+clan.getFounded()+":"+clan.getName()+":"+color.apply(clan)));
        }
        if (signature.equals(previous)) return;
        Map<String,MarkerSet> layers=new LinkedHashMap<>();
        for (BlueMapMap map:api.getMaps()) {
            layers.put(map.getId(),MarkerSet.builder().label("Clan territories").toggleable(true)
                    .defaultHidden(false).sorting(100).build());
        }
        for (Territory territory:store.all()) {
            Clan clan=resolve.apply(territory);
            var world=Bukkit.getWorld(territory.world());
            if (clan==null || world==null) continue;
            var mappedWorld=api.getWorld(world);
            if (mappedWorld.isEmpty()) continue;
            int rgb=color.apply(clan);
            String name=escape(ChatColor.stripColor(clan.getName()).replaceAll("[\\p{Cntrl}]",""));
            Shape outline=new Shape(territory.vertices().stream().map(point -> new Vector2d(point.x(),point.z())).toList());
            double[] anchor=territory.labelPoint();
            int brightness=((rgb>>16&255)*299+(rgb>>8&255)*587+(rgb&255)*114)/1000;
            String backdrop=brightness<140?"rgba(255,255,255,.88)":"rgba(0,0,0,.68)";
            String html="<span style=\"display:block;white-space:nowrap;font:700 15px/1.3 sans-serif;"
                    +"padding:3px 7px;border-radius:5px;pointer-events:none;user-select:none;"
                    +"color:"+ClanColors.hex(rgb)+";background:"+backdrop+"\">"+name+"</span>";
            for (BlueMapMap map:mappedWorld.get().getMaps()) {
                MarkerSet layer=layers.get(map.getId());
                if (layer==null) continue;
                layer.getMarkers().put(territory.id()+"-area",ShapeMarker.builder()
                        .label(name).listed(false).shape(outline,64).centerPosition().detail("")
                        .depthTestEnabled(false).lineWidth(2).lineColor(new Color(rgb,.9f))
                        .fillColor(new Color(rgb,.18f)).build());
                layer.getMarkers().put(territory.id()+"-name",HtmlMarker.builder().label(name).listed(false)
                        .position(anchor[0],65,anchor[1]).html(html).styleClasses("westshield-clan-label").build());
            }
        }
        for (BlueMapMap map:api.getMaps()) map.getMarkerSets().put(LAYER,layers.get(map.getId()));
        previous=List.copyOf(signature);
    }

    private static String escape(String text) {
        return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }

    public boolean ready() { return api!=null; }
    @Override public void close() {
        BlueMapAPI.unregisterListener(enableListener);BlueMapAPI.unregisterListener(disableListener);
        if (api!=null) for(BlueMapMap map:api.getMaps()) map.getMarkerSets().remove(LAYER);
        api=null;
    }
}
