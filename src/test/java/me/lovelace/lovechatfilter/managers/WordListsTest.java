package me.lovelace.lovechatfilter.managers;

import static me.lovelace.lovechatfilter.managers.WordRules.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WordListsTest {
    /** Keeps entries like the database does, so a second WordLists over it models a restart. */
    private static class MemoryStore implements WordLists.WordStore {
        final List<WordLists.Entry> rows = new ArrayList<>();
        public List<WordLists.Entry> loadWords() { return List.copyOf(rows); }
        public void saveWord(WordLists.Entry e) { rows.removeIf(r -> r.kind() == e.kind() && r.word().equals(e.word())); rows.add(e); }
        public void deleteWord(WordRules.Kind k, String w) { rows.removeIf(r -> r.kind() == k && r.word().equals(w)); }
    }

    private static WordLists lists(MemoryStore store, List<String> errors) {
        WordLists lists = new WordLists(store, Runnable::run, errors::add);
        lists.load();
        return lists;
    }

    @Test
    void addedWordIsVisibleAtOnceAndSurvivesARestart() {
        var store = new MemoryStore();
        var first = lists(store, new ArrayList<>());
        assertEquals("дурак", first.add(BAD, " Дурак ", "Admin", Set.of()));
        assertTrue(first.words(BAD).contains("дурак"));

        var afterRestart = lists(store, new ArrayList<>());
        assertEquals(Set.of("дурак"), afterRestart.words(BAD));
        assertEquals("Admin", afterRestart.list(BAD).get(0).addedBy());
    }

    @Test
    void duplicatesAreRefused() {
        var lists = lists(new MemoryStore(), new ArrayList<>());
        lists.add(SAFE, "скрафт*", "Admin", Set.of());
        assertThrows(IllegalArgumentException.class, () -> lists.add(SAFE, "СКРАФТ*", "Admin", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> lists.add(BAD, "сука", "Admin", Set.of("сука")));
    }

    @Test
    void kindsDoNotMix() {
        var lists = lists(new MemoryStore(), new ArrayList<>());
        lists.add(BAD, "дурак", "Admin", Set.of());
        assertTrue(lists.words(SAFE).isEmpty());
        assertFalse(lists.remove(SAFE, "дурак"));
        assertTrue(lists.remove(BAD, "дурак"));
        assertFalse(lists.remove(BAD, "дурак"));
    }

    @Test
    void removalIsPersisted() {
        var store = new MemoryStore();
        var lists = lists(store, new ArrayList<>());
        lists.add(AD, "mysrv.net", "Admin", Set.of());
        lists.remove(AD, "mysrv.net");
        assertTrue(lists(store, new ArrayList<>()).words(AD).isEmpty());
    }

    @Test
    void aFailingStoreIsReportedNotThrownIntoTheCommand() {
        var errors = new ArrayList<String>();
        var broken = new MemoryStore() {
            @Override public void saveWord(WordLists.Entry e) { throw new IllegalStateException("диск полон"); }
        };
        var lists = lists(broken, errors);
        assertDoesNotThrow(() -> lists.add(BAD, "дурак", "Admin", Set.of()));
        assertEquals(1, errors.size());
        assertTrue(lists.words(BAD).contains("дурак"));
    }

    @Test
    void newestIsListedFirst() throws Exception {
        var lists = lists(new MemoryStore(), new ArrayList<>());
        lists.add(BAD, "первое", "A", Set.of());
        Thread.sleep(1100);
        lists.add(BAD, "второе", "A", Set.of());
        assertEquals("второе", lists.list(BAD).get(0).word());
    }
}
