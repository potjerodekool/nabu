package io.github.potjerodekool.employee;

import io.github.potjerodekool.ByteCodePrinter;
import io.github.potjerodekool.nabu.example.IdAndName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IdAndNameTest {

    @Test
    void test() {
        ByteCodePrinter.print("io/github/potjerodekool/nabu/example/IdAndName.class");

        final var idAndName = new IdAndName("10", "Evert");
        final Class<?> clazz = idAndName.getClass();
        assertEquals(Record.class, clazz.getSuperclass());
        assertEquals("10", idAndName.id());
        assertEquals("Evert", idAndName.name());
    }
}
