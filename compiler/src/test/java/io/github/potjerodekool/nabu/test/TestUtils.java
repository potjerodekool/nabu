package io.github.potjerodekool.nabu.test;


import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

public final class TestUtils {

    private TestUtils() {
    }

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
