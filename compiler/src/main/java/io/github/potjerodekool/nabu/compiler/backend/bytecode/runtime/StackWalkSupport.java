package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * StackWalker for native programs. There is no VM stack to walk, so the walk sees no frames: code that looks for a
 * caller (Spring's main-class deduction) gets an empty answer and carries on.
 */
public final class StackWalkSupport {

    private StackWalkSupport() {
    }

    public static Object walk(final StackWalker walker, final Function<Object, Object> function) {
        return function.apply(Stream.empty());
    }

    public static void forEach(final StackWalker walker, final Consumer<Object> action) {
    }

    public static Class<?> getCallerClass(final StackWalker walker) {
        return Object.class;
    }
}
