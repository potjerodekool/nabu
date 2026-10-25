package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * {@code ThreadBuilders.BoundVirtualThread.run}: runs the task. The JDK version runs it with the scoped-value
 * bindings of the thread, which the runtime does not keep.
 */
public final class BoundVirtualThreadSubstitution {

    private final Runnable task;

    private BoundVirtualThreadSubstitution() {
        this.task = null;
    }

    public void run() {
        task.run();
    }
}
