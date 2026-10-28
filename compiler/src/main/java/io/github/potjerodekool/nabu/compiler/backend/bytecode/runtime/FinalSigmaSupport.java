package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * The "Final_Cased" condition of {@code ConditionalSpecialCasing} (a capital sigma becomes a final sigma at the end of
 * a word when a string is lower-cased) without {@code BreakIterator}. The JDK asks a word BreakIterator for the word
 * boundaries; that brings the locale providers, resource bundles, regex and streams into every program that calls
 * {@code toLowerCase}. Here a word is a run of letters, digits, marks and underscores, with an apostrophe or a full stop
 * between two letters as part of it (the rules of UAX 29 that matter for words of a language).
 *
 * <p>Written for bytecode lowering: no string concatenation with {@code +} and no lambdas.
 */
public final class FinalSigmaSupport {

    private FinalSigmaSupport() {
    }

    /** Replaces {@code ConditionalSpecialCasing.isFinalCased(String, int, Locale)}. */
    public static boolean isFinalCased(final String src, final int index) {
        final int len = src.length();
        for (int i = index; i >= 0 && !isBoundary(src, i); ) {
            final int ch = src.codePointBefore(i);
            if (isCased(ch)) {
                for (int j = index + Character.charCount(src.codePointAt(index)); j < len && !isBoundary(src, j); ) {
                    final int after = src.codePointAt(j);
                    if (isCased(after)) {
                        return false;
                    }
                    j += Character.charCount(after);
                }
                return true;
            }
            i -= Character.charCount(ch);
        }
        return false;
    }

    private static boolean isBoundary(final String src, final int i) {
        final int len = src.length();
        if (i <= 0 || i >= len) {
            return true;
        }
        final int before = src.codePointBefore(i);
        final int at = src.codePointAt(i);
        if (isWordCharacter(before) && isWordCharacter(at)) {
            return false;
        }
        // an apostrophe or a full stop between two letters
        if (isJoiner(at) && Character.isLetter(before)) {
            final int next = i + Character.charCount(at);
            return !(next < len && Character.isLetter(src.codePointAt(next)));
        }
        if (isJoiner(before) && Character.isLetter(at)) {
            final int previous = i - Character.charCount(before);
            return !(previous > 0 && Character.isLetter(src.codePointBefore(previous)));
        }
        return true;
    }

    private static boolean isJoiner(final int ch) {
        return ch == '\'' || ch == '.' || ch == 0x2019 || ch == 0x2024;
    }

    private static boolean isWordCharacter(final int ch) {
        if (Character.isLetterOrDigit(ch) || ch == '_') {
            return true;
        }
        final int type = Character.getType(ch);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    /** As {@code ConditionalSpecialCasing.isCased}: the cased letters, with Other_Lowercase and Other_Uppercase. */
    private static boolean isCased(final int ch) {
        final int type = Character.getType(ch);
        if (type == Character.LOWERCASE_LETTER || type == Character.UPPERCASE_LETTER
                || type == Character.TITLECASE_LETTER) {
            return true;
        }
        return Character.isLowerCase(ch) || Character.isUpperCase(ch);
    }
}
