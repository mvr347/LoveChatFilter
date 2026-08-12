package me.lovelace.lovechatfilter.filters;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catches obfuscated profanity that the plain {@code (?uiU)} regex misses:
 * homoglyphs (Latin look-alikes) and digit-substitution (leetspeak), plus
 * typo-tolerant matching for genuine misspellings.
 *
 * Canonicalization is strictly char-for-char (no insertions/deletions), so
 * match offsets found in the canonical string always line up with the same
 * offsets in the original message - no separate index-mapping is needed.
 */
final class AdvancedProfanityFilter {

    private static final Pattern WORD_TOKEN = Pattern.compile("(?U)\\p{L}+");
    private static final Pattern NON_LETTER = Pattern.compile("(?U)[^\\p{L}]+");
    private static final Pattern STRETCHED_LETTER = Pattern.compile("(?U)(\\p{L})\\1{2,}");

    private AdvancedProfanityFilter() {}

    record Result(String text, boolean wasFiltered) {}

    /**
     * Collapses a canonical (homoglyph/digit-mapped) message down to bare letters, with any
     * run of 3+ identical letters squashed to 1. Catches evasion that spreads a word across
     * spaces/punctuation ("с у . к а") or stretches it ("сууукааа") — both defeat the smart
     * regex's per-letter {@code [\W_]*} gaps once combined with homoglyphs, since that regex
     * still expects letters in the original left-to-right order but doesn't reason about
     * lookahead across many separators as cheaply as a plain substring check does. Position
     * information is intentionally discarded: callers that need offsets should fall back to
     * whole-message replacement when only the compact form matches.
     */
    static String compact(String canonicalText) {
        String noSeparators = NON_LETTER.matcher(canonicalText).replaceAll("");
        return STRETCHED_LETTER.matcher(noSeparators).replaceAll("$1");
    }

    /** Same collapsing rule applied to a single badword, so both sides compare on equal footing. */
    static String compactWord(String word) {
        String noSeparators = NON_LETTER.matcher(word).replaceAll("");
        return STRETCHED_LETTER.matcher(noSeparators).replaceAll("$1");
    }

    /**
     * Cheap substring scan for badwords hidden by spacing/punctuation/letter-stretching once
     * both sides are reduced to their compact form. This intentionally cannot report offsets
     * (the compaction is not 1:1 with the original text), so it only answers "does this message
     * contain a badword once evasion is stripped away" — callers must censor the whole message,
     * not attempt a partial replace.
     */
    static boolean containsCompactMatch(String compactText, List<String> badwords, int minLength) {
        for (String badword : badwords) {
            String compactBadword = compactWord(badword);
            if (compactBadword.length() >= minLength && compactText.contains(compactBadword)) {
                return true;
            }
        }
        return false;
    }

    static String canonicalize(String text, boolean homoglyphs, boolean digits) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = Character.toLowerCase(chars[i]);
            if (homoglyphs) c = mapHomoglyph(c);
            if (digits) c = mapDigit(c);
            chars[i] = c;
        }
        return new String(chars);
    }

    private static char mapHomoglyph(char c) {
        return switch (c) {
            case 'a' -> 'а';
            case 'e' -> 'е';
            case 'o' -> 'о';
            case 'p' -> 'р';
            case 'c' -> 'с';
            case 'y' -> 'у';
            case 'x' -> 'х';
            case 'b' -> 'б';
            case 'n' -> 'п';
            case 'm' -> 'м';
            case 'h' -> 'н';
            case 'k' -> 'к';
            case 'l' -> 'л';
            default -> c;
        };
    }

    private static char mapDigit(char c) {
        return switch (c) {
            case '0' -> 'о';
            case '3' -> 'з';
            case '4' -> 'а';
            case '5' -> 'с';
            case '6' -> 'б';
            case '7' -> 'т';
            case '8' -> 'в';
            default -> c;
        };
    }

    /** Levenshtein-matches whole letter-tokens against the badword list; offsets are 1:1 with {@code original}. */
    static Result applyFuzzyFilter(String original, String canonical, List<String> badwords, int maxDistance, int minLength, String repl) {
        boolean useNone = repl.equalsIgnoreCase("NONE");
        Matcher m = WORD_TOKEN.matcher(canonical);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean matched = false;

        while (m.find()) {
            String token = m.group();
            if (token.length() < minLength) continue;

            boolean tokenMatched = false;
            for (String badword : badwords) {
                if (Math.abs(token.length() - badword.length()) > maxDistance) continue;
                if (levenshtein(token, badword) <= maxDistance) {
                    tokenMatched = true;
                    break;
                }
            }

            if (tokenMatched) {
                matched = true;
                sb.append(original, last, m.start());
                sb.append(useNone ? "" : repl.repeat(m.end() - m.start()));
                last = m.end();
            }
        }

        sb.append(original, last, original.length());
        return new Result(sb.toString(), matched);
    }

    private static int levenshtein(CharSequence a, CharSequence b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;

        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }
}
