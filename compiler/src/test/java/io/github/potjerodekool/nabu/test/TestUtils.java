package io.github.potjerodekool.nabu.test;


import java.io.IOException;

/**
 * Test utilities.
 */
public final class TestUtils {

    private TestUtils() {
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
