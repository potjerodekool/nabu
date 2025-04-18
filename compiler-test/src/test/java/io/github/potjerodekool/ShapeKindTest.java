package io.github.potjerodekool;

import  io.github.potjerodekool.nabu.example.ShapeKind;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

public class ShapeKindTest {

    @Test
    void values() {
        final var values = ShapeKind.values();

        for (var value : values) {
            System.out.println(value);
        }
    }
}
