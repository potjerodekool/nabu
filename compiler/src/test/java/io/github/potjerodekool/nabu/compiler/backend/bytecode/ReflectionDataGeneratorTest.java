package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.compiler.backend.bytecode.ReflectionDataGenerator.ClassMethods;
import io.github.potjerodekool.nabu.compiler.backend.bytecode.ReflectionDataGenerator.ReflectedMethod;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionDataGeneratorTest {

    private static ReflectedMethod method(final String owner, final String name, final String desc) {
        return new ReflectedMethod(owner, false, name, desc, Opcodes.ACC_PUBLIC);
    }

    @Test
    void aMethodWithManyParametersHasEnoughStack() {
        // Fifteen parameters, three of them two slots wide: more than the fixed stack size the generator had.
        final var desc = "(IJDLjava/lang/String;ZBCSFIIIIII)V";
        final var manyParameters = method("p/Big", "run", desc);
        final var constructor = method("p/Big", "<init>", desc);
        final var classes = Map.of("p.Big", new ClassMethods(List.of(manyParameters), List.of(manyParameters),
                List.of(constructor)));

        final var generated = ReflectionDataGenerator.generate(classes, List.of());

        for (final MethodNode node : generated.methods) {
            assertDoesNotThrow(() -> new Analyzer<>(new BasicInterpreter()).analyze(generated.name, node),
                    node.name);
        }
    }

    @Test
    void thousandsOfMethodsDoNotMakeOneMethodTooLarge() {
        // A program with Spring has thousands of reflected methods; a method of the JVM has at most 64 KB of code.
        final Map<String, ClassMethods> classes = new LinkedHashMap<>();
        for (int c = 0; c < 400; c++) {
            final var owner = "p/Class" + c;
            final List<ReflectedMethod> methods = new ArrayList<>();
            for (int m = 0; m < 30; m++) {
                methods.add(method(owner, "method" + m, "(Ljava/lang/String;I)Ljava/lang/Object;"));
            }
            final var constructors = List.of(method(owner, "<init>", "()V"));
            classes.put(owner.replace('/', '.'), new ClassMethods(methods, methods, constructors));
        }

        final var generated = ReflectionDataGenerator.generate(classes, List.of());
        final var writer = new ClassWriter(0);
        generated.accept(writer);

        // toByteArray throws a MethodTooLargeException for a method of more than 64 KB.
        final var bytes = assertDoesNotThrow(writer::toByteArray);
        assertTrue(bytes.length > 0);

        // The three entry points are still there, with the same signatures as before.
        final var names = generated.methods.stream().map(node -> node.name + node.desc).toList();
        assertTrue(names.contains("methods(Ljava/lang/String;Z)[Ljava/lang/reflect/Method;"));
        assertTrue(names.contains("constructors(Ljava/lang/String;)[Ljava/lang/reflect/Constructor;"));
        assertTrue(names.contains("invoke(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"));

        for (final MethodNode node : generated.methods) {
            assertDoesNotThrow(() -> new Analyzer<>(new BasicInterpreter()).analyze(generated.name, node),
                    node.name);
        }
    }

    @Test
    void everyMethodIsInExactlyOneBucket() {
        final List<ReflectedMethod> methods = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            methods.add(method("p/Owner", "m" + i, "()V"));
        }
        final var classes = Map.of("p.Owner", new ClassMethods(methods, List.of(), List.of()));

        final var generated = ReflectionDataGenerator.generate(classes, List.of());

        // Every key appears once as a constant in the invoke$N methods.
        final var constants = new ArrayList<String>();
        for (final MethodNode node : generated.methods) {
            if (!node.name.startsWith("invoke$")) {
                continue;
            }
            node.instructions.forEach(insn -> {
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof String text
                        && text.startsWith("p/Owner#")) {
                    constants.add(text);
                }
            });
        }
        assertEquals(500, constants.size());
        assertEquals(500, constants.stream().distinct().count());
    }
}
