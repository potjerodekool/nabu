package io.github.potjerodekool.nabu.test;


import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

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

}
