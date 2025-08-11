package io.github.potjerodekool;

import  io.github.potjerodekool.nabu.example.ShapeKind;
import org.junit.jupiter.api.Test;

public class ShapeKindTest {

    @Test
    void values() {
        final var values = ShapeKind.values();

        for (var value : values) {
            System.out.println(value);
        }
    }
}
