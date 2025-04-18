package io.github.potjerodekool;

import io.github.potjerodekool.nabu.example.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StatusTest {

    @Test
    void test() {
        final var values = Status.values();

        assertEquals("On", values[0].getText());
        assertEquals("Off", values[1].getText());
    }
}
