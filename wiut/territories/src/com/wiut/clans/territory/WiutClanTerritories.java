package com.wiut.clans.territory;

import fr.xephi.authme.api.v3.AuthMeApi;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.events.PreCreateClanEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.TagChangeEvent;
import net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Administrative polygon labels. No territory handler cancels a gameplay event. */
public final class WiutClanTerritories extends JavaPlugin implements Listener {
    public static final String ADMIN = "wiutclans.admin";
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, Presence> presence = new HashMap<>();
    private TerritoryStore store;
    private SimpleClans clans;
    private NamespacedKey wandKey;
    private BlueMapBridge blueMap;

    private static final class Selection {
        final UUID world;
        final String worldName;
        final ArrayList<Vertex> points = new ArrayList<>();
        Selection(Location location) {
            world = location.getWorld().getUID();
            worldName = location.getWorld().getName();
        }
    }
    private record Presence(String identity, String name, int rgb) { }

    @Override public void onEnable() {
        try {
            clans = (SimpleClans) Bukkit.getPluginManager().getPlugin("SimpleClans");
            wandKey = new NamespacedKey(this,"selection-wand");
            store = new TerritoryStore(getDataFolder().toPath().resolve("territories.yml"));
            blueMap = new BlueMapBridge(this,store,this::currentClan,this::clanColor);
            Bukkit.getPluginManager().registerEvents(this,this);
            Bukkit.getScheduler().runTaskTimer(this,this::observePlayers,10L,10L);
            getLogger().info("Loaded " + store.all().size() + " informational polygon territories; protection=false");
        } catch (Exception failure) {
            getLogger().log(java.util.logging.Level.SEVERE,"Territory configuration could not be loaded; existing file retained",failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private boolean authenticated(Player player) { return AuthMeApi.getInstance().isAuthenticated(player); }
    private int clanColor(Clan clan) {
        return ClanColors.rgb(clan,clans.getSettingsManager().getColored(ConfigField.TAG_DEFAULT_COLOR));
    }
    private boolean admin(Player player) { return player.hasPermission(ADMIN) && authenticated(player); }
    private boolean isWand(ItemStack item) {
        return item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey,PersistentDataType.BYTE);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPreCreate(PreCreateClanEvent event) {
        if (!admin(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Создавать кланы может только вошедший администратор.");
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onTagChange(TagChangeEvent event) {
        if (!admin(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Цвет и оформление клана меняет администрация.");
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onWand(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isWand(event.getItem()) || !admin(event.getPlayer())) return;
        // Only an explicitly marked administrator tool consumes clicks, regardless of any territory.
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && event.getClickedBlock() != null) {
            event.setCancelled(true);
            try { addPoint(event.getPlayer(),event.getClickedBlock().getLocation()); }
            catch (IllegalArgumentException failure) { event.getPlayer().sendMessage(failure.getMessage()); }
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
            event.setCancelled(true);
            undo(event.getPlayer());
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onWandBreak(BlockBreakEvent event) {
        if (admin(event.getPlayer()) && isWand(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
        presence.remove(event.getPlayer().getUniqueId());
    }

    private void addPoint(Player player, Location location) {
        Selection selection = selections.computeIfAbsent(player.getUniqueId(),ignored -> new Selection(location));
        if (!selection.world.equals(location.getWorld().getUID())) {
            throw new IllegalArgumentException("Все вершины должны быть в одном мире. /cterr clear — начать заново.");
        }
        Vertex point = new Vertex(location.getBlockX(),location.getBlockZ());
        if (!selection.points.isEmpty() && selection.points.getFirst().equals(point) && selection.points.size() >= 3) {
            player.sendMessage("Последняя вершина соединится с первой автоматически. Сохранить: /cterr save <тег> <id>.");
            return;
        }
        if (selection.points.contains(point)) throw new IllegalArgumentException("Эта вершина уже выбрана.");
        selection.points.add(point);
        player.spawnParticle(Particle.END_ROD,location.clone().add(.5,1.1,.5),6,.15,.2,.15,0);
        player.sendMessage("Вершина " + selection.points.size() + " добавлена. ПКМ или /cterr undo — отменить.");
    }

    private void undo(Player player) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.points.isEmpty()) { player.sendMessage("Выбранных вершин нет.");return; }
        selection.points.removeLast();
        player.sendMessage("Последняя вершина отменена. Осталось: " + selection.points.size() + ".");
    }

    private Clan requireClan(String tag) {
        Clan clan = clans.getClanManager().getClan(tag);
        if (clan == null) throw new IllegalArgumentException("Клан с таким тегом не найден.");
        return clan;
    }

    private Clan currentClan(Territory territory) {
        Clan exact = clans.getClanManager().getClan(territory.clanTag());
        if (exact != null && exact.getFounded() == territory.clanFounded()) return exact;
        // A tag rename preserves founding time; tag reuse by a new clan must not inherit the territory.
        List<Clan> renamed = clans.getClanManager().getClans().stream()
                .filter(clan -> clan.getFounded() == territory.clanFounded()).toList();
        return renamed.size() == 1 ? renamed.getFirst() : null;
    }

    private Territory requireTerritory(String id) {
        Territory territory = store.get(id);
        if (territory == null) throw new IllegalArgumentException("Территория не найдена.");
        return territory;
    }

    private void saveSelection(Player player, String tag, String id, boolean replace) throws IOException {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null) throw new IllegalArgumentException("Сначала отметьте вершины жезлом или /cterr point.");
        if (!selection.world.equals(player.getWorld().getUID())) throw new IllegalArgumentException("Вернитесь в мир выделения.");
        Clan clan = requireClan(tag);
        Territory territory = new Territory(id,selection.world,selection.worldName,clan.getTag(),clan.getFounded(),selection.points);
        store.put(territory,replace);
        selections.remove(player.getUniqueId());
        player.sendMessage("Территория " + id + " сохранена для клана " + plain(clan.getName())
                + ". Вершин: " + territory.vertices().size() + ". Это обозначение, не приват.");
        getLogger().info(player.getName() + " " + (replace ? "reshaped" : "created") + " territory " + id + " for " + clan.getTag());
    }

    private static String plain(String value) {
        return ChatColor.stripColor(value).replaceAll("[\\p{Cntrl}]","");
    }

    private void observePlayers() {
        blueMap.refresh();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!authenticated(player)) { presence.remove(player.getUniqueId());continue; }
            Location location = player.getLocation();
            Presence next = null;
            for (Territory territory : store.at(player.getWorld().getUID(),location.getBlockX(),location.getBlockZ())) {
                Clan clan = currentClan(territory);
                if (clan != null) {
                    next = new Presence(Long.toString(clan.getFounded()) + ":" + clan.getTag(),plain(clan.getName()),clanColor(clan));
                    break;
                }
            }
            Presence previous = presence.get(player.getUniqueId());
            if (java.util.Objects.equals(previous,next)) continue;
            if (next != null) {
                player.sendActionBar(Component.text("Территория клана: " + next.name(),TextColor.color(next.rgb())));
                presence.put(player.getUniqueId(),next);
            } else {
                if (previous != null) player.sendActionBar(Component.text("Вы покинули территорию клана: " + previous.name(),NamedTextColor.GRAY));
                presence.remove(player.getUniqueId());
            }
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) { sender.sendMessage("Эта команда доступна только администрации.");return true; }
        if (sender instanceof Player player && !authenticated(player)) {
            player.sendMessage("Сначала войдите: /login или /register.");return true;
        }
        try {
            if (command.getName().equals("clanadmin")) {
                if (args.length > 0 && args[0].equalsIgnoreCase("color")) { changeColor(sender,args);return true; }
                if (!(sender instanceof Player player)) throw new IllegalArgumentException("Создавайте клан из игры под вошедшим администратором.");
                createClanFor(player,args);return true;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender);return true; }
            String action = args[0].toLowerCase(Locale.ROOT);
            if (action.equals("status")) {
                sender.sendMessage("WIUT territories: ready=true; polygons=" + store.all().size()
                        + "; protection=false; clan-creation=admin-only; fixed-vertex-limit=none; bluemap="+blueMap.ready());return true;
            }
            if (action.equals("list")) {
                for (Territory territory : store.all()) {
                    if (args.length > 1 && !territory.clanTag().equalsIgnoreCase(args[1])) continue;
                    Clan clan = currentClan(territory);
                    sender.sendMessage(territory.id() + " — " + (clan == null ? "клан удалён" : plain(clan.getName()))
                            + " — " + territory.worldName() + " — вершин: " + territory.vertices().size());
                }
                sender.sendMessage("Всего территорий: " + store.all().size());return true;
            }
            if (action.equals("delete") && args.length == 2) {
                store.remove(args[1]);sender.sendMessage("Территория удалена: " + args[1]);
                getLogger().info(sender.getName() + " deleted territory " + args[1]);return true;
            }
            if (action.equals("assign") && args.length == 3) {
                Territory old = requireTerritory(args[1]);Clan clan = requireClan(args[2]);
                store.put(new Territory(old.id(),old.world(),old.worldName(),clan.getTag(),clan.getFounded(),old.vertices()),true);
                sender.sendMessage("Территория передана клану " + plain(clan.getName()));
                getLogger().info(sender.getName() + " reassigned territory " + old.id() + " to " + clan.getTag());return true;
            }
            if (!(sender instanceof Player player)) throw new IllegalArgumentException("Выбор вершин доступен из игры.");
            switch (action) {
                case "wand" -> {
                    ItemStack wand = ItemStack.of(Material.BLAZE_ROD);
                    wand.editMeta(meta -> {
                        meta.displayName(Component.text("Разметка территории клана",NamedTextColor.GOLD));
                        meta.lore(List.of(Component.text("ЛКМ по блоку — добавить вершину"),Component.text("ПКМ — отменить последнюю вершину")));
                        meta.getPersistentDataContainer().set(wandKey,PersistentDataType.BYTE,(byte)1);
                    });
                    if (player.getInventory().addItem(wand).isEmpty()) player.sendMessage("Жезл выдан. Обойдите границу, отмечая вершины по порядку.");
                    else throw new IllegalArgumentException("Освободите ячейку инвентаря.");
                }
                case "point" -> {
                    if (args.length == 1) addPoint(player,player.getLocation());
                    else if (args.length == 3) addPoint(player,new Location(player.getWorld(),Integer.parseInt(args[1]),player.getY(),Integer.parseInt(args[2])));
                    else throw new IllegalArgumentException("/cterr point [x z]");
                }
                case "undo" -> undo(player);
                case "clear" -> { selections.remove(player.getUniqueId());player.sendMessage("Выделение очищено."); }
                case "save" -> {
                    if (args.length != 3) throw new IllegalArgumentException("/cterr save <тег клана> <id территории>");
                    saveSelection(player,args[1],args[2],false);
                }
                case "reshape" -> {
                    if (args.length != 2) throw new IllegalArgumentException("/cterr reshape <id территории>");
                    Territory old = requireTerritory(args[1]);
                    Clan clan = currentClan(old);
                    if (clan == null) throw new IllegalArgumentException("Клан удалён. Сначала /cterr assign <id> <тег>.");
                    saveSelection(player,clan.getTag(),old.id(),true);
                }
                case "selection" -> {
                    Selection selected = selections.get(player.getUniqueId());
                    player.sendMessage(selected == null ? "Выделения нет." : "Мир: " + selected.worldName + "; вершин: " + selected.points.size());
                }
                case "info" -> {
                    List<Territory> found = args.length == 2 ? List.of(requireTerritory(args[1]))
                            : store.at(player.getWorld().getUID(),player.getLocation().getBlockX(),player.getLocation().getBlockZ());
                    if (found.isEmpty()) player.sendMessage("Здесь нет территории клана.");
                    for (Territory territory : found) {
                        Clan clan = currentClan(territory);
                        player.sendMessage(territory.id() + ": " + (clan == null ? "клан удалён" : plain(clan.getName()))
                                + "; мир " + territory.worldName() + "; вершин " + territory.vertices().size()
                                + "; площадь " + territory.area() + "; защита выключена.");
                    }
                }
                default -> help(sender);
            }
        } catch (IllegalArgumentException failure) {
            sender.sendMessage(failure instanceof NumberFormatException ? "Координаты должны быть целыми числами." : failure.getMessage());
        } catch (IOException failure) {
            sender.sendMessage("Не удалось сохранить территорию. Изменение не применено; сообщите администрации.");
            getLogger().log(java.util.logging.Level.SEVERE,"Territory persistence failed",failure);
        }
        return true;
    }

    private void createClanFor(Player administrator, String[] args) {
        if (args.length < 4 || !args[0].equalsIgnoreCase("create")) {
            throw new IllegalArgumentException("/clanadmin create <тег> <онлайн-лидер> <название клана>");
        }
        String tag = args[1];Player leader = Bukkit.getPlayerExact(args[2]);
        if (leader == null || !authenticated(leader)) throw new IllegalArgumentException("Будущий лидер должен быть онлайн и войти через AuthMe.");
        if (clans.getClanManager().isClan(tag)) throw new IllegalArgumentException("Этот тег клана уже занят.");
        var membership = clans.getClanManager().getAnyClanPlayer(leader.getUniqueId());
        if (membership != null && membership.getClan() != null) throw new IllegalArgumentException("Будущий лидер уже состоит в клане.");
        var invalidTag = clans.getTagValidator().validate(administrator,tag);
        if (invalidTag.isPresent()) throw new IllegalArgumentException(invalidTag.get());
        String name = String.join(" ",Arrays.copyOfRange(args,3,args.length));
        int min = clans.getSettingsManager().getInt(ConfigField.CLAN_MIN_LENGTH);
        int max = clans.getSettingsManager().getInt(ConfigField.CLAN_MAX_LENGTH);
        if (name.length() <= min || name.length() > max || name.contains("&") || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Название: " + (min+1) + "–" + max + " символов без цветовых кодов.");
        }
        PreCreateClanEvent proposed = new PreCreateClanEvent(administrator,tag,name);
        Bukkit.getPluginManager().callEvent(proposed);
        if (proposed.isCancelled()) throw new IllegalArgumentException("Создание клана отменено сервером.");
        var freshMembership = clans.getClanManager().getAnyClanPlayer(leader.getUniqueId());
        if (!admin(administrator) || !leader.isOnline() || !authenticated(leader)
                || clans.getClanManager().isClan(tag) || freshMembership != null && freshMembership.getClan() != null) {
            throw new IllegalArgumentException("Права или состав кланов изменились; проверьте состояние и повторите команду.");
        }
        clans.getClanManager().createClan(leader,tag,name);
        administrator.sendMessage("Клан " + name + " создан. Лидер: " + leader.getName());
        getLogger().info(administrator.getName() + " created clan " + tag + " for " + leader.getName());
    }

    private void changeColor(CommandSender administrator,String[] args) {
        if (args.length!=3 || !args[2].matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("/clanadmin color <тег> #RRGGBB — например #2A9D8F.");
        }
        Clan clan=requireClan(args[1]);
        clan.changeClanTag("&"+args[2].toUpperCase(Locale.ROOT)+clan.getTag());
        for(Player player:Bukkit.getOnlinePlayers()) {
            if(clans.getClanManager().getClanByPlayerUniqueId(player.getUniqueId())==clan) clans.getClanManager().updateDisplayName(player);
        }
        blueMap.refresh();
        administrator.sendMessage(Component.text("Цвет клана "+plain(clan.getName())+": "+args[2].toUpperCase(Locale.ROOT),TextColor.color(clanColor(clan))));
        getLogger().info(administrator.getName()+" changed clan "+clan.getTag()+" color to "+args[2].toUpperCase(Locale.ROOT));
    }

    @Override public void onDisable() { if(blueMap!=null) blueMap.close(); }

    private void help(CommandSender sender) {
        sender.sendMessage("/cterr wand — жезл: ЛКМ добавляет вершину, ПКМ отменяет последнюю.");
        sender.sendMessage("/cterr point [x z] — добавить вершину командой; /cterr undo; /cterr clear; /cterr selection.");
        sender.sendMessage("/cterr save <тег> <id> — сохранить многоугольник; /cterr reshape <id> — заменить его границу выделением.");
        sender.sendMessage("/cterr assign <id> <тег>; /cterr delete <id>; /cterr list [тег]; /cterr info [id].");
        sender.sendMessage("/clanadmin create <тег> <онлайн-лидер> <название> — создать клан для игрока.");
        sender.sendMessage("/clanadmin color <тег> #RRGGBB — общий цвет тега в чате и территории на BlueMap.");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN) || (sender instanceof Player player && !authenticated(player))) return List.of();
        List<String> choices = List.of();
        if (command.getName().equals("clanadmin")) {
            if (args.length == 1) choices = List.of("create","color");
            else if (args.length == 2 && args[0].equalsIgnoreCase("color")) choices = clans.getClanManager().getClans().stream().map(Clan::getTag).toList();
            else if (args.length == 3 && args[0].equalsIgnoreCase("create")) choices = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        } else if (args.length == 1) {
            choices = List.of("wand","point","undo","clear","selection","save","reshape","assign","delete","list","info","status","help");
        } else if (args.length == 2 && List.of("save","list").contains(args[0].toLowerCase(Locale.ROOT))
                || args.length == 3 && args[0].equalsIgnoreCase("assign")) {
            choices = clans.getClanManager().getClans().stream().map(Clan::getTag).toList();
        } else if (args.length == 2 && List.of("reshape","assign","delete","info").contains(args[0].toLowerCase(Locale.ROOT))) {
            choices = store.all().stream().map(Territory::id).toList();
        }
        String partial = args.length == 0 ? "" : args[args.length-1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(partial)).toList();
    }
}
