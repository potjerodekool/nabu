package io.github.potjerodekool.employee;

import io.github.potjerodekool.nabu.example.Box;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BoxTest {

    @Test
    void test() throws Exception {
        final var box = new Box();
        Assertions.assertTrue(box.isBox());
    }
}
