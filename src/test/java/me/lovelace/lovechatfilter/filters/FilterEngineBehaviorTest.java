package me.lovelace.lovechatfilter.filters;

import static org.junit.jupiter.api.Assertions.*;

import me.lovelace.lovechatfilter.LoveChatFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The engine on a mocked server: every place text goes through the filter keeps working. */
class FilterEngineBehaviorTest {
    private ServerMock server;
    private LoveChatFilter plugin;
    private FilterEngine engine;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(LoveChatFilter.class);
        engine = plugin.getFilterEngine();
        player = server.addPlayer("Guest");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void signBookAndItemNamesAreFiltered() {
        for (FilterEngine.ContentType type : new FilterEngine.ContentType[] {
                FilterEngine.ContentType.SIGN, FilterEngine.ContentType.BOOK, FilterEngine.ContentType.ITEM}) {
            String out = engine.processContent(player, "мой скрафтить сука", type);
            assertTrue(out.contains("скрафтить"), type + ": " + out);
            assertFalse(out.contains("сука"), type + ": " + out);
        }
    }

    @Test
    void floodOfOneCharacterIsShortenedNotBlocked() {
        var result = engine.processChat(player, "даааааааааааа");
        assertFalse(result.cancelled);
        assertEquals("дааааа", result.message);
    }

    @Test
    void isProfaneStillWorksForOtherPlugins() {
        assertTrue(engine.isProfane("сука"));
        assertTrue(engine.isProfane("suka"));
        assertFalse(engine.isProfane("скрафтить"));
        assertFalse(engine.isProfane("сумка"));
    }

    @Test
    void oneBrokenRegexInTheConfigDoesNotSwitchTheFilterOff() {
        plugin.getConfig().set("badwords-filter.list", java.util.List.of("(?i)(незакрытая", "сука"));
        engine.reloadCache();
        assertTrue(engine.isProfane("сука"));
    }

    @Test
    void obfuscatedAdIsCensoredAsAWhole() {
        plugin.getConfig().set("ads-filter.patterns", java.util.List.of("\\.ru"));
        engine.reloadCache();
        var result = engine.processChat(player, "заходи mysrv (точка) ru");
        assertFalse(result.message.contains("mysrv"), result.message);
    }

    @Test
    void anOldConfigWithoutSafeWordsStillGetsTheBuiltInOnes() {
        // A banned fragment sitting inside an ordinary word, exactly the reported "скрафтить" case.
        plugin.getConfig().set("badwords-filter.list", java.util.List.of("рафт"));
        plugin.getConfig().set("badwords-filter.safe-words", null);
        engine.reloadCache();
        assertTrue(engine.isProfane("рафт"));
        assertFalse(engine.isProfane("скрафтить"));
        assertEquals("скрафтить", engine.processContent(player, "скрафтить", FilterEngine.ContentType.SIGN));
    }

    @Test
    void obfuscatedAdAfterADirectOneIsStillCensored() {
        plugin.getConfig().set("ads-filter.patterns", java.util.List.of("\\.ru"));
        engine.reloadCache();
        var result = engine.processChat(player, "decoy.ru mysrv (точка) ru");
        assertFalse(result.message.contains("mysrv"), result.message);
    }

    @Test
    void advertisingCheckForOtherPlugins() {
        assertTrue(engine.hasAdvertising("mysrv.ru"));
        assertTrue(engine.hasAdvertising("mysrv (точка) ru"));
        assertTrue(engine.hasAdvertising("play.mysrv.net"));
        assertFalse(engine.hasAdvertising("Золотая Орда"));
        assertFalse(engine.hasAdvertising("Точка сбора"));
        assertFalse(engine.hasAdvertising(null));
    }

    @Test
    void advertisingCheckRespectsTheWhitelistAndTheModuleSwitch() {
        assertFalse(engine.hasAdvertising("vk.com/my_server_group"));
        plugin.getConfig().set("modules.ads-filter", false);
        engine.reloadCache();
        assertFalse(engine.hasAdvertising("mysrv.ru"));
    }

    @Test
    void shippedSafeWordsApplyEvenWhenTheServerConfigHasItsOwnSafeWordsKey() {
        // A banned fragment inside shipped words: the server config lists other safe words only.
        plugin.getConfig().set("badwords-filter.list", java.util.List.of("(?i)[с$][уу]*[ч]*[к][а@4иу]*"));
        plugin.getConfig().set("badwords-filter.safe-words", java.util.List.of("крафт*"));
        plugin.getConfig().set("badwords-filter.safe-words-disabled", java.util.List.of());
        engine.reloadCache();
        assertFalse(engine.isProfane("сказать"), "shipped word");
        assertFalse(engine.isProfane("москва"), "shipped word");
        assertTrue(engine.isProfane("сука"));
    }

    @Test
    void safeWordsDisabledInTheConfigAreCensoredAgain() {
        plugin.getConfig().set("badwords-filter.list", java.util.List.of("(?i)[с$][уу]*[ч]*[к][а@4иу]*"));
        plugin.getConfig().set("badwords-filter.safe-words-disabled", java.util.List.of("сказать"));
        engine.reloadCache();
        assertTrue(engine.isProfane("сказать"));
        assertFalse(engine.isProfane("москва"));
    }

    @Test
    void shippedWordsCountAsAlreadyKnownButAreNotListedForAdmins() {
        assertTrue(engine.configWords(me.lovelace.lovechatfilter.managers.WordRules.Kind.SAFE).contains("сказать"));
        assertFalse(engine.listedConfigWords(me.lovelace.lovechatfilter.managers.WordRules.Kind.SAFE).contains("сказать"));
    }

    @Test
    void shippedWordsComeFromTheirOwnDatabaseAndCanBeSwitchedOffThere() {
        plugin.getConfig().set("badwords-filter.list", java.util.List.of("(?i)[с$][уу]*[ч]*[к][а@4иу]*"));
        engine.reloadCache();
        assertNotNull(plugin.getSafeWordStore(), "safe-words.db is opened on enable");
        assertTrue(plugin.getSafeWordStore().bundledCount() > 10000);
        assertFalse(engine.isProfane("сказал"), "a shipped form");
        plugin.getSafeWordStore().setBundledDisabled("сказал", true);
        engine.reloadCache();
        assertTrue(engine.isProfane("сказал"));
    }
}
