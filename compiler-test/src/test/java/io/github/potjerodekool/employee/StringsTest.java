package io.github.potjerodekool.employee;

import io.github.potjerodekool.ByteCodePrinter;
import org.junit.jupiter.api.Test;

import io.github.potjerodekool.nabu.example.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StringsTest {

    @Test
    void test() throws Exception {
        ByteCodePrinter.print("io/github/potjerodekool/nabu/example/Strings.class");

        /*
        //final var strings = Strings.class.getDeclaredConstructor().newInstance();
        final var strings = new Strings();
        final var actual = strings.sayHi();
        final var expected = "Hi Evert";
        assertEquals(expected, actual);
        */
    }
}
