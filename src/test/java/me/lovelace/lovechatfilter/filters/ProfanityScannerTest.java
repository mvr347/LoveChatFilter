package me.lovelace.lovechatfilter.filters;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfanityScannerTest {
    private static final ProfanityScanner.Settings ALL =
            new ProfanityScanner.Settings(true, true, true, true, true, true, 1, 4);

    private static ProfanityScanner scanner(List<String> bad, List<String> safe) {
        return ProfanityScanner.create(bad, List.of(), safe, ALL, w -> {});
    }

    private static final List<String> BAD = List.of("блять", "хуй", "сука", "пиздец");
    private static final List<String> SAFE = List.of("скрафт*", "сумка");

    @Test
    void safeWordStaysWhileSwearNextToItIsCensored() {
        var r = scanner(BAD, SAFE).scan("скрафтить сука", "#");
        assertTrue(r.filtered());
        assertEquals("скрафтить ####", r.text());
    }

    @Test
    void innocentTextIsUntouched() {
        var r = scanner(BAD, SAFE).scan("скрафтил сумку и пошёл домой", "#");
        assertFalse(r.filtered());
        assertEquals("скрафтил сумку и пошёл домой", r.text());
    }

    @Test
    void safeWordInsideCompactMergeDoesNotCensorTheWholeMessage() {
        // "с указкой" compacts to "суказкой" which contains "сука": only that piece may be hit.
        var r = scanner(BAD, SAFE).scan("скрафтить с указкой", "#");
        assertTrue(r.text().startsWith("скрафтить "), r.text());
    }

    @Test
    void shortWordsNeedExactMatch() {
        var scanner = scanner(BAD, List.of());
        assertFalse(scanner.scan("сумка", "#").filtered());
        assertFalse(scanner.scan("сушка", "#").filtered());
        assertFalse(scanner.scan("судьба", "#").filtered());
    }

    @Test
    void longWordStillForgivesATypo() {
        assertTrue(scanner(BAD, List.of()).scan("пиздецц", "#").filtered());
    }

    @Test
    void twoTricksInOneMessageAreBothCensored() {
        var r = scanner(BAD, List.of()).scan("сука и bлять", "#");
        assertEquals("#### и #####", r.text());
        assertTrue(r.layers().containsAll(List.of("regex", "homoglyph")), r.layers().toString());
    }

    @Test
    void transliterationCatchesLatinSwearsOnly() {
        var scanner = scanner(BAD, SAFE);
        for (String swear : List.of("suka", "blyat", "hui", "xuy", "pizdec"))
            assertTrue(scanner.scan(swear, "#").filtered(), swear);
        for (String fine : List.of("blat", "hello", "class", "super", "huge", "sucker"))
            assertFalse(scanner.scan(fine, "#").filtered(), fine);
    }

    @Test
    void spacedAndStretchedWordsAreCaught() {
        var scanner = scanner(BAD, List.of());
        assertTrue(scanner.scan("с у к а", "#").filtered());
        assertTrue(scanner.scan("сууукааа", "#").filtered());
    }

    @Test
    void zeroWidthCharactersDoNotHideAWord() {
        assertTrue(scanner(BAD, List.of()).scan("су​ка", "#").filtered());
    }

    @Test
    void symbolLookalikesAreCaught() {
        assertTrue(scanner(BAD, List.of()).scan("$ука", "#").filtered());
        assertTrue(scanner(BAD, List.of()).scan("сук@", "#").filtered());
    }

    @Test
    void brokenPatternIsSkippedNotFatal() {
        List<String> warnings = new ArrayList<>();
        var scanner = ProfanityScanner.create(
                List.of("(?i)(незакрытая", "сука"), List.of(), List.of(), ALL, warnings::add);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("незакрытая"));
        assertTrue(scanner.scan("сука", "#").filtered());
    }

    @Test
    void noneReplacementCutsTheWord() {
        var r = scanner(BAD, SAFE).scan("скрафтить сука дальше", "NONE");
        assertEquals("скрафтить  дальше", r.text());
    }

    @Test
    void customWordsGoThroughEveryLayer() {
        var scanner = ProfanityScanner.create(List.of(), List.of("дурак"), List.of(), ALL, w -> {});
        assertTrue(scanner.scan("дурак", "#").filtered());
        assertTrue(scanner.scan("д у р а к", "#").filtered());
        assertTrue(scanner.scan("durak", "#").filtered());
    }

    @Test
    void emptyListNeverFilters() {
        var scanner = scanner(List.of(), List.of());
        assertFalse(scanner.scan("что угодно", "#").filtered());
    }
}
