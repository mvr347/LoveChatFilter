package me.lovelace.lovechatfilter.filters;

/**
 * Latin transliteration of Russian speech ("suka", "blyat", "hui") back to Cyrillic, plus a
 * coarse "fold" that erases the letter distinctions transliteration cannot express (й/и, ц/к,
 * ь/ъ...). Both sides of a comparison go through the same fold, so "blyat" meets "блять".
 *
 * Not 1:1 (digraphs shrink), so callers match whole tokens and censor whole tokens.
 */
final class Translit {

    private Translit() {}

    static boolean hasLatin(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = Character.toLowerCase(token.charAt(i));
            if (c >= 'a' && c <= 'z') return true;
        }
        return false;
    }

    static String toCyrillic(String latin) {
        String s = latin.toLowerCase();
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            switch (c) {
                case 's' -> {
                    if (next == 'h') { out.append('ш'); i++; } else out.append('с');
                }
                case 'c' -> {
                    if (next == 'h') { out.append('ч'); i++; } else out.append('ц');
                }
                case 'z' -> {
                    if (next == 'h') { out.append('ж'); i++; } else out.append('з');
                }
                case 'k' -> {
                    if (next == 'h') { out.append('х'); i++; } else out.append('к');
                }
                case 't' -> {
                    if (next == 's') { out.append('ц'); i++; } else out.append('т');
                }
                case 'y' -> {
                    switch (next) {
                        case 'a' -> { out.append('я'); i++; }
                        case 'u' -> { out.append('ю'); i++; }
                        case 'o' -> { out.append('ё'); i++; }
                        case 'e' -> { out.append('е'); i++; }
                        // After a vowel y is the glide ("xuy" -> хуй, "moy" -> мой); after a consonant it
                        // is the vowel ("xyu", "pizdy").
                        default -> out.append(endsWithVowel(out) ? 'й' : 'у');
                    }
                }
                case 'a' -> out.append('а');
                case 'b' -> out.append('б');
                case 'd' -> out.append('д');
                case 'e' -> out.append('е');
                case 'f' -> out.append('ф');
                case 'g' -> out.append('г');
                case 'h' -> out.append('х');
                case 'i' -> out.append('и');
                case 'j' -> out.append('й');
                case 'l' -> out.append('л');
                case 'm' -> out.append('м');
                case 'n' -> out.append('н');
                case 'o' -> out.append('о');
                case 'p' -> out.append('п');
                case 'q' -> out.append('к');
                case 'r' -> out.append('р');
                case 'u' -> out.append('у');
                case 'v' -> out.append('в');
                case 'w' -> out.append('в');
                case 'x' -> out.append('х');
                case '\'' -> out.append('ь');
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean endsWithVowel(StringBuilder out) {
        return !out.isEmpty() && "аеёиоуыэюя".indexOf(out.charAt(out.length() - 1)) >= 0;
    }

    /**
     * Erases distinctions that Latin spelling cannot carry: й=и, ё=е, э=е, ы=и, ц=к, ь/ъ dropped,
     * and runs of the same letter squashed. "хуй" and "hui", "блять" and "blyat" fold equal.
     */
    static String fold(String cyrillic) {
        StringBuilder out = new StringBuilder(cyrillic.length());
        char previous = '\0';
        for (int i = 0; i < cyrillic.length(); i++) {
            char c = Character.toLowerCase(cyrillic.charAt(i));
            char mapped = switch (c) {
                case 'й', 'ы' -> 'и';
                case 'ё', 'э' -> 'е';
                case 'ц' -> 'к';
                case 'ь', 'ъ' -> '\0';
                default -> c;
            };
            if (mapped == '\0' || mapped == previous) continue;
            out.append(mapped);
            previous = mapped;
        }
        return out.toString();
    }
}
