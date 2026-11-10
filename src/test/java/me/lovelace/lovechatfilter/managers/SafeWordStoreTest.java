package me.lovelace.lovechatfilter.managers;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeWordStoreTest {

    @TempDir
    Path dir;

    private SafeWordStore open() throws Exception {
        return SafeWordStore.open(new File(dir.toFile(), "safe-words.db"));
    }

    @Test
    void shippedWordsAreSeededOnceAndSurviveAReopen() throws Exception {
        List<String> shipped = List.of("сказать", "данные", "плохо");
        try (SafeWordStore store = open()) {
            assertTrue(store.seedBundled(shipped, SafeWordStore.fingerprint(shipped)));
            assertEquals(Set.of("сказать", "данные", "плохо"), store.activeBundled());
        }
        try (SafeWordStore again = open()) {
            assertFalse(again.seedBundled(shipped, SafeWordStore.fingerprint(shipped)), "same list: nothing to write");
            assertEquals(3, again.bundledCount());
        }
    }

    @Test
    void aNewReleaseAddsAndDropsWordsButKeepsWhatAnAdminSwitchedOff() throws Exception {
        try (SafeWordStore store = open()) {
            List<String> v1 = List.of("сказать", "данные", "плохо");
            store.seedBundled(v1, SafeWordStore.fingerprint(v1));
            assertTrue(store.setBundledDisabled("плохо", true));
            assertFalse(store.setBundledDisabled("нет такого", true));
            assertEquals(Set.of("сказать", "данные"), store.activeBundled());

            List<String> v2 = List.of("сказать", "плохо", "москва");
            assertTrue(store.seedBundled(v2, SafeWordStore.fingerprint(v2)));
            assertEquals(Set.of("сказать", "москва"), store.activeBundled(), "данные dropped, москва added, плохо stays off");
            assertEquals(3, store.bundledCount());
        }
    }

    @Test
    void gameWordsAreKeptApartFromShippedOnes() throws Exception {
        try (SafeWordStore store = open()) {
            store.saveGameWord(new WordLists.Entry(WordRules.Kind.SAFE, "скрафт*", "Admin", 5L));
            store.saveGameWord(new WordLists.Entry(WordRules.Kind.SAFE, "сумка", "Admin", 6L));
            store.deleteGameWord("сумка");
            List<WordLists.Entry> words = store.loadGameWords();
            assertEquals(1, words.size());
            assertEquals("скрафт*", words.get(0).word());
            assertEquals("Admin", words.get(0).addedBy());
            assertTrue(store.activeBundled().isEmpty());
        }
    }

    // ---- the routing store, with the main database replaced by memory ----

    private static final class MemoryStore implements WordLists.WordStore {
        final List<WordLists.Entry> rows = new ArrayList<>();
        public List<WordLists.Entry> loadWords() { return List.copyOf(rows); }
        public void saveWord(WordLists.Entry e) { rows.removeIf(r -> r.kind() == e.kind() && r.word().equals(e.word())); rows.add(e); }
        public void deleteWord(WordRules.Kind k, String w) { rows.removeIf(r -> r.kind() == k && r.word().equals(w)); }
    }

    @Test
    void oldSafeWordsMoveOverOnceAndOtherKindsStayWhereTheyWere() throws Exception {
        MemoryStore main = new MemoryStore();
        main.saveWord(new WordLists.Entry(WordRules.Kind.SAFE, "сумка", "Old", 1L));
        main.saveWord(new WordLists.Entry(WordRules.Kind.BAD, "дурак", "Old", 2L));
        try (SafeWordStore safe = open()) {
            SplitWordStore split = new SplitWordStore(main, safe);
            assertEquals(1, main.rows.size(), "the safe word left database.db");
            assertEquals(WordRules.Kind.BAD, main.rows.get(0).kind());
            assertEquals(2, split.loadWords().size());
            assertTrue(safe.migrated());

            split.saveWord(new WordLists.Entry(WordRules.Kind.SAFE, "кошка", "Admin", 3L));
            split.saveWord(new WordLists.Entry(WordRules.Kind.AD, "mysrv.net", "Admin", 4L));
            assertEquals(1 + 1, main.rows.size(), "bad + ad in the main database, the new safe word is not there");
            split.deleteWord(WordRules.Kind.SAFE, "сумка");
            assertEquals(List.of("кошка"), safe.loadGameWords().stream().map(WordLists.Entry::word).toList());
        }
    }
}
