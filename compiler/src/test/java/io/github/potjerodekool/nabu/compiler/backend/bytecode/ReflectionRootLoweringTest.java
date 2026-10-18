package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.nativeimage.GraalVMNativeImageConfig.MethodEntry;
import io.github.potjerodekool.nabu.backend.nativeimage.GraalVMNativeImageConfig.ReflectEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Klassen uit de GraalVM-reflectie-configuratie zijn wortels van de
 * sluiting: hun constructors en methodes worden gelowered zoals de
 * configuratie ze noemt, ook zonder aanroep vanuit de broncode.
 */
class ReflectionRootLoweringTest {

    private static List<String> functionsOf(final List<IRModule> modules, final String className) {
        return modules.stream()
                .filter(m -> m.name.replace('/', '.').equals(className))
                .flatMap(m -> m.functions().stream())
                .filter(f -> !f.isExternal())
                .map(f -> f.name)
                .toList();
    }

    @Test
    void publicConstructorsOnly() {
        final var root = new ReflectEntry("java.util.StringJoiner",
                false, true, false, false, List.of(), List.of());

        final var modules = new BytecodeReachabilityCompiler(null, Set.of())
                .compileClosure(List.of(root), List.of());

        final var names = functionsOf(modules, "java.util.StringJoiner");
        assertTrue(names.stream().anyMatch(n -> n.contains("StringJoiner_init")), names.toString());
        assertFalse(names.stream().anyMatch(n -> n.endsWith("_setEmptyValue")), names.toString());
    }

    @Test
    void explicitMethod() {
        final var root = new ReflectEntry("java.util.StringJoiner",
                false, false, false, false, List.of(),
                List.of(new MethodEntry("setEmptyValue", List.of("java.lang.CharSequence"))));

        final var modules = new BytecodeReachabilityCompiler(null, Set.of())
                .compileClosure(List.of(root), List.of());

        final var names = functionsOf(modules, "java.util.StringJoiner");
        assertTrue(names.stream().anyMatch(n -> n.endsWith("_setEmptyValue")), names.toString());
    }

    @Test
    void runtimeVisibleAnnotationsReachTheIr() {
        final var root = new ReflectEntry("java.lang.Runnable",
                false, false, false, false, List.of(), List.of());

        final var modules = new BytecodeReachabilityCompiler(null, Set.of())
                .compileClosure(List.of(root), List.of());

        final var runnable = modules.stream()
                .filter(m -> m.name.replace('/', '.').equals("java.lang.Runnable"))
                .findFirst()
                .orElseThrow();
        assertTrue(runnable.bytecodeAnnotations().stream()
                        .anyMatch(a -> a.typeInternalName().equals("java/lang/FunctionalInterface")),
                runnable.bytecodeAnnotations().toString());
    }

    @Test
    void sourceClassesAreSkipped() {
        final var root = new ReflectEntry("java.util.StringJoiner",
                true, true, true, true, List.of(), List.of());

        final var modules = new BytecodeReachabilityCompiler(null, Set.of("java.util.StringJoiner"))
                .compileClosure(List.of(root), List.of());

        assertTrue(functionsOf(modules, "java.util.StringJoiner").isEmpty());
    }
}
