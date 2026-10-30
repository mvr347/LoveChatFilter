package me.lovelace.lovechatfilter.filters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small pure text rules used by the engine, kept apart so they can be tested without a server. */
final class TextRules {
    private TextRules() {}

    /**
     * "[.]", "(.)", "(dot)", " dot ", " точка " followed by a domain zone: a dot written so that a
     * link filter misses it. The zone lookahead is the guard: "встретимся в точке сбора" has no zone.
     */
    private static final Pattern OBFUSCATED_DOT = Pattern.compile(
            "(?iu)\\s*(?:[\\[({]\\s*(?:\\.|dot|точка)\\s*[\\])}]|\\s(?:dot|точка)\\s)\\s*"
                    + "(?=(?:ru|рф|com|net|org|gg|me|su|io|xyz|fun|pro|club|online|tk|ml)(?![\\p{L}\\d]))");

    static String normalizeObfuscatedDots(String text) {
        return OBFUSCATED_DOT.matcher(text).replaceAll(".");
    }

    /** Shortens runs of one character (letter, digit or punctuation) longer than {@code max} down to {@code max}. */
    static String collapseRepeats(String text, int max) {
        if (max < 2) return text;
        Pattern pattern = Pattern.compile("(?U)([\\p{L}\\d\\p{Punct}])\\1{" + max + ",}");
        return pattern.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1).repeat(max)));
    }
}
