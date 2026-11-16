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

    /** The class with the entry point of the program, or null. */
    private static native Class<?> mainClass0();

    /**
     * The one frame a walk sees: {@code main} of the program's main class. Spring Boot looks for the frame of
     * {@code main} to find its main application class (the AOT initializers are named after it).
     */
    public static Object walk(final StackWalker walker, final Function<Object, Object> function) {
        final Class<?> main = mainClass0();
        if (main == null) {
            return function.apply(Stream.empty());
        }
        return function.apply(Stream.of(new MainFrame(main)));
    }

    private static final class MainFrame implements StackWalker.StackFrame {
        private final Class<?> type;

        MainFrame(final Class<?> type) {
            this.type = type;
        }

        @Override
        public String getClassName() {
            return type.getName();
        }

        @Override
        public String getMethodName() {
            return "main";
        }

        @Override
        public Class<?> getDeclaringClass() {
            return type;
        }

        @Override
        public int getByteCodeIndex() {
            return 0;
        }

        @Override
        public String getFileName() {
            return null;
        }

        @Override
        public int getLineNumber() {
            return -1;
        }

        @Override
        public boolean isNativeMethod() {
            return false;
        }

        @Override
        public StackTraceElement toStackTraceElement() {
            return new StackTraceElement(type.getName(), "main", null, -1);
        }
    }

    public static void forEach(final StackWalker walker, final Consumer<Object> action) {
    }

    public static Class<?> getCallerClass(final StackWalker walker) {
        return Object.class;
    }
}
