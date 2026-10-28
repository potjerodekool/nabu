package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.FinalSigmaSupport;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FinalSigmaSupport} replaces the BreakIterator of the JDK in the final-sigma rule of {@code toLowerCase};
 * for the words of a text it must give what the JDK gives.
 */
class FinalSigmaSupportTest {

    /** Lower-cases the way the JDK does, with the support in place of the word BreakIterator. */
    private static String lower(final String text) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == 'Σ') {
                out.append(FinalSigmaSupport.isFinalCased(text, i) ? 'ς' : 'σ');
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    @Test
    void aSigmaAtTheEndOfAWordIsFinal() {
        assertTrue(FinalSigmaSupport.isFinalCased("ΟΔΟΣ", 3));
        assertTrue(FinalSigmaSupport.isFinalCased("ΣΣ ", 1));
    }

    @Test
    void aSigmaAloneOrInTheMiddleOrAtTheStartIsNot() {
        assertFalse(FinalSigmaSupport.isFinalCased("Σ", 0));
        assertFalse(FinalSigmaSupport.isFinalCased("ΣΑ", 0));
        assertFalse(FinalSigmaSupport.isFinalCased("ΑΣΑ", 1));
        assertFalse(FinalSigmaSupport.isFinalCased("x Σ", 2));
    }

    @Test
    void anApostropheBetweenLettersBelongsToTheWord() {
        // the letter after the apostrophe is cased, so the sigma is not at the end of the word
        assertFalse(FinalSigmaSupport.isFinalCased("ΑΣ'Α", 1));
        assertTrue(FinalSigmaSupport.isFinalCased("ΑΣ' ", 1));
    }

    @Test
    void theResultIsTheOneOfTheJdk() {
        final String[] texts = {
                "ΟΔΟΣ", "ΟΔΟΣ ΟΔΟΣ.", "Σ", "ΣΑΣ",
                "ΑΣ ΒΣ, ΓΣ!", "ΣΣΣ", "ABC Σ", "Σ ABC", "xΣy", "ÉΣ",
                "ΑΣ'Α", "ΑΣ.Α", "ΑΣ1", "1Σ", "ΆΣ"
        };
        for (final String text : texts) {
            assertEquals(text.toLowerCase(Locale.ROOT), lower(text), text);
        }
    }
}
