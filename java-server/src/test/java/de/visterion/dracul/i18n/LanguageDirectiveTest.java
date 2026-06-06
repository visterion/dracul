package de.visterion.dracul.i18n;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDirectiveTest {

    @Test
    void appendsGermanDirectiveNamingTheLanguage() {
        String result = LanguageDirective.append("BASE PROMPT", "de");
        assertThat(result).startsWith("BASE PROMPT");
        assertThat(result).contains("German");
        assertThat(result).contains("do not translate");
    }

    @Test
    void appendsEnglishDirectiveNamingTheLanguage() {
        String result = LanguageDirective.append("BASE PROMPT", "en");
        assertThat(result).contains("English");
    }

    @Test
    void unknownLanguageFallsBackToGerman() {
        String result = LanguageDirective.append("BASE", "xx");
        assertThat(result).contains("German");
    }

    @Test
    void blankLanguageFallsBackToGerman() {
        assertThat(LanguageDirective.append("BASE", null)).contains("German");
        assertThat(LanguageDirective.append("BASE", "  ")).contains("German");
    }
}
