package me.lovelace.lovechatfilter.managers;

import static me.lovelace.lovechatfilter.managers.WordRules.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WordRulesTest {
    @Test
    void kindsAcceptRussianAndEnglishNames() {
        assertEquals(BAD, WordRules.Kind.parse("мат"));
        assertEquals(SAFE, WordRules.Kind.parse("БЕЗОПАСНОЕ"));
        assertEquals(AD, WordRules.Kind.parse("ads"));
        assertNull(WordRules.Kind.parse("что-то"));
        assertNull(WordRules.Kind.parse(null));
    }

    @Test
    void badWordIsNormalisedAndBounded() {
        assertEquals("иди туда", WordRules.validate(BAD, "  Иди   ТУДА "));
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(BAD, "аб"));
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(BAD, "123456"));
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(BAD, "x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(BAD, "слово\nещё"));
    }

    @Test
    void regexIsNotAcceptedFromTheGame() {
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(BAD, "(?i)(a+)+$"));
    }

    @Test
    void safeWordAllowsOnlyOneWordWithOptionalStar() {
        assertEquals("скрафт*", WordRules.validate(SAFE, "Скрафт*"));
        assertEquals("елка", WordRules.validate(SAFE, "Ёлка"));
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(SAFE, "два слова"));
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(SAFE, "ск*"));
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(SAFE, "с.л.о.в.о"));
    }

    @Test
    void adIsALiteralWithoutSpaces() {
        assertEquals("mysrv.net", WordRules.validate(AD, "MySrv.NET"));
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(AD, "my srv"));
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(AD, "..."));
        assertThrows(IllegalArgumentException.class, () -> WordRules.validate(AD, "ab"));
    }
}
