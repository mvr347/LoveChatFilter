package me.lovelace.lovechatfilter.filters;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TranslitTest {
    @Test
    void latinSpellingsFoldToTheSameAsTheCyrillicWord() {
        assertEquals(Translit.fold("хуй"), Translit.fold(Translit.toCyrillic("hui")));
        assertEquals(Translit.fold("хуй"), Translit.fold(Translit.toCyrillic("xuy")));
        assertEquals(Translit.fold("блять"), Translit.fold(Translit.toCyrillic("blyat")));
        assertEquals(Translit.fold("сука"), Translit.fold(Translit.toCyrillic("suka")));
    }

    @Test
    void blatIsNotBlyat() {
        assertNotEquals(Translit.fold("блять"), Translit.fold(Translit.toCyrillic("blat")));
    }

    @Test
    void detectsLatinLetters() {
        assertTrue(Translit.hasLatin("sука"));
        assertFalse(Translit.hasLatin("сука"));
    }
}
