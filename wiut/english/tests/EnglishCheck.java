package com.wiut.english;

import java.text.MessageFormat;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.TranslationStore;

/** Verify output meaning and interaction/style retention, not only format syntax. */
public final class EnglishCheck {
    public static void main(String[] args) {
        var dictionary=TranslationStore.messageFormat(Key.key("wiut","english-check"));
        dictionary.register("test",Locale.US,new MessageFormat(WiutEnglish.messagePattern("%2$s's {literal} %% and %1$s"),Locale.US));
        dictionary.register("repeat",Locale.US,new MessageFormat(WiutEnglish.messagePattern("%1$s / %1$s"),Locale.US));
        var renderer=TranslatableComponentRenderer.usingTranslationSource(dictionary);
        var first=Component.text("Alpha",NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/say test"));
        var second=Component.text("Beta",NamedTextColor.BLUE);
        var translated=renderer.render(Component.translatable("test",first,second),Locale.US);
        String plain=PlainTextComponentSerializer.plainText().serialize(translated);
        if(!plain.equals("Beta's {literal} % and Alpha"))throw new AssertionError(plain);
        String json=GsonComponentSerializer.gson().serialize(translated);
        if(!json.contains("red")||!json.contains("blue")||!json.contains("run_command")||!json.contains("/say test"))throw new AssertionError("Lost formatting or action");
        String repeated=PlainTextComponentSerializer.plainText().serialize(renderer.render(Component.translatable("repeat",first),Locale.US));
        if(!repeated.equals("Alpha / Alpha"))throw new AssertionError(repeated);
        var playerText=Component.text("Привет from a player");
        if(!renderer.render(playerText,Locale.US).equals(playerText))throw new AssertionError("Changed literal player text");
        var missing=Component.translatable("unknown.plugin.key").color(NamedTextColor.GREEN);
        if(!renderer.render(missing,Locale.US).equals(missing))throw new AssertionError("Changed an unknown translation key");
        System.out.println("PASS English rendering retains arguments, apostrophes, braces, percent signs, repeated arguments, colors, click actions, literal player text and unknown keys");
    }
}
