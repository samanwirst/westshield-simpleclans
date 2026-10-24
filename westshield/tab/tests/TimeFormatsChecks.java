import com.westshield.tab.TimeFormats;
import java.time.*;
public final class TimeFormatsChecks {
 private static int checks;
 private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  ZoneId zone=ZoneId.of("Asia/Tashkent");
  Instant before=Instant.parse("2026-10-24T18:59:59Z");
  Instant after=Instant.parse("2026-10-24T19:00:00Z");
  check(TimeFormats.days(LocalDate.parse("2026-10-14"),before,zone)==10);
  check(TimeFormats.days(LocalDate.parse("2026-10-14"),after,zone)==11);
  check(TimeFormats.age(Instant.parse("2026-10-24T18:59:59Z").toEpochMilli(),after,zone).equals("1d"));
  check(TimeFormats.age(after.toEpochMilli(),before,zone).equals("Unavailable"));
  check(TimeFormats.age(0,after,zone).equals("Unavailable"));
  check(TimeFormats.online(53L*3600000).equals("2d 5h"));
  check(TimeFormats.online(3599999).equals("0d 0h"));
  check(TimeFormats.online(-1).equals("0d 0h"));
  check(TimeFormats.area(64).equals("64"));
  check(TimeFormats.area(12345.5).equals("12,345.5"));
  check(TimeFormats.days(LocalDate.parse("2024-02-28"),Instant.parse("2024-03-01T00:00:00Z"),zone)==2);
  System.out.println("Passed "+checks+" time/area checks");
 }
}
