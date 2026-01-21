package io.github.potjerodekool.nabu;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class ByteCodePrinter {

    public static void print(final String resourceName) {
        try (var input = getInputStream(resourceName)) {
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

    public static void validate(final String resourceName) {
        try(var input = getInputStream(resourceName)) {
            final var bytecode = input.readAllBytes();
            final var classReader = new ClassReader(bytecode);
            final var classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);
            final var classValidator = new CheckClassAdapter(classWriter, true);
            classReader.accept(classValidator, 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Attempts to open an InputStream for the given resource name.
     * When using this method, ensure that the returned InputStream is properly closed.
     *
     * @param resourceName The name of the resource to open.
     * @return InputStream or null if resource not found
     */
    private static InputStream getInputStream(final String resourceName) {
        final var input = ByteCodePrinter.class.getClassLoader().getResourceAsStream(resourceName);

        if (input != null) {
            return input;
        }

        try {
            return new FileInputStream(resourceName);
        } catch (final FileNotFoundException e) {
            return null;
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
