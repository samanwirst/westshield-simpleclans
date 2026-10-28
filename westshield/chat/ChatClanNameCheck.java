import com.westshield.clans.ChatClanName;

public final class ChatClanNameCheck {
    private static int checks;

    private static void check(String tag, String name, String expected) {
        String actual = ChatClanName.render(tag, name);
        if (!actual.equals(expected)) throw new AssertionError("Unexpected clan label: " + actual);
        checks++;
    }

    public static void main(String[] args) {
        check("§4sndct", "Syndicate", "§4Syndicate");
        check("§x§a§a§0§0§0§0sndct", "Syndicate", "§x§a§a§0§0§0§0Syndicate");
        check("§9§lclub", "Minecraft Club", "§9§lMinecraft Club");
        check("tag", "Builders & Friends", "Builders & Friends");
        check("§4sndct", "§bDifferent color", "§4Different color");
        check("§aabc", "Клуб O‘zbek", "§aКлуб O‘zbek");
        check("§4sndct", "Syndicate\nNew line", "§4Syndicate New line");
        System.out.println("Clan name label checks passed: " + checks);
    }
}
