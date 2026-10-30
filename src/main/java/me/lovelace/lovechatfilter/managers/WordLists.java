package me.lovelace.lovechatfilter.managers;

import me.lovelace.lovechatfilter.managers.WordRules.Kind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Words admins add and remove from the game. Everything lives in memory (the chat thread never
 * waits for SQL) and is written to the store on the given executor. Reads see a change at once.
 */
public final class WordLists {

    public record Entry(Kind kind, String word, String addedBy, long addedAt) {}

    /** Where the words survive a restart. */
    public interface WordStore {
        List<Entry> loadWords();
        void saveWord(Entry entry);
        void deleteWord(Kind kind, String word);
    }

    private final WordStore store;
    private final Executor io;
    private final Consumer<String> onError;
    private final Map<Kind, Map<String, Entry>> data = new EnumMap<>(Kind.class);

    public WordLists(WordStore store, Executor io, Consumer<String> onError) {
        this.store = store;
        this.io = io;
        this.onError = onError;
        for (Kind kind : Kind.values()) data.put(kind, new ConcurrentHashMap<>());
    }

    /** Reads the saved words; called once while the plugin enables. */
    public void load() {
        for (Kind kind : Kind.values()) data.get(kind).clear();
        for (Entry entry : store.loadWords()) data.get(entry.kind()).put(entry.word(), entry);
    }

    /**
     * Adds a word. {@code alsoKnown} lists words that already exist elsewhere (config.yml), so an
     * admin is told instead of silently doubling an entry.
     *
     * @return the normalised word that was stored
     * @throws IllegalArgumentException with a readable reason when the word is invalid or already there
     */
    public String add(Kind kind, String raw, String addedBy, Set<String> alsoKnown) {
        String word = WordRules.validate(kind, raw);
        if (alsoKnown.contains(word))
            throw new IllegalArgumentException("Это слово уже есть в config.yml.");
        Entry entry = new Entry(kind, word, addedBy, System.currentTimeMillis() / 1000L);
        if (data.get(kind).putIfAbsent(word, entry) != null)
            throw new IllegalArgumentException("Это слово уже добавлено.");
        io.execute(() -> {
            try {
                store.saveWord(entry);
            } catch (RuntimeException e) {
                onError.accept("Слово «" + word + "» не сохранено в базе: " + e.getMessage());
            }
        });
        return word;
    }

    /** @return false when the word was not in the list */
    public boolean remove(Kind kind, String raw) {
        String word;
        try {
            word = WordRules.validate(kind, raw);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (data.get(kind).remove(word) == null) return false;
        io.execute(() -> {
            try {
                store.deleteWord(kind, word);
            } catch (RuntimeException e) {
                onError.accept("Слово «" + word + "» не удалено из базы: " + e.getMessage());
            }
        });
        return true;
    }

    public Set<String> words(Kind kind) {
        return Set.copyOf(data.get(kind).keySet());
    }

    /** Newest first. */
    public List<Entry> list(Kind kind) {
        List<Entry> entries = new ArrayList<>(data.get(kind).values());
        entries.sort(Comparator.comparingLong(Entry::addedAt).reversed().thenComparing(Entry::word));
        return entries;
    }
}
