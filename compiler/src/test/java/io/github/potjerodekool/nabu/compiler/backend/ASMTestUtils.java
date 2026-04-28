package io.github.potjerodekool.nabu.compiler.backend;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.stream.Collectors;

public final class ASMTestUtils {

    private ASMTestUtils() {}

    public static void validate(final byte[] bytecode) {
        try {
            final var classReader = new ClassReader(bytecode);
            final var classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);
            final var classValidator = new CheckClassAdapter(classWriter, true);
            classReader.accept(classValidator, 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String byteCodeToText(final byte[] bytecode) {
        final var sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        final var textifier = new Textifier();
        final var visitor = new TraceClassVisitor(null, textifier, pw);

        final var reader = new ClassReader(bytecode);
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);

        return textifier.getText().stream()
                .map(it -> {
                    if (it instanceof List<?> list) {
                        return list.stream()
                                .map(Object::toString)
                                .collect(Collectors.joining());
                    }
                    return it.toString();
                })
                .collect(Collectors.joining());
    }
}
