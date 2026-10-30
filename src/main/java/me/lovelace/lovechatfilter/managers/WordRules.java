package me.lovelace.lovechatfilter.managers;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Input rules for the words an admin adds from the game. Pure and Bukkit-free so the rules are
 * tested without a server. Arbitrary regex is deliberately not accepted here (a typo or a
 * catastrophic pattern would stall every chat message): regex entries stay a config.yml-only
 * feature.
 */
public final class WordRules {
    private WordRules() {}

    public enum Kind {
        /** A banned word, caught by every evasion layer. */
        BAD("bad", List.of("bad", "мат", "ругательство")),
        /** An innocent word that must never be flagged or censored, even if it contains a banned fragment. */
        SAFE("safe", List.of("safe", "безопасное", "безопасные", "ok")),
        /** A literal piece of advertising: a domain, an address, a server name. */
        AD("ad", List.of("ad", "ads", "реклама", "адрес"));

        private final String id;
        private final List<String> aliases;

        Kind(String id, List<String> aliases) {
            this.id = id;
            this.aliases = aliases;
        }

        public String id() { return id; }

        public static Kind parse(String text) {
            if (text == null) return null;
            String lower = text.toLowerCase(Locale.ROOT);
            for (Kind kind : values()) if (kind.aliases.contains(lower)) return kind;
            return null;
        }
    }

    public static final int BAD_MIN = 3, BAD_MAX = 64, AD_MIN = 4, AD_MAX = 100;
    private static final Pattern SAFE_WORD = Pattern.compile("(?U)[\\p{L}\\d]{2,40}\\*?");

    /** Normalised form of an accepted word; throws {@link IllegalArgumentException} with a player-readable reason. */
    public static String validate(Kind kind, String raw) {
        if (raw == null) throw new IllegalArgumentException("Укажите слово.");
        String text = raw.strip();
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i)))
                throw new IllegalArgumentException("В слове не должно быть управляющих символов.");
        }
        return switch (kind) {
            case BAD -> validateBad(text);
            case SAFE -> validateSafe(text);
            case AD -> validateAd(text);
        };
    }

    private static String validateBad(String text) {
        String word = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (word.startsWith("(?i)"))
            throw new IllegalArgumentException("Регулярные выражения добавляются только в config.yml.");
        if (word.length() < BAD_MIN || word.length() > BAD_MAX)
            throw new IllegalArgumentException("Длина слова: от " + BAD_MIN + " до " + BAD_MAX + " символов.");
        long letters = word.chars().filter(Character::isLetter).count();
        if (letters < BAD_MIN)
            throw new IllegalArgumentException("В слове должно быть минимум " + BAD_MIN + " буквы.");
        return word;
    }

    private static String validateSafe(String text) {
        String word = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (!SAFE_WORD.matcher(word).matches())
            throw new IllegalArgumentException("Безопасное слово: одно слово из букв и цифр, можно с «*» на конце (скрафт*).");
        if (word.endsWith("*") && word.length() - 1 < 3)
            throw new IllegalArgumentException("Шаблон со «*» должен начинаться минимум с 3 символов: короче он покроет лишнее.");
        return word;
    }

    private static String validateAd(String text) {
        String word = text.toLowerCase(Locale.ROOT);
        if (word.length() < AD_MIN || word.length() > AD_MAX)
            throw new IllegalArgumentException("Длина адреса: от " + AD_MIN + " до " + AD_MAX + " символов.");
        if (word.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Адрес пишется без пробелов.");
        if (word.chars().noneMatch(Character::isLetterOrDigit))
            throw new IllegalArgumentException("В адресе должны быть буквы или цифры.");
        return word;
    }
}
