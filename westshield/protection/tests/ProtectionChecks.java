import com.westshield.protection.WordFilter;
import com.westshield.protection.StrikeLedger;
import com.westshield.protection.ChatScope;
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
  UUID other=UUID.randomUUID();ledger.record(other,"OtherFixture",UUID.randomUUID(),"GrimAC","Other");
  UUID pardon=ledger.pardon(id,"Fixture","console","False positive reviewed");
  check("Pardon resets the live counter without a reload",ledger.count(id)==0 && ledger.bannedAccounts()==0);
  check("Pardon preserves unrelated counters",ledger.count(other)==1);
  var saved=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(path.toFile());
  check("Pardon keeps the three original receipts",saved.getConfigurationSection("pardons."+pardon+".previous.receipts").getKeys(false).size()==3);
  check("Pardon records its actor and reason",saved.getString("pardons."+pardon+".actor").equals("console") && saved.getString("pardons."+pardon+".reason").equals("False positive reviewed"));
  check("A cleared account has no active name match",ledger.accountsNamed("Fixture").isEmpty());
  ledger=new StrikeLedger(path);check("Pardon survives restart",ledger.count(id)==0 && ledger.count(other)==1);
  check("An archived kick receipt cannot count again",ledger.record(id,"Fixture",event,"GrimAC","Test")==0);
  check("A new kick starts a fresh cycle at one",ledger.record(id,"Fixture",UUID.randomUUID(),"GrimAC","New")==1);
  check("Account lookup ignores casing",ledger.accountsNamed("fIxTuRe").equals(Set.of(id)));
  ledger.record(id,"Fixture",UUID.randomUUID(),"GrimAC","New");ledger.record(id,"Fixture",UUID.randomUUID(),"GrimAC","New");
  check("Three new kicks can ban again after pardon",ledger.count(id)==3 && ledger.bannedAccounts()==1);
  ledger.pardon(id,"Fixture","console","Second review");saved=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(path.toFile());
  check("A later pardon preserves both audit cycles",saved.getConfigurationSection("pardons").getKeys(false).size()==2 && saved.getConfigurationSection("pardons."+pardon+".previous.receipts").getKeys(false).size()==3);
  Path blockedPardon=base.resolve("pardon-blocked");var cannotPardon=new StrikeLedger(blockedPardon.resolve("strikes.yml"));cannotPardon.record(id,"Fixture",UUID.randomUUID(),"GrimAC","Test");Files.move(blockedPardon,base.resolve("pardon-backup"));Files.writeString(blockedPardon,"not a directory");boolean pardonFailed=false;
  try{cannotPardon.pardon(id,"Fixture","console","Test");}catch(Exception expected){pardonFailed=true;}
  check("Failed pardon persistence keeps the live sanction",pardonFailed && cannotPardon.count(id)==1 && cannotPardon.accountsNamed("Fixture").contains(id));
  UUID world=UUID.randomUUID(),nether=UUID.randomUUID();
  check("Plain local message keeps its exact text",!ChatScope.global("hello !") && ChatScope.content("hello !").equals("hello !"));
  check("Leading bang selects global and removes only the prefix",ChatScope.global("! hello") && ChatScope.content("! hello").equals("hello"));
  check("Another bang in global text remains",ChatScope.content("!!hello").equals("!hello"));
  check("Empty global message is identifiable",ChatScope.content("!  ").isBlank());
  check("Local chat includes its 100-block boundary",ChatScope.receives(false,world,0,0,0,world,100,0,0,10000));
  check("Local chat excludes positions beyond 100 blocks",!ChatScope.receives(false,world,0,0,0,world,100.01,0,0,10000));
  check("Local chat uses 3D distance",!ChatScope.receives(false,world,0,0,0,world,80,0,80,10000) && !ChatScope.receives(false,world,0,0,0,world,0,101,0,10000));
  check("Local chat never crosses dimensions",!ChatScope.receives(false,world,0,0,0,nether,0,0,0,10000));
  check("Global chat crosses dimensions and distances",ChatScope.receives(true,world,0,0,0,nether,10000,200,10000,10000));
  Path bad=base.resolve("bad.yml");Files.writeString(bad,"version: 2\n");boolean refused=false;try{new StrikeLedger(bad);}catch(Exception expected){refused=true;}check("Unknown ledger schema fails closed without rewriting",refused && Files.readString(bad).equals("version: 2\n"));
  Path blocked=base.resolve("blocked");var failing=new StrikeLedger(blocked.resolve("strikes.yml"));Files.writeString(blocked,"not a directory");boolean failed=false;try{failing.record(id,"Fixture",UUID.randomUUID(),"GrimAC","Test");}catch(Exception expected){failed=true;}check("Failed persistence does not activate a counter",failed && failing.count(id)==0);
  System.out.println("PROTECTION_CHECKS="+checks);
 }
}
