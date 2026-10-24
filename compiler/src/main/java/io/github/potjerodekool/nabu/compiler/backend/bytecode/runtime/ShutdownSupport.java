package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Shutdown hooks in a native executable. {@code Runtime.addShutdownHook} is redirected here (see
 * {@code RuntimeCalls}); the hooks run, one after the other on the exiting thread (there are no other threads),
 * when the program ends: after {@code main}, after {@code System.exit} and after an uncaught exception. The C
 * runtime calls {@link #runHooks} through {@code atexit}. Written for bytecode lowering: no string concatenation
 * with {@code +} and no lambdas.
 */
public final class ShutdownSupport {

    private static final List<Thread> HOOKS = new ArrayList<Thread>();

    private static boolean running;

    private ShutdownSupport() {
    }

    /** The Runnable a Thread object was made with, or null. */
    private static native Object targetOf0(Thread thread);

    public static void addShutdownHook(final Runtime runtime, final Thread hook) {
        if (hook == null) {
            throw new NullPointerException();
        }
        if (running) {
            throw new IllegalStateException("Shutdown in progress");
        }
        if (HOOKS.contains(hook)) {
            throw new IllegalArgumentException("Hook previously registered");
        }
        HOOKS.add(hook);
        if (HOOKS == null) {
            // Never true: it keeps runHooks in the program, so that the C runtime can call it.
            runHooks();
        }
    }

    public static boolean removeShutdownHook(final Runtime runtime, final Thread hook) {
        if (running) {
            throw new IllegalStateException("Shutdown in progress");
        }
        return HOOKS.remove(hook);
    }

    /** Runs the hooks, once. */
    public static void runHooks() {
        if (running) {
            return;
        }
        running = true;
        final List<Thread> hooks = new ArrayList<Thread>(HOOKS);
        for (final Thread hook : hooks) {
            try {
                final Object target = targetOf0(hook);
                if (target instanceof Runnable runnable) {
                    runnable.run();
                } else {
                    hook.run();
                }
            } catch (final Throwable t) {
                // A failing hook does not stop the others, as in the JVM.
            }
        }
    }
}
