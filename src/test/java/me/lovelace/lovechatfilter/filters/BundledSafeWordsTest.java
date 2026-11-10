package me.lovelace.lovechatfilter.filters;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.lovelace.lovechatfilter.managers.WordRules;
import org.junit.jupiter.api.Test;

/**
 * The words shipped in safe-words.txt, checked against the wide regexes of a real server list (an excerpt of the ones that censor
 * innocent words): every shipped word stays clean, the real insults stay censored, and no glued bypass appears.
 */
class BundledSafeWordsTest {

    private static final ProfanityScanner.Settings ALL =
            new ProfanityScanner.Settings(true, true, true, true, true, true, 1, 4);

    /** Excerpt of a live badwords-filter.list: the broad entries (the comments name what each one is for). */
    private static final List<String> LIVE_LIST = List.of(
            "(?i)[с$][уу]*[ч]*[к][а@4иу]*", // сука, сучка (matches any "ск")
            "(?i)д[а@4][уу]*н", // даун (matches any "дан")
            "(?i)[л][о0а@]*х[а-я]*", // лох
            "(?i)[lл][oо0][xх]", // лох, смесь
            "(?i)л[\\s\\.\\-\\_]*о[\\s\\.\\-\\_]*х", // Л.о.х
            "(?i)[нn][іiи1][щshw][іiи1еe]*", // нищие
            "(?i)[т][в][а@4][р][ь]*", // тварь
            "(?i)[а@4][уу]*т[ии1][с$][т][к]*[а-я]*", // аутист
            "(?i)(?:т[ыы]|[в]ы|в[ые])\\s*[^а-я]*(?:[о0]л[еи]н[ь]|[б][а@4]р[а@4][н]|[о0][с$][еи]л|[п][еи][с$])", // ты/вы + пёс
            "(?i)п[ии1][з][д][еи]ц");

    private static ProfanityScanner scanner(List<String> safe) {
        return ProfanityScanner.create(LIVE_LIST, List.of(), safe, ALL, w -> {});
    }

    private static List<String> shipped() {
        List<String> words = FilterEngine.bundledResourceWords();
        assertFalse(words.isEmpty(), "safe-words.txt is missing from the jar");
        return words;
    }

    @Test
    void theShippedListIsWellFormed() {
        Set<String> seen = new HashSet<>();
        for (String word : shipped()) {
            assertEquals(word, WordRules.validate(WordRules.Kind.SAFE, word), word + " is not in the normalised form");
            assertFalse(word.contains("*"), word + ": no wildcards in the shipped list");
            assertTrue(seen.add(word), word + " is listed twice");
        }
        assertTrue(shipped().size() > 5000, "the list is expected to hold thousands of words: " + shipped().size());
    }

    @Test
    void withoutTheShippedWordsTheBroadRegexesCensorInnocentWords() {
        ProfanityScanner bare = scanner(List.of());
        for (String word : List.of("сказать", "данные", "плохо", "статистика", "утварь", "выписать")) {
            assertTrue(bare.scan(word, "#").filtered(), word + " is the reported false positive");
        }
    }

    @Test
    void everyShippedWordIsCleanAloneAndInASentence() {
        ProfanityScanner scanner = scanner(shipped());
        for (String word : shipped()) {
            assertFalse(scanner.scan(word, "#").filtered(), word);
            String sentence = "я прочитал " + word + " и пошёл домой";
            assertEquals(sentence, scanner.scan(sentence, "#").text(), sentence);
        }
    }

    @Test
    void realInsultsStayCensored() {
        ProfanityScanner scanner = scanner(shipped());
        for (String word : List.of("сука", "сучка", "даун", "лох", "нищий", "тварь", "аутист", "пиздец")) {
            assertTrue(scanner.scan(word, "#").filtered(), word);
        }
        assertTrue(scanner.scan("вы пес", "#").filtered());
    }

    @Test
    void wordsMeetingAcrossASpaceStayCleanWhenTheyAreShipped() {
        ProfanityScanner scanner = scanner(shipped());
        assertFalse(scanner.scan("дело хорошо", "#").filtered(), "…ло х…");
        assertFalse(scanner.scan("стол охрана", "#").filtered(), "…л ох…");
        assertFalse(scanner.scan("вы писать", "#").filtered(), "вы пис…");
        assertFalse(scanner.scan("число хлеб", "#").filtered());
    }

    @Test
    void aBannedWordGluedToAShippedWordIsStillCaught() {
        ProfanityScanner scanner = scanner(shipped());
        for (String safe : List.of("сказать", "данные", "плохо", "дело")) {
            assertTrue(scanner.scan(safe + "пиздец", "#").filtered(), safe + "+пиздец");
            assertTrue(scanner.scan("пиздец" + safe, "#").filtered(), "пиздец+" + safe);
            assertTrue(scanner.scan(safe + " пиздец", "#").filtered(), safe + " пиздец");
        }
    }

    @Test
    void anAdminCanSwitchAShippedWordOff() {
        // The engine drops it from the merged list, so the scanner censors it again.
        List<String> kept = shipped().stream().filter(w -> !w.equals("плохо")).toList();
        assertTrue(scanner(kept).scan("плохо", "#").filtered());
        assertFalse(scanner(kept).scan("плохой", "#").filtered());
    }
}
