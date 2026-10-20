package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.StackTraceSupport;

/**
 * {@code Throwable}'s stack trace without the VM: {@code fillInStackTrace}
 * stores the frame addresses ({@link StackTraceSupport#capture}) and
 * {@code getOurStackTrace} turns them into elements on demand. The fields
 * are declared here only to compile against (and it extends {@code Throwable}
 * so that {@code this} has the right type); their owner becomes
 * {@code Throwable} when the body is copied.
 */
public final class ThrowableSubstitution extends Throwable {

    private StackTraceElement[] stackTrace;
    private Object backtrace;
    private int depth;
    private static StackTraceElement[] UNASSIGNED_STACK;

    private ThrowableSubstitution() {
    }

    public Throwable fillInStackTrace(final int dummy) {
        final long[] frames = StackTraceSupport.capture();
        backtrace = frames;
        depth = frames == null ? 0 : frames.length;
        return this;
    }

    private StackTraceElement[] getOurStackTrace() {
        if (stackTrace == UNASSIGNED_STACK || stackTrace == null) {
            if (backtrace != null) {
                stackTrace = StackTraceSupport.elements(backtrace);
            } else {
                return UNASSIGNED_STACK;
            }
        }
        return stackTrace;
    }
}
