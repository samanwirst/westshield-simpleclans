import com.westshield.protection.WordFilter;
import com.westshield.protection.StrikeLedger;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class ProtectionChecks {
 static int checks;
 static void check(String label,boolean value) { if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label); }
 public static void main(String[] args) throws Exception {
  var stream=Objects.requireNonNull(WordFilter.class.getClassLoader().getResourceAsStream("words.txt"));
  var words=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).lines().toList();
  var f=new WordFilter(words,List.of(),"****");check("Bundled multilingual literal dictionary loads",f.size()>2000);
  String[] dirty={"fuck","FUCK","f.u.c.k","f u c k","fuuuuuck","fuсk","f\u200buck","sh1t","shit the server","chaff fuck","блядь","пиздец","хуй","blyat","siktir","sikaman","sikish","s!kt!r","сиктир","jalab","jalablar","қанжиқ","scheisse","merde","puta","onangni am"};
  for(String s:dirty)check("Censor: "+s,f.censor(s).contains("****"));
  String[] clean={"I am from Tashkent","WestShield Minecraft Club WestDevs","class assignment","assassin","Scunthorpe","analytics and analysis","siklon sikl siklga","o'qish oʻqish o‘qish","Bugun yaxshi kun","samanwirst Fixinite","анализ аналитика","hello world","qo'yaman tashlayman","किताब","我是学生"};
  for(String s:clean)check("Preserve: "+s,f.censor(s).equals(s));
  check("Only matched spans become exactly four stars",f.censor("hello fuck world").equals("hello **** world"));
  check("Repeat folding does not censor a shorter innocent token",new WordFilter(List.of("ass"),List.of(),"****").censor("as").equals("as"));
  check("Explicit allowlist wins",new WordFilter(List.of("customword"),List.of("customword"),"****").censor("customword").equals("customword"));
  Path base=Files.createTempDirectory("westshield-protection-checks-");Path path=base.resolve("strikes.yml");UUID id=UUID.randomUUID(),event=UUID.randomUUID();
  var ledger=new StrikeLedger(path);check("New player has no strikes",ledger.count(id)==0);
  check("First confirmed receipt increments once",ledger.record(id,"Fixture",event,"GrimAC","Test")==1);
  check("Receipt replay is idempotent",ledger.record(id,"Fixture",event,"GrimAC","Test")==1);
  ledger=new StrikeLedger(path);check("Counter survives reload",ledger.count(id)==1);
  check("Second distinct receipt",ledger.record(id,"Fixture",UUID.randomUUID(),"LightAntiCheat-Plus","Test")==2);
  check("Third receipt reaches permanent-ban threshold",ledger.record(id,"Fixture",UUID.randomUUID(),"GrimAC","Test")==3 && ledger.bannedAccounts()==1);
  check("Counter is capped at three",ledger.record(id,"Fixture",UUID.randomUUID(),"GrimAC","Test")==3);
  check("Other account stays independent",ledger.count(UUID.randomUUID())==0);
  Path bad=base.resolve("bad.yml");Files.writeString(bad,"version: 2\n");boolean refused=false;try{new StrikeLedger(bad);}catch(Exception expected){refused=true;}check("Unknown ledger schema fails closed without rewriting",refused && Files.readString(bad).equals("version: 2\n"));
  Path blocked=base.resolve("blocked");var failing=new StrikeLedger(blocked.resolve("strikes.yml"));Files.writeString(blocked,"not a directory");boolean failed=false;try{failing.record(id,"Fixture",UUID.randomUUID(),"GrimAC","Test");}catch(Exception expected){failed=true;}check("Failed persistence does not activate a counter",failed && failing.count(id)==0);
  System.out.println("PROTECTION_CHECKS="+checks);
 }
}
