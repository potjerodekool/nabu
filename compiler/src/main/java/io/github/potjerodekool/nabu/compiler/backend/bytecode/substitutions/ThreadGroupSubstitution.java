package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * {@code ThreadGroup(ThreadGroup, String)} that accepts a null parent: the group of the main thread is made without
 * a parent group (the JVM has a "system" group above it, which a native executable does not have). Without a parent
 * the maximum priority is 10. The group is not registered with its parent, so {@code activeGroupCount} and
 * {@code enumerate(ThreadGroup[])} do not list it.
 */
public final class ThreadGroupSubstitution {

    private final ThreadGroup parent;

    private final String name;

    private volatile int maxPriority;

    private volatile boolean daemon;

    public ThreadGroupSubstitution(final ThreadGroup parent, final String name) {
        this.parent = parent;
        this.name = name;
        this.maxPriority = parent != null ? parent.getMaxPriority() : Thread.MAX_PRIORITY;
        this.daemon = parent != null && parent.isDaemon();
    }
}
