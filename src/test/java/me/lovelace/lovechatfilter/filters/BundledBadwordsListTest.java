package me.lovelace.lovechatfilter.filters;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The badwords list shipped in config.yml: every entry compiles, the narrowed entries stay narrow, real insults stay censored. */
class BundledBadwordsListTest {

    private static final ProfanityScanner.Settings ALL =
            new ProfanityScanner.Settings(true, true, true, true, true, true, 1, 4);

    private static List<String> bundledList() {
        var in = BundledBadwordsListTest.class.getResourceAsStream("/config.yml");
        assertNotNull(in);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> list = yaml.getStringList("badwords-filter.list");
        assertFalse(list.isEmpty());
        return list;
    }

    private static ProfanityScanner scanner(List<String> safe, List<String> warnings) {
        return ProfanityScanner.create(bundledList(), List.of(), safe, ALL, warnings::add);
    }

    @Test
    void everyEntryCompiles() {
        List<String> warnings = new ArrayList<>();
        scanner(List.of(), warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(142, bundledList().size());
    }

    @Test
    void theSkEntryNoLongerCatchesEveryWordWithSk() {
        ProfanityScanner scanner = scanner(List.of(), new ArrayList<>());
        for (String word : List.of("употреблять", "влюбляться", "расслабляться", "сказать", "москва", "русский", "доска", "маска", "сколько", "данные", "задание",
                "колхоз", "статистика", "шахматист", "утварь", "отвар", "днище", "пристанище", "чмокнуть", "чмок")) {
            assertFalse(scanner.scan(word, "#").filtered(), word + " must not be censored by the shipped list");
        }
    }

    @Test
    void realInsultsAreStillCensored() {
        ProfanityScanner scanner = scanner(List.of(), new ArrayList<>());
        for (String word : List.of("сука", "сучка", "суки", "даун", "дауны", "лох", "лохи", "нищий", "тварь", "твари",
                "аутист", "аутистка", "чмо", "пиздец", "пидор", "блядина", "мразь", "идиот", "дебил", "скотина", "ублюдок",
                "cyka", "suka", "blyat", "xuy", "блять", "блядь", "похуй", "pidor", "сцикун")) {
            assertTrue(scanner.scan(word, "#").filtered(), word);
        }
        for (String phrase : List.of("ты тупой", "вы пес", "ты клоун", "ты лох", "п.и.д.о.р")) {
            assertTrue(scanner.scan(phrase, "#").filtered(), phrase);
        }
    }

    @Test
    void theShippedSafeWordsStillCoverTheRemainingFalsePositives() {
        // «плохо» is still caught by the mixed-script «лох» entries (they have no word start); the shipped safe words cover it.
        assertTrue(scanner(List.of(), new ArrayList<>()).scan("плохо", "#").filtered());
        ProfanityScanner scanner = scanner(FilterEngine.bundledResourceWords(), new ArrayList<>());
        for (String word : List.of("сказал", "данные", "плохо", "москва", "статистика")) {
            assertFalse(scanner.scan(word, "#").filtered(), word);
        }
        assertTrue(scanner.scan("скрафтхуй", "#").filtered());
    }
}
