package com.wiut.english;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisconnect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.translation.TranslationStore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Render server text without changing client settings, chat signatures or item data. */
public final class WiutEnglish extends JavaPlugin {
    private static final Pattern ARGUMENT=Pattern.compile("%((\\d+)\\$)?s|%%");
    private final AtomicLong rewritten=new AtomicLong();
    private final AtomicLong failures=new AtomicLong();
    private TranslatableComponentRenderer<Locale> renderer;
    private PacketListenerAbstract listener;
    private int dictionarySize;

    static String messagePattern(String template) {
        Matcher matcher=ARGUMENT.matcher(template);
        StringBuilder result=new StringBuilder();int offset=0;int sequential=0;
        while(matcher.find()) {
            result.append(escape(template.substring(offset,matcher.start())));
            if(matcher.group().equals("%%")) result.append('%');
            else {
                int position=matcher.group(2)==null?sequential++:Integer.parseInt(matcher.group(2))-1;
                result.append('{').append(position).append('}');
            }
            offset=matcher.end();
        }
        return result.append(escape(template.substring(offset))).toString();
    }
    private static String escape(String text) {
        return text.replace("'","''").replace("{","'{'" ).replace("}","'}'");
    }
    @Override public void onEnable() {
        var dictionary=TranslationStore.messageFormat(Key.key("wiut","english"));
        dictionary.defaultLocale(Locale.US);
        try(var expected=Objects.requireNonNull(getResource("english-dictionary.sha256"))) {
            var path=getDataFolder().toPath().getParent().resolve("Geyser-Spigot/locales/en_us.json");
            byte[] data=Files.readAllBytes(path);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            if(!hash.equals(new String(expected.readAllBytes(),StandardCharsets.US_ASCII).trim()))
                throw new IllegalStateException("English dictionary version changed; rebuild against the installed Geyser dictionary");
            Map<String,String> messages=new Gson().fromJson(new String(data,StandardCharsets.UTF_8),new TypeToken<Map<String,String>>(){}.getType());
            for(var entry:messages.entrySet())dictionary.register(entry.getKey(),Locale.US,new MessageFormat(messagePattern(entry.getValue()),Locale.US));
            dictionarySize=messages.size();
            if(dictionarySize<1000 || !messages.containsKey("command.unknown.command"))throw new IllegalStateException("Incomplete English dictionary");
        } catch(Exception error) {throw new IllegalStateException("Could not load the pinned English message dictionary",error);}
        renderer=TranslatableComponentRenderer.usingTranslationSource(dictionary);
        listener=new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override public void onPacketSend(PacketSendEvent event) {
                try {
                    var type=event.getPacketType();
                    if(type==PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
                        var packet=new WrapperPlayServerSystemChatMessage(event);translate(event,packet.getMessage(),packet::setMessage);
                    } else if(type==PacketType.Play.Server.ACTION_BAR) {
                        var packet=new WrapperPlayServerActionBar(event);translate(event,packet.getActionBarText(),packet::setActionBarText);
                    } else if(type==PacketType.Play.Server.OPEN_WINDOW) {
                        var packet=new WrapperPlayServerOpenWindow(event);translate(event,packet.getTitle(),packet::setTitle);
                    } else if(type==PacketType.Play.Server.DISCONNECT) {
                        var packet=new WrapperPlayServerDisconnect(event);translate(event,packet.getReason(),packet::setReason);
                    } else if(type==PacketType.Login.Server.DISCONNECT) {
                        var packet=new WrapperLoginServerDisconnect(event);translate(event,packet.getReason(),packet::setReason);
                    }
                } catch(RuntimeException error) {
                    // Keep the original packet if a future protocol changes; never cancel delivery.
                    if(failures.incrementAndGet()<=3)getLogger().warning("English message rendering failed: "+error.getClass().getSimpleName());
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        getLogger().info("English server messages enabled; dictionary-keys="+dictionarySize);
    }
    private void translate(PacketSendEvent event,Component original,Consumer<Component> setter) {
        Component english=renderer.render(original,Locale.US);
        if(!english.equals(original)) {setter.accept(english);event.markForReEncode(true);rewritten.incrementAndGet();}
    }
    @Override public void onDisable() {
        if(listener!=null)PacketEvents.getAPI().getEventManager().unregisterListener(listener);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        sender.sendMessage("WIUT English: active="+(listener!=null)+"; locale=en-US; dictionary-keys="+dictionarySize
                +"; translated-components="+rewritten.get()+"; failures="+failures.get());
        return true;
    }
}
