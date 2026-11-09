package me.lovelace.lovechatfilter.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import me.lovelace.lovechatfilter.LoveChatFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** The administration part of the public API that LoveWebAdmin calls. */
class AdminApiTest {
    private ServerMock server;
    private LoveChatFilter plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(LoveChatFilter.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void wordsAreAddedListedCheckedAndRemoved() {
        assertFalse((Boolean) plugin.checkText("дурак").get("profane"));
        assertEquals("дурак", plugin.addWord("bad", "Дурак", "Панель"));
        assertTrue((Boolean) plugin.checkText("дурак").get("profane"));
        assertFalse((Boolean) plugin.checkText("дурак").get("clean"));
        var game = plugin.listWords("bad").stream().filter(r -> r.get("source").equals("game")).toList();
        assertEquals("дурак", game.get(0).get("word"));
        assertEquals("Панель", game.get(0).get("addedBy"));
        assertTrue(plugin.listWords("bad").stream().anyMatch(r -> r.get("source").equals("config")));
        assertTrue(plugin.removeWord("bad", "дурак"));
        assertFalse(plugin.removeWord("bad", "дурак"));
        assertTrue((Boolean) plugin.checkText("дурак").get("clean"));
    }

    @Test
    void refusalsCarryAReadableReason() {
        var unknown = assertThrows(IllegalArgumentException.class, () -> plugin.listWords("что-то"));
        assertTrue(unknown.getMessage().contains("bad"));
        assertThrows(IllegalArgumentException.class, () -> plugin.addWord("bad", "", "Панель"));
        plugin.addWord("bad", "дурак", "Панель");
        assertThrows(IllegalArgumentException.class, () -> plugin.addWord("bad", "дурак", "Панель"));
        assertThrows(IllegalArgumentException.class, () -> plugin.checkText("  "));
        assertThrows(IllegalArgumentException.class, () -> plugin.checkText("а".repeat(501)));
    }

    @Test
    void aSettingChangesAtOnceAndIsResetToTheFileValue() {
        assertTrue((Boolean) plugin.checkText("сука").get("profane"));
        plugin.changeSetting("modules.badwords-filter", "false");
        assertTrue((Boolean) plugin.checkText("сука").get("clean"), "the module is off now");
        var row = plugin.settings().stream().filter(r -> r.get("key").equals("modules.badwords-filter")).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, row.get("value"));
        assertEquals(Boolean.TRUE, row.get("overridden"));
        plugin.resetSetting("modules.badwords-filter");
        assertTrue((Boolean) plugin.checkText("сука").get("profane"), "config.yml applies again");
        row = plugin.settings().stream().filter(r -> r.get("key").equals("modules.badwords-filter")).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, row.get("overridden"));
        assertThrows(IllegalArgumentException.class, () -> plugin.changeSetting("ignore-commands", "x"));
        assertThrows(IllegalArgumentException.class, () -> plugin.changeSetting("anti-caps.max-caps-percent", "500"));
    }

    @Test
    void statisticsHaveEveryTypeEvenWithoutViolations() {
        var stats = plugin.statistics(7);
        assertEquals(7, stats.get("days"));
        @SuppressWarnings("unchecked")
        var totals = (Map<String, Long>) stats.get("totals");
        assertEquals(List.of("profanity", "spam", "caps", "ads"), List.copyOf(totals.keySet()));
    }
}
