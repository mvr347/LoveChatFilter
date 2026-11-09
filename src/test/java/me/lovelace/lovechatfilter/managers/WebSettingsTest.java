package me.lovelace.lovechatfilter.managers;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WebSettingsTest {

    @Test
    void onlyKnownKeysAreAccepted() {
        assertThrows(IllegalArgumentException.class, () -> WebSettings.validate("ignore-commands", "x"));
        assertThrows(IllegalArgumentException.class, () -> WebSettings.validate(null, "x"));
        assertEquals(Boolean.TRUE, WebSettings.validate("modules.anti-caps", "TRUE"));
        assertEquals(Boolean.FALSE, WebSettings.validate("modules.anti-caps", " false "));
    }

    @Test
    void numbersStayInsideTheirRange() {
        assertEquals(70, WebSettings.validate("anti-caps.max-caps-percent", "70"));
        assertThrows(IllegalArgumentException.class, () -> WebSettings.validate("anti-caps.max-caps-percent", "0"));
        assertThrows(IllegalArgumentException.class, () -> WebSettings.validate("anti-caps.max-caps-percent", "101"));
        assertThrows(IllegalArgumentException.class, () -> WebSettings.validate("anti-caps.max-caps-percent", "много"));
        assertThrows(IllegalArgumentException.class, () -> WebSettings.validate("modules.anti-caps", "yes"));
    }

    @Test
    void textHasALengthLimitAndNoControlCharacters() {
        assertEquals("NONE", WebSettings.validate("badwords-filter.replacement-char", "NONE"));
        assertThrows(IllegalArgumentException.class, () -> WebSettings.validate("badwords-filter.replacement-char", ""));
        assertThrows(IllegalArgumentException.class, () -> WebSettings.validate("badwords-filter.replacement-char", "12345"));
        assertThrows(IllegalArgumentException.class, () -> WebSettings.validate("badwords-filter.replacement-char", "a\nb"));
    }

    @Test
    void describeListsEverySettingWithItsCurrentValue() {
        var rows = WebSettings.describe(key -> key.equals("anti-caps.max-caps-percent") ? 70 : false,
                Set.of("anti-caps.max-caps-percent"));
        assertEquals(WebSettings.all().size(), rows.size());
        var caps = rows.stream().filter(r -> r.get("key").equals("anti-caps.max-caps-percent")).findFirst().orElseThrow();
        assertEquals(70, caps.get("value"));
        assertEquals(Boolean.TRUE, caps.get("overridden"));
        assertEquals("int", caps.get("type"));
        assertEquals(List.of(1, 100), List.of(caps.get("min"), caps.get("max")));
    }
}
