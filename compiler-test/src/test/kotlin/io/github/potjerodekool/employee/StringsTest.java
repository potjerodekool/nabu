package io.github.potjerodekool.employee;

import io.github.potjerodekool.ByteCodePrinter;
import org.junit.jupiter.api.Test;

import io.github.potjerodekool.nabu.example.Strings;

public class StringsTest {

    @Test
    void test() {
        ByteCodePrinter.print("io/github/potjerodekool/nabu/example/SwitchIt$1.class");
        //ByteCodePrinter.print("C:\\projects\\nabu\\jpa-support\\target\\test-classes\\io\\github\\potjerodekool\\nabu\\EnumUsage$1.class");
        //

        /*
        //final var strings = Strings.class.getDeclaredConstructor().newInstance();
        final var strings = new Strings();
        final var actual = strings.sayHi();
        final var expected = "Hi Evert";
        assertEquals(expected, actual);
        */
    }
}
