package com.westshield.protection;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Literal Unicode dictionary matching, with mapped source spans and no executable regex. */
public final class WordFilter {
    private final Node root = new Node();
    private final Set<String> allowed;
    private final String replacement;
    private int size;
    private static final class Node {
        final Map<Character, Node> next = new HashMap<>();
        final List<int[]> lengths = new ArrayList<>();
    }
    private record Run(char letter, List<Span> units) {
        int count() { return units.size(); }
    }
    private record Span(int start, int end) { }

    public WordFilter(Collection<String> words, Collection<String> allow, String replacement) {
        this.replacement = replacement;
        allowed = new HashSet<>();
        allow.forEach(word -> allowed.add(canonical(word)));
        Set<String> unique = new HashSet<>();
        for (String word : words) {
            String key = canonical(word);
            if (key.isEmpty() || !unique.add(key)) continue;
            List<Run> runs = runs(word);
            Node node = root;
            int[] lengths = new int[runs.size()];
            for (int index = 0; index < runs.size(); index++) {
                Run run = runs.get(index);
                node = node.next.computeIfAbsent(run.letter(), ignored -> new Node());
                lengths[index] = run.count();
            }
            node.lengths.add(lengths);
            size++;
        }
    }

    public int size() { return size; }

    private static char fold(char input) {
        return switch (input) {
            case '0' -> 'o'; case '1', '!' -> 'i'; case '3' -> 'e'; case '4', '@' -> 'a';
            case '5', '$' -> 's'; case '7' -> 't';
            case 'а' -> 'a'; case 'е', 'є' -> 'e'; case 'о' -> 'o'; case 'р' -> 'p';
            case 'с' -> 'c'; case 'х' -> 'x'; case 'у' -> 'y'; case 'і', 'ї' -> 'i'; case 'ј' -> 'j';
            default -> input;
        };
    }

    private static List<Run> runs(String text) {
        ArrayList<Run> result = new ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            int end = offset + Character.charCount(point);
            String letters = Normalizer.normalize(new String(Character.toChars(point)), Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
            for (char input : letters.toCharArray()) {
                char letter = fold(input);
                int type=Character.getType(letter);
                boolean mark=(type==Character.NON_SPACING_MARK || type==Character.COMBINING_SPACING_MARK) && !(letter>=0x0300 && letter<=0x036f);
                if (!Character.isLetterOrDigit(letter) && !mark) continue;
                if (!result.isEmpty() && result.getLast().letter() == letter) {
                    Run last = result.removeLast();
                    last.units().add(new Span(offset,end));result.add(last);
                } else result.add(new Run(letter, new ArrayList<>(List.of(new Span(offset,end)))));
            }
            offset = end;
        }
        return result;
    }

    static String canonical(String word) {
        StringBuilder out = new StringBuilder();
        for (Run run : runs(word)) out.append(String.valueOf(run.letter()).repeat(run.count()));
        return out.toString();
    }

    public String censor(String text) {
        List<Run> input = runs(text);
        ArrayList<Span> spans = new ArrayList<>();
        for (int start = 0; start < input.size(); start++) {
            Node node = root;
            for (int end = start; end < input.size() && end - start < 80; end++) {
                node = node.next.get(input.get(end).letter());
                if (node == null) break;
                for (int[] counts : node.lengths) {
                    boolean enough = counts.length == end - start + 1;
                    for (int i = 0; enough && i < counts.length; i++) enough = input.get(start + i).count() >= counts[i];
                    if (!enough) continue;
                    Run first=input.get(start), last=input.get(end);
                    boolean cjk = isCjk(first.letter());
                    for(int a=0;a<=first.count()-counts[0];a++) {
                        int from=first.units().get(a).start();
                        if(!cjk && from>0 && Character.isLetterOrDigit(text.codePointBefore(from)))continue;
                        int firstEnd=start==end?Math.max(a+counts[0]-1,counts[counts.length-1]-1):counts[counts.length-1]-1;
                        for(int b=firstEnd;b<last.count();b++) {
                            if(start==end && b-a+1<counts[0])continue;
                            int to=last.units().get(b).end();
                            if(!cjk && to<text.length() && Character.isLetterOrDigit(text.codePointAt(to)))continue;
                            if(to-from>Math.max(32,counts.length*5))break;
                            if(!allowed.contains(canonical(text.substring(from,to))))spans.add(new Span(from,to));
                        }
                    }
                }
            }
        }
        if (spans.isEmpty()) return text;
        spans.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::end));
        ArrayList<Span> merged = new ArrayList<>();
        for (Span span : spans) {
            if (!merged.isEmpty() && span.start() < merged.getLast().end()) {
                Span previous = merged.removeLast();merged.add(new Span(previous.start(), Math.max(previous.end(), span.end())));
            } else merged.add(span);
        }
        StringBuilder out = new StringBuilder();int position = 0;
        for (Span span : merged) { out.append(text, position, span.start()).append(replacement);position = span.end(); }
        return out.append(text, position, text.length()).toString();
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.THAI;
    }
}
