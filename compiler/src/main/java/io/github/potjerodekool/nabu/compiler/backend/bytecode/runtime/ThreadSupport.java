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
    public static void addGroup(final ThreadGroup group) {
        groups.add(group);
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
