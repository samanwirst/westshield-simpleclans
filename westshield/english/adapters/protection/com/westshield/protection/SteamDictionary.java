package com.westshield.protection;

import com.google.gson.JsonParser;
import com.google.re2j.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** Owner-supplied Steam vocabulary. No Steam client, network call or Valve data is embedded in the JAR. */
public final class SteamDictionary {
    private final List<String> words,allow;
    private final Set<String> allowed;
    private final Pattern pattern;
    private final int languages,patterns;
    private record View(String text,List<Integer> starts,List<Integer> ends) { }

    private SteamDictionary(List<String> words,List<String> allow,Pattern pattern,int languages,int patterns){
        this.words=List.copyOf(words);this.allow=List.copyOf(allow);this.pattern=pattern;this.languages=languages;this.patterns=patterns;
        Set<String> keys=new HashSet<>();allow.forEach(word->keys.add(WordFilter.canonical(word)));allowed=Set.copyOf(keys);
    }
    public static SteamDictionary empty(){return new SteamDictionary(List.of(),List.of(),null,0,0);}
    public static SteamDictionary load(Path directory)throws Exception{
        Path manifestFile=directory.resolve("manifest.json");
        if(Files.size(manifestFile)>65536)throw new IllegalArgumentException("Oversized dictionary manifest");
        var manifest=JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();
        if(manifest.get("version").getAsInt()!=1)throw new IllegalArgumentException("Unsupported external dictionary version");
        Map<String,List<String>> loaded=new HashMap<>();
        for(String name:List.of("words.txt","patterns.txt","allow-words.txt")){
            Path file=directory.resolve(name);if(Files.size(file)>4*1024*1024)throw new IllegalArgumentException("Oversized external dictionary");
            byte[] bytes=Files.readAllBytes(file);String expected=manifest.getAsJsonObject("files").getAsJsonObject(name).get("sha256").getAsString();
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new IllegalArgumentException("External dictionary checksum mismatch: "+name);
            List<String> entries=Files.readAllLines(file).stream().filter(word->!word.isBlank()).toList();
            if(entries.size()>50000 || entries.stream().anyMatch(word->word.length()>256))throw new IllegalArgumentException("External dictionary bounds exceeded");
            loaded.put(name,entries);
        }
        List<String> rules=loaded.get("patterns.txt");
        if(rules.size()>1000)throw new IllegalArgumentException("Too many external patterns");
        String combined=String.join("|",rules.stream().map(SteamDictionary::normaliseRule).map(rule->"(?:"+rule+")").toList());
        Pattern compiled=rules.isEmpty()?null:Pattern.compile(combined,Pattern.CASE_INSENSITIVE|Pattern.LONGEST_MATCH);
        if(compiled!=null && compiled.matcher("").find())throw new IllegalArgumentException("External pattern matches empty text");
        return new SteamDictionary(loaded.get("words.txt"),loaded.get("allow-words.txt"),compiled,manifest.getAsJsonArray("languages").size(),rules.size());
    }
    public List<String> words(){return words;}
    public List<String> allowWords(){return allow;}
    public int languageCount(){return languages;}
    public int patternCount(){return patterns;}

    private static String normaliseRule(String original){
        StringBuilder result=new StringBuilder();
        for(int offset=0;offset<original.length();){
            int point=original.codePointAt(offset);String value=new String(Character.toChars(point));
            result.append(point>=128?view(value).text():value);offset+=Character.charCount(point);
        }
        // Steam's Unicode word wildcards must include Cyrillic, rather than RE2's default ASCII \w.
        return result.toString().replace("\\w","[\\p{L}\\p{M}\\p{N}_]").replace("\\W","[^\\p{L}\\p{M}\\p{N}_]");
    }
    private static View view(String original){
        StringBuilder result=new StringBuilder();List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();
        for(int offset=0;offset<original.length();){
            int point=original.codePointAt(offset),end=offset+Character.charCount(point);
            String value=Normalizer.normalize(new String(Character.toChars(point)),Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
            for(int i=0;i<value.length();){
                int c=value.codePointAt(i);i+=Character.charCount(c);
                if(Character.getType(c)==Character.FORMAT || c>=0x0300 && c<=0x036f)continue;
                String chars=new String(Character.toChars(c));result.append(chars);
                for(int j=0;j<chars.length();j++){starts.add(offset);ends.add(end);}
            }
            offset=end;
        }
        return new View(result.toString(),starts,ends);
    }
    private static boolean letter(int point){int kind=Character.getType(point);return Character.isLetterOrDigit(point)||kind==Character.NON_SPACING_MARK||kind==Character.COMBINING_SPACING_MARK;}
    public String censor(String input){
        if(pattern==null || input.isEmpty())return input;
        View view=view(input);var matcher=pattern.matcher(view.text());StringBuilder result=new StringBuilder();int position=0;
        while(matcher.find()){
            int start=matcher.start(),end=matcher.end();if(start==end)continue;
            int first=view.text().codePointAt(start);
            boolean unspaced=Character.UnicodeScript.of(first)==Character.UnicodeScript.HAN||Character.UnicodeScript.of(first)==Character.UnicodeScript.HIRAGANA||Character.UnicodeScript.of(first)==Character.UnicodeScript.KATAKANA||Character.UnicodeScript.of(first)==Character.UnicodeScript.HANGUL||Character.UnicodeScript.of(first)==Character.UnicodeScript.THAI;
            if(!unspaced && (start>0 && letter(view.text().codePointBefore(start)) || end<view.text().length() && letter(view.text().codePointAt(end))))continue;
            int from=view.starts().get(start),to=view.ends().get(end-1);
            if(allowed.contains(WordFilter.canonical(input.substring(from,to))))continue;
            result.append(input,position,from).append("****");position=to;
        }
        return result.append(input,position,input.length()).toString();
    }
}
