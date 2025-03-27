package io.github.potjerodekool.employee;

import org.junit.jupiter.api.Test;

import io.github.potjerodekool.nabu.example.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StringsTest {

    @Test
    void test() {
        final var strings = new Strings();
        final var actual = strings.sayHi();
        final var expected = "Hi Evert";
        assertEquals(expected, actual);
    }
}
