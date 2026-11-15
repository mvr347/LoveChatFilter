package me.lovelace.lovechatfilter.filters;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import me.lovelace.lovechat.api.LovechatAPI.LovechatMessageEvent;
import me.lovelace.lovechatfilter.LoveChatFilter;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Reported "жестко/жёстко" false positive, through the real registered chat listener. */
class ReportedSafeWordChatTest {
    ServerMock server;
    LoveChatFilter plugin;
    PlayerMock player, admin;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(LoveChatFilter.class);
        player = server.addPlayer("0nRop_w");
        admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, "lovechatfilter.manage", true);
        // Keep unrelated rate limits/grammar out of this profanity regression.
        for (String module : List.of("anti-spam", "grammar-fix", "anti-caps", "auto-moderation"))
            plugin.getConfig().set("modules." + module, false);
        // Existing servers keep their own expressions. Reproduce a rule matching a fragment
        // inside the innocent word, instead of assuming that their config matches the new default.
        var rules = new ArrayList<>(plugin.getConfig().getStringList("badwords-filter.list"));
        rules.add("(?i)стко");
        plugin.getConfig().set("badwords-filter.list", rules);
        plugin.getFilterEngine().reloadCache();
        for (String spelling : List.of("жестко", "жёстко")) {
            var event = chat(spelling);
            assertTrue(event.getMessage().startsWith(spelling.substring(0, 2)), event.getMessage());
            assertTrue(event.getMessage().contains("👑"), "warm the actual chat cache: " + spelling);
        }
    }

    @AfterEach void stop() { MockBukkit.unmock(); }

    private LovechatMessageEvent chat(String text) {
        var event = new LovechatMessageEvent(player, text, "G");
        server.getPluginManager().callEvent(event);
        return event;
    }

    private void assertBothSpellingsStayUncensored() {
        for (String spelling : List.of("жестко", "жёстко", "Жестко", "Жёстко")) {
            var event = chat(spelling);
            assertFalse(event.isCancelled(), spelling);
            assertEquals(spelling, event.getMessage());
        }
        var mixed = chat("жёстко сука");
        assertTrue(mixed.getMessage().startsWith("жёстко "), mixed.getMessage());
        assertTrue(mixed.getMessage().contains("👑"), "safe must not exempt the rest of the message");
    }

    @Test void gameCommandUpdatesAlreadyCensoredChatForBothSpellings() {
        assertTrue(server.dispatchCommand(admin, "lovechatfilteradmin add safe жестко"));
        assertTrue(plugin.listWords("safe").stream().anyMatch(row -> row.get("word").equals("жестко") && row.get("source").equals("game")));
        assertBothSpellingsStayUncensored();
    }

    @Test void panelApiUpdatesAlreadyCensoredChatAndPreservesTheWordAcrossReload() {
        plugin.addWord("safe", "жестко", "WebAdmin");
        assertBothSpellingsStayUncensored();
        plugin.getWordLists().flush();
        plugin.saveConfig();
        plugin.reloadPlugin();
        assertBothSpellingsStayUncensored();
    }
}
