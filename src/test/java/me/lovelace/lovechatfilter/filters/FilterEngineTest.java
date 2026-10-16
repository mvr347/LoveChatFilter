package me.lovelace.lovechatfilter.filters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilterEngineTest {
    @Test
    void stripPercentRemovesPlaceholderDelimiters() {
        assertEquals("server_ip", FilterEngine.stripPercent("%server_ip%"));
        assertEquals("", FilterEngine.stripPercent(null));
    }
}
