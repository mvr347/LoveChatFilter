package me.lovelace.lovechatfilter.filters;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextRulesTest {
    @Test
    void obfuscatedDotBeforeADomainZoneIsNormalised() {
        assertEquals("заходи mysrv.net", TextRules.normalizeObfuscatedDots("заходи mysrv (точка) net"));
        assertEquals("mysrv.net", TextRules.normalizeObfuscatedDots("mysrv [.] net"));
        assertEquals("mysrv.gg", TextRules.normalizeObfuscatedDots("mysrv (dot) gg"));
        assertEquals("mysrv.ru", TextRules.normalizeObfuscatedDots("mysrv точка ru"));
    }

    @Test
    void ordinarySpeechWithADotWordIsLeftAlone() {
        for (String text : List_.OF) assertEquals(text, TextRules.normalizeObfuscatedDots(text), text);
    }

    @Test
    void repeatedCharactersAreShortenedToTheCap() {
        assertEquals("ааааа", TextRules.collapseRepeats("аааааааааа", 5));
        assertEquals("ну!!!!! ладно", TextRules.collapseRepeats("ну!!!!!!!!!! ладно", 5));
        assertEquals("даааа", TextRules.collapseRepeats("даааа", 5));
        assertEquals("ааааааааа", TextRules.collapseRepeats("ааааааааа", 0));
    }

    private static final class List_ {
        static final String[] OF = {
            "встретимся в точке сбора",
            "поставь точку net в конце",
            "точка рублей не хватает",
        };
    }
}
