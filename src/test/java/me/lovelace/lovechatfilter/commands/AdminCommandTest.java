package me.lovelace.lovechatfilter.commands;

import static org.junit.jupiter.api.Assertions.*;

import me.lovelace.lovechatfilter.LoveChatFilter;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AdminCommandTest {
    private ServerMock server;
    private LoveChatFilter plugin;
    private PlayerMock admin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(LoveChatFilter.class);
        admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, "lovechatfilter.manage", true);
        admin.addAttachment(plugin, "lovechatfilter.reload", true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private String run(Player who, String command) {
        server.dispatchCommand(who, command);
        StringBuilder out = new StringBuilder();
        net.kyori.adventure.text.Component line;
        while ((line = ((PlayerMock) who).nextComponentMessage()) != null)
            out.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        return out.toString();
    }

    private String check(String text) {
        return run(admin, "lovechatfilteradmin check " + text);
    }

    @Test
    void safeWordFromConfigKeepsThe_WordWhileTheSwearNextToItIsCensored() {
        String out = check("скрафтить сука");
        assertTrue(out.contains("скрафтить"), out);
        assertFalse(out.contains("сука"), out);
        assertTrue(out.contains("regex"), out);
    }

    @Test
    void shortWordsAreNotMistakenForSwears() {
        assertTrue(check("сумка").contains("ничего не меняет"), "сумка");
    }

    @Test
    void addedSwearIsCaughtImmediatelyAndByEveryLayer() {
        assertTrue(run(admin, "lovechatfilteradmin add мат дурак").contains("Работает сразу"));
        assertTrue(check("дурак").contains("Найдено"));
        assertTrue(check("д у р а к").contains("Найдено"));
        assertTrue(check("durak").contains("Найдено"));
        assertTrue(run(admin, "lovechatfilteradmin remove мат дурак").contains("Убрано"));
        assertTrue(check("дурак").contains("ничего не меняет"));
    }

    @Test
    void addedSafeWordIsNotCensoredAnymore() {
        assertTrue(check("сука").contains("Найдено"));
        run(admin, "lovechatfilteradmin add безопасное сука");
        assertTrue(check("сука").contains("ничего не меняет"));
    }

    @Test
    void addedAdIsCaught() {
        assertTrue(check("заходи на mysrv.net").contains("ничего не меняет"));
        run(admin, "lovechatfilteradmin add реклама mysrv.net");
        assertTrue(check("заходи на mysrv.net").contains("реклама"));
        assertTrue(check("заходи на mysrv (точка) net").contains("реклама"));
        assertTrue(check("встретимся в точке сбора").contains("ничего не меняет"));
    }

    @Test
    void transliterationIsCaughtAndPlainEnglishIsNot() {
        for (String swear : new String[] {"suka", "blyat", "xuy"}) assertTrue(check(swear).contains("Найдено"), swear);
        for (String fine : new String[] {"blat", "hello"}) assertTrue(check(fine).contains("ничего не меняет"), fine);
    }

    @Test
    void listShowsWhoAddedTheWord() {
        run(admin, "lovechatfilteradmin add мат дурак");
        String out = run(admin, "lovechatfilteradmin list мат");
        assertTrue(out.contains("дурак"), out);
        assertTrue(out.contains("Admin"), out);
    }

    @Test
    void wordsSurviveARestart() {
        run(admin, "lovechatfilteradmin add мат дурак");
        run(admin, "lovechatfilteradmin add безопасное мандарин*");
        server.getScheduler().performOneTick();
        server.getScheduler().waitAsyncTasksFinished();
        // A restart reads the same database file with a fresh connection.
        var reopened = new me.lovelace.lovechatfilter.managers.DatabaseManager(plugin);
        try {
            var saved = reopened.loadWords();
            assertTrue(saved.stream().anyMatch(e -> e.kind() == me.lovelace.lovechatfilter.managers.WordRules.Kind.BAD
                    && e.word().equals("дурак") && e.addedBy().equals("Admin")), saved.toString());
            assertTrue(saved.stream().anyMatch(e -> e.word().equals("мандарин*")), saved.toString());
        } finally {
            reopened.close();
        }
    }

    @Test
    void duplicatesAndRegexAreRefused() {
        run(admin, "lovechatfilteradmin add мат дурак");
        assertTrue(run(admin, "lovechatfilteradmin add мат дурак").contains("уже"));
        assertTrue(run(admin, "lovechatfilteradmin add мат (?i)(a+)+$").contains("config.yml"));
    }

    @Test
    void withoutPermissionTheCommandIsSilent() {
        PlayerMock guest = server.addPlayer("Guest");
        assertEquals("", run(guest, "lovechatfilteradmin add мат тест"));
        assertEquals("", run(guest, "lovechatfilteradmin check сука"));
        assertTrue(plugin.getWordLists().words(me.lovelace.lovechatfilter.managers.WordRules.Kind.BAD).isEmpty());
    }

    @Test
    void addingAWordDoesNotResetTheAntiSpamCooldown() {
        PlayerMock guest = server.addPlayer("Guest");
        var engine = plugin.getFilterEngine();
        assertFalse(engine.processChat(guest, "первое").cancelled);
        assertTrue(engine.processChat(guest, "второе").cancelled, "second message right away is spam");
        run(admin, "lovechatfilteradmin add мат дурак");
        assertTrue(engine.processChat(guest, "третье").cancelled, "adding a word must not reset the cooldown");
    }

    @Test
    void politePhraseIsNotCreditedForASwearingMessage() {
        PlayerMock guest = server.addPlayer("Guest");
        var heard = new java.util.concurrent.atomic.AtomicInteger();
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void on(me.lovelace.lovechatfilter.api.events.PolitePhraseEvent e) { heard.incrementAndGet(); }
        }, plugin);
        plugin.getFilterEngine().processChat(guest, "спасибо, сука");
        assertEquals(0, heard.get());
        server.getScheduler().performTicks(40);
        plugin.getFilterEngine().processChat(server.addPlayer("Other"), "спасибо большое");
        assertEquals(1, heard.get());
    }
}
