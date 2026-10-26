package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport;

/**
 * {@code ThreadGroup(ThreadGroup, String)} that accepts a null parent: the "system" group at the top of the tree has
 * none. Without a parent the maximum priority is 10. The group is not registered with its parent but with
 * {@link ThreadSupport}, which lists the groups and the running threads for {@code activeCount},
 * {@code activeGroupCount} and {@code enumerate}.
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
        ThreadSupport.addGroup((ThreadGroup) (Object) this);
    }

    public int activeCount() {
        return ThreadSupport.countThreads((ThreadGroup) (Object) this);
    }

    public int enumerate(final Thread[] list) {
        return ThreadSupport.enumerateThreads((ThreadGroup) (Object) this, list, true);
    }

    public int enumerate(final Thread[] list, final boolean recurse) {
        return ThreadSupport.enumerateThreads((ThreadGroup) (Object) this, list, recurse);
    }

    public int activeGroupCount() {
        return ThreadSupport.enumerateGroups((ThreadGroup) (Object) this, null, true);
    }

    public int enumerate(final ThreadGroup[] list) {
        return ThreadSupport.enumerateGroups((ThreadGroup) (Object) this, list, true);
    }

    public int enumerate(final ThreadGroup[] list, final boolean recurse) {
        return ThreadSupport.enumerateGroups((ThreadGroup) (Object) this, list, recurse);
    }

    public final void interrupt() {
        ThreadSupport.interruptGroup((ThreadGroup) (Object) this);
    }

    public final void setMaxPriority(final int pri) {
        if (pri >= Thread.MIN_PRIORITY && pri <= Thread.MAX_PRIORITY) {
            maxPriority = parent != null ? Math.min(pri, parent.getMaxPriority()) : pri;
            final ThreadGroup self = (ThreadGroup) (Object) this;
            final ThreadGroup[] children = new ThreadGroup[ThreadSupport.enumerateGroups(self, null, false)];
            ThreadSupport.enumerateGroups(self, children, false);
            for (final ThreadGroup child : children) {
                child.setMaxPriority(pri);
            }
        }
    }
}
