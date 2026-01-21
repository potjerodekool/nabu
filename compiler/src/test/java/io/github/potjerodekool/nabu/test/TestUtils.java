package io.github.potjerodekool.nabu.test;


import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.io.IOException;

/**
 * Test utilities.
 */
public final class TestUtils {

    private TestUtils() {
    }

    /**
     * Resets the counter field of ILabel
     */
    public static void resetLabels() {
        try {
            final var field = ILabel.class.getDeclaredField("cntr");
            field.trySetAccessible();
            field.set(null, 0);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String readResource(final String name) {
        try (var input = TestUtils.class.getClassLoader().getResourceAsStream(name)) {
            return new String(input.readAllBytes());
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String fixLines(final String text) {
        if (text == null || text.isEmpty()) {
            return text;
        } else {
            return text.replace("\r", "");
        }
    }

}
