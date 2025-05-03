package io.github.potjerodekool;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.util.List;

public class ByteCodePrinter {

    public static void print(final String resourceName) {
        try (var input = ByteCodePrinter.class.getClassLoader().getResourceAsStream(resourceName)) {
            final var reader = new ClassReader(
                    input.readAllBytes()
            );

            final var visitor = new TraceClassVisitor(
                    null,
                    null
            );

            reader.accept(visitor, 0);

            print(visitor.p.getText());
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }


    private static void print(final List<?> list) {
        list.forEach(e -> {
            if (e instanceof List subList) {
                print(subList);
            } else {
                System.out.print(e);
            }
        });
    }
}
