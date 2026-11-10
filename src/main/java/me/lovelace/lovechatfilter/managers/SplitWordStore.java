package me.lovelace.lovechatfilter.managers;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes the words admins add: safe words go to their own database ({@link SafeWordStore}), bad words and ad patterns stay in the main
 * one. On first use the safe words that were saved in {@code database.db} before the split are copied over and removed there.
 */
public final class SplitWordStore implements WordLists.WordStore {

    private final WordLists.WordStore main;
    private final SafeWordStore safe;

    public SplitWordStore(WordLists.WordStore main, SafeWordStore safe) {
        this.main = main;
        this.safe = safe;
        migrate();
    }

    private void migrate() {
        if (safe.migrated()) return;
        for (WordLists.Entry entry : main.loadWords()) {
            if (entry.kind() != WordRules.Kind.SAFE) continue;
            safe.saveGameWord(entry);
            main.deleteWord(WordRules.Kind.SAFE, entry.word());
        }
        safe.markMigrated();
    }

    @Override
    public List<WordLists.Entry> loadWords() {
        List<WordLists.Entry> out = new ArrayList<>();
        for (WordLists.Entry entry : main.loadWords()) {
            if (entry.kind() != WordRules.Kind.SAFE) out.add(entry);
        }
        out.addAll(safe.loadGameWords());
        return out;
    }

    @Override
    public void saveWord(WordLists.Entry entry) {
        if (entry.kind() == WordRules.Kind.SAFE) safe.saveGameWord(entry);
        else main.saveWord(entry);
    }

    @Override
    public void deleteWord(WordRules.Kind kind, String word) {
        if (kind == WordRules.Kind.SAFE) safe.deleteGameWord(word);
        else main.deleteWord(kind, word);
    }
}
