package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * The body of a started thread in a native executable. The C runtime creates the native thread in
 * {@code Thread.start} and calls {@link #runThread} on it. Written for bytecode lowering: no string
 * concatenation with {@code +} and no lambdas.
 */
public final class ThreadSupport {

    private ThreadSupport() {
    }

    private static final java.util.ArrayList<Thread> live = new java.util.ArrayList<>();

    /** Runs the thread: its {@code run} method, or the Runnable it was made with. An exception that escapes is reported. */
    public static void runThread(final Thread thread) {
        live.add(thread);
        try {
            if (thread.getClass() == Thread.class) {
                final Object target = ShutdownSupport.targetOf0(thread);
                if (target instanceof Runnable runnable) {
                    runnable.run();
                }
            } else {
                ((Runnable) thread).run();
            }
        } catch (final Throwable t) {
            dispatchUncaught(thread, t);
        } finally {
            // By reference: equals on a Thread object does not dispatch to anything useful here.
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i) == thread) {
                    live.remove(i);
                    break;
                }
            }
            final Object container = containerOf0(thread);
            if (container != null) {
                containerRemove0(container, thread);
            }
        }
    }

    private static native void setContainer0(Thread thread, Object container);

    private static native Object containerOf0(Thread thread);

    private static native void inheritBindings0(Thread thread);

    // The three bodies below are replaced when the program is compiled (BytecodeReachabilityCompiler): they call
    // jdk.internal.vm.ThreadContainer, which cannot be named in this source.

    /** container.add(thread): the container counts the thread (a StructuredTaskScope waits for its threads). */
    private static void containerAdd0(final Object container, final Thread thread) {
    }

    /** container.remove(thread): the thread has ended. */
    private static void containerRemove0(final Object container, final Thread thread) {
    }

    /** Whether the threads of the container inherit the scoped values of the thread that started them. */
    private static boolean containerInherits0(final Object container) {
        return false;
    }

    /** Thread.start(ThreadContainer): starts the thread as a member of the container. */
    public static void startInContainer(final Thread thread, final Object container) {
        if (getState(thread) != Thread.State.NEW || containerOf0(thread) != null) {
            throw new IllegalThreadStateException();
        }
        setContainer0(thread, container);
        containerAdd0(container, thread);
        boolean started = false;
        try {
            if (containerInherits0(container)) {
                inheritBindings0(thread);
            }
            thread.start();
            started = true;
        } finally {
            if (!started) {
                containerRemove0(container, thread);
            }
        }
    }

    private static native Thread mainThread0();

    /** The threads that are running: the main thread and the started threads that have not ended. */
    public static Thread[] liveThreads() {
        final Thread[] threads = new Thread[live.size() + 1];
        threads[0] = mainThread0();
        for (int i = 0; i < live.size(); i++) {
            threads[i + 1] = live.get(i);
        }
        return threads;
    }

    private static native Object handlerOf0(Thread thread);

    /** The handler of the thread, else its group (which passes it on to the default handler or prints it). */
    private static void dispatchUncaught(final Thread thread, final Throwable t) {
        Thread.UncaughtExceptionHandler handler = (Thread.UncaughtExceptionHandler) handlerOf0(thread);
        if (handler == null) {
            handler = getThreadGroup(thread);
        }
        try {
            handler.uncaughtException(thread, t);
        } catch (final Throwable ignored) {
            // The JVM ignores what a handler throws.
        }
    }

    /** Thread.getUncaughtExceptionHandler(): none once the thread has ended, else its handler or its group. */
    public static Thread.UncaughtExceptionHandler getUncaughtExceptionHandler(final Thread thread) {
        if (getState(thread) == Thread.State.TERMINATED) {
            return null;
        }
        final Thread.UncaughtExceptionHandler handler = (Thread.UncaughtExceptionHandler) handlerOf0(thread);
        return handler != null ? handler : getThreadGroup(thread);
    }

    /** Object.wait(long) of the C runtime. */
    private static native void waitOn0(Object object, long millis) throws InterruptedException;

    public static void waitOn(final Object object) throws InterruptedException {
        waitOn0(object, 0L);
    }

    public static void waitOn(final Object object, final long millis) throws InterruptedException {
        if (millis < 0L) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        waitOn0(object, millis);
    }

    public static void waitOn(final Object object, final long millis, final int nanos) throws InterruptedException {
        if (millis < 0L) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }
        waitOn0(object, nanos > 0 && millis < Long.MAX_VALUE ? millis + 1L : millis);
    }

    public static void join(final Thread thread) throws InterruptedException {
        join(thread, 0L);
    }

    public static void join(final Thread thread, final long millis) throws InterruptedException {
        if (millis < 0L) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        synchronized (thread) {
            if (millis == 0L) {
                while (thread.isAlive()) {
                    waitOn0(thread, 0L);
                }
            } else {
                final long deadline = System.currentTimeMillis() + millis;
                while (thread.isAlive()) {
                    final long left = deadline - System.currentTimeMillis();
                    if (left <= 0L) {
                        break;
                    }
                    waitOn0(thread, left);
                }
            }
        }
    }

    /** Thread.join(Duration): whether the thread has ended. */
    public static boolean join(final Thread thread, final java.time.Duration duration) throws InterruptedException {
        final long millis = duration.toMillis();
        if (millis > 0L || duration.getNano() > 0) {
            join(thread, millis > 0L ? millis : 1L);
        }
        return !thread.isAlive();
    }

    private static ThreadGroup systemGroup;

    private static ThreadGroup mainGroup;

    private static ThreadGroup virtualGroup;

    private static final java.util.ArrayList<ThreadGroup> groups = new java.util.ArrayList<>();

    /** The group a thread was made in, or null for the main group. */
    private static native Object groupOf0(Thread thread);

    private static void makeRootGroups() {
        if (mainGroup == null) {
            systemGroup = new ThreadGroup(null, "system");
            mainGroup = new ThreadGroup(systemGroup, "main");
        }
    }

    /** Thread.getThreadGroup(): the group of a thread, null once it has ended. A virtual thread is in "VirtualThreads". */
    public static ThreadGroup getThreadGroup(final Thread thread) {
        makeRootGroups();
        if (getState(thread) == Thread.State.TERMINATED) {
            return null;
        }
        if (thread.isVirtual()) {
            if (virtualGroup == null) {
                virtualGroup = new ThreadGroup(systemGroup, "VirtualThreads");
            }
            return virtualGroup;
        }
        final Object group = groupOf0(thread);
        return group == null ? mainGroup : (ThreadGroup) group;
    }

    /** Called by the constructor of every ThreadGroup. */
    private static native void nameGroup0(Object group, String name);

    public static void addGroup(final ThreadGroup group) {
        groups.add(group);
        nameGroup0(group, group.getName());
    }

    public static int countThreads(final ThreadGroup group) {
        return enumerateThreads(group, null, true);
    }

    /** Fills the list (when given) with the running threads of the group; returns how many it has (copied). */
    public static int enumerateThreads(final ThreadGroup group, final Thread[] list, final boolean recurse) {
        int n = 0;
        for (final Thread thread : liveThreads()) {
            final ThreadGroup g = getThreadGroup(thread);
            if (g != null && (recurse ? group.parentOf(g) : g == group)) {
                if (list == null) {
                    n++;
                } else if (n < list.length) {
                    list[n++] = thread;
                }
            }
        }
        return n;
    }

    private static int seed = 0x9E3779B9;

    /** A pseudo-random number for the cache slots of ScopedValue (xorshift). */
    public static int secondarySeed() {
        int x = seed;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        seed = x;
        return x;
    }

    /** ThreadGroup.interrupt(): interrupts the running threads of the group and of its subgroups. */
    public static void interruptGroup(final ThreadGroup group) {
        for (final Thread thread : liveThreads()) {
            final ThreadGroup g = getThreadGroup(thread);
            if (g != null && group.parentOf(g)) {
                thread.interrupt();
            }
        }
    }

    /** Fills the list (when given) with the groups below the group; returns how many it has (copied). */
    public static int enumerateGroups(final ThreadGroup group, final ThreadGroup[] list, final boolean recurse) {
        int n = 0;
        for (final ThreadGroup g : groups) {
            if (g != group && (recurse ? group.parentOf(g) : g.getParent() == group)) {
                if (list == null) {
                    n++;
                } else if (n < list.length) {
                    list[n++] = g;
                }
            }
        }
        return n;
    }

    /** ThreadGroup.list(): the group, its threads and its subgroups, indented by four spaces per level. */
    public static void listGroup(final ThreadGroup group, final int indent) {
        final String pad = " ".repeat(indent);
        System.out.println(pad + group);
        final Thread[] threads = new Thread[enumerateThreads(group, null, false)];
        enumerateThreads(group, threads, false);
        for (final Thread thread : threads) {
            if (thread != null) {
                System.out.println(pad + "    " + thread);
            }
        }
        final ThreadGroup[] children = new ThreadGroup[enumerateGroups(group, null, false)];
        enumerateGroups(group, children, false);
        for (final ThreadGroup child : children) {
            if (child != null) {
                listGroup(child, indent + 4);
            }
        }
    }

    /** 0 new, 1 started, 2 terminated. */
    private static native int stateOf0(Thread thread);

    public static Thread.State getState(final Thread thread) {
        final int state = stateOf0(thread);
        if (state == 0) {
            return Thread.State.NEW;
        }
        return state == 1 ? Thread.State.RUNNABLE : Thread.State.TERMINATED;
    }
}
