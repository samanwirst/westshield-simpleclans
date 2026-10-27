import com.westshield.loot.LootStore;
import java.nio.file.*;
import java.util.*;
public final class StoreChecks {
 static int checks;
 static void check(boolean b,String name){if(!b)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 static void denied(Checked work,String name)throws Exception{boolean refused=false;try{work.run();}catch(Exception e){refused=true;}check(refused,name);}
 interface Checked{void run()throws Exception;}
 static byte[] b(int i){return new byte[]{(byte)i};}
 public static void main(String[] args)throws Exception{
  Path path=Path.of(args[0]);UUID world=UUID.randomUUID(),id=UUID.randomUUID(),a=UUID.randomUUID(),c=UUID.randomUUID();
  var box=new LootStore.Container(id,world,"BLOCK",null,List.of(new LootStore.Cell(1,60,1,"minecraft:chest","minecraft:chests/simple_dungeon",7)),"minecraft:chests/simple_dungeon",7,false);
  try(var s=new LootStore(path)){
   s.register(box,100);check(s.containers().getFirst().equals(box),"Full natural provenance persists");
   var duplicate=new LootStore.Container(UUID.randomUUID(),world,"BLOCK",null,box.cells(),box.table(),8,false);
   denied(()->s.register(duplicate,100),"The same physical anchor cannot create another reward identity");check(s.count("containers")==1,"Failed registration rolls back atomically");
   check(s.reserve(id,a,11).seed()==11,"First account reserves a random seed");check(s.reserve(id,a,22).seed()==11,"A retry cannot reroll the seed");
   check(s.reserve(id,c,22).seed()==22,"Another canonical account receives its own seed");
   check(Arrays.equals(s.generated(id,a,b(1)).remaining(),b(1)),"Generated loot becomes durable");
   check(Arrays.equals(s.generated(id,a,b(99)).remaining(),b(1)),"Generation cannot overwrite an existing roll");
   s.generated(id,c,b(3));
   var t=new LootStore.Transfer(UUID.randomUUID(),id,a,world,b(10),b(11),b(1),b(2));s.prepare(t);
   check(s.pending(a).id().equals(t.id()),"Unfinished native delivery retains its exact journal");
   check(Arrays.equals(s.roll(id,a).remaining(),b(2)),"Loot is debited before native inventory changes");
   check(s.recover(a,UUID.randomUUID(),b(10))==LootStore.Recovery.REVIEW_REQUIRED,"Another world cannot inherit a recovery decision");
   check(s.recover(a,world,b(90))==LootStore.Recovery.REVIEW_REQUIRED,"Unknown native state never repeats a reward");
   check(s.pending(a)!=null,"Uncertain delivery keeps its durable hold");
   check(s.recover(a,world,b(10))==LootStore.Recovery.RETURNED,"Unissued delivery restores original remaining loot");
   check(Arrays.equals(s.roll(id,a).remaining(),b(1)) && s.pending(a)==null,"Pre-grant recovery restores exactly the prior roll");
   s.prepare(t);check(s.recover(a,world,b(11))==LootStore.Recovery.DELIVERED,"Already saved native delivery is acknowledged without another grant");
   check(Arrays.equals(s.roll(id,a).remaining(),b(2)) && s.pending(a)==null,"Post-grant recovery retains the debit");
   denied(()->s.prepare(t),"A stale debit cannot be admitted again");
   var t2=new LootStore.Transfer(UUID.randomUUID(),id,a,world,b(11),b(12),b(2),b(4));s.prepare(t2);
   s.destroyed(id,"fixture-break");check(s.containers().getFirst().retired(),"Breaking permanently tombstones the container");
   s.rollback(t2);check(s.pending(a)==null,"A fenced transfer can unwind after physical destruction");
   denied(()->s.reserve(id,UUID.randomUUID(),88),"New players cannot loot a destroyed container");
   denied(()->s.register(duplicate,100),"A replacement chest at the same location cannot recreate loot");
  }
  try(var s=new LootStore(path)){
   check(s.containers().getFirst().retired(),"Destruction survives a clean database restart");
   check(Arrays.equals(s.roll(id,a).remaining(),b(2)),"Returning-player history is never reset");
   check(s.roll(id,c).seed()==22,"Other players keep independent permanent rolls");
   UUID next=UUID.randomUUID();var v=new LootStore.Container(next,world,"BLOCK",null,List.of(new LootStore.Cell(3,60,3,"minecraft:barrel","minecraft:chests/shipwreck_supply",1)),"minecraft:chests/shipwreck_supply",1,false);s.register(v,100);
   check(s.reserve(next,a,100).seed()==100,"Old players can discover a different new container");
   denied(()->s.count("rolls; DROP TABLE rolls"),"Metrics cannot inject SQL identifiers");
   check(s.count("rolls")==3,"Historical rolls are not expired or purged");
  }
  try(var s=new LootStore(path)){check(s.reserve(s.containers().stream().filter(x->!x.retired()).findFirst().orElseThrow().id(),a,999).seed()==100,"An interrupted first generation resumes the same seed after restart");}
  System.out.println("Passed "+checks+" storage checks");
 }
}
