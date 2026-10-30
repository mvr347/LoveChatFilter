package me.lovelace.lovechatfilter.filters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The whole badwords pipeline as one immutable, Bukkit-free object, so the same code answers the
 * chat filter, {@code isProfane} for other plugins and the {@code /lcf check} preview, and can be
 * unit-tested.
 *
 * Layers (each one runs on what the previous ones left, so a message with two different tricks is
 * censored in full instead of only by the first layer that fired):
 * plain regex -> homoglyph/digit regex -> typo-tolerant tokens -> Latin transliteration -> compact
 * (spaced / stretched words).
 *
 * Safe words are cut out of the message before any layer sees it: a message is scanned piece by
 * piece around them, so an innocent word that merely contains a banned fragment is neither
 * flagged nor partly censored.
 */
final class ProfanityScanner {

    private static final Pattern WORD_TOKEN = Pattern.compile("(?U)\\p{L}+");

    record Settings(
            boolean advanced,
            boolean homoglyphs,
            boolean digits,
            boolean fuzzy,
            boolean compact,
            boolean transliteration,
            int maxTypoDistance,
            int minFuzzyLength) {}

    /** {@code layers} names the layers that fired, for the admin preview. */
    record Result(String text, boolean filtered, Set<String> layers) {}

    private final Pattern profanity;
    private final List<String> plainBadwords;
    private final List<String> foldedBadwords;
    private final Set<String> safeExact;
    private final List<String> safePrefixes;
    private final Settings settings;

    private ProfanityScanner(
            Pattern profanity,
            List<String> plainBadwords,
            Set<String> safeExact,
            List<String> safePrefixes,
            Settings settings) {
        this.profanity = profanity;
        this.plainBadwords = List.copyOf(plainBadwords);
        this.safeExact = Set.copyOf(safeExact);
        this.safePrefixes = List.copyOf(safePrefixes);
        this.settings = settings;
        Set<String> folded = new LinkedHashSet<>();
        for (String word : plainBadwords) {
            String f = Translit.fold(AdvancedProfanityFilter.compactWord(word));
            if (f.length() >= 3) folded.add(f);
        }
        this.foldedBadwords = List.copyOf(folded);
    }

    /**
     * Builds a scanner. A word whose pattern does not compile is reported through {@code warn}
     * and skipped on its own; one broken entry used to switch the entire filter off silently.
     */
    static ProfanityScanner create(
            Collection<String> configWords,
            Collection<String> customWords,
            Collection<String> safeWords,
            Settings settings,
            Consumer<String> warn) {
        List<String> fragments = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        List<String> all = new ArrayList<>(configWords);
        all.addAll(customWords);
        for (String word : all) {
            if (word == null || word.isBlank()) continue;
            String fragment;
            if (word.startsWith("(?i)")) {
                fragment = word.substring(4);
            } else {
                StringBuilder sb = new StringBuilder();
                char[] chars = word.toLowerCase(Locale.ROOT).toCharArray();
                for (int i = 0; i < chars.length; i++) {
                    sb.append(Pattern.quote(String.valueOf(chars[i]))).append("+");
                    if (i < chars.length - 1) sb.append("[\\W_]*");
                }
                fragment = sb.toString();
            }
            try {
                Pattern.compile("(?uiU)(" + fragment + ")");
            } catch (RuntimeException broken) {
                warn.accept("badwords-filter: слово «" + word + "» пропущено, неверное выражение: "
                        + broken.getMessage().lines().findFirst().orElse(""));
                continue;
            }
            fragments.add(fragment);
            if (!word.startsWith("(?i)")) plain.add(word.toLowerCase(Locale.ROOT));
        }

        Pattern pattern = null;
        if (!fragments.isEmpty()) {
            pattern = Pattern.compile("(?uiU)(" + String.join("|", fragments) + ")");
        }

        Set<String> exact = new LinkedHashSet<>();
        List<String> prefixes = new ArrayList<>();
        for (String safe : safeWords) {
            String normalized = normalize(safe);
            if (normalized.isEmpty()) continue;
            if (normalized.endsWith("*")) {
                String prefix = normalized.substring(0, normalized.length() - 1);
                if (!prefix.isEmpty()) prefixes.add(prefix);
            } else {
                exact.add(normalized);
            }
        }
        return new ProfanityScanner(pattern, plain, exact, prefixes, settings);
    }

    /** Lowercase with ё folded to е, the form safe words are stored and compared in. */
    static String normalize(String word) {
        if (word == null) return "";
        return word.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    boolean isSafe(String token) {
        String normalized = normalize(token);
        if (safeExact.contains(normalized)) return true;
        for (String prefix : safePrefixes) {
            if (normalized.startsWith(prefix)) return true;
        }
        return false;
    }

    boolean isEmpty() {
        return profanity == null && plainBadwords.isEmpty();
    }

    Result scan(String text, String repl) {
        Set<String> layers = new LinkedHashSet<>();
        if (text == null || text.isEmpty() || isEmpty()) return new Result(text, false, layers);

        StringBuilder out = new StringBuilder(text.length());
        boolean filtered = false;
        Matcher words = WORD_TOKEN.matcher(text);
        int segmentStart = 0;
        while (words.find()) {
            if (!isSafe(words.group())) continue;
            Result part = scanSegment(text.substring(segmentStart, words.start()), repl, layers);
            out.append(part.text());
            filtered |= part.filtered();
            out.append(words.group());
            segmentStart = words.end();
        }
        Result tail = scanSegment(text.substring(segmentStart), repl, layers);
        out.append(tail.text());
        filtered |= tail.filtered();
        return new Result(out.toString(), filtered, layers);
    }

    private Result scanSegment(String segment, String repl, Set<String> layers) {
        if (segment.isEmpty()) return new Result(segment, false, layers);
        boolean useNone = repl.equalsIgnoreCase("NONE");
        String message = segment;
        boolean filtered = false;

        if (profanity != null) {
            Replaced r = replaceMatches(message, message, profanity, repl, useNone);
            if (r.matched) { message = r.text; filtered = true; layers.add("regex"); }
        }

        if (settings.advanced()) {
            if (profanity != null) {
                String canonical = canonical(message);
                if (!canonical.equals(message)) {
                    Replaced r = replaceMatches(message, canonical, profanity, repl, useNone);
                    if (r.matched) { message = r.text; filtered = true; layers.add("homoglyph"); }
                }
            }

            if (settings.fuzzy() && !plainBadwords.isEmpty()) {
                Replaced r = fuzzy(message, canonical(message), repl, useNone);
                if (r.matched) { message = r.text; filtered = true; layers.add("fuzzy"); }
            }

            if (settings.transliteration() && !foldedBadwords.isEmpty()) {
                Replaced r = transliterated(message, repl, useNone);
                if (r.matched) { message = r.text; filtered = true; layers.add("translit"); }
            }

            // Spaced / stretched words have no 1:1 offsets, so this layer censors what is left of
            // the segment. Segments end at safe words, so an innocent word next to the trigger
            // is no longer swept away with it.
            if (settings.compact() && !plainBadwords.isEmpty()) {
                var chains = AdvancedProfanityFilter.compactChains(canonical(message));
                if (AdvancedProfanityFilter.containsCompactMatch(chains, plainBadwords, settings.minFuzzyLength())) {
                    message = useNone ? "" : repl.repeat(message.length());
                    filtered = true;
                    layers.add("compact");
                }
            }
        }
        return new Result(message, filtered, layers);
    }

    private String canonical(String text) {
        return AdvancedProfanityFilter.canonicalize(text, settings.homoglyphs(), settings.digits());
    }

    private record Replaced(String text, boolean matched) {}

    /** Replaces every match found in {@code searched}; offsets are 1:1 with {@code original}. */
    private static Replaced replaceMatches(String original, String searched, Pattern pattern, String repl, boolean useNone) {
        Matcher m = pattern.matcher(searched);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean matched = false;
        while (m.find()) {
            if (m.end() == m.start()) continue;
            matched = true;
            sb.append(original, last, m.start());
            if (!useNone) sb.append(repl.repeat(m.end() - m.start()));
            last = m.end();
        }
        if (!matched) return new Replaced(original, false);
        sb.append(original, last, original.length());
        return new Replaced(sb.toString(), true);
    }

    /**
     * How many typos a word of this length may carry. A flat distance made every four-letter
     * badword swallow its neighbours ("сумка" is one letter from "сука"), so short words must
     * match exactly and only long ones are forgiving.
     */
    int allowedDistance(int badwordLength) {
        int byLength = badwordLength <= 4 ? 0 : badwordLength <= 7 ? 1 : 2;
        return Math.min(byLength, settings.maxTypoDistance());
    }

    private Replaced fuzzy(String original, String canonical, String repl, boolean useNone) {
        Matcher m = WORD_TOKEN.matcher(canonical);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean matched = false;
        while (m.find()) {
            String token = m.group();
            if (token.length() < settings.minFuzzyLength()) continue;
            if (isSafe(original.substring(m.start(), m.end()))) continue;
            boolean hit = false;
            for (String badword : plainBadwords) {
                int allowed = allowedDistance(badword.length());
                if (Math.abs(token.length() - badword.length()) > allowed) continue;
                if (AdvancedProfanityFilter.levenshtein(token, badword) <= allowed) { hit = true; break; }
            }
            if (!hit) continue;
            matched = true;
            sb.append(original, last, m.start());
            if (!useNone) sb.append(repl.repeat(m.end() - m.start()));
            last = m.end();
        }
        if (!matched) return new Replaced(original, false);
        sb.append(original, last, original.length());
        return new Replaced(sb.toString(), true);
    }

    /** Latin-script words ("suka", "blyat", "hui") compared with the list after transliteration. */
    private Replaced transliterated(String original, String repl, boolean useNone) {
        Matcher m = WORD_TOKEN.matcher(original);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean matched = false;
        while (m.find()) {
            String token = m.group();
            if (token.length() < 3 || !Translit.hasLatin(token) || isSafe(token)) continue;
            String folded = Translit.fold(Translit.toCyrillic(token));
            boolean hit = false;
            for (String badword : foldedBadwords) {
                if (folded.equals(badword)
                        || (badword.length() >= 5 && folded.contains(badword))
                        || (badword.length() >= 5
                                && Math.abs(folded.length() - badword.length()) <= 1
                                && AdvancedProfanityFilter.levenshtein(folded, badword) <= 1)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) continue;
            matched = true;
            sb.append(original, last, m.start());
            if (!useNone) sb.append(repl.repeat(m.end() - m.start()));
            last = m.end();
        }
        if (!matched) return new Replaced(original, false);
        sb.append(original, last, original.length());
        return new Replaced(sb.toString(), true);
    }
}
