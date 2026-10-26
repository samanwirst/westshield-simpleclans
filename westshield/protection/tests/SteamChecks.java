import com.westshield.protection.SteamDictionary;
import com.westshield.protection.WordFilter;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Real owner-supplied data is provided explicitly; it is never embedded in this test or plugin. */
public final class SteamChecks {
    static int checks;
    static void check(String name,boolean success){System.out.println((success?"PASS ":"FAIL ")+name);if(!success)throw new AssertionError(name);checks++;}
    public static void main(String[] args)throws Exception{
        var steam=SteamDictionary.load(Path.of(args[0]));var words=new ArrayList<String>();
        try(var reader=new BufferedReader(new InputStreamReader(Objects.requireNonNull(WordFilter.class.getClassLoader().getResourceAsStream("words.txt")),StandardCharsets.UTF_8))){reader.lines().forEach(words::add);}
        words.addAll(steam.words());var literal=new WordFilter(words,steam.allowWords(),"****");
        java.util.function.Function<String,String> censor=text->steam.censor(literal.censor(text));
        check("Actual Steam data loads with all imported patterns",steam.languageCount()==29 && steam.patternCount()==357 && steam.words().size()>13000);
        for(String text:new String[]{"чмо","лох","гондон","уебан","сучь","чмошники","гондоны","уебанская","ЧМ0","л0х","г.o.н.д.о.н","у е б а н","yеbаn","fuuuuucking","motherfuuucker","еболапами","сиктир","siktir","jalablar","блээээять"})check("Censor variant: "+text,censor.apply(text).contains("****"));
        for(String text:new String[]{"иди домой","такое бывает","мебель","хлеб","лохматый кот","блоха","блохи","плохой интернет","плохо работает","математика","страховка","достопримечательности","телохранитель","class assignment","assassin","Scunthorpe","I am from Tashkent","gay lesbian bisexual","пенис секс гей","Bugun yaxshi kun","qo'yaman tashlayman","WestShield Minecraft Club WestDevs","книга","我是学生","किताब"})check("Preserve ordinary text: "+text,censor.apply(text).equals(text));
        check("Only the offending words are replaced",censor.apply("иди уебан домой").equals("иди **** домой"));
        check("Separate insults become four stars each",censor.apply("чмо лох гондон").equals("**** **** ****"));
        check("Disabled external dictionaries leave their input untouched",SteamDictionary.empty().censor("ordinary input").equals("ordinary input"));
        Path invalid=Files.createTempDirectory("westshield-steam-integrity-");
        for(String name:List.of("manifest.json","words.txt","patterns.txt","allow-words.txt"))Files.copy(Path.of(args[0]).resolve(name),invalid.resolve(name));
        Files.writeString(invalid.resolve("words.txt"),"changed",StandardOpenOption.APPEND);boolean refused=false;
        try{SteamDictionary.load(invalid);}catch(Exception expected){refused=true;}
        check("Changed external dictionary data fails its pinned checksum",refused);
        long start=System.nanoTime();String stress="a".repeat(255)+"!";
        for(int i=0;i<250;i++)censor.apply(stress);
        long millis=(System.nanoTime()-start)/1_000_000;check("Long benign input is bounded with a non-backtracking engine",millis<15000);
        System.out.println("STEAM_FILTER_CHECKS="+checks+"; stress250-ms="+millis+"; literal-words="+literal.size());
    }
}
